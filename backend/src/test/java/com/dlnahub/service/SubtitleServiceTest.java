package com.dlnahub.service;

import com.dlnahub.subtitle.SubtitleTrackInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubtitleServiceTest {

    private static SubtitleTrackInfo embedded(String id, String language, boolean defaultTrack,
                                              boolean forced, boolean textBased) {
        return new SubtitleTrackInfo(id, SubtitleTrackInfo.Source.EMBEDDED, language, null,
                textBased ? "S_TEXT/UTF8" : "S_HDMV/PGS", defaultTrack, forced, textBased);
    }

    // ---------------------------------------------------------------- sidecar URL

    @Test
    void deriveSidecarUrl_mp4Url_swapsExtensionToSrt() {
        // given
        String video = "http://10.0.0.60:50002/v/NDLNA/38318.mp4";

        // when
        String subtitle = SubtitleService.deriveSidecarUrl(video);

        // then
        assertEquals("http://10.0.0.60:50002/v/NDLNA/38318.srt", subtitle);
    }

    @Test
    void deriveSidecarUrl_mkvUrl_swapsExtensionToSrt() {
        // given
        String video = "http://10.0.0.60:50002/v/NDLNA/38337.mkv";

        // when
        String subtitle = SubtitleService.deriveSidecarUrl(video);

        // then
        assertEquals("http://10.0.0.60:50002/v/NDLNA/38337.srt", subtitle);
    }

    @Test
    void deriveSidecarUrl_urlWithNoExtension_returnsNull() {
        // given
        String video = "http://10.0.0.60:50002/v/NDLNA/38318";

        // when
        String subtitle = SubtitleService.deriveSidecarUrl(video);

        // then
        assertNull(subtitle);
    }

    @Test
    void deriveSidecarUrl_dotOnlyInHost_returnsNull() {
        // given — the dots are in the IP address, not a filename
        String video = "http://10.0.0.60:50002/v/NDLNA/file";

        // when
        String subtitle = SubtitleService.deriveSidecarUrl(video);

        // then
        assertNull(subtitle);
    }

    @Test
    void deriveSidecarUrl_urlWithQueryString_returnsNull() {
        // given — swapping here would bury the query inside the filename
        String video = "http://host/v/38318.mp4?token=abc";

        // when
        String subtitle = SubtitleService.deriveSidecarUrl(video);

        // then
        assertNull(subtitle);
    }

    @Test
    void deriveSidecarUrl_nullOrEmpty_returnsNull() {
        // given

        // when

        // then
        assertNull(SubtitleService.deriveSidecarUrl(null));
        assertNull(SubtitleService.deriveSidecarUrl(""));
    }

    // ---------------------------------------------------------------- container detection

    @Test
    void isMatroska_mkvUrl_isTrue() {
        // given

        // when

        // then
        assertTrue(SubtitleService.isMatroska("http://host/a/b.mkv"));
        assertTrue(SubtitleService.isMatroska("http://host/a/B.MKV"));
    }

    @Test
    void isMatroska_mp4Url_isFalse() {
        // given

        // when

        // then
        assertFalse(SubtitleService.isMatroska("http://host/a/b.mp4"));
        assertFalse(SubtitleService.isMatroska(null));
    }

    // ---------------------------------------------------------------- track preference

    @Test
    void pickDefault_englishEmbeddedAndSidecar_prefersTheEnglishTrack() {
        // given — a sidecar carries no language, so a known-English track beats it
        List<SubtitleTrackInfo> tracks = List.of(
                SubtitleTrackInfo.sidecar(),
                embedded("embedded:3", "eng", false, false, true));

        // when
        SubtitleTrackInfo picked = SubtitleService.pickDefault(tracks);

        // then
        assertEquals("embedded:3", picked.id());
    }

    @Test
    void pickDefault_sidecarAndForeignEmbedded_prefersTheSidecar() {
        // given
        List<SubtitleTrackInfo> tracks = List.of(
                embedded("embedded:5", "fre", true, false, true),
                SubtitleTrackInfo.sidecar());

        // when
        SubtitleTrackInfo picked = SubtitleService.pickDefault(tracks);

        // then
        assertEquals("sidecar", picked.id());
    }

    @Test
    void pickDefault_twoEnglishTracks_prefersTheDefaultFlagged() {
        // given — the Beverly Hills Cop case: plain, default and SDH English tracks
        List<SubtitleTrackInfo> tracks = List.of(
                embedded("embedded:4", "eng", false, false, true),
                embedded("embedded:3", "eng", true, false, true),
                embedded("embedded:5", "eng", false, false, true));

        // when
        SubtitleTrackInfo picked = SubtitleService.pickDefault(tracks);

        // then
        assertEquals("embedded:3", picked.id());
    }

    @Test
    void pickDefault_forcedEnglishTrack_losesToAnOrdinaryOne() {
        // given — a forced track only covers foreign dialogue
        List<SubtitleTrackInfo> tracks = List.of(
                embedded("embedded:2", "eng", true, true, true),
                embedded("embedded:6", "eng", false, false, true));

        // when
        SubtitleTrackInfo picked = SubtitleService.pickDefault(tracks);

        // then
        assertEquals("embedded:6", picked.id());
    }

    @Test
    void pickDefault_onlyBitmapTracks_returnsNull() {
        // given — PGS cannot be read without OCR, so offering it would be a lie
        List<SubtitleTrackInfo> tracks = List.of(
                embedded("embedded:2", "eng", true, false, false));

        // when
        SubtitleTrackInfo picked = SubtitleService.pickDefault(tracks);

        // then
        assertNull(picked);
    }

    @Test
    void pickDefault_noTracks_returnsNull() {
        // given

        // when
        SubtitleTrackInfo picked = SubtitleService.pickDefault(List.of());

        // then
        assertNull(picked);
    }
}
