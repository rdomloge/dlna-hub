package com.dlnahub.controller;

import com.dlnahub.dlna.model.BrowsableItem;
import com.dlnahub.exception.DeviceNotFoundException;
import com.dlnahub.service.ContentBrowseService;
import com.dlnahub.service.SubtitleService;
import com.dlnahub.subtitle.SubtitleCue;
import com.dlnahub.subtitle.SubtitleTrackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Subtitle endpoints.
 *
 * <p>Listing tracks is cheap and synchronous. Fetching cues may not be: an embedded Matroska
 * track takes a full-file scan, so this returns whatever has been extracted so far along with
 * a {@code complete} flag, and the client polls until it flips. Cues arrive in playback order,
 * so the panel is usable long before extraction finishes.
 */
@RestController
@Validated
@RequestMapping("/api/servers")
public class SubtitleController {

    private static final Logger log = LoggerFactory.getLogger(SubtitleController.class);

    private final ContentBrowseService contentBrowseService;
    private final SubtitleService subtitleService;

    @Autowired
    public SubtitleController(ContentBrowseService contentBrowseService,
                              SubtitleService subtitleService) {
        this.contentBrowseService = contentBrowseService;
        this.subtitleService = subtitleService;
    }

    @GetMapping("/{serverId}/subtitles/{itemId}/tracks")
    public TracksResponse tracks(@PathVariable String serverId, @PathVariable String itemId) {
        String videoUrl = resolveVideoUrl(serverId, itemId);
        List<SubtitleTrackInfo> tracks = subtitleService.listTracks(videoUrl);
        SubtitleTrackInfo preferred = SubtitleService.pickDefault(tracks);
        return new TracksResponse(tracks, preferred == null ? null : preferred.id());
    }

    @GetMapping("/{serverId}/subtitles/{itemId}")
    public CuesResponse cues(@PathVariable String serverId,
                             @PathVariable String itemId,
                             @RequestParam(value = "track", required = false) String track) {
        String videoUrl = resolveVideoUrl(serverId, itemId);

        String trackId = track;
        if (trackId == null || trackId.isBlank()) {
            SubtitleTrackInfo preferred = SubtitleService.pickDefault(
                    subtitleService.listTracks(videoUrl));
            if (preferred == null) {
                // Two thirds of the library has nothing. The client asks for every item it
                // plays, so this is the ordinary case, not an error — a 404 here would mean a
                // failed request on the common path. Same reasoning as TmdbController.
                return CuesResponse.unavailable();
            }
            trackId = preferred.id();
        }

        SubtitleService.Extraction extraction =
                subtitleService.cues(serverId, itemId, videoUrl, trackId);
        List<SubtitleCue> cues = extraction.snapshot();
        log.debug("Subtitles for {} track {}: {} cues, state {}",
                itemId, trackId, cues.size(), extraction.state());

        return new CuesResponse(true, trackId, extraction.state().name(),
                extraction.complete(), cues.size(), cues, extraction.message());
    }

    @GetMapping(value = "/{serverId}/subtitles/{itemId}.vtt", produces = "text/vtt;charset=UTF-8")
    public ResponseEntity<String> vtt(@PathVariable String serverId,
                                      @PathVariable String itemId,
                                      @RequestParam(value = "track", required = false) String track) {
        CuesResponse response = cues(serverId, itemId, track);
        if (!response.available()) {
            throw new DeviceNotFoundException("No subtitles for item: " + itemId);
        }

        StringBuilder out = new StringBuilder("WEBVTT\n\n");
        for (SubtitleCue cue : response.cues()) {
            out.append(timestamp(cue.startMs())).append(" --> ").append(timestamp(cue.endMs()))
                    .append('\n')
                    .append(String.join("\n", cue.lines()))
                    .append("\n\n");
        }

        HttpHeaders headers = new HttpHeaders();
        // Only cacheable once the whole track is in; a partial extraction must not stick.
        headers.setCacheControl(response.complete() ? "public, max-age=3600" : "no-store");
        return new ResponseEntity<>(out.toString(), headers, org.springframework.http.HttpStatus.OK);
    }

    /** WebVTT uses a period before the milliseconds where SRT uses a comma. */
    private static String timestamp(long ms) {
        long safe = Math.max(0, ms);
        return String.format("%02d:%02d:%02d.%03d",
                safe / 3_600_000, (safe / 60_000) % 60, (safe / 1000) % 60, safe % 1000);
    }

    private String resolveVideoUrl(String serverId, String itemId) {
        List<BrowsableItem> items = contentBrowseService.browseMetadata(serverId, itemId, "*");
        if (items.isEmpty()) {
            throw new DeviceNotFoundException("Item not found: " + itemId);
        }
        String url = items.get(0).getResourceName();
        if (url == null || url.isEmpty()) {
            throw new DeviceNotFoundException("Item has no playable resource: " + itemId);
        }
        return url;
    }

    public record TracksResponse(List<SubtitleTrackInfo> tracks, String defaultTrackId) {
    }

    public record CuesResponse(boolean available, String trackId, String status, boolean complete,
                               int cueCount, List<SubtitleCue> cues, String message) {

        static CuesResponse unavailable() {
            return new CuesResponse(false, null, "NONE", true, 0, List.of(), null);
        }
    }
}
