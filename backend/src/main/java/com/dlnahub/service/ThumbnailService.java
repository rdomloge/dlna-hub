package com.dlnahub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);

    private final Map<ThumbnailKey, String> thumbnailCache = new ConcurrentHashMap<>();
    private final RestTemplate restTemplate;

    public ThumbnailService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    public void cache(String serverId, String itemId, String url) {
        thumbnailCache.put(new ThumbnailKey(serverId, itemId), url);
    }

    public String getUrl(String serverId, String itemId) {
        return thumbnailCache.get(new ThumbnailKey(serverId, itemId));
    }

    public byte[] fetch(String url) {
        try {
            log.debug("Fetching thumbnail from: {}", url);
            return restTemplate.getForObject(url, byte[].class);
        } catch (Exception e) {
            log.error("Failed to fetch thumbnail from {}: {}", url, e.getMessage());
            return null;
        }
    }

    public String detectContentType(String url, byte[] data) {
        if (data == null || data.length == 0) {
            return "application/octet-stream";
        }
        if (data.length >= 3) {
            if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
                return "image/jpeg";
            }
            if (data.length >= 4 && (data[0] & 0xFF) == 0x89
                    && (data[1] & 0xFF) == (byte) 0x50
                    && (data[2] & 0xFF) == (byte) 0x4E
                    && (data[3] & 0xFF) == (byte) 0x47) {
                return "image/png";
            }
        }
        if (url != null) {
            if (url.contains(".jpg") || url.contains(".jpeg")) return "image/jpeg";
            if (url.contains(".png")) return "image/png";
            if (url.contains(".gif")) return "image/gif";
            if (url.contains(".webp")) return "image/webp";
        }
        return "application/octet-stream";
    }

    static class ThumbnailKey {
        private final String serverId;
        private final String itemId;

        ThumbnailKey(String serverId, String itemId) {
            this.serverId = serverId;
            this.itemId = itemId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ThumbnailKey that = (ThumbnailKey) o;
            return serverId.equals(that.serverId) && itemId.equals(that.itemId);
        }

        @Override
        public int hashCode() {
            return 31 * serverId.hashCode() + itemId.hashCode();
        }
    }
}
