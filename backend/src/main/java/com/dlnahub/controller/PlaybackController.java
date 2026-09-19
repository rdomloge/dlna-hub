package com.dlnahub.controller;

import com.dlnahub.dlna.util.DidlUtils;
import com.dlnahub.exception.DlnaException;
import com.dlnahub.dto.PlayRequestDto;
import com.dlnahub.dto.PlaybackStatusDto;
import com.dlnahub.dto.SeekRequestDto;
import com.dlnahub.dto.VolumeRequestDto;
import com.dlnahub.service.AvTransportService;
import com.dlnahub.service.RenderingControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/players")
public class PlaybackController {

    private static final Logger log = LoggerFactory.getLogger(PlaybackController.class);

    /**
     * The UPnP transport state reported while a renderer is setting the stream up. A renderer that
     * is connecting has no position to report, and asking for one is expensive rather than merely
     * pointless: the Xbox answers GetTransportInfo promptly but stops answering its whole control
     * endpoint until the stream is up, so GetPositionInfo sits out jUPnP's full 10s timeout and
     * holds up the status response with it — long enough for the UI to give up waiting on us.
     */
    private static final String STATE_CONNECTING = "CONNECTING";

    private final AvTransportService avTransportService;
    private final RenderingControlService renderingControlService;

    @Autowired
    public PlaybackController(AvTransportService avTransportService,
                               RenderingControlService renderingControlService) {
        this.avTransportService = avTransportService;
        this.renderingControlService = renderingControlService;
    }

    @PostMapping("/{playerId}/play")
    public ResponseEntity<Map<String, Object>> play(@PathVariable String playerId,
                                                      @RequestBody(required = false) PlayRequestDto request) {
        log.info("Play request for player {}: {}", playerId, request);
        if (request != null && request.getUri() != null && !request.getUri().isEmpty()) {
            avTransportService.setUriAndPlay(playerId, request);
            avTransportService.play(playerId);
        } else {
            avTransportService.play(playerId);
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{playerId}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable String playerId) {
        log.info("Pause request for player {}", playerId);
        avTransportService.pause(playerId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{playerId}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String playerId) {
        log.info("Stop request for player {}", playerId);
        avTransportService.stop(playerId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{playerId}/seek")
    public ResponseEntity<Map<String, Object>> seek(@PathVariable String playerId,
                                                      @RequestBody @jakarta.validation.Valid SeekRequestDto request) {
        log.info("Seek request for player {} to {} seconds", playerId, request.getSeconds());
        avTransportService.seek(playerId, request.getSeconds());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{playerId}/forward")
    public ResponseEntity<Map<String, Object>> forward(@PathVariable String playerId) {
        log.info("Forward request for player {}", playerId);
        avTransportService.forward(playerId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{playerId}/backward")
    public ResponseEntity<Map<String, Object>> backward(@PathVariable String playerId) {
        log.info("Backward request for player {}", playerId);
        avTransportService.backward(playerId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/{playerId}/status")
    public ResponseEntity<PlaybackStatusDto> status(@PathVariable String playerId,
                                                      @RequestParam(defaultValue = "true") boolean includeVolume) {
        log.debug("Status request for player {} (includeVolume={})", playerId, includeVolume);
        String state = avTransportService.getTransportState(playerId);

        // Null rather than "00:00:00" for anything we did not learn: a zero is a real position
        // reading, and the UI would move the playhead to the start on it. Absent means "keep
        // whatever is already on screen".
        String trackUri = null;
        String trackDuration = null;
        String trackPosition = null;
        String trackTitle = null;
        if (!STATE_CONNECTING.equalsIgnoreCase(state)) {
            try {
                var positionInfo = avTransportService.getPositionInfo(playerId);
                trackUri = positionInfo.trackUri();
                trackDuration = positionInfo.trackDuration();
                trackPosition = positionInfo.trackPosition();
                trackTitle = DidlUtils.extractTitleFromMetadata(positionInfo.trackMetaData());
            } catch (Exception e) {
                // The state is worth having alongside this: a renderer that refuses the position
                // call while claiming to be PLAYING is a different problem from one that is
                // honestly still CONNECTING, and this is the only place we see the difference.
                log.warn("GetPositionInfo failed for player {} while state={}: {}",
                        playerId, state, e.getMessage());
            }
        }

        Integer volume = null;
        if (includeVolume) {
            try {
                volume = renderingControlService.getVolume(playerId);
            } catch (Exception e) {
                log.warn("GetVolume failed for player {}: {}", playerId, e.getMessage());
            }
        }

        PlaybackStatusDto dto = new PlaybackStatusDto();
        dto.setState(state);
        dto.setTrackUri(trackUri);
        dto.setTrackDuration(trackDuration);
        dto.setTrackPosition(trackPosition);
        dto.setTrackTitle(trackTitle);
        dto.setVolume(volume);

        return ResponseEntity.ok(dto);
    }

    @GetMapping("/{playerId}/volume")
    public ResponseEntity<Map<String, Integer>> getVolume(@PathVariable String playerId) {
        log.debug("Get volume request for player {}", playerId);
        int volume = renderingControlService.getVolume(playerId);
        return ResponseEntity.ok(Map.of("volume", volume));
    }

    @PutMapping("/{playerId}/volume")
    public ResponseEntity<Map<String, Object>> setVolume(@PathVariable String playerId,
                                                           @RequestBody @jakarta.validation.Valid VolumeRequestDto request) {
        log.info("Set volume request for player {} to {}", playerId, request.getVolume());
        renderingControlService.setVolume(playerId, request.getVolume());
        return ResponseEntity.ok(Map.of("success", true));
    }
}
