package com.dlnahub.service;

import com.dlnahub.dlna.UpnpServiceManager;
import com.dlnahub.dlna.util.DidlUtils;
import com.dlnahub.dto.PlayRequestDto;
import com.dlnahub.exception.DlnaException;
import org.jupnp.controlpoint.ActionCallback;
import org.jupnp.controlpoint.ControlPoint;
import org.jupnp.model.action.ActionArgumentValue;
import org.jupnp.model.action.ActionException;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.meta.Action;
import org.jupnp.model.meta.ActionArgument;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.model.types.UDAServiceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AvTransportService {

    private static final Logger log = LoggerFactory.getLogger(AvTransportService.class);

    private static final int SKIP_SECONDS = 10;

    private final PlaybackService playbackService;
    private final UpnpServiceManager upnpServiceManager;

    @Autowired
    public AvTransportService(PlaybackService playbackService, UpnpServiceManager upnpServiceManager) {
        this.playbackService = playbackService;
        this.upnpServiceManager = upnpServiceManager;
    }

    private String resolveArgName(Action action, String standardName) {
        // Check both input and output arguments
        for (ActionArgument arg : action.getInputArguments()) {
            String n = arg.getName();
            if (n.equals(standardName) || n.equals("A_" + standardName)) {
                log.debug("Resolved arg '{}' -> '{}' for {}", standardName, n, action.getName());
                return n;
            }
        }
        for (ActionArgument arg : action.getOutputArguments()) {
            String n = arg.getName();
            if (n.equals(standardName) || n.equals("A_" + standardName)) {
                log.debug("Resolved arg '{}' -> '{}' for {}", standardName, n, action.getName());
                return n;
            }
        }
        log.warn("Argument '{}' not found on action {}. Available: {}",
                standardName, action.getName(), java.util.Arrays.toString(action.getInputArguments()));
        return standardName;
    }

    private void setInstanceId(ActionInvocation invocation, Action action) {
        invocation.setInput(resolveArgName(action, "InstanceID"), "0");
    }

    private void setInput(ActionInvocation invocation, Action action, String name, String value) {
        invocation.setInput(resolveArgName(action, name), value);
    }

    private String getOutput(ActionInvocation invocation, Action action, String name) {
        ActionArgumentValue output = invocation.getOutput(resolveArgName(action, name));
        if (output == null) return null;
        Object value = output.getValue();
        return value != null ? value.toString() : null;
    }

    public void setUriAndPlay(String playerId, PlayRequestDto request) {
        RemoteDevice device = playbackService.getDevice(playerId);
        RemoteService service = device.findService(new UDAServiceType("AVTransport"));
        if (service == null) {
            throw new DlnaException("AVTransport service not found on player: " + playerId);
        }

        String uri = request.getUri();
        if (uri == null || uri.isEmpty()) {
            throw new DlnaException("URI is required for playback");
        }

        String metadataXml;
        if (request.getMetadataXml() != null && !request.getMetadataXml().isEmpty()) {
            metadataXml = request.getMetadataXml();
        } else {
            metadataXml = DidlUtils.generateSimpleMetadataXml(
                    uri,
                    request.getTitle(),
                    request.getMimeType(),
                    request.getProtocolInfo()
            );
        }

        Action setUriAction = service.getAction("SetAVTransportURI");
        if (setUriAction == null) {
            throw new DlnaException("SetAVTransportURI action not supported on this player");
        }

        log.info("SetAVTransportURI inputs: {}", java.util.Arrays.toString(setUriAction.getInputArguments()));

        ActionInvocation invocation = new ActionInvocation(setUriAction);
        setInstanceId(invocation, setUriAction);
        setInput(invocation, setUriAction, "CurrentURI", uri);
        setInput(invocation, setUriAction, "CurrentURIMetaData", metadataXml);
        executeSync(invocation, playerId);

        log.info("URI set on player {} to {}", playerId, uri);
    }

    public void play(String playerId) {
        RemoteService service = playbackService.getAvTransportService(playerId);

        Action playAction = service.getAction("Play");
        if (playAction == null) {
            throw new DlnaException("Play action not supported on this player");
        }

        ActionInvocation invocation = new ActionInvocation(playAction);
        setInstanceId(invocation, playAction);
        setInput(invocation, playAction, "Speed", "1");
        executeSync(invocation, playerId);

        log.info("Play command sent to player {}", playerId);
    }

    public void pause(String playerId) {
        RemoteService service = playbackService.getAvTransportService(playerId);

        Action pauseAction = service.getAction("Pause");
        if (pauseAction == null) {
            throw new DlnaException("Pause action not supported on this player");
        }

        ActionInvocation invocation = new ActionInvocation(pauseAction);
        setInstanceId(invocation, pauseAction);
        executeSync(invocation, playerId);

        log.info("Pause command sent to player {}", playerId);
    }

    public void stop(String playerId) {
        RemoteService service = playbackService.getAvTransportService(playerId);

        Action stopAction = service.getAction("Stop");
        if (stopAction == null) {
            throw new DlnaException("Stop action not supported on this player");
        }

        ActionInvocation invocation = new ActionInvocation(stopAction);
        setInstanceId(invocation, stopAction);
        executeSync(invocation, playerId);

        log.info("Stop command sent to player {}", playerId);
    }

    public void seek(String playerId, int seconds) {
        if (seconds < 0) {
            throw new DlnaException("Seek target must not be negative: " + seconds);
        }
        RemoteService service = playbackService.getAvTransportService(playerId);

        Action seekAction = service.getAction("Seek");
        if (seekAction == null) {
            throw new DlnaException("Seek action not supported on this player");
        }

        String targetTime = formatTime(seconds);
        ActionInvocation invocation = new ActionInvocation(seekAction);
        setInstanceId(invocation, seekAction);
        setInput(invocation, seekAction, "Unit", "REL_TIME");
        setInput(invocation, seekAction, "Target", targetTime);
        executeSync(invocation, playerId);

        log.info("Seek to {} ({}) on player {}", targetTime, seconds, playerId);
    }

    public void forward(String playerId) {
        PositionInfo info = readPositionInfoQuietly(playerId);
        int currentSeconds = parseTimeSeconds(info != null ? info.trackPosition() : null);
        int durationSeconds = parseTimeSeconds(info != null ? info.trackDuration() : null);
        int newSeconds = currentSeconds + SKIP_SECONDS;
        if (durationSeconds > 0) {
            newSeconds = Math.min(newSeconds, Math.max(0, durationSeconds - 1));
        }
        seek(playerId, newSeconds);
    }

    public void backward(String playerId) {
        PositionInfo info = readPositionInfoQuietly(playerId);
        int currentSeconds = parseTimeSeconds(info != null ? info.trackPosition() : null);
        seek(playerId, Math.max(0, currentSeconds - SKIP_SECONDS));
    }

    /** GetPositionInfo, or null if the renderer refuses it — skipping must not hard-fail. */
    private PositionInfo readPositionInfoQuietly(String playerId) {
        try {
            return getPositionInfo(playerId);
        } catch (DlnaException e) {
            log.warn("Failed to get position info for {}: {}", playerId, e.getMessage());
            return null;
        }
    }

    public String getTransportState(String playerId) {
        RemoteService service = playbackService.getAvTransportService(playerId);

        Action action = service.getAction("GetTransportInfo");
        if (action == null) {
            throw new DlnaException("GetTransportInfo action not supported on this player");
        }

        log.info("GetTransportInfo inputs: {}", java.util.Arrays.toString(action.getInputArguments()));
        log.info("GetTransportInfo outputs: {}", java.util.Arrays.toString(action.getOutputArguments()));

        ActionInvocation invocation = new ActionInvocation(action);
        setInstanceId(invocation, action);
        executeSync(invocation, playerId);

        String state = getOutput(invocation, action, "CurrentTransportState");
        return state != null ? state : "UNKNOWN";
    }

    public PositionInfo getPositionInfo(String playerId) {
        RemoteService service = playbackService.getAvTransportService(playerId);

        Action action = service.getAction("GetPositionInfo");
        if (action == null) {
            throw new DlnaException("GetPositionInfo action not supported on this player");
        }

        log.info("GetPositionInfo inputs: {}", java.util.Arrays.toString(action.getInputArguments()));
        log.info("GetPositionInfo outputs: {}", java.util.Arrays.toString(action.getOutputArguments()));

        ActionInvocation invocation = new ActionInvocation(action);
        setInstanceId(invocation, action);
        executeSync(invocation, playerId);

        return new PositionInfo(
                getOutput(invocation, action, "TrackURI"),
                getOutput(invocation, action, "TrackDuration"),
                getOutput(invocation, action, "RelTime"),
                getOutput(invocation, action, "TrackMetaData")
        );
    }

    private void executeSync(ActionInvocation invocation, String playerId) {
        ControlPoint controlPoint = upnpServiceManager.getUpnpService().getControlPoint();
        new ActionCallback.Default(invocation, controlPoint).run();

        ActionException failure = invocation.getFailure();
        if (failure != null) {
            int errorCode = failure.getErrorCode() > 0 ? failure.getErrorCode() : -1;
            throw new DlnaException(
                    "AVTransport action failed on player " + playerId + ": " + failure.getMessage(),
                    errorCode
            );
        }
    }


    public static String formatTime(int totalSeconds) {
        int clamped = Math.max(0, totalSeconds);
        int hours = clamped / 3600;
        int minutes = (clamped % 3600) / 60;
        int seconds = clamped % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    public static int parseTimeSeconds(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return 0;

        String[] parts = timeStr.split(":");
        try {
            switch (parts.length) {
                case 3:
                    return Integer.parseInt(parts[0]) * 3600
                            + Integer.parseInt(parts[1]) * 60
                            + Integer.parseInt(parts[2]);
                case 2:
                    return Integer.parseInt(parts[0]) * 60
                            + Integer.parseInt(parts[1]);
                case 1:
                    return Integer.parseInt(parts[0]);
                default:
                    return 0;
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse time string: {}", timeStr);
            return 0;
        }
    }

    public record PositionInfo(String trackUri, String trackDuration, String trackPosition, String trackMetaData) {
    }
}
