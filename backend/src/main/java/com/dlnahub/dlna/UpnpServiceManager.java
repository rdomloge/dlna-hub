package com.dlnahub.dlna;

import org.jupnp.UpnpService;
import org.jupnp.UpnpServiceImpl;
import org.jupnp.DefaultUpnpServiceConfiguration;
import org.jupnp.binding.xml.DeviceDescriptorBinder;
import org.jupnp.binding.xml.RecoveringUDA10DeviceDescriptorBinderImpl;
import org.jupnp.binding.xml.ServiceDescriptorBinder;
import org.jupnp.binding.xml.UDA10ServiceDescriptorBinderSAXImpl;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteDeviceIdentity;
import org.jupnp.model.types.UDN;
import org.jupnp.protocol.RetrieveRemoteDescriptors;
import org.jupnp.registry.DefaultRegistryListener;
import org.jupnp.registry.Registry;
import org.jupnp.registry.RegistryListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class UpnpServiceManager {

    private static final Logger log = LoggerFactory.getLogger(UpnpServiceManager.class);

    private final UpnpServiceConfig config;
    private final DiscoveryManager discoveryManager;
    private final RendererDiscoveryManager rendererDiscoveryManager;
    private final ConcurrentHashMap<UDN, Long> configuredDeviceScans = new ConcurrentHashMap<>();
    private UpnpService upnpService;
    private RegistryListener registryListener;

    public UpnpServiceManager(
            UpnpServiceConfig config,
            DiscoveryManager discoveryManager,
            RendererDiscoveryManager rendererDiscoveryManager) {
        this.config = config;
        this.discoveryManager = discoveryManager;
        this.rendererDiscoveryManager = rendererDiscoveryManager;
    }

    @PostConstruct
    public void init() {
        try {
            configureNetworkSelection();
            DefaultUpnpServiceConfiguration upnpConfig = new DefaultUpnpServiceConfiguration() {
                @Override
                public DeviceDescriptorBinder getDeviceDescriptorBinderUDA10() {
                    return new RecoveringUDA10DeviceDescriptorBinderImpl();
                }

                @Override
                public ServiceDescriptorBinder getServiceDescriptorBinderUDA10() {
                    return new UDA10ServiceDescriptorBinderSAXImpl();
                }
            };
            upnpService = new UpnpServiceImpl(upnpConfig);
            upnpService.startup();
            registryListener = createRegistryListener();
            Registry registry = upnpService.getRegistry();
            registry.addListener(registryListener);
            for (RemoteDevice device : registry.getRemoteDevices()) {
                deviceAdded(device);
            }
            discover();
            refreshConfiguredDevices();
            log.info("UPnP service started; searching all devices on {}", config.getNetworkInterface());
        } catch (Exception e) {
            log.error("Failed to start UPnP service", e);
            throw new RuntimeException("Failed to start UPnP service", e);
        }
    }

    @Scheduled(
        fixedDelayString = "${dlna.discovery-interval:60000}",
        initialDelayString = "${dlna.discovery-interval:60000}"
    )
    public void discover() {
        if (upnpService == null) {
            return;
        }
        try {
            int mxSeconds = Math.max(1, (config.getDiscoveryTimeout() + 999) / 1000);
            upnpService.getControlPoint().search(mxSeconds);
            log.debug(
                "Dispatched SSDP all search; currently tracking {} server(s) and {} renderer(s)",
                discoveryManager.getDiscoveredServers().size(),
                rendererDiscoveryManager.getDiscoveredRenderers().size()
            );
        } catch (Exception e) {
            log.error("Failed to search for UPnP devices", e);
        }
    }

    @Scheduled(
        fixedDelayString = "${dlna.static-device-check-interval:5000}",
        initialDelayString = "${dlna.static-device-check-interval:5000}"
    )
    public void refreshConfiguredDevices() {
        if (upnpService == null) {
            return;
        }
        for (UpnpServiceConfig.StaticDevice staticDevice : config.getStaticDevices()) {
            if (staticDevice.getUdn() == null || staticDevice.getUdn().isBlank()
                    || staticDevice.getDescriptorUrl() == null || staticDevice.getDescriptorUrl().isBlank()) {
                continue;
            }
            try {
                UDN udn = UDN.valueOf(staticDevice.getUdn());
                RemoteDevice registeredDevice = upnpService.getRegistry().getRemoteDevice(udn, true);
                if (registeredDevice != null) {
                    String descriptorUrl = registeredDevice.getIdentity().getDescriptorURL().toString();
                    if (!isDescriptorAvailable(descriptorUrl)) {
                        upnpService.getRegistry().removeDevice(registeredDevice);
                        log.info("Configured UPnP device is no longer available: {}", registeredDevice.getDisplayString());
                    }
                    continue;
                }

                String descriptorUrl = staticDevice.getDescriptorUrl();
                if (!isDescriptorAvailable(descriptorUrl)) {
                    descriptorUrl = findConfiguredDescriptor(staticDevice, udn);
                }
                if (descriptorUrl == null) {
                    continue;
                }
                RemoteDevice device = new RemoteDevice(new RemoteDeviceIdentity(
                    udn,
                    1800,
                    URI.create(descriptorUrl).toURL(),
                    null,
                    InetAddress.getByName(config.getNetworkInterface())
                ));
                if (!RetrieveRemoteDescriptors.isRetrievalInProgress(device)) {
                    upnpService.getConfiguration().getAsyncProtocolExecutor().execute(
                        new RetrieveRemoteDescriptors(upnpService, device)
                    );
                    log.debug("Retrieving configured UPnP descriptor: {}", descriptorUrl);
                }
            } catch (Exception e) {
                log.warn("Could not retrieve configured UPnP device {}: {}", staticDevice.getUdn(), e.getMessage());
            }
        }
    }

    private String findConfiguredDescriptor(UpnpServiceConfig.StaticDevice staticDevice, UDN udn) {
        Integer firstPort = staticDevice.getPortRangeStart();
        Integer lastPort = staticDevice.getPortRangeEnd();
        if (firstPort == null || lastPort == null || firstPort < 1 || lastPort > 65535 || firstPort > lastPort) {
            return null;
        }

        long now = System.currentTimeMillis();
        Long nextScan = configuredDeviceScans.get(udn);
        if (nextScan != null && nextScan > now) {
            return null;
        }
        configuredDeviceScans.put(udn, now + 60000);

        int portCount = lastPort - firstPort + 1;
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(32, portCount));
        CompletionService<String> results = new ExecutorCompletionService<>(executor);
        try {
            URI template = URI.create(staticDevice.getDescriptorUrl());
            for (int port = firstPort; port <= lastPort; port++) {
                int candidatePort = port;
                results.submit(() -> {
                    try {
                        URI candidate = new URI(
                            template.getScheme(),
                            template.getUserInfo(),
                            template.getHost(),
                            candidatePort,
                            template.getPath(),
                            template.getQuery(),
                            null
                        );
                        return isExpectedDescriptor(candidate.toString(), udn) ? candidate.toString() : null;
                    } catch (Exception e) {
                        return null;
                    }
                });
            }
            for (int i = 0; i < portCount; i++) {
                String descriptorUrl = results.take().get();
                if (descriptorUrl != null) {
                    log.info("Found configured UPnP device at dynamic descriptor URL: {}", descriptorUrl);
                    return descriptorUrl;
                }
            }
        } catch (Exception e) {
            log.debug("Configured UPnP descriptor scan failed: {}", e.getMessage());
        } finally {
            executor.shutdownNow();
        }
        return null;
    }

    private boolean isExpectedDescriptor(String descriptorUrl, UDN udn) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(descriptorUrl).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(250);
            connection.setReadTimeout(500);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            String descriptor = new String(connection.getInputStream().readNBytes(8192), StandardCharsets.UTF_8);
            return descriptor.contains("uuid:" + udn.getIdentifierString());
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean isDescriptorAvailable(String descriptorUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(descriptorUrl).toURL().openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            int status = connection.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void configureNetworkSelection() {
        String networkInterface = config.getNetworkInterface();
        if (networkInterface == null || networkInterface.isBlank()) {
            return;
        }
        if (networkInterface.matches("[0-9.,\\s]+")) {
            System.setProperty("org.jupnp.network.useAddresses", networkInterface);
        } else {
            System.setProperty("org.jupnp.network.useInterfaces", networkInterface);
        }
    }

    private RegistryListener createRegistryListener() {
        return new DefaultRegistryListener() {
            @Override
            public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
                UpnpServiceManager.this.deviceAdded(device);
                log.debug(
                    "UPnP device registered: {} [type: {}, descriptor: {}]",
                    device.getDisplayString(),
                    device.getType(),
                    device.getIdentity().getDescriptorURL()
                );
            }

            @Override
            public void remoteDeviceRemoved(Registry registry, RemoteDevice device) {
                discoveryManager.deviceRemoved(device);
                rendererDiscoveryManager.deviceRemoved(device);
            }

            @Override
            public void remoteDeviceDiscoveryFailed(Registry registry, RemoteDevice device, Exception ex) {
                String message = "Could not load UPnP device " + device.getDisplayString()
                    + " from " + device.getIdentity().getDescriptorURL() + ": " + ex.getMessage();
                if (discoveryManager.isMediaServer(device) || rendererDiscoveryManager.isMediaRenderer(device)) {
                    log.warn(message);
                } else {
                    log.debug(message);
                }
            }
        };
    }

    private void deviceAdded(RemoteDevice device) {
        discoveryManager.deviceAdded(device);
        rendererDiscoveryManager.deviceAdded(device);
    }

    @PreDestroy
    public void destroy() {
        if (upnpService != null) {
            try {
                if (registryListener != null) {
                    upnpService.getRegistry().removeListener(registryListener);
                }
                upnpService.shutdown();
                log.info("UPnP service stopped");
            } catch (Exception e) {
                log.error("Error shutting down UPnP service", e);
            }
        }
    }

    public UpnpService getUpnpService() {
        return upnpService;
    }
}
