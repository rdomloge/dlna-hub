package com.dlnahub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);

    /**
     * Bounded LRU of item-id to thumbnail URL. Entries are added for every item seen in a
     * browse result, so an unbounded map would grow with the size of the library and never
     * shrink. Access-order LRU keeps the thumbnails the user is actually looking at.
     * URLs only, never image bytes — those are re-fetched on demand.
     */
    private static final int MAX_CACHED_THUMBNAIL_URLS = 5_000;

    private final Map<ThumbnailKey, String> thumbnailCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ThumbnailKey, String> eldest) {
                    return size() > MAX_CACHED_THUMBNAIL_URLS;
                }
            });
    private final RestTemplate restTemplate;

    public ThumbnailService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    public void cache(String serverId, String itemId, String url) {
        if (serverId == null || itemId == null || url == null || url.isEmpty()) {
            return;
        }
        thumbnailCache.put(new ThumbnailKey(serverId, itemId), url);
    }

    public String getUrl(String serverId, String itemId) {
        if (serverId == null || itemId == null) {
            return null;
        }
        return thumbnailCache.get(new ThumbnailKey(serverId, itemId));
    }

    /** Visible for testing. */
    int cacheSize() {
        return thumbnailCache.size();
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

    record ThumbnailKey(String serverId, String itemId) {
    }
}
