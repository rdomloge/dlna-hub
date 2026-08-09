package com.dlnahub.service;

import com.dlnahub.dlna.Renderer;
import com.dlnahub.dlna.RendererDiscoveryManager;
import com.dlnahub.exception.DlnaException;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.model.types.UDAServiceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PlaybackService {

    private static final Logger log = LoggerFactory.getLogger(PlaybackService.class);

    private static final String AV_TRANSPORT_SERVICE = "AVTransport";
    private static final String RENDERING_CONTROL_SERVICE = "RenderingControl";

    private final RendererDiscoveryManager rendererDiscoveryManager;

    @Autowired
    public PlaybackService(RendererDiscoveryManager rendererDiscoveryManager) {
        this.rendererDiscoveryManager = rendererDiscoveryManager;
    }

    public RemoteDevice getDevice(String playerId) {
        Renderer renderer = rendererDiscoveryManager.getRenderer(playerId);
        if (renderer == null) {
            throw new DlnaException("Player not found: " + playerId);
        }
        RemoteDevice device = rendererDiscoveryManager.getDevice(playerId);
        if (device == null) {
            throw new DlnaException("UPnP device not available for player: " + playerId);
        }
        return device;
    }

    public RemoteService getAvTransportService(String playerId) {
        RemoteDevice device = getDevice(playerId);
        RemoteService service = device.findService(new UDAServiceType(AV_TRANSPORT_SERVICE));
        if (service == null) {
            throw new DlnaException("AVTransport service not found on player: " + playerId);
        }
        return service;
    }

    public RemoteService getRenderingControlService(String playerId) {
        RemoteDevice device = getDevice(playerId);
        RemoteService service = device.findService(new UDAServiceType(RENDERING_CONTROL_SERVICE));
        if (service == null) {
            throw new DlnaException("RenderingControl service not found on player: " + playerId);
        }
        return service;
    }
}
