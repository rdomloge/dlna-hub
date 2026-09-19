package com.dlnahub.controller;

import com.dlnahub.dto.PlaybackStatusDto;
import com.dlnahub.exception.DlnaException;
import com.dlnahub.service.AvTransportService;
import com.dlnahub.service.RenderingControlService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlaybackControllerTest {

    private static final String PLAYER_ID = "player-1";

    private final AvTransportService avTransportService = Mockito.mock(AvTransportService.class);
    private final RenderingControlService renderingControlService =
            Mockito.mock(RenderingControlService.class);
    private final PlaybackController controller =
            new PlaybackController(avTransportService, renderingControlService);

    @Test
    void status_connectingState_skipsPositionInfoCall() {
        // given a renderer that has accepted the URI and is still bringing the stream up — in
        // this state it answers GetTransportInfo and nothing else
        when(avTransportService.getTransportState(PLAYER_ID)).thenReturn("CONNECTING");

        // when
        ResponseEntity<PlaybackStatusDto> response = controller.status(PLAYER_ID, true);

        // then the position call is never made, so a status poll cannot block on it
        verify(avTransportService, never()).getPositionInfo(Mockito.anyString());
        PlaybackStatusDto body = response.getBody();
        assert body != null;
        assertEquals("CONNECTING", body.getState());
        assertNull(body.getTrackPosition());
        assertNull(body.getTrackDuration());
    }

    @Test
    void status_playingState_fetchesPositionInfo() {
        // given
        when(avTransportService.getTransportState(PLAYER_ID)).thenReturn("PLAYING");
        when(avTransportService.getPositionInfo(PLAYER_ID)).thenReturn(
                new AvTransportService.PositionInfo(
                        "http://nas/media.mkv", "00:42:10", "00:01:23", null));
        when(renderingControlService.getVolume(PLAYER_ID)).thenReturn(30);

        // when
        PlaybackStatusDto body = controller.status(PLAYER_ID, true).getBody();

        // then
        assert body != null;
        assertEquals("PLAYING", body.getState());
        assertEquals("00:42:10", body.getTrackDuration());
        assertEquals("00:01:23", body.getTrackPosition());
        assertEquals(Integer.valueOf(30), body.getVolume());
    }

    @Test
    void status_includeVolumeFalse_skipsRenderingControlCall() {
        // given
        when(avTransportService.getTransportState(PLAYER_ID)).thenReturn("PLAYING");
        when(avTransportService.getPositionInfo(PLAYER_ID)).thenReturn(
                new AvTransportService.PositionInfo(
                        "http://nas/media.mkv", "00:42:10", "00:01:23", null));

        // when
        PlaybackStatusDto body = controller.status(PLAYER_ID, false).getBody();

        // then the whole RenderingControl round-trip is skipped
        verify(renderingControlService, never()).getVolume(PLAYER_ID);
        assert body != null;
        assertNull(body.getVolume());
        assertEquals("00:01:23", body.getTrackPosition());
    }

    @Test
    void status_positionInfoFails_leavesPositionUnknownRatherThanZero() {
        // given a renderer that refuses the position call
        when(avTransportService.getTransportState(PLAYER_ID)).thenReturn("PLAYING");
        when(avTransportService.getPositionInfo(PLAYER_ID))
                .thenThrow(new DlnaException("action failed"));

        // when
        PlaybackStatusDto body = controller.status(PLAYER_ID, true).getBody();

        // then the position is absent, not 00:00:00 — a zero is a real reading and the client
        // would move the playhead to the start on it
        assert body != null;
        assertNull(body.getTrackPosition());
        assertNull(body.getTrackDuration());
    }

    @Test
    void status_volumeFails_stillReportsPlaybackState() {
        // given
        when(avTransportService.getTransportState(PLAYER_ID)).thenReturn("PLAYING");
        when(avTransportService.getPositionInfo(PLAYER_ID)).thenReturn(
                new AvTransportService.PositionInfo(
                        "http://nas/media.mkv", "00:42:10", "00:01:23", null));
        when(renderingControlService.getVolume(PLAYER_ID))
                .thenThrow(new DlnaException("service unavailable"));

        // when
        PlaybackStatusDto body = controller.status(PLAYER_ID, true).getBody();

        // then
        assert body != null;
        assertEquals("PLAYING", body.getState());
        assertNull(body.getVolume());
    }

    @Test
    void status_includeVolumeQueryParam_absentOrFalseControlsTheRenderingControlCall()
            throws Exception {
        // given a caller hitting the HTTP endpoint rather than the Java method — only the real
        // request binding proves the parameter name the frontend sends is the one we read
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(avTransportService.getTransportState(PLAYER_ID)).thenReturn("PLAYING");
        when(avTransportService.getPositionInfo(PLAYER_ID)).thenReturn(
                new AvTransportService.PositionInfo(
                        "http://nas/media.mkv", "00:42:10", "00:01:23", null));
        when(renderingControlService.getVolume(PLAYER_ID)).thenReturn(40);

        // when
        mockMvc.perform(get("/api/players/" + PLAYER_ID + "/status")
                        .param("includeVolume", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.volume").doesNotExist());
        mockMvc.perform(get("/api/players/" + PLAYER_ID + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.volume").value(40));

        // then: only the request that asked for volume paid for it; a caller that says nothing
        // still gets it, so an older client keeps the behaviour it has
        verify(renderingControlService, times(1)).getVolume(PLAYER_ID);
    }
}
