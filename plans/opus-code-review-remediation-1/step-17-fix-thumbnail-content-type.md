# Step 17 — Fix the thumbnail endpoint's content type

**Phase:** 2 — Security and robustness
**Severity:** Medium (report: M10)
**Files:** `backend/src/main/java/com/dlnahub/controller/BrowseController.java`
**Depends on:** step-16

## Problem

```java
@GetMapping(value = "/{serverId}/thumbnail/{itemId}", produces = MediaType.IMAGE_JPEG_VALUE)
public ResponseEntity<?> thumbnail(...) {
    ...
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "..."));
    ...
    headers.setContentType(MediaType.parseMediaType(contentType));   // may be image/png
```

`produces = image/jpeg` declares that this handler only ever emits JPEG. But
`ThumbnailService.detectContentType` can return `image/png`, `image/gif`, `image/webp`, or
`application/octet-stream`, and the error paths return a JSON object. Spring's content
negotiation uses `produces` to select a message converter, so the JSON error bodies and
non-JPEG images conflict with the declared type — a client sending `Accept:
application/json` gets a 406 rather than the error it asked for.

The `produces` attribute is simply wrong here: the type is determined at runtime from the
bytes, not statically.

## Change

Replace the whole `thumbnail` method with:

```java
    @GetMapping("/{serverId}/thumbnail/{itemId}")
    public ResponseEntity<byte[]> thumbnail(
            @PathVariable String serverId,
            @PathVariable String itemId) {
        String url = thumbnailService.getUrl(serverId, itemId);
        if (url == null) {
            // Not an error worth a JSON body: the browser asked for an <img> src. A bare 404
            // lets the alt text / placeholder render. The content type is decided from the
            // fetched bytes below, so this handler declares no static `produces` type.
            throw new DeviceNotFoundException("Thumbnail not found for item: " + itemId);
        }

        byte[] data = thumbnailService.fetch(url);
        if (data == null || data.length == 0) {
            throw new DeviceNotFoundException("Thumbnail could not be fetched for item: " + itemId);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(thumbnailService.detectContentType(url, data)));
        headers.setCacheControl("public, max-age=3600");
        headers.setContentLength(data.length);
        return new ResponseEntity<>(data, headers, HttpStatus.OK);
    }
```

Add the import for `DeviceNotFoundException` (created in step-16).

The 404 now goes through `GlobalExceptionHandler`, which returns a JSON body under
`application/json` — no conflict, because the handler declares no `produces` type.

## Do not

- Do not change `ThumbnailService.detectContentType` — its magic-byte sniffing is correct
  (the `(byte)` casts on the PNG check are redundant but harmless).
- Do not add a fallback placeholder image; a 404 is the right answer and the frontend can
  handle it with an `onError` handler (step-27).

## Verify

```bash
cd backend && mvn test && mvn spring-boot:run
```

```bash
# Browse a folder first so thumbnails get cached, then:
curl -s -i "http://localhost:9100/api/servers/<id>/thumbnail/<itemId>" | head -5
# expect: HTTP/1.1 200, Content-Type: image/jpeg (or image/png), Cache-Control: public, max-age=3600

curl -s -i -H "Accept: application/json" "http://localhost:9100/api/servers/<id>/thumbnail/nope" | head -3
# expect: HTTP/1.1 404 with a JSON error body, NOT a 406
```
