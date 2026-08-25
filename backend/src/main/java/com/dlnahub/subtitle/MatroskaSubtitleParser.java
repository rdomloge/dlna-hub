package com.dlnahub.subtitle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls subtitle tracks out of a Matroska container.
 *
 * <p>Two operations with very different costs — see {@code plans/xbox-subtitles/FINDINGS.md} §3:
 *
 * <ul>
 *   <li>{@link #readTracks} reads the {@code Tracks} element, which sits within the first few
 *       kilobytes of the file. Sub-second over a range request.
 *   <li>{@link #extractCues} scans the whole file, because subtitle blocks are interleaved
 *       through every cluster. Measured at ~9 s for a 687 MB episode on the LAN.
 * </ul>
 *
 * <p>No transcoding is involved. {@code S_TEXT/UTF8} stores the cue text verbatim; the timings
 * come from the cluster timestamp plus each block's relative offset, scaled by TimestampScale.
 */
public final class MatroskaSubtitleParser {

    private static final Logger log = LoggerFactory.getLogger(MatroskaSubtitleParser.class);

    private static final long SEGMENT = 0x18538067L;
    private static final long INFO = 0x1549A966L;
    private static final long TIMESTAMP_SCALE = 0x2AD7B1L;
    private static final long TRACKS = 0x1654AE6BL;
    private static final long TRACK_ENTRY = 0xAEL;
    private static final long TRACK_NUMBER = 0xD7L;
    private static final long TRACK_TYPE = 0x83L;
    private static final long CODEC_ID = 0x86L;
    private static final long LANGUAGE = 0x22B59CL;
    private static final long LANGUAGE_BCP47 = 0x22B59DL;
    private static final long TRACK_NAME = 0x536EL;
    private static final long FLAG_DEFAULT = 0x88L;
    private static final long FLAG_FORCED = 0x55AAL;
    private static final long CLUSTER = 0x1F43B675L;
    private static final long CLUSTER_TIMESTAMP = 0xE7L;
    private static final long SIMPLE_BLOCK = 0xA3L;
    private static final long BLOCK_GROUP = 0xA0L;
    private static final long BLOCK = 0xA1L;
    private static final long BLOCK_DURATION = 0x9BL;

    private static final long TRACK_TYPE_SUBTITLE = 0x11L;
    private static final long DEFAULT_TIMESTAMP_SCALE_NS = 1_000_000L;

    /** Fallback when a SimpleBlock carries no duration. Trimmed against the next cue later. */
    private static final long ASSUMED_CUE_MS = 3_000L;

    private MatroskaSubtitleParser() {
    }

    /** Receives cues as they are found, in playback order. */
    public interface CueSink {
        void accept(long startMs, long endMs, String text);
    }

    /**
     * Lists the subtitle tracks. Feed this the first megabyte or so of the file — the parse
     * stops as soon as the track table is read.
     */
    public static List<SubtitleTrackInfo> readTracks(InputStream in) throws IOException {
        Context ctx = new Context(null, -1);
        ctx.tracksOnly = true;
        try {
            walk(new EbmlReader(in), Long.MAX_VALUE, ctx);
        } catch (EOFException e) {
            // Expected: we are reading a prefix of the file, not the whole thing.
            log.debug("Reached end of the fetched header while listing tracks");
        }
        return ctx.tracks;
    }

    /**
     * Scans the stream and pushes every cue belonging to {@code trackNumber} into the sink.
     *
     * @param trackNumber the Matroska track number, as reported by {@link #readTracks}
     */
    public static void extractCues(InputStream in, int trackNumber, CueSink sink)
            throws IOException {
        Context ctx = new Context(sink, trackNumber);
        try {
            walk(new EbmlReader(in), Long.MAX_VALUE, ctx);
        } catch (EOFException e) {
            // A truncated tail costs the last few cues, not the whole extraction.
            log.debug("Stream ended during extraction after {} cues", ctx.emitted);
        }
        ctx.flush();
        if (ctx.lacedBlocksSkipped > 0) {
            log.warn("Skipped {} laced subtitle blocks — unusual, cues may be missing",
                    ctx.lacedBlocksSkipped);
        }
    }

    private static void walk(EbmlReader r, long end, Context ctx) throws IOException {
        while (r.position() < end && !ctx.stop) {
            long id = r.readId();
            if (EbmlReader.isEndOfStream(id)) return;
            long size = r.readSize();
            if (EbmlReader.isEndOfStream(size)) return;

            boolean unknownSize = size == EbmlReader.UNKNOWN_SIZE;
            long contentEnd = unknownSize ? end : r.position() + size;

            if (id == SEGMENT) {
                walk(r, contentEnd, ctx);
                continue;
            }
            if (id == INFO) {
                parseInfo(r, contentEnd, ctx);
                continue;
            }
            if (id == TRACKS) {
                parseTracks(r, contentEnd, ctx);
                if (ctx.tracksOnly) {
                    ctx.stop = true;
                }
                continue;
            }
            if (id == CLUSTER) {
                if (ctx.tracksOnly) {
                    // The track table normally precedes the clusters. If we have it, we are done;
                    // if not, skip past and keep looking.
                    if (!ctx.tracks.isEmpty() || unknownSize) {
                        ctx.stop = true;
                        return;
                    }
                    r.skip(size);
                    continue;
                }
                parseCluster(r, contentEnd, ctx);
                continue;
            }

            if (unknownSize) {
                // Nothing else legitimately uses an unknown size, and we cannot skip past it.
                return;
            }
            r.skip(size);
        }
    }

    private static void parseInfo(EbmlReader r, long end, Context ctx) throws IOException {
        while (r.position() < end) {
            long id = r.readId();
            if (EbmlReader.isEndOfStream(id)) return;
            long size = r.readSize();
            if (EbmlReader.isEndOfStream(size) || size == EbmlReader.UNKNOWN_SIZE) return;

            if (id == TIMESTAMP_SCALE) {
                ctx.timestampScaleNs = r.readUnsignedInteger((int) size);
            } else {
                r.skip(size);
            }
        }
    }

    private static void parseTracks(EbmlReader r, long end, Context ctx) throws IOException {
        while (r.position() < end) {
            long id = r.readId();
            if (EbmlReader.isEndOfStream(id)) return;
            long size = r.readSize();
            if (EbmlReader.isEndOfStream(size) || size == EbmlReader.UNKNOWN_SIZE) return;

            long entryEnd = r.position() + size;
            if (id == TRACK_ENTRY) {
                SubtitleTrackInfo track = parseTrackEntry(r, entryEnd, ctx);
                if (track != null) {
                    ctx.tracks.add(track);
                }
                if (r.position() < entryEnd) {
                    r.skip(entryEnd - r.position());
                }
            } else {
                r.skip(size);
            }
        }
    }

    private static SubtitleTrackInfo parseTrackEntry(EbmlReader r, long end, Context ctx)
            throws IOException {
        long number = -1;
        long type = -1;
        String codec = null;
        String language = null;
        String name = null;
        // Matroska's FlagDefault defaults to 1 when the element is absent.
        boolean defaultTrack = true;
        boolean forced = false;

        while (r.position() < end) {
            long id = r.readId();
            if (EbmlReader.isEndOfStream(id)) break;
            long size = r.readSize();
            if (EbmlReader.isEndOfStream(size) || size == EbmlReader.UNKNOWN_SIZE) break;
            int len = (int) size;

            if (id == TRACK_NUMBER) {
                number = len == 0 ? 0 : r.readUnsignedInteger(len);
            } else if (id == TRACK_TYPE) {
                type = len == 0 ? 0 : r.readUnsignedInteger(len);
            } else if (id == CODEC_ID) {
                codec = r.readString(len);
            } else if (id == TRACK_NAME) {
                name = r.readString(len);
            } else if (id == LANGUAGE) {
                language = r.readString(len);
            } else if (id == LANGUAGE_BCP47) {
                String bcp47 = r.readString(len);
                if (!bcp47.isEmpty()) {
                    language = bcp47;
                }
            } else if (id == FLAG_DEFAULT) {
                defaultTrack = len != 0 && r.readUnsignedInteger(len) != 0;
            } else if (id == FLAG_FORCED) {
                forced = len != 0 && r.readUnsignedInteger(len) != 0;
            } else {
                r.skip(size);
            }
        }

        if (type != TRACK_TYPE_SUBTITLE || number < 0) {
            return null;
        }
        // S_TEXT/* is readable text. S_HDMV/PGS and S_VOBSUB are bitmaps and would need OCR.
        boolean textBased = codec != null && codec.startsWith("S_TEXT");
        SubtitleTrackInfo track = new SubtitleTrackInfo(
                "embedded:" + number, SubtitleTrackInfo.Source.EMBEDDED,
                emptyToNull(language), emptyToNull(name), codec,
                defaultTrack, forced, textBased);
        ctx.codecByTrack.put((int) number, codec);
        return track;
    }

    private static void parseCluster(EbmlReader r, long end, Context ctx) throws IOException {
        long clusterTimestamp = 0;

        while (r.position() < end && !ctx.stop) {
            long id = r.readId();
            if (EbmlReader.isEndOfStream(id)) return;
            long size = r.readSize();
            if (EbmlReader.isEndOfStream(size)) return;

            if (id == CLUSTER) {
                // Only reachable when the previous cluster had an unknown size: its content
                // ran until here. Carry on in this loop against the new cluster's timestamp.
                clusterTimestamp = 0;
                continue;
            }
            if (size == EbmlReader.UNKNOWN_SIZE) {
                return;
            }

            if (id == CLUSTER_TIMESTAMP) {
                clusterTimestamp = size == 0 ? 0 : r.readUnsignedInteger((int) size);
            } else if (id == SIMPLE_BLOCK) {
                PendingBlock block = readBlock(r, size, clusterTimestamp, ctx);
                if (block != null) {
                    ctx.emit(block.startMs(), 0, block.text());
                }
            } else if (id == BLOCK_GROUP) {
                parseBlockGroup(r, r.position() + size, clusterTimestamp, ctx);
            } else {
                r.skip(size);
            }
        }
    }

    private static void parseBlockGroup(EbmlReader r, long end, long clusterTimestamp, Context ctx)
            throws IOException {
        PendingBlock pending = null;
        long durationTicks = -1;

        while (r.position() < end) {
            long id = r.readId();
            if (EbmlReader.isEndOfStream(id)) break;
            long size = r.readSize();
            if (EbmlReader.isEndOfStream(size) || size == EbmlReader.UNKNOWN_SIZE) break;

            if (id == BLOCK) {
                PendingBlock block = readBlock(r, size, clusterTimestamp, ctx);
                if (block != null) {
                    pending = block;
                }
            } else if (id == BLOCK_DURATION) {
                durationTicks = size == 0 ? 0 : r.readUnsignedInteger((int) size);
            } else {
                r.skip(size);
            }
        }

        if (pending != null) {
            long durationMs = durationTicks < 0 ? 0 : ctx.toMillis(durationTicks);
            ctx.emit(pending.startMs(), durationMs > 0 ? pending.startMs() + durationMs : 0,
                    pending.text());
        }
    }

    /**
     * Reads one Block/SimpleBlock. Returns null — having skipped the payload — unless the block
     * belongs to the track we want. This is the decision that keeps the scan cheap: the vast
     * majority of blocks are video and are never copied out of the stream.
     */
    private static PendingBlock readBlock(EbmlReader r, long size, long clusterTimestamp,
                                          Context ctx) throws IOException {
        long start = r.position();
        long trackNumber = r.readSize();
        if (EbmlReader.isEndOfStream(trackNumber)) {
            return null;
        }
        long remaining = size - (r.position() - start);
        if (remaining < 3) {
            if (remaining > 0) r.skip(remaining);
            return null;
        }
        if (trackNumber != ctx.targetTrack) {
            r.skip(remaining);
            return null;
        }

        long relative = (short) r.readUnsignedInteger(2);
        int flags = (int) r.readUnsignedInteger(1);
        remaining -= 3;

        // Lacing packs several frames into one block. Subtitles effectively never use it, and
        // guessing at the frame boundaries would corrupt the text.
        if (((flags >> 1) & 0x03) != 0) {
            ctx.lacedBlocksSkipped++;
            r.skip(remaining);
            return null;
        }

        byte[] payload = r.readBytes((int) remaining);
        String text = new String(payload, StandardCharsets.UTF_8);
        return new PendingBlock(ctx.toMillis(clusterTimestamp + relative), text);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private record PendingBlock(long startMs, String text) {
    }

    /** Parser state for one pass over a file. */
    private static final class Context {
        final CueSink sink;
        final int targetTrack;
        final List<SubtitleTrackInfo> tracks = new ArrayList<>();
        final java.util.Map<Integer, String> codecByTrack = new java.util.HashMap<>();

        long timestampScaleNs = DEFAULT_TIMESTAMP_SCALE_NS;
        boolean tracksOnly;
        boolean stop;
        int lacedBlocksSkipped;
        int emitted;

        private long pendingStart = -1;
        private long pendingEnd;
        private String pendingText;

        Context(CueSink sink, int targetTrack) {
            this.sink = sink;
            this.targetTrack = targetTrack;
        }

        long toMillis(long ticks) {
            return ticks * timestampScaleNs / 1_000_000L;
        }

        /**
         * Buffers one cue so the next one can close an open-ended block. A SimpleBlock carries no
         * duration, so without this every such cue would have to guess how long it lasts.
         */
        void emit(long startMs, long endMs, String rawText) {
            String text = decodeCueText(rawText);
            if (text.isEmpty()) {
                return;
            }
            flushPending(startMs);
            pendingStart = startMs;
            pendingEnd = endMs;
            pendingText = text;
        }

        private void flushPending(long nextStartMs) {
            if (pendingStart < 0) return;
            long end = pendingEnd > 0 ? pendingEnd : pendingStart + ASSUMED_CUE_MS;
            if (nextStartMs > pendingStart && end > nextStartMs) {
                end = nextStartMs;
            }
            sink.accept(pendingStart, end, pendingText);
            emitted++;
            pendingStart = -1;
        }

        void flush() {
            flushPending(Long.MAX_VALUE);
        }

        /**
         * ASS/SSA blocks are not bare text: Matroska stores the Dialogue fields, comma-separated,
         * with the actual line last. S_TEXT/UTF8 needs no such treatment.
         */
        private String decodeCueText(String raw) {
            String codec = codecByTrack.get(targetTrack);
            if (codec != null && (codec.startsWith("S_TEXT/ASS") || codec.startsWith("S_TEXT/SSA"))) {
                String[] fields = raw.split(",", 9);
                return fields.length == 9 ? fields[8] : raw;
            }
            return raw;
        }
    }
}
