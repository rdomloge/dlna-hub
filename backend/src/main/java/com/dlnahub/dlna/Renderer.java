package com.dlnahub.dlna;

import java.util.List;
import java.util.Set;

public class Renderer {

    private final String id;
    private final String name;
    private final String ip;
    private final int port;
    private final String manufacturer;
    private final String modelName;
    private final String deviceType;
    private final String presentationUrl;
    private final List<String> supportedProtocols;
    private final Set<String> transportCapabilities;

    public Renderer(String id, String name, String ip, int port, String manufacturer,
                    String modelName, String deviceType, String presentationUrl,
                    List<String> supportedProtocols, Set<String> transportCapabilities) {
        this.id = id;
        this.name = name;
        this.ip = ip;
        this.port = port;
        this.manufacturer = manufacturer;
        this.modelName = modelName;
        this.deviceType = deviceType;
        this.presentationUrl = presentationUrl;
        this.supportedProtocols = supportedProtocols;
        this.transportCapabilities = transportCapabilities;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public String getModelName() {
        return modelName;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public String getPresentationUrl() {
        return presentationUrl;
    }

    public List<String> getSupportedProtocols() {
        return supportedProtocols;
    }

    public Set<String> getTransportCapabilities() {
        return transportCapabilities;
    }
}
