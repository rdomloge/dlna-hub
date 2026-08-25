# Step 03 — REST endpoints for cues and WebVTT

**Phase:** 1 — The subtitle backend
**Files:** `backend/src/main/java/com/dlnahub/controller/SubtitleController.java` (new)
**Depends on:** step-02

## Problem

`SubtitleService` produces cues; the phone needs to fetch them. The controller also has to
resolve `itemId` to a video URL, because the frontend holds an item ID and the service
needs a resource URL.

## Change

A new controller under the existing `/api/servers` prefix, matching `BrowseController`'s
shape (`@RestController`, `@Validated`, constructor injection, `@PathVariable`).

```java
@RestController
@Validated
@RequestMapping("/api/servers")
public class SubtitleController {

    @GetMapping("/{serverId}/subtitles/{itemId}")
    public SubtitleDto subtitles(@PathVariable String serverId, @PathVariable String itemId)

    @GetMapping(value = "/{serverId}/subtitles/{itemId}.vtt", produces = "text/vtt")
    public ResponseEntity<String> subtitlesVtt(@PathVariable String serverId,
                                               @PathVariable String itemId)
}
```

### Resolving the item

Both handlers need the video URL. Get it the way the rest of the codebase does:

```java
List<BrowsableItem> items = contentBrowseService.browseMetadata(serverId, itemId, "*");
if (items.isEmpty()) {
    throw new DeviceNotFoundException("Item not found: " + itemId);
}
String videoUrl = items.get(0).getResourceName();
```

`browseMetadata` is already the metadata path used by `BrowseController.metadata`, and
`resourceName` is already parsed out of `<res>` by `ContentBrowseService.parseItem`. Nothing
new is needed on the browse side.

### The JSON shape

```java
public record SubtitleDto(boolean available, int cueCount, List<SubtitleCue> cues) {
}
```

When the NAS has no subtitle, return `200` with `available: false` and an empty list —
**not** a 404. This is the two-in-three case, and the frontend asks for it on every item; a
404 would mean an error path on the normal case and a console full of red. This is the same
reasoning `TmdbController` uses for `{"available": false}`.

### The WebVTT variant

Cheap to add and worth having: it is the format a `<track>` element takes, so if a browser
video player is ever pointed at the hub, or you want to hand the file to something else, it
is already there.

```
WEBVTT

00:03:09.273 --> 00:03:10.606
GEMENE DISPM-CRUTE
```

Two differences from SRT and they are the whole conversion: the `WEBVTT` header, and `.`
instead of `,` as the decimal separator. Drop the cue index. Return `404` here when there
is no subtitle — unlike the JSON endpoint this one is fetched deliberately, not
speculatively.

Note the mapping: `{itemId}.vtt` with a dotted suffix works because Spring Boot 3 does not
do suffix pattern matching by default, so the `.vtt` is literal. Keep the two mappings
distinct rather than using a `format` query parameter — it keeps the `produces` type static
and honest, which is the lesson of `step-17-fix-thumbnail-content-type`.

### Caching header

```java
headers.setCacheControl("public, max-age=3600");
```

Subtitle files do not change during a film.

## Do not

- Do not proxy the raw `.srt` bytes through. There is no consumer for it: step-01 settled
  that the Xbox will not fetch one, and the frontend wants cues. Add it later if something
  actually needs it.
- Do not add a `?offset=` parameter to shift timings server-side. Offset is a viewing
  preference and it lives in the browser (step-06) where it can be adjusted without a round
  trip.
- Do not page the cues. A two-hour film is ~1500 cues, well under 200 KB of JSON, fetched
  once per film.
- Do not add the availability flag to browse results here — that is step-04.

## Verify

```bash
cd backend && mvn test && mvn spring-boot:run
```

```bash
SRV=b31ca191-a364-38bd-b881-10e0d2460e16

# A title that has a subtitle (Street Kings)
curl -s "http://localhost:9100/api/servers/$SRV/subtitles/44%24%4038318" | head -c 400
# expect: {"available":true,"cueCount":<~1200>,"cues":[{"index":1,"startMs":189273,...

# A title that does not — expect 200, available:false, empty cues, NOT a 404
curl -s -i "http://localhost:9100/api/servers/$SRV/subtitles/<an-item-with-no-srt>" | head -3

# WebVTT
curl -s -i "http://localhost:9100/api/servers/$SRV/subtitles/44%24%4038318.vtt" | head -8
# expect: HTTP/1.1 200, Content-Type: text/vtt, body starting "WEBVTT"

# Unknown item
curl -s -i "http://localhost:9100/api/servers/$SRV/subtitles/nope" | head -3
# expect: 404 with the GlobalExceptionHandler JSON body
```

Note the item IDs contain `$` and `@` — URL-encode them (`%24`, `%40`) or the shell and the
server will disagree about where the path ends.
