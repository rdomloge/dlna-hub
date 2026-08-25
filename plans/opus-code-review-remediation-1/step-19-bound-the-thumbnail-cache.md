# Step 19 — Bound the thumbnail URL cache

**Phase:** 3 — Performance
**Severity:** High (report: H7)
**Files:** `backend/src/main/java/com/dlnahub/service/ThumbnailService.java`,
`backend/src/test/java/com/dlnahub/service/ThumbnailServiceTest.java`
**Depends on:** —

## Problem

```java
private final Map<ThumbnailKey, String> thumbnailCache = new ConcurrentHashMap<>();

public void cache(String serverId, String itemId, String url) {
    thumbnailCache.put(new ThumbnailKey(serverId, itemId), url);
}
```

An entry is added for every item with a thumbnail seen in any browse, search, or metadata
response, and **nothing is ever removed**. Browsing a large library adds tens of thousands
of entries that live for the lifetime of the process. The backend container has a 640 Mi
memory limit, and `ContentBrowseService.containerDateCache` is competing for the same heap.

## Change

Replace the raw map with a bounded LRU. `LinkedHashMap` in access-order mode with
`removeEldestEntry` gives this in a few lines; wrap it in `Collections.synchronizedMap`
because access-order LRU mutates on reads and is not thread-safe.

Replace the field and the two accessor methods:

```java
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
```

Update the imports: add `java.util.Collections` and `java.util.LinkedHashMap`, drop
`java.util.concurrent.ConcurrentHashMap` if nothing else uses it.

While here, convert `ThumbnailKey` to a record — it is a pure value class and the
hand-written `equals`/`hashCode` can go:

```java
    record ThumbnailKey(String serverId, String itemId) {
    }
```

## Add a test

Append to `ThumbnailServiceTest.java`:

```java
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
```

Add whichever JUnit static imports the file is missing.

## Do not

- Do not cache the image bytes — only URLs. Bytes would defeat the point of the bound.
- Do not add eviction to `containerDateCache` here; that cache has its own
  `SystemUpdateID`-based invalidation and is handled in step-20.

## Verify

```bash
cd backend && mvn test -Dtest=ThumbnailServiceTest
cd backend && mvn test
```
