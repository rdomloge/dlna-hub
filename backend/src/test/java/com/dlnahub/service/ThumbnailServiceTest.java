package com.dlnahub.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThumbnailServiceTest {

    @Test
    void detectContentType_jpegMagicBytes_returnsImageJpeg() {
        // given
        ThumbnailService service = new ThumbnailService();
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};

        // when
        String type = service.detectContentType("http://example.com/x.bin", jpeg);

        // then
        assertEquals("image/jpeg", type);
    }

    @Test
    void detectContentType_pngMagicBytes_returnsImagePng() {
        // given
        ThumbnailService service = new ThumbnailService();
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x00};

        // when
        String type = service.detectContentType("http://example.com/x.bin", png);

        // then
        assertEquals("image/png", type);
    }

    @Test
    void detectContentType_unknownMagicBytesWithKnownUrlExtension_returnsExtensionMimeType() {
        // given
        ThumbnailService service = new ThumbnailService();
        byte[] unknown = new byte[]{0x00, 0x01, 0x02};

        // when
        String fromJpg = service.detectContentType("http://example.com/photo.jpg", unknown);
        String fromGif = service.detectContentType("http://example.com/anim.gif", unknown);
        String fromWebp = service.detectContentType("http://example.com/img.webp", unknown);

        // then
        assertEquals("image/jpeg", fromJpg);
        assertEquals("image/gif", fromGif);
        assertEquals("image/webp", fromWebp);
    }

    @Test
    void detectContentType_noDataOrUnknownType_returnsOctetStream() {
        // given
        ThumbnailService service = new ThumbnailService();

        // when
        String noData = service.detectContentType(null, null);
        String unknown = service.detectContentType("http://example.com/file.xyz", new byte[]{0x01, 0x02});

        // then
        assertEquals("application/octet-stream", noData);
        assertEquals("application/octet-stream", unknown);
    }

    @Test
    void getUrl_afterCache_returnsCachedUrl() {
        // given
        ThumbnailService service = new ThumbnailService();
        service.cache("server1", "item42", "http://example.com/thumb.jpg");

        // when
        String url = service.getUrl("server1", "item42");

        // then
        assertEquals("http://example.com/thumb.jpg", url);
    }

    @Test
    void getUrl_uncachedItem_returnsNull() {
        // given
        ThumbnailService service = new ThumbnailService();

        // when
        String url = service.getUrl("server1", "item99");

        // then
        assertNull(url);
    }

    @Test
    void cacheSize_overflowBeyondLimit_boundedAtFiveThousand() {
        // given
        ThumbnailService service = new ThumbnailService();

        // when
        for (int i = 0; i < 6_000; i++) {
            service.cache("server-1", "item-" + i, "http://host/thumb/" + i);
        }

        // then
        assertTrue(service.cacheSize() <= 5_000, "cache should be bounded, was " + service.cacheSize());
        // The most recently written entry must survive.
        assertEquals("http://host/thumb/5999", service.getUrl("server-1", "item-5999"));
    }

    @Test
    void cache_nullAndEmptyValues_notStored() {
        // given
        ThumbnailService service = new ThumbnailService();

        // when
        service.cache("s", "i", null);
        service.cache("s", "i", "");
        service.cache(null, "i", "http://host/x");
        String url = service.getUrl("s", "i");

        // then
        assertNull(url);
    }
}
