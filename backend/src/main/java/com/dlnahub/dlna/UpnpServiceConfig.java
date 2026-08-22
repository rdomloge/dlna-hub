package com.dlnahub.dlna;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "dlna")
public class UpnpServiceConfig {

    private String networkInterface;
    private int discoveryTimeout;
    private int remoteDeviceMaxAgeSeconds = 90;
    private List<StaticDevice> staticDevices = new ArrayList<>();

    public String getNetworkInterface() {
        return networkInterface;
    }

    public void setNetworkInterface(String networkInterface) {
        this.networkInterface = networkInterface;
    }

    public int getDiscoveryTimeout() {
        return discoveryTimeout;
    }

    public void setDiscoveryTimeout(int discoveryTimeout) {
        this.discoveryTimeout = discoveryTimeout;
    }

    public int getRemoteDeviceMaxAgeSeconds() {
        return remoteDeviceMaxAgeSeconds;
    }

    public void setRemoteDeviceMaxAgeSeconds(int remoteDeviceMaxAgeSeconds) {
        this.remoteDeviceMaxAgeSeconds = remoteDeviceMaxAgeSeconds;
    }

    public List<StaticDevice> getStaticDevices() {
        return staticDevices;
    }

    public void setStaticDevices(List<StaticDevice> staticDevices) {
        this.staticDevices = staticDevices;
    }

    public static class StaticDevice {
        private String udn;
        private String descriptorUrl;
        private Integer portRangeStart;
        private Integer portRangeEnd;

        public String getUdn() {
            return udn;
        }

        public void setUdn(String udn) {
            this.udn = udn;
        }

        public String getDescriptorUrl() {
            return descriptorUrl;
        }

        public void setDescriptorUrl(String descriptorUrl) {
            this.descriptorUrl = descriptorUrl;
        }

        public Integer getPortRangeStart() {
            return portRangeStart;
        }

        public void setPortRangeStart(Integer portRangeStart) {
            this.portRangeStart = portRangeStart;
        }

        public Integer getPortRangeEnd() {
            return portRangeEnd;
        }

        public void setPortRangeEnd(Integer portRangeEnd) {
            this.portRangeEnd = portRangeEnd;
        }
    }
}
