package com.dlnahub.dlna;

import java.util.UUID;

public class MediaServer {

    private final String id;
    private final String name;
    private final String location;
    private final String manufacturer;
    private final String modelName;
    private final String deviceType;

    public MediaServer(UUID id, String name, String location, String manufacturer, String modelName, String deviceType) {
        this.id = id.toString();
        this.name = name;
        this.location = location;
        this.manufacturer = manufacturer;
        this.modelName = modelName;
        this.deviceType = deviceType;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLocation() {
        return location;
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
}
