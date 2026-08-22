package com.dlnahub.service;

import com.dlnahub.exception.DlnaException;
import org.jupnp.controlpoint.ActionCallback;
import org.jupnp.controlpoint.ControlPoint;
import org.jupnp.model.action.ActionArgumentValue;
import org.jupnp.model.action.ActionException;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.meta.Action;
import org.jupnp.model.meta.ActionArgument;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.model.types.UDAServiceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RenderingControlService {

    private static final Logger log = LoggerFactory.getLogger(RenderingControlService.class);

    private final PlaybackService playbackService;
    private final com.dlnahub.dlna.UpnpServiceManager upnpServiceManager;

    @Autowired
    public RenderingControlService(PlaybackService playbackService,
                                     com.dlnahub.dlna.UpnpServiceManager upnpServiceManager) {
        this.playbackService = playbackService;
        this.upnpServiceManager = upnpServiceManager;
    }

    private String resolveArgName(Action action, String standardName) {
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
        return standardName;
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

    public int getVolume(String playerId) {
        RemoteService service = playbackService.getRenderingControlService(playerId);

        Action action = service.getAction("GetVolume");
        if (action == null) {
            throw new DlnaException("GetVolume action not supported on this player");
        }

        ActionInvocation invocation = new ActionInvocation(action);
        setInput(invocation, action, "Channel", "Master");
        setInput(invocation, action, "InstanceID", "0");
        executeSync(invocation, playerId);

        String volumeStr = getOutput(invocation, action, "CurrentVolume");
        if (volumeStr == null) return 0;
        try {
            int volume = Integer.parseInt(volumeStr);
            log.debug("GetVolume for player {}: {}", playerId, volume);
            return volume;
        } catch (NumberFormatException e) {
            log.warn("Failed to parse volume value: {}", volumeStr);
            return 0;
        }
    }

    public void setVolume(String playerId, int volume) {
        if (volume < 0 || volume > 100) {
            throw new DlnaException("Volume must be between 0 and 100");
        }

        RemoteService service = playbackService.getRenderingControlService(playerId);

        Action action = service.getAction("SetVolume");
        if (action == null) {
            throw new DlnaException("SetVolume action not supported on this player");
        }

        ActionInvocation invocation = new ActionInvocation(action);
        setInput(invocation, action, "Channel", "Master");
        setInput(invocation, action, "InstanceID", "0");
        setInput(invocation, action, "DesiredVolume", String.valueOf(volume));
        executeSync(invocation, playerId);

        log.info("Volume set to {} on player {}", volume, playerId);
    }

    private void executeSync(ActionInvocation invocation, String playerId) {
        ControlPoint controlPoint = upnpServiceManager.getUpnpService().getControlPoint();
        new ActionCallback.Default(invocation, controlPoint).run();

        ActionException failure = invocation.getFailure();
        if (failure != null) {
            int errorCode = failure.getErrorCode() > 0 ? failure.getErrorCode() : -1;
            throw new DlnaException(
                    "RenderingControl action failed on player " + playerId + ": " + failure.getMessage(),
                    errorCode
            );
        }
    }

}
