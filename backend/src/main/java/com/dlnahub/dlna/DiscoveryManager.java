package com.dlnahub.dlna;

import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.types.UDADeviceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DiscoveryManager {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryManager.class);

    private static final UDADeviceType MEDIA_SERVER_DEVICE_TYPE = new UDADeviceType("MediaServer", 1);

    private final ConcurrentHashMap<String, MediaServer> discoveredServers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RemoteDevice> devicesById = new ConcurrentHashMap<>();

    void deviceAdded(RemoteDevice device) {
        processDevice(device);
        for (RemoteDevice embeddedDevice : device.getEmbeddedDevices()) {
            deviceAdded(embeddedDevice);
        }
    }

    private void processDevice(RemoteDevice device) {
        if (!isMediaServer(device)) {
            return;
        }
        String identifier = device.getIdentity().getUdn().getIdentifierString();
        String friendlyName = device.getDetails().getFriendlyName();
        String manufacturer = device.getDetails().getManufacturerDetails() != null
            ? device.getDetails().getManufacturerDetails().getManufacturer() : "Unknown";
        String modelName = device.getDetails().getModelDetails() != null
            ? device.getDetails().getModelDetails().getModelName() : "Unknown";
        String location = device.getIdentity().getDescriptorURL() != null
            ? device.getIdentity().getDescriptorURL().toString() : "Unknown";

        UUID id = UUID.nameUUIDFromBytes(identifier.getBytes(StandardCharsets.UTF_8));
        devicesById.put(id.toString(), device);

        MediaServer previous = discoveredServers.put(id.toString(), new MediaServer(
                id,
                friendlyName,
                location,
                manufacturer,
                modelName,
                device.getType().getType()
        ));
        if (previous == null) {
            log.info("Discovered media server: {} ({})", friendlyName, id);
        }
    }

    void deviceRemoved(RemoteDevice device) {
        if (isMediaServer(device)) {
            String identifier = device.getIdentity().getUdn().getIdentifierString();
            UUID id = UUID.nameUUIDFromBytes(identifier.getBytes(StandardCharsets.UTF_8));
            devicesById.remove(id.toString());
            MediaServer removed = discoveredServers.remove(id.toString());
            if (removed != null) {
                log.info("Media server removed: {}", removed.getName());
            }
        }
        for (RemoteDevice embeddedDevice : device.getEmbeddedDevices()) {
            deviceRemoved(embeddedDevice);
        }
    }

    boolean isMediaServer(RemoteDevice device) {
        return device.getType() != null && device.getType().implementsVersion(MEDIA_SERVER_DEVICE_TYPE);
    }

    public Set<MediaServer> getDiscoveredServers() {
        return Collections.unmodifiableSet(discoveredServers.values().parallelStream().collect(Collectors.toSet()));
    }

    public RemoteDevice getDevice(String serverId) {
        return devicesById.get(serverId);
    }
}
