package com.dlnahub.subtitle;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds small synthetic Matroska files in memory and parses them back.
 *
 * <p>Synthetic rather than a checked-in fixture because the interesting cases — a video block
 * that must be skipped, a SimpleBlock with no duration, a bitmap codec — are all about the
 * container's structure, and hand-building it makes each case explicit.
 */
class MatroskaSubtitleParserTest {

    // ------------------------------------------------------------------ EBML builder

    /** Encodes a length as an 8-byte EBML vint: valid for any size, and simple. */
    private static byte[] size(long value) {
        byte[] out = new byte[8];
        out[0] = 0x01;
        for (int i = 7; i >= 1; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return out;
    }

    private static byte[] element(long id, byte[] payload) {
        byte[] idBytes = idToBytes(id);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(idBytes);
        out.writeBytes(size(payload.length));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] idToBytes(long id) {
        int length = id > 0xFFFFFFL ? 4 : id > 0xFFFFL ? 3 : id > 0xFFL ? 2 : 1;
        byte[] out = new byte[length];
        for (int i = length - 1; i >= 0; i--) {
            out[i] = (byte) (id & 0xFF);
            id >>>= 8;
        }
        return out;
    }

    private static byte[] uint(long value) {
        if (value == 0) return new byte[]{0};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean started = false;
        for (int shift = 56; shift >= 0; shift -= 8) {
            int b = (int) ((value >>> shift) & 0xFF);
            if (b != 0) started = true;
            if (started) out.write(b);
        }
        return out.toByteArray();
    }

    private static byte[] str(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    /** A Block payload: track number vint, int16 relative timestamp, flags, then the data. */
    private static byte[] blockPayload(int trackNumber, int relativeMs, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80 | trackNumber);
        out.write((relativeMs >> 8) & 0xFF);
        out.write(relativeMs & 0xFF);
        out.write(0x00);
        out.writeBytes(data);
        return out.toByteArray();
    }

    private static byte[] subtitleTrackEntry(int number, String codec, String language,
                                             boolean defaultTrack, boolean forced) {
        return element(0xAE, concat(
                element(0xD7, uint(number)),
                element(0x83, uint(0x11)),
                element(0x86, str(codec)),
                element(0x22B59C, str(language)),
                element(0x88, uint(defaultTrack ? 1 : 0)),
                element(0x55AA, uint(forced ? 1 : 0))));
    }

    private static byte[] videoTrackEntry(int number) {
        return element(0xAE, concat(
                element(0xD7, uint(number)),
                element(0x83, uint(1)),
                element(0x86, str("V_MPEG4/ISO/AVC"))));
    }

    private static byte[] file(byte[] tracks, byte[] clusters) {
        byte[] info = element(0x1549A966, element(0x2AD7B1, uint(1_000_000)));
        return element(0x18538067, concat(info, tracks, clusters));
    }

    private static List<Cue> extract(byte[] mkv, int trackNumber) throws IOException {
        List<Cue> cues = new ArrayList<>();
        MatroskaSubtitleParser.extractCues(new ByteArrayInputStream(mkv), trackNumber,
                (start, end, text) -> cues.add(new Cue(start, end, text)));
        return cues;
    }

    private record Cue(long startMs, long endMs, String text) {
    }

    // ------------------------------------------------------------------ track listing

    @Test
    void readTracks_subtitleTrack_isReported() throws IOException {
        // given
        byte[] mkv = file(
                element(0x1654AE6B, concat(
                        videoTrackEntry(1),
                        subtitleTrackEntry(3, "S_TEXT/UTF8", "eng", true, false))),
                new byte[0]);

        // when
        List<SubtitleTrackInfo> tracks =
                MatroskaSubtitleParser.readTracks(new ByteArrayInputStream(mkv));

        // then
        assertEquals(1, tracks.size());
        SubtitleTrackInfo track = tracks.get(0);
        assertEquals("embedded:3", track.id());
        assertEquals("eng", track.language());
        assertEquals("S_TEXT/UTF8", track.codec());
        assertTrue(track.textBased());
        assertTrue(track.defaultTrack());
        assertTrue(track.isEnglish());
    }

    @Test
    void readTracks_videoAndAudioOnly_reportsNoSubtitles() throws IOException {
        // given — the 28 Days Later file in the real library looks like this
        byte[] mkv = file(element(0x1654AE6B, videoTrackEntry(1)), new byte[0]);

        // when
        List<SubtitleTrackInfo> tracks =
                MatroskaSubtitleParser.readTracks(new ByteArrayInputStream(mkv));

        // then
        assertTrue(tracks.isEmpty());
    }

    @Test
    void readTracks_bitmapSubtitleCodec_isReportedAsNotTextBased() throws IOException {
        // given — PGS is a picture format and would need OCR
        byte[] mkv = file(
                element(0x1654AE6B, subtitleTrackEntry(2, "S_HDMV/PGS", "eng", true, false)),
                new byte[0]);

        // when
        List<SubtitleTrackInfo> tracks =
                MatroskaSubtitleParser.readTracks(new ByteArrayInputStream(mkv));

        // then
        assertEquals(1, tracks.size());
        assertEquals(false, tracks.get(0).textBased());
    }

    @Test
    void readTracks_forcedFlag_isCarriedThrough() throws IOException {
        // given
        byte[] mkv = file(
                element(0x1654AE6B, subtitleTrackEntry(4, "S_TEXT/UTF8", "eng", false, true)),
                new byte[0]);

        // when
        List<SubtitleTrackInfo> tracks =
                MatroskaSubtitleParser.readTracks(new ByteArrayInputStream(mkv));

        // then
        assertTrue(tracks.get(0).forced());
        assertEquals(false, tracks.get(0).defaultTrack());
    }

    // ------------------------------------------------------------------ cue extraction

    @Test
    void extractCues_blockGroupWithDuration_usesThatDuration() throws IOException {
        // given — cluster at 1000ms, block +500ms, lasting 2000ms
        byte[] cluster = element(0x1F43B675, concat(
                element(0xE7, uint(1000)),
                element(0xA0, concat(
                        element(0xA1, blockPayload(3, 500, str("Hello"))),
                        element(0x9B, uint(2000))))));
        byte[] mkv = file(
                element(0x1654AE6B, subtitleTrackEntry(3, "S_TEXT/UTF8", "eng", true, false)),
                cluster);

        // when
        List<Cue> cues = extract(mkv, 3);

        // then
        assertEquals(1, cues.size());
        assertEquals(1500, cues.get(0).startMs());
        assertEquals(3500, cues.get(0).endMs());
        assertEquals("Hello", cues.get(0).text());
    }

    @Test
    void extractCues_blocksOfOtherTracks_areIgnored() throws IOException {
        // given — a video block sits between two subtitle blocks
        byte[] cluster = element(0x1F43B675, concat(
                element(0xE7, uint(0)),
                element(0xA3, blockPayload(1, 0, new byte[]{1, 2, 3, 4, 5})),
                element(0xA0, concat(
                        element(0xA1, blockPayload(3, 1000, str("Only me"))),
                        element(0x9B, uint(1000)))),
                element(0xA3, blockPayload(1, 40, new byte[]{9, 9, 9}))));
        byte[] mkv = file(
                element(0x1654AE6B, concat(
                        videoTrackEntry(1),
                        subtitleTrackEntry(3, "S_TEXT/UTF8", "eng", true, false))),
                cluster);

        // when
        List<Cue> cues = extract(mkv, 3);

        // then
        assertEquals(1, cues.size());
        assertEquals("Only me", cues.get(0).text());
    }

    @Test
    void extractCues_multipleClusters_accumulatesTimestamps() throws IOException {
        // given
        byte[] clusters = concat(
                element(0x1F43B675, concat(
                        element(0xE7, uint(0)),
                        element(0xA0, concat(
                                element(0xA1, blockPayload(3, 100, str("First"))),
                                element(0x9B, uint(900)))))),
                element(0x1F43B675, concat(
                        element(0xE7, uint(60_000)),
                        element(0xA0, concat(
                                element(0xA1, blockPayload(3, 250, str("Second"))),
                                element(0x9B, uint(1000)))))));
        byte[] mkv = file(
                element(0x1654AE6B, subtitleTrackEntry(3, "S_TEXT/UTF8", "eng", true, false)),
                clusters);

        // when
        List<Cue> cues = extract(mkv, 3);

        // then
        assertEquals(2, cues.size());
        assertEquals(100, cues.get(0).startMs());
        assertEquals(60_250, cues.get(1).startMs());
    }

    @Test
    void extractCues_simpleBlockWithoutDuration_endsWhenTheNextCueStarts() throws IOException {
        // given — SimpleBlock carries no duration, so the end has to be inferred
        byte[] cluster = element(0x1F43B675, concat(
                element(0xE7, uint(0)),
                element(0xA3, blockPayload(3, 1000, str("Open ended"))),
                element(0xA3, blockPayload(3, 2000, str("Next")))));
        byte[] mkv = file(
                element(0x1654AE6B, subtitleTrackEntry(3, "S_TEXT/UTF8", "eng", true, false)),
                cluster);

        // when
        List<Cue> cues = extract(mkv, 3);

        // then
        assertEquals(2, cues.size());
        assertEquals(2000, cues.get(0).endMs());
        // The last cue has nothing after it, so it falls back to the assumed length.
        assertEquals(5000, cues.get(1).endMs());
    }

    @Test
    void extractCues_nonDefaultTimestampScale_isApplied() throws IOException {
        // given — scale in nanoseconds; 100000ns means each tick is 0.1ms
        byte[] info = element(0x1549A966, element(0x2AD7B1, uint(100_000)));
        byte[] cluster = element(0x1F43B675, concat(
                element(0xE7, uint(10_000)),
                element(0xA0, concat(
                        element(0xA1, blockPayload(3, 0, str("Scaled"))),
                        element(0x9B, uint(10_000))))));
        byte[] mkv = element(0x18538067, concat(info,
                element(0x1654AE6B, subtitleTrackEntry(3, "S_TEXT/UTF8", "eng", true, false)),
                cluster));

        // when
        List<Cue> cues = extract(mkv, 3);

        // then — 10000 ticks * 0.1ms = 1000ms
        assertEquals(1000, cues.get(0).startMs());
        assertEquals(2000, cues.get(0).endMs());
    }

    @Test
    void extractCues_assCodec_takesOnlyTheDialogueText() throws IOException {
        // given — ASS blocks store the Dialogue fields, with the line itself last
        String assPayload = "0,0,Default,,0,0,0,,Actual dialogue here";
        byte[] cluster = element(0x1F43B675, concat(
                element(0xE7, uint(0)),
                element(0xA0, concat(
                        element(0xA1, blockPayload(2, 500, str(assPayload))),
                        element(0x9B, uint(1000))))));
        byte[] mkv = file(
                element(0x1654AE6B, subtitleTrackEntry(2, "S_TEXT/ASS", "eng", true, false)),
                cluster);

        // when
        List<Cue> cues = extract(mkv, 2);

        // then
        assertEquals("Actual dialogue here", cues.get(0).text());
    }

    @Test
    void extractCues_truncatedStream_keepsWhatItAlreadyRead() throws IOException {
        // given — a full file, cut off part way through
        byte[] cluster = element(0x1F43B675, concat(
                element(0xE7, uint(0)),
                element(0xA0, concat(
                        element(0xA1, blockPayload(3, 100, str("Survived"))),
                        element(0x9B, uint(900)))),
                element(0xA0, concat(
                        element(0xA1, blockPayload(3, 2000, str("Lost"))),
                        element(0x9B, uint(900))))));
        byte[] mkv = file(
                element(0x1654AE6B, subtitleTrackEntry(3, "S_TEXT/UTF8", "eng", true, false)),
                cluster);
        byte[] truncated = new byte[mkv.length - 30];
        System.arraycopy(mkv, 0, truncated, 0, truncated.length);

        // when
        List<Cue> cues = extract(truncated, 3);

        // then
        assertEquals(1, cues.size());
        assertEquals("Survived", cues.get(0).text());
    }
}
