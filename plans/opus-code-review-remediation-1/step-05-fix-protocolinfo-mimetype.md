# Step 05 — Fix the MIME type extracted from `protocolInfo`

**Phase:** 1 — Correctness
**Severity:** Critical (report: C2)
**Files:** `backend/src/main/java/com/dlnahub/service/ContentBrowseService.java`,
`backend/src/test/java/com/dlnahub/service/ContentBrowseServiceTest.java`
**Depends on:** —

## Problem

A DLNA `protocolInfo` string has four colon-separated fields:

```
http-get : * : video/x-matroska : DLNA.ORG_PN=AVC_MKV;DLNA.ORG_OP=01
   [0]    [1]        [2]                      [3]
protocol network  contentFormat            additionalInfo
```

`extractMimeType` returns `parts[1]` — the *network* field, which is almost always `"*"`.
So **every** `BrowsableItem.mimeType` is `"*"`.

Two visible consequences:

1. `BrowsePage.mediaType()` checks `startsWith('video/')` etc., never matches, and labels
   every media item **"File"** instead of Video / Audio / Image.
2. `DidlUtils.generateSimpleMetadataXml` derives `upnp:class` from the MIME type, so every
   item — videos included — is announced to the renderer as
   `object.item.audioItem.musicTrack`.

## Change

In `ContentBrowseService.java`, at the bottom of the file, replace:

```java
    private String extractMimeType(String protocolInfo) {
        if (protocolInfo == null) return null;
        String[] parts = protocolInfo.split(":");
        if (parts.length >= 2) {
            return parts[1];
        }
        return null;
    }
```

with:

```java
    /**
     * Extracts the content format (MIME type) from a DLNA protocolInfo string, which has the
     * shape {@code <protocol>:<network>:<contentFormat>:<additionalInfo>} — e.g.
     * {@code http-get:*:video/x-matroska:DLNA.ORG_PN=AVC_MKV}. The third field is the MIME type;
     * the second is the network field and is almost always "*".
     */
    static String extractMimeType(String protocolInfo) {
        if (protocolInfo == null) return null;
        String[] parts = protocolInfo.split(":");
        if (parts.length >= 3) {
            String contentFormat = parts[2].trim();
            return contentFormat.isEmpty() || "*".equals(contentFormat) ? null : contentFormat;
        }
        return null;
    }
```

Two changes beyond the index: the method becomes package-private `static` so it is
testable, and a wildcard/empty content format now yields `null` rather than a bogus type.

Update the single call site (in `parseItem`) — it stays `extractMimeType(proto)`, but if
the compiler complains about the removed `private` modifier, no call-site change is needed.

## Add tests

Append to `ContentBrowseServiceTest.java`:

```java
    @Test
    void extractsContentFormatFromProtocolInfo() {
        assertEquals("video/x-matroska",
                ContentBrowseService.extractMimeType("http-get:*:video/x-matroska:DLNA.ORG_PN=AVC_MKV"));
        assertEquals("audio/mpeg",
                ContentBrowseService.extractMimeType("http-get:*:audio/mpeg:*"));
        assertEquals("image/jpeg",
                ContentBrowseService.extractMimeType("http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN"));
    }

    @Test
    void extractMimeTypeReturnsNullForUnusableProtocolInfo() {
        assertNull(ContentBrowseService.extractMimeType(null));
        assertNull(ContentBrowseService.extractMimeType("http-get:*"));
        assertNull(ContentBrowseService.extractMimeType("http-get:*:*:*"));
    }
```

## Do not

- Do not change `DidlUtils.generateSimpleMetadataXml` — once the MIME type is right, its
  class-type mapping works correctly.
- Do not change `BrowsePage.mediaType()` — it is already correct.

## Verify

```bash
cd backend && mvn test -Dtest=ContentBrowseServiceTest
```

Then, against the real NAS: browse a video folder and confirm items are labelled "Video"
rather than "File".
