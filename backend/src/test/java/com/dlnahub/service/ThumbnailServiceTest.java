package com.dlnahub.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThumbnailServiceTest {

    @Test
    void detectContentTypeByJpegMagicBytes() {
        ThumbnailService service = new ThumbnailService();
        byte[] jpeg = new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, 0x00};
        assertEquals("image/jpeg", service.detectContentType("http://example.com/x.bin", jpeg));
    }

    @Test
    void detectContentTypeByPngMagicBytes() {
        ThumbnailService service = new ThumbnailService();
        byte[] png = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x00};
        assertEquals("image/png", service.detectContentType("http://example.com/x.bin", png));
    }

    @Test
    void detectContentTypeFallsBackToUrlExtension() {
        ThumbnailService service = new ThumbnailService();
        byte[] unknown = new byte[]{0x00, 0x01, 0x02};
        assertEquals("image/jpeg", service.detectContentType("http://example.com/photo.jpg", unknown));
        assertEquals("image/gif", service.detectContentType("http://example.com/anim.gif", unknown));
        assertEquals("image/webp", service.detectContentType("http://example.com/img.webp", unknown));
    }

    @Test
    void detectContentTypeReturnsOctetStreamWhenUnknown() {
        ThumbnailService service = new ThumbnailService();
        assertEquals("application/octet-stream", service.detectContentType(null, null));
        assertEquals("application/octet-stream", service.detectContentType("http://example.com/file.xyz", new byte[]{0x01,0x02}));
    }

    @Test
    void cacheAndGetUrl() {
        ThumbnailService service = new ThumbnailService();
        service.cache("server1", "item42", "http://example.com/thumb.jpg");
        assertEquals("http://example.com/thumb.jpg", service.getUrl("server1", "item42"));
        assertNull(service.getUrl("server1", "item99"));
    }

    @Test
    void thumbnailCacheIsBounded() {
        ThumbnailService service = new ThumbnailService();
        for (int i = 0; i < 6_000; i++) {
            service.cache("server-1", "item-" + i, "http://host/thumb/" + i);
        }
        assertTrue(service.cacheSize() <= 5_000, "cache should be bounded, was " + service.cacheSize());
        // The most recently written entry must survive.
        assertEquals("http://host/thumb/5999", service.getUrl("server-1", "item-5999"));
    }

    @Test
    void cacheIgnoresNullAndEmptyValues() {
        ThumbnailService service = new ThumbnailService();
        service.cache("s", "i", null);
        service.cache("s", "i", "");
        service.cache(null, "i", "http://host/x");
        assertNull(service.getUrl("s", "i"));
    }
}
