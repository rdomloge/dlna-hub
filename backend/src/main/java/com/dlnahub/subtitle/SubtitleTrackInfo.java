package com.dlnahub.subtitle;

/**
 * A subtitle track that can be offered for an item.
 *
 * <p>Two sources exist and they cost very different amounts to read — see
 * {@code plans/xbox-subtitles/FINDINGS.md} §1 and §3:
 *
 * <ul>
 *   <li>{@link Source#SIDECAR} — a {@code .srt} the NAS serves at the video URL with the
 *       extension swapped. One small HTTP GET.
 *   <li>{@link Source#EMBEDDED} — a track inside the Matroska container. Listing it is a
 *       range request over the file header; extracting its cues is a full-file scan.
 * </ul>
 *
 * @param id         opaque handle the client passes back, e.g. {@code sidecar} or {@code embedded:3}
 * @param source     where the cues come from
 * @param language   ISO-639-2 code as the container reports it ({@code eng}), or null for a sidecar
 * @param name       the track's own name, e.g. "SDH", when the container supplies one
 * @param codec      {@code SRT} for a sidecar, otherwise the Matroska CodecID
 * @param defaultTrack the container's FlagDefault
 * @param forced     the container's FlagForced — usually a translations-only track
 * @param textBased  false for bitmap formats (PGS, VobSub), which we cannot read without OCR
 */
public record SubtitleTrackInfo(
        String id,
        Source source,
        String language,
        String name,
        String codec,
        boolean defaultTrack,
        boolean forced,
        boolean textBased) {

    public enum Source {
        SIDECAR,
        EMBEDDED
    }

    public static SubtitleTrackInfo sidecar() {
        return new SubtitleTrackInfo("sidecar", Source.SIDECAR, null, null, "SRT",
                false, false, true);
    }

    /** True when this track's language looks like English. */
    public boolean isEnglish() {
        if (language == null) return false;
        String lang = language.toLowerCase();
        return lang.startsWith("en");
    }
}
