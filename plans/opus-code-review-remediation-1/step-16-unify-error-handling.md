# Step 16 — Unify error handling and fix the null-message NPE

**Phase:** 2 — Security and robustness
**Severity:** Medium (report: M9, M12)
**Files:** `backend/src/main/java/com/dlnahub/exception/GlobalExceptionHandler.java`,
`backend/src/main/java/com/dlnahub/exception/DlnaException.java` (new subclass),
`backend/src/main/java/com/dlnahub/controller/BrowseController.java`,
`backend/src/main/java/com/dlnahub/service/ContentBrowseService.java`
**Depends on:** —

## Problem

Three separate issues in the same area:

1. **`Map.of` NPE.** `Map.of("error", e.getMessage())` appears seven times. `Map.of`
   rejects null values, and `getMessage()` is null for a bare `NullPointerException` — so
   the error handler itself throws, turning a handled 500 into an unhandled one with no
   useful body.

2. **Inconsistent status codes.** `BrowseController` catches `IllegalArgumentException`
   and returns **404**; `GlobalExceptionHandler` catches the same type and returns **400**.
   The same "server not found" condition returns a different status depending on the
   endpoint.

3. **`upnpErrorCode` is never populated for browse errors.** `ContentBrowseService.
   executeSync` throws a bare `RuntimeException`, so all the `DlnaException` machinery in
   `GlobalExceptionHandler` only ever applies to playback. Both README and AGENTS.md claim
   browse errors carry `upnpErrorCode`.

## Change

### 1. Introduce a not-found exception

Create `backend/src/main/java/com/dlnahub/exception/DeviceNotFoundException.java`:

```java
package com.dlnahub.exception;

/** A server or renderer id that does not match any currently discovered device. Maps to 404. */
public class DeviceNotFoundException extends RuntimeException {
    public DeviceNotFoundException(String message) {
        super(message);
    }
}
```

### 2. Throw it from `ContentBrowseService`

Replace every occurrence of

```java
throw new IllegalArgumentException("Server not found: " + serverId);
```

with

```java
throw new DeviceNotFoundException("Server not found: " + serverId);
```

There are four (in `browse`, `browseInternal`, `search`, `browseMetadata`). Add the import.

### 3. Make browse action failures carry the UPnP error code

In `ContentBrowseService.executeSync`, replace:

```java
        ActionException failure = invocation.getFailure();
        if (failure != null) {
            throw new RuntimeException("DLNA Browse action failed: " + failure.getMessage(), failure);
        }
```

with:

```java
        ActionException failure = invocation.getFailure();
        if (failure != null) {
            int errorCode = failure.getErrorCode() > 0 ? failure.getErrorCode() : -1;
            throw new DlnaException("ContentDirectory action failed: " + failure.getMessage(), errorCode);
        }
```

This matches what `AvTransportService.executeSync` already does. **Check the `search`
fallback still works:** `search()` catches `RuntimeException`, and `DlnaException` extends
`RuntimeException`, so the Synology UPnP-501 fallback path is unaffected.

### 4. Delete the try/catch blocks from `BrowseController`

All four handler methods should let exceptions propagate to `GlobalExceptionHandler`. For
example `browse` becomes:

```java
    @GetMapping("/{serverId}/browse")
    public BrowseResult browse(
            @PathVariable String serverId,
            @RequestParam(value = "objectId", defaultValue = "0") String objectId,
            @RequestParam(value = "index", defaultValue = "0") int index,
            @RequestParam(value = "count", defaultValue = "50") int count,
            @RequestParam(value = "filter", defaultValue = DEFAULT_FILTER) String filter,
            @RequestParam(value = "sortBy", defaultValue = "") String sortBy) {
        log.debug("Browse request: server={}, objectId={}, index={}, count={}",
                serverId, objectId, index, count);
        return contentBrowseService.browse(serverId, objectId, index, count, filter, sortBy);
    }
```

Do the same for `search` and `metadata`. `metadata` keeps its empty-result check:

```java
        List<BrowsableItem> items = contentBrowseService.browseMetadata(serverId, itemId, filter);
        if (items.isEmpty()) {
            throw new DeviceNotFoundException("Item not found: " + itemId);
        }
        return items.get(0);
```

Leave `thumbnail` alone — step-17 rewrites it.

### 5. Rewrite `GlobalExceptionHandler`

```java
package com.dlnahub.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Builds the error body. Uses LinkedHashMap rather than Map.of because an exception
     * message can legitimately be null (e.g. a bare NullPointerException) and Map.of
     * rejects null values — which would make the error handler itself throw.
     */
    private static Map<String, Object> body(String message, Integer upnpErrorCode) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message != null ? message : "Unexpected error");
        if (upnpErrorCode != null) {
            map.put("upnpErrorCode", upnpErrorCode);
        }
        return map;
    }

    @ExceptionHandler(DeviceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(DeviceNotFoundException e) {
        log.warn("Not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(e.getMessage(), null));
    }

    @ExceptionHandler(DlnaException.class)
    public ResponseEntity<Map<String, Object>> handleDlnaException(DlnaException e) {
        HttpStatus status = e.getUpnpErrorCode() > 0 ? HttpStatus.BAD_GATEWAY
                                                     : HttpStatus.INTERNAL_SERVER_ERROR;
        log.warn("DLNA error ({}): {}", e.getUpnpErrorCode(), e.getMessage());
        return ResponseEntity.status(status).body(body(e.getMessage(), e.getUpnpErrorCode()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(body(e.getMessage(), null));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.error("Illegal state: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(e.getMessage(), null));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(e.getMessage(), null));
    }
}
```

Note the status change for a UPnP error: **502 Bad Gateway**, not 400. The upstream *device*
rejected the action; the client's request was well-formed. Update the README error-handling
section to match.

## Do not

- Do not change `PlaybackController` — it already has no try/catch and relies on the
  handler, which is the pattern being adopted here.
- Do not leak stack traces into response bodies.

## Verify

```bash
cd backend && mvn test && mvn spring-boot:run
```

```bash
# Unknown server id: expect 404 from both endpoints, consistently
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:9100/api/servers/nope/browse"
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:9100/api/servers/nope/search?query=x"
# Unknown player id: expect 500 with a JSON body containing "error"
curl -s "http://localhost:9100/api/players/nope/status"
```
