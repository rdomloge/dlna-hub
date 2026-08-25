package com.dlnahub.dlna;

import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.model.meta.Action;
import org.jupnp.model.meta.StateVariable;
import org.jupnp.model.types.UDADeviceType;
import org.jupnp.model.types.UDAServiceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RendererDiscoveryManager {

    private static final Logger log = LoggerFactory.getLogger(RendererDiscoveryManager.class);

    private static final UDADeviceType MEDIA_RENDERER_DEVICE_TYPE = new UDADeviceType("MediaRenderer", 1);

    private final ConcurrentHashMap<String, Renderer> discoveredRenderers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RemoteDevice> devicesById = new ConcurrentHashMap<>();

    void deviceAdded(RemoteDevice device) {
        processDevice(device);
        for (RemoteDevice embeddedDevice : device.getEmbeddedDevices()) {
            deviceAdded(embeddedDevice);
        }
    }

    private void processDevice(RemoteDevice device) {
        if (!isMediaRenderer(device)) {
            log.debug("Skipping non-media-renderer device: {}", device.getDisplayString());
            return;
        }
        log.info("Processing media renderer device: {}", device.getDisplayString());
        try {
            String identifier = device.getIdentity().getUdn().getIdentifierString();
            UUID id = UUID.nameUUIDFromBytes(identifier.getBytes(StandardCharsets.UTF_8));
            String friendlyName = device.getDetails().getFriendlyName();
            String manufacturer = device.getDetails().getManufacturerDetails() != null
                ? device.getDetails().getManufacturerDetails().getManufacturer() : "Unknown";
            String modelName = device.getDetails().getModelDetails() != null
                ? device.getDetails().getModelDetails().getModelName() : "Unknown";

            String presentationUrl = null;
            URI presUri = device.getDetails().getPresentationURI();
            if (presUri != null) {
                presentationUrl = presUri.toString();
            }

            String ip = "0.0.0.0";
            int port = 0;
            try {
                URL descriptorURL = device.getIdentity().getDescriptorURL();
                if (descriptorURL != null) {
                    ip = descriptorURL.getHost();
                    // getPort() is -1 for a default-port URL; fall back to the scheme default.
                    port = descriptorURL.getPort() != -1
                            ? descriptorURL.getPort()
                            : descriptorURL.getDefaultPort();
                }
            } catch (Exception e) {
                log.warn("Could not get IP/port for device: {}", friendlyName, e);
            }

            List<String> supportedProtocols = extractProtocols(device);
            Set<String> transportCapabilities = extractTransportCapabilities(device);
            log.info("Renderer {} capabilities: AVTransport actions={}, protocols={}",
                friendlyName, transportCapabilities, supportedProtocols);

            devicesById.put(id.toString(), device);

            Renderer renderer = new Renderer(
                id.toString(),
                friendlyName,
                ip,
                port,
                manufacturer,
                modelName,
                device.getType().getType(),
                presentationUrl,
                supportedProtocols,
                transportCapabilities
            );

            Renderer previous = discoveredRenderers.put(id.toString(), renderer);
            if (previous == null) {
                log.info("Discovered media renderer: {} ({}) at {}:{}", friendlyName, id, ip, port);
            }
        } catch (Exception e) {
            log.error("Failed to process discovered renderer: {}", device.getDisplayString(), e);
        }
    }

    void deviceRemoved(RemoteDevice device) {
        if (isMediaRenderer(device)) {
            String identifier = device.getIdentity().getUdn().getIdentifierString();
            UUID id = UUID.nameUUIDFromBytes(identifier.getBytes(StandardCharsets.UTF_8));
            devicesById.remove(id.toString());
            Renderer removed = discoveredRenderers.remove(id.toString());
            if (removed != null) {
                log.info("Media renderer removed: {}", removed.getName());
            }
        }
        for (RemoteDevice embeddedDevice : device.getEmbeddedDevices()) {
            deviceRemoved(embeddedDevice);
        }
    }

    boolean isMediaRenderer(RemoteDevice device) {
        return device.getType() != null && device.getType().implementsVersion(MEDIA_RENDERER_DEVICE_TYPE);
    }

    /**
     * Protocols the renderer advertises via ConnectionManager's ProtocolInfo state variable.
     * Returns an empty list when the device reports none — deliberately not a guessed default:
     * this list is published to clients, and inventing capabilities the device may not have is
     * worse than admitting they are unknown.
     */
    private List<String> extractProtocols(RemoteDevice device) {
        List<String> protocols = new ArrayList<>();

        for (RemoteService service : device.findServices(new UDAServiceType("ConnectionManager"))) {
            StateVariable<RemoteService> protocolInfoVar = service.getStateVariable("ProtocolInfo");
            if (protocolInfoVar == null || protocolInfoVar.getTypeDetails() == null) {
                continue;
            }
            String defaultValue = protocolInfoVar.getTypeDetails().getDefaultValue();
            if (defaultValue == null || defaultValue.isEmpty()) {
                continue;
            }
            for (String entry : defaultValue.split(";")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    protocols.add(trimmed);
                }
            }
        }

        if (protocols.isEmpty()) {
            log.debug("Renderer {} reports no ProtocolInfo", device.getDisplayString());
        }
        return protocols;
    }

    /**
     * The AVTransport action names the renderer actually declares in its SCPD. Empty when the
     * device declares none — see extractProtocols for why this is not defaulted.
     */
    private Set<String> extractTransportCapabilities(RemoteDevice device) {
        Set<String> capabilities = new HashSet<>();
        for (RemoteService service : device.findServices(new UDAServiceType("AVTransport"))) {
            for (Action<RemoteService> action : service.getActions()) {
                capabilities.add(action.getName());
            }
        }
        if (capabilities.isEmpty()) {
            log.debug("Renderer {} declares no AVTransport actions", device.getDisplayString());
        }
        return capabilities;
    }

    public Set<Renderer> getDiscoveredRenderers() {
        return Collections.unmodifiableSet(
            discoveredRenderers.values().parallelStream().collect(Collectors.toSet())
        );
    }

    public Renderer getRenderer(String id) {
        return discoveredRenderers.get(id);
    }

    public RemoteDevice getDevice(String id) {
        return devicesById.get(id);
    }
}
