package com.dlnahub.subtitle;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SrtParserTest {

    @Test
    void parse_simpleCue_readsTimesAndText() {
        // given
        String srt = "1\n00:00:01,500 --> 00:00:03,750\nHello there\n";

        // when
        List<SubtitleCue> cues = SrtParser.parse(srt.getBytes(StandardCharsets.UTF_8));

        // then
        assertEquals(1, cues.size());
        assertEquals(1500, cues.get(0).startMs());
        assertEquals(3750, cues.get(0).endMs());
        assertEquals(List.of("Hello there"), cues.get(0).lines());
    }

    @Test
    void parse_crlfLineEndings_producesCues() {
        // given — the NAS serves CRLF files
        String srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nLine one\r\nLine two\r\n\r\n";

        // when
        List<SubtitleCue> cues = SrtParser.parse(srt.getBytes(StandardCharsets.UTF_8));

        // then
        assertEquals(1, cues.size());
        assertEquals(List.of("Line one", "Line two"), cues.get(0).lines());
    }

    @Test
    void parse_multipleCues_indexesFromOne() {
        // given
        String srt = """
                1
                00:00:01,000 --> 00:00:02,000
                First

                2
                00:00:03,000 --> 00:00:04,000
                Second
                """;

        // when
        List<SubtitleCue> cues = SrtParser.parse(srt.getBytes(StandardCharsets.UTF_8));

        // then
        assertEquals(2, cues.size());
        assertEquals(1, cues.get(0).index());
        assertEquals(2, cues.get(1).index());
    }

    @Test
    void parse_outOfOrderCues_areSortedByStart() {
        // given — the frontend binary-searches these, so order is load-bearing
        String srt = """
                1
                00:00:09,000 --> 00:00:10,000
                Later

                2
                00:00:02,000 --> 00:00:03,000
                Earlier
                """;

        // when
        List<SubtitleCue> cues = SrtParser.parse(srt.getBytes(StandardCharsets.UTF_8));

        // then
        assertEquals(2000, cues.get(0).startMs());
        assertEquals(List.of("Earlier"), cues.get(0).lines());
        assertEquals(9000, cues.get(1).startMs());
    }

    @Test
    void parse_cueWithMalformedTiming_skipsThatCueOnly() {
        // given
        String srt = """
                1
                not a timing line
                Broken

                2
                00:00:05,000 --> 00:00:06,000
                Fine
                """;

        // when
        List<SubtitleCue> cues = SrtParser.parse(srt.getBytes(StandardCharsets.UTF_8));

        // then
        assertEquals(1, cues.size());
        assertEquals(List.of("Fine"), cues.get(0).lines());
    }

    @Test
    void parse_italicTags_areStripped() {
        // given — Matroska S_TEXT/UTF8 cues are full of these
        String srt = "1\n00:00:01,000 --> 00:00:02,000\n<i>Tonight, a</i> 60 Minutes <i>exclusive.</i>\n";

        // when
        List<SubtitleCue> cues = SrtParser.parse(srt.getBytes(StandardCharsets.UTF_8));

        // then
        assertEquals(List.of("Tonight, a 60 Minutes exclusive."), cues.get(0).lines());
    }

    @Test
    void parse_hoursOmitted_stillParses() {
        // given
        String srt = "1\n01:30,000 --> 01:32,000\nShort form\n";

        // when
        List<SubtitleCue> cues = SrtParser.parse(srt.getBytes(StandardCharsets.UTF_8));

        // then
        assertEquals(90_000, cues.get(0).startMs());
        assertEquals(92_000, cues.get(0).endMs());
    }

    @Test
    void parse_periodDecimalSeparator_isAccepted() {
        // given
        String srt = "1\n00:00:01.250 --> 00:00:02.500\nWebVTT flavour\n";

        // when
        List<SubtitleCue> cues = SrtParser.parse(srt.getBytes(StandardCharsets.UTF_8));

        // then
        assertEquals(1250, cues.get(0).startMs());
        assertEquals(2500, cues.get(0).endMs());
    }

    @Test
    void parse_endBeforeStart_clampsEndToStart() {
        // given
        String srt = "1\n00:00:05,000 --> 00:00:02,000\nBackwards\n";

        // when
        List<SubtitleCue> cues = SrtParser.parse(srt.getBytes(StandardCharsets.UTF_8));

        // then
        assertEquals(5000, cues.get(0).startMs());
        assertEquals(5000, cues.get(0).endMs());
    }

    @Test
    void decode_utf16LeWithBom_decodesText() {
        // given — the Aliens sidecar in the real library is UTF-16LE
        byte[] bom = {(byte) 0xFF, (byte) 0xFE};
        byte[] text = "There goes our salvage.".getBytes(StandardCharsets.UTF_16LE);
        byte[] data = new byte[bom.length + text.length];
        System.arraycopy(bom, 0, data, 0, bom.length);
        System.arraycopy(text, 0, data, bom.length, text.length);

        // when
        String decoded = SrtParser.decode(data);

        // then
        assertEquals("There goes our salvage.", decoded);
        assertFalse(decoded.contains("\0"));
    }

    @Test
    void decode_utf8WithBom_stripsTheBom() {
        // given
        byte[] data = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'H', 'i'};

        // when
        String decoded = SrtParser.decode(data);

        // then
        assertEquals("Hi", decoded);
    }

    @Test
    void decode_invalidUtf8_fallsBackToTheGivenCharset() {
        // given — 0xC3 followed by 'R' is not valid UTF-8. It is 'A-breve' in ISO-8859-2 and
        // 'A-tilde' in windows-1252, which is why the fallback has to be configurable.
        byte[] data = "DISP".getBytes(StandardCharsets.US_ASCII);
        byte[] full = new byte[data.length + 5];
        System.arraycopy(data, 0, full, 0, data.length);
        full[data.length] = (byte) 0xC3;
        full[data.length + 1] = 'R';
        full[data.length + 2] = 'U';
        full[data.length + 3] = 'T';
        full[data.length + 4] = 'E';

        // when
        String latin2 = SrtParser.decode(full, Charset.forName("ISO-8859-2"));
        String cp1252 = SrtParser.decode(full, Charset.forName("windows-1252"));

        // then
        assertEquals("DISPĂRUTE", latin2);
        assertEquals("DISPÃRUTE", cp1252);
    }

    @Test
    void decode_validUtf8_isNotSentToTheFallback() {
        // given
        byte[] data = "Ce dracu' faci ?".getBytes(StandardCharsets.UTF_8);

        // when
        String decoded = SrtParser.decode(data, Charset.forName("ISO-8859-2"));

        // then
        assertEquals("Ce dracu' faci ?", decoded);
    }

    @Test
    void stripMarkup_fontAndAssOverrides_areRemoved() {
        // given
        String input = "{\\an8}<font color=\"#ffffff\">Positioned</font>";

        // when
        String stripped = SrtParser.stripMarkup(input);

        // then
        assertEquals("Positioned", stripped);
    }

    @Test
    void stripMarkup_dialogueContainingAngleBrackets_isPreserved() {
        // given — a blanket <[^>]*> would eat this
        String input = "<-- look at that";

        // when
        String stripped = SrtParser.stripMarkup(input);

        // then
        assertEquals("<-- look at that", stripped);
    }

    @Test
    void cleanLines_linesThatWereOnlyMarkup_areDropped() {
        // given
        List<String> raw = List.of("<i>", "Real text", "  ");

        // when
        List<String> cleaned = SrtParser.cleanLines(raw);

        // then
        assertEquals(List.of("Real text"), cleaned);
    }

    @Test
    void parse_emptyInput_returnsNoCues() {
        // given

        // when
        List<SubtitleCue> cues = SrtParser.parse(new byte[0]);

        // then
        assertTrue(cues.isEmpty());
    }
}
