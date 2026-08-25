package com.dlnahub.subtitle;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses SRT subtitle files, and the cue text found inside Matroska {@code S_TEXT/UTF8} blocks.
 *
 * <p>Encoding is sniffed rather than assumed. The library holds at least one UTF-16LE file
 * (Aliens) alongside ordinary UTF-8 ones, and older subtitles are frequently CP1252 — see
 * {@code plans/xbox-subtitles/FINDINGS.md} §1.
 */
public final class SrtParser {

    /**
     * Hours are optional in the wild and the decimal separator is a comma in SRT but a period
     * in WebVTT-flavoured files. Accept both rather than rejecting a whole file over punctuation.
     */
    private static final Pattern TIMING = Pattern.compile(
            "(?:(\\d{1,3}):)?(\\d{1,3}):(\\d{2})[,.](\\d{1,3})"
                    + "\\s*-->\\s*"
                    + "(?:(\\d{1,3}):)?(\\d{1,3}):(\\d{2})[,.](\\d{1,3})");

    /**
     * Only the tags that actually occur in subtitles. A blanket {@code <[^>]*>} would eat
     * dialogue like "<-- look at that".
     */
    private static final Pattern MARKUP = Pattern.compile(
            "</?(?:i|b|u|s|font|ruby|rt)(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);

    /** ASS/SSA override blocks, e.g. {\an8} for positioning. */
    private static final Pattern ASS_OVERRIDE = Pattern.compile("\\{\\\\[^}]*\\}");

    /**
     * Used when a file is neither BOM-marked nor valid UTF-8. Western European is the right
     * default for an English-language library, but it is not universal: Romanian, Polish and
     * Czech subtitles are usually ISO-8859-2, where the same bytes mean different letters
     * ({@code 0xC3} is 'Ă' in Latin-2 and 'Ã' in CP1252). Override via {@code subtitles.fallback-charset}.
     */
    public static final Charset DEFAULT_FALLBACK_CHARSET = Charset.forName("windows-1252");

    private SrtParser() {
    }

    /** Parses a whole SRT file. Never throws on malformed input — bad cues are dropped. */
    public static List<SubtitleCue> parse(byte[] data) {
        return parse(data, DEFAULT_FALLBACK_CHARSET);
    }

    public static List<SubtitleCue> parse(byte[] data, Charset fallbackCharset) {
        if (data == null || data.length == 0) {
            return List.of();
        }
        return parseText(decode(data, fallbackCharset));
    }

    static List<SubtitleCue> parseText(String text) {
        String normalised = text.replace("\r\n", "\n").replace('\r', '\n');
        List<SubtitleCue> cues = new ArrayList<>();

        for (String block : normalised.split("\n{2,}")) {
            Cue parsed = parseBlock(block);
            if (parsed != null) {
                cues.add(new SubtitleCue(0, parsed.startMs, parsed.endMs, parsed.lines));
            }
        }

        // Most files are already ordered; a few are not, and the frontend binary-searches these.
        cues.sort(Comparator.comparingLong(SubtitleCue::startMs));
        return reindex(cues);
    }

    /** Renumbers cues from 1 so the index reflects playback order, whatever the file said. */
    static List<SubtitleCue> reindex(List<SubtitleCue> cues) {
        List<SubtitleCue> out = new ArrayList<>(cues.size());
        for (int i = 0; i < cues.size(); i++) {
            SubtitleCue c = cues.get(i);
            out.add(new SubtitleCue(i + 1, c.startMs(), c.endMs(), c.lines()));
        }
        return out;
    }

    private static Cue parseBlock(String block) {
        String[] lines = block.split("\n");
        int timingLine = -1;
        Matcher matcher = null;

        for (int i = 0; i < lines.length; i++) {
            Matcher m = TIMING.matcher(lines[i]);
            if (m.find()) {
                timingLine = i;
                matcher = m;
                break;
            }
        }
        // A block with no timing line is a stray index or a comment. Drop it; one malformed
        // cue must not cost us the other two thousand.
        if (matcher == null) {
            return null;
        }

        long start = toMillis(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
        long end = toMillis(matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8));
        if (end < start) {
            end = start;
        }

        List<String> text = cleanLines(Arrays.asList(lines).subList(timingLine + 1, lines.length));
        if (text.isEmpty()) {
            return null;
        }
        return new Cue(start, end, text);
    }

    /** Strips markup from raw cue text and drops lines that were nothing but markup. */
    public static List<String> cleanLines(List<String> rawLines) {
        List<String> out = new ArrayList<>(rawLines.size());
        for (String raw : rawLines) {
            String cleaned = stripMarkup(raw).trim();
            if (!cleaned.isEmpty()) {
                out.add(cleaned);
            }
        }
        return out;
    }

    public static String stripMarkup(String input) {
        if (input == null || input.isEmpty()) return "";
        String out = MARKUP.matcher(input).replaceAll("");
        out = ASS_OVERRIDE.matcher(out).replaceAll("");
        return out.replace("\\N", " ").replace("\\n", " ");
    }

    private static long toMillis(String hours, String minutes, String seconds, String fraction) {
        long h = hours == null ? 0 : Long.parseLong(hours);
        long m = Long.parseLong(minutes);
        long s = Long.parseLong(seconds);
        // "5" in a millisecond field means 500ms, not 5ms — pad rather than parse blindly.
        long ms = Long.parseLong((fraction + "000").substring(0, 3));
        return ((h * 60 + m) * 60 + s) * 1000 + ms;
    }

    /**
     * Decodes subtitle bytes. BOM first; failing that, strict UTF-8 with a CP1252 fallback.
     *
     * <p>The strictness matters: decoding CP1252 leniently as UTF-8 silently replaces every
     * accented character rather than failing, so we would never learn we had guessed wrong.
     */
    static String decode(byte[] data) {
        return decode(data, DEFAULT_FALLBACK_CHARSET);
    }

    static String decode(byte[] data, Charset fallbackCharset) {
        if (hasPrefix(data, 0xEF, 0xBB, 0xBF)) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (hasPrefix(data, 0xFF, 0xFE)) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        if (hasPrefix(data, 0xFE, 0xFF)) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, fallbackCharset);
        }
    }

    private static boolean hasPrefix(byte[] data, int... prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }

    private record Cue(long startMs, long endMs, List<String> lines) {
    }
}
