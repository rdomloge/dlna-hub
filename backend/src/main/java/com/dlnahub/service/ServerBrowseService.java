package com.dlnahub.service;

import com.dlnahub.dlna.DiscoveryManager;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.registry.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ServerBrowseService {

    private static final Logger log = LoggerFactory.getLogger(ServerBrowseService.class);

    private final DiscoveryManager discoveryManager;

    @Autowired
    public ServerBrowseService(DiscoveryManager discoveryManager) {
        this.discoveryManager = discoveryManager;
    }

    public RemoteDevice getDevice(String serverId) {
        RemoteDevice device = discoveryManager.getDevice(serverId);
        if (device == null) {
            log.warn("No device found for server ID: {}", serverId);
        }
        return device;
    }
}
