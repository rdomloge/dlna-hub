# Step 06 — Fix the artist and album fallbacks in `parseItem`

**Phase:** 1 — Correctness
**Severity:** Medium (report: M6)
**Files:** `backend/src/main/java/com/dlnahub/service/ContentBrowseService.java`
**Depends on:** —

## Problem

Near the end of `parseItem`:

```java
if ((artist == null || artist.isEmpty()) && (description == null || description.isEmpty())) {
    artist = findNsText(itemEl, "artist");
}
if (album == null || album.isEmpty()) {
    album = findNsText(itemEl, "album");
}
```

Two bugs:

1. The `description` clause is meaningless. An item that *has* a `dc:description` never
   gets its `upnp:artist` fallback, so artists silently go missing on richly-described
   items. There is no reason a description should suppress an artist lookup.
2. The album fallback calls `findNsText(itemEl, "album")` — the **same call** that already
   produced `album` twenty lines earlier. It can never yield a different result. Dead code.

## Change

Replace the block above with:

```java
        // upnp:artist is the fallback when the server omits dc:creator.
        if (artist == null || artist.isEmpty()) {
            artist = findNsText(itemEl, "artist");
        }
```

Delete the album block entirely.

## Do not

- Do not change `findNsText` or the namespace list.
- Do not change how `album` is read the first time.

## Verify

```bash
cd backend && mvn test
```

Then, against the real NAS: browse a music folder where items carry both `dc:description`
and `upnp:artist`, and confirm the artist now appears in the JSON:

```bash
curl -s "http://localhost:9100/api/servers/<id>/browse?objectId=<musicFolder>" | grep -o '"artist":"[^"]*"' | head
```
