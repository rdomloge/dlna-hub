package com.dlnahub.service;

import com.dlnahub.subtitle.MatroskaSubtitleParser;
import com.dlnahub.subtitle.SrtParser;
import com.dlnahub.subtitle.SubtitleCue;
import com.dlnahub.subtitle.SubtitleTrackInfo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds and reads subtitles for an item.
 *
 * <p>There are two sources, and they cost very different amounts — see
 * {@code plans/xbox-subtitles/FINDINGS.md}:
 *
 * <ul>
 *   <li><b>Sidecar.</b> The NAS serves a {@code .srt} at the video URL with the extension
 *       swapped, without ever advertising it in DIDL. One small GET, so this is synchronous.
 *   <li><b>Embedded.</b> An {@code S_TEXT/*} track inside a Matroska file, which the NAS will
 *       not extract. Listing costs a range request over the header; extracting costs a
 *       full-file scan (~9 s for a 687 MB episode), so it runs in the background and results
 *       are published as they are found.
 * </ul>
 */
@Service
public class SubtitleService {

    private static final Logger log = LoggerFactory.getLogger(SubtitleService.class);

    /**
     * How much of the file to pull when listing tracks. The Tracks element normally starts
     * around byte 300; 1 MB leaves room for a large SeekHead or attached fonts in front of it.
     */
    private static final int HEADER_BYTES = 1_048_576;

    /** Retry span for the rare file that puts Tracks behind something bulky. */
    private static final int HEADER_BYTES_RETRY = 8_388_608;

    /** Cues for a long film run to a few hundred KB; a handful of films is all anyone has open. */
    private static final int MAX_CACHED_EXTRACTIONS = 8;
    private static final int MAX_CACHED_TRACK_LISTS = 256;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Two threads: extraction is bound by how fast the NAS serves bytes, and a third concurrent
     * scan would only take throughput away from the two already running.
     */
    private final ExecutorService extractionPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "subtitle-extract");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, List<SubtitleTrackInfo>> trackCache = boundedCache(MAX_CACHED_TRACK_LISTS);
    private final Map<String, Extraction> extractions = boundedCache(MAX_CACHED_EXTRACTIONS);

    /**
     * Charset for sidecar files that are neither BOM-marked nor valid UTF-8. Set
     * {@code subtitles.fallback-charset: ISO-8859-2} for a library of Central or Eastern
     * European subtitles — the byte values collide with Western European ones.
     */
    private final Charset fallbackCharset;

    public SubtitleService(
            @Value("${subtitles.fallback-charset:windows-1252}") String fallbackCharsetName) {
        Charset resolved;
        try {
            resolved = Charset.forName(fallbackCharsetName);
        } catch (RuntimeException e) {
            log.warn("Unknown subtitles.fallback-charset '{}' — using {}",
                    fallbackCharsetName, SrtParser.DEFAULT_FALLBACK_CHARSET);
            resolved = SrtParser.DEFAULT_FALLBACK_CHARSET;
        }
        this.fallbackCharset = resolved;
    }

    private static <V> Map<String, V> boundedCache(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    @PreDestroy
    void shutdown() {
        extractionPool.shutdownNow();
        try {
            extractionPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- track discovery

    /**
     * The sidecar URL implied by a video resource URL, or null when there is no sensible one.
     * The NAS serves {@code .../38318.srt} alongside {@code .../38318.mp4} — see FINDINGS §1.
     */
    public static String deriveSidecarUrl(String videoUrl) {
        if (videoUrl == null || videoUrl.isEmpty()) {
            return null;
        }
        int lastSlash = videoUrl.lastIndexOf('/');
        int dot = videoUrl.lastIndexOf('.');
        // A dot before the last slash belongs to the host or a path segment, not a filename.
        if (dot <= lastSlash) {
            return null;
        }
        // Do not bury a query string in the middle of the derived name.
        if (videoUrl.indexOf('?', dot) >= 0) {
            return null;
        }
        return videoUrl.substring(0, dot) + ".srt";
    }

    static boolean isMatroska(String videoUrl) {
        return videoUrl != null && videoUrl.toLowerCase().endsWith(".mkv");
    }

    /** Every track we could offer for this item, sidecar first. */
    public List<SubtitleTrackInfo> listTracks(String videoUrl) {
        if (videoUrl == null || videoUrl.isEmpty()) {
            return List.of();
        }
        List<SubtitleTrackInfo> cached = trackCache.get(videoUrl);
        if (cached != null) {
            return cached;
        }

        List<SubtitleTrackInfo> tracks = new ArrayList<>();
        if (sidecarAvailable(videoUrl)) {
            tracks.add(SubtitleTrackInfo.sidecar());
        }
        if (isMatroska(videoUrl)) {
            tracks.addAll(readEmbeddedTracks(videoUrl));
        }

        trackCache.put(videoUrl, tracks);
        return tracks;
    }

    /** True when the NAS answers for the derived sidecar URL. */
    public boolean sidecarAvailable(String videoUrl) {
        String url = deriveSidecarUrl(videoUrl);
        if (url == null) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(8))
                    .build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            log.debug("No sidecar subtitle at {}: {}", url, e.toString());
            return false;
        }
    }

    private List<SubtitleTrackInfo> readEmbeddedTracks(String videoUrl) {
        List<SubtitleTrackInfo> tracks = readEmbeddedTracks(videoUrl, HEADER_BYTES);
        if (tracks.isEmpty()) {
            // Rare, but a big SeekHead or attached fonts can push Tracks past the first megabyte.
            tracks = readEmbeddedTracks(videoUrl, HEADER_BYTES_RETRY);
        }
        return tracks;
    }

    private List<SubtitleTrackInfo> readEmbeddedTracks(String videoUrl, int byteCount) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(videoUrl))
                    .header("Range", "bytes=0-" + (byteCount - 1))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300) {
                log.warn("Header fetch for {} returned {}", videoUrl, response.statusCode());
                return List.of();
            }
            try (InputStream body = response.body()) {
                return MatroskaSubtitleParser.readTracks(body);
            }
        } catch (Exception e) {
            log.warn("Could not read subtitle tracks from {}: {}", videoUrl, e.toString());
            return List.of();
        }
    }

    /**
     * The track to use when the client does not name one.
     *
     * <p>English wins over everything, because that is the point of the feature; a forced track
     * is avoided since it only covers foreign dialogue. A sidecar sits mid-table: it is instant
     * to read but carries no language metadata, so a known-English embedded track beats it.
     */
    public static SubtitleTrackInfo pickDefault(List<SubtitleTrackInfo> tracks) {
        return tracks.stream()
                .filter(SubtitleTrackInfo::textBased)
                .max(Comparator.comparingInt(SubtitleService::preferenceScore))
                .orElse(null);
    }

    private static int preferenceScore(SubtitleTrackInfo track) {
        if (track.source() == SubtitleTrackInfo.Source.SIDECAR) {
            return 40;
        }
        int score = 0;
        if (track.isEnglish()) score += 100;
        if (track.defaultTrack()) score += 10;
        if (track.forced()) score -= 50;
        return score;
    }

    // ---------------------------------------------------------------- cues

    /**
     * Cues for a track. Sidecars come back complete; an embedded track starts extracting in the
     * background and returns whatever has been found so far.
     */
    public Extraction cues(String serverId, String itemId, String videoUrl, String trackId) {
        String key = serverId + "|" + itemId + "|" + trackId;
        Extraction existing = extractions.get(key);
        if (existing != null) {
            return existing;
        }

        synchronized (extractions) {
            existing = extractions.get(key);
            if (existing != null) {
                return existing;
            }
            Extraction extraction = new Extraction();
            extractions.put(key, extraction);
            start(extraction, videoUrl, trackId);
            return extraction;
        }
    }

    private void start(Extraction extraction, String videoUrl, String trackId) {
        if ("sidecar".equals(trackId)) {
            // One small GET — no point deferring it to a worker and making the client poll.
            try {
                byte[] data = fetchSidecar(videoUrl);
                extraction.addAll(SrtParser.parse(data, fallbackCharset));
                extraction.finish();
            } catch (Exception e) {
                log.warn("Sidecar fetch failed for {}: {}", videoUrl, e.toString());
                extraction.fail("Could not read the subtitle file");
            }
            return;
        }

        int trackNumber;
        try {
            trackNumber = Integer.parseInt(trackId.substring(trackId.indexOf(':') + 1));
        } catch (RuntimeException e) {
            extraction.fail("Unknown subtitle track: " + trackId);
            return;
        }

        extractionPool.submit(() -> extractEmbedded(extraction, videoUrl, trackNumber));
    }

    private byte[] fetchSidecar(String videoUrl) throws Exception {
        String url = deriveSidecarUrl(videoUrl);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<byte[]> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    private void extractEmbedded(Extraction extraction, String videoUrl, int trackNumber) {
        long startedAt = System.currentTimeMillis();
        try {
            // No request timeout: this deliberately reads the whole file and takes seconds.
            HttpRequest request = HttpRequest.newBuilder(URI.create(videoUrl)).GET().build();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300) {
                extraction.fail("The media server returned HTTP " + response.statusCode());
                return;
            }
            try (InputStream body = response.body()) {
                MatroskaSubtitleParser.extractCues(body, trackNumber, (start, end, text) ->
                        extraction.add(start, end, text));
            }
            extraction.finish();
            log.info("Extracted {} cues from track {} of {} in {} ms",
                    extraction.size(), trackNumber, videoUrl,
                    System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.warn("Subtitle extraction failed for {} track {}: {}",
                    videoUrl, trackNumber, e.toString());
            extraction.fail("Subtitle extraction failed");
        }
    }

    // ---------------------------------------------------------------- extraction state

    /**
     * A subtitle read in progress or finished.
     *
     * <p>Cues are published as they are found rather than at the end. Extraction is a forward
     * scan from the start of the file, so the opening cues land within a second and the panel
     * can display them while the rest of the file is still being read.
     */
    public static final class Extraction {

        public enum State {
            EXTRACTING, READY, FAILED
        }

        private final List<SubtitleCue> cues = new ArrayList<>();
        private volatile State state = State.EXTRACTING;
        private volatile String message;

        void add(long startMs, long endMs, String rawText) {
            List<String> lines = SrtParser.cleanLines(List.of(rawText.split("\\r?\\n")));
            if (lines.isEmpty()) {
                return;
            }
            synchronized (cues) {
                cues.add(new SubtitleCue(cues.size() + 1, startMs, endMs, lines));
            }
        }

        void addAll(List<SubtitleCue> parsed) {
            synchronized (cues) {
                cues.addAll(parsed);
            }
        }

        void finish() {
            state = State.READY;
        }

        void fail(String reason) {
            message = reason;
            state = State.FAILED;
        }

        public State state() {
            return state;
        }

        public String message() {
            return message;
        }

        public boolean complete() {
            return state != State.EXTRACTING;
        }

        public int size() {
            synchronized (cues) {
                return cues.size();
            }
        }

        /** A stable copy — the extraction thread may still be appending. */
        public List<SubtitleCue> snapshot() {
            synchronized (cues) {
                return new ArrayList<>(cues);
            }
        }
    }
}
