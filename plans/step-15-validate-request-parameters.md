# Step 15 — Validate request parameters

**Phase:** 2 — Security and robustness
**Severity:** Medium (report: M11)
**Files:** `backend/pom.xml`,
`backend/src/main/java/com/dlnahub/controller/BrowseController.java`,
`backend/src/main/java/com/dlnahub/controller/PlaybackController.java`,
`backend/src/main/java/com/dlnahub/dto/SeekRequestDto.java`,
`backend/src/main/java/com/dlnahub/dto/VolumeRequestDto.java`,
`backend/src/main/java/com/dlnahub/exception/GlobalExceptionHandler.java`
**Depends on:** step-16 (do 16 first if you want the handler edits in one pass)

## Problem

No endpoint validates its inputs.

- `GET /browse?count=-1` reaches `pagedResult` / `searchInMemory`, where
  `subList(from, to)` is called with `to < from` and throws
  `IllegalArgumentException` — a 500 for what is a client error.
- `GET /browse?count=999999` triggers an unbounded fetch.
- `POST /seek {"seconds": -5}` produces a malformed `REL_TIME` (also fixed defensively in
  step-12, but the request should be rejected outright).
- `PUT /volume {"volume": 500}` is caught inside `RenderingControlService`, but as a
  `DlnaException` mapped to 500, not a 400.
- `Integer.parseInt(totalMatchesStr)` in three places throws on a non-numeric
  `TotalMatches` from a misbehaving server.

## Change

### 1. Add the validation starter to `backend/pom.xml`

Next to `spring-boot-starter-web`:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
```

### 2. Constrain the browse and search parameters

In `BrowseController`, add `@Validated` to the class and constraints to the parameters:

```java
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;

@RestController
@Validated
@RequestMapping("/api/servers")
public class BrowseController {
```

On `browse`:

```java
            @RequestParam(value = "index", defaultValue = "0") @Min(0) int index,
            @RequestParam(value = "count", defaultValue = "50") @Min(1) @Max(500) int count,
```

On `search`, the same two, plus:

```java
            @RequestParam @NotBlank String query,
```

### 3. Constrain the request bodies

`SeekRequestDto`:

```java
import jakarta.validation.constraints.Min;

public class SeekRequestDto {
    @Min(value = 0, message = "seconds must not be negative")
    private int seconds;
    // ... unchanged
```

`VolumeRequestDto`:

```java
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class VolumeRequestDto {
    @Min(value = 0, message = "volume must be between 0 and 100")
    @Max(value = 100, message = "volume must be between 0 and 100")
    private int volume;
    // ... unchanged
```

In `PlaybackController`, add `@Valid` to both body parameters:

```java
    public ResponseEntity<Map<String, Object>> seek(@PathVariable String playerId,
                                                      @RequestBody @jakarta.validation.Valid SeekRequestDto request) {
```

```java
    public ResponseEntity<Map<String, Object>> setVolume(@PathVariable String playerId,
                                                           @RequestBody @jakarta.validation.Valid VolumeRequestDto request) {
```

### 4. Map validation failures to 400 in `GlobalExceptionHandler`

```java
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException e) {
        log.warn("Invalid request parameter: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleInvalidBody(
            org.springframework.web.bind.MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request body");
        log.warn("Invalid request body: {}", message);
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
```

### 5. Make `TotalMatches` parsing defensive

In `ContentBrowseService`, add a helper and use it at all three
`Integer.parseInt(totalMatchesStr)` sites (`browse`, `browseInternal`, `searchPage`):

```java
    /** TotalMatches from a misbehaving server may be absent or non-numeric; treat it as 0. */
    private static int parseTotalMatches(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            log.warn("Server returned a non-numeric TotalMatches: {}", value);
            return 0;
        }
    }
```

Each call site becomes `int totalMatches = parseTotalMatches(totalMatchesStr);`.

## Do not

- Do not add validation to `PlayRequestDto` — `uri` is legitimately optional (an empty URI
  means "resume whatever is loaded").
- Do not lower the `@Max(500)` on `count`; 500 matches the internal `CRAWL_PAGE_SIZE`.

## Verify

```bash
cd backend && mvn test && mvn spring-boot:run
```

```bash
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:9100/api/servers/x/browse?count=-1"   # expect 400
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:9100/api/servers/x/browse?count=9999" # expect 400
curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Content-Type: application/json" \
     -d '{"seconds":-5}' "http://localhost:9100/api/players/x/seek"                              # expect 400
curl -s -o /dev/null -w "%{http_code}\n" -X PUT -H "Content-Type: application/json" \
     -d '{"volume":500}' "http://localhost:9100/api/players/x/volume"                            # expect 400
```
