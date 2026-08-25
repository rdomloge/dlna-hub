# Step 02 — `SubtitleService`: derive, fetch, decode, parse

**Phase:** 1 — The subtitle backend
**Files:** `backend/src/main/java/com/dlnahub/service/SubtitleService.java` (new),
`backend/src/main/java/com/dlnahub/dlna/model/SubtitleCue.java` (new),
`backend/src/test/java/com/dlnahub/service/SubtitleServiceTest.java` (new)
**Depends on:** step-01 reporting a negative result

## Problem

The NAS serves the subtitle but never advertises it. `FINDINGS.md` §1 establishes the whole
contract:

- the URL is the video's `resourceName` with the extension replaced by `.srt`
- roughly a third of items have one; the rest 404
- the encoding varies — UTF-8, and at least one UTF-16LE-with-BOM in the library
- the payload is SRT with CRLF line endings

This step turns that into a service that hands back a parsed cue list, and does not depend
on anything above it.

## Change

### `SubtitleCue`

A record, in the style of `AvTransportService.PositionInfo`:

```java
package com.dlnahub.dlna.model;

import java.util.List;

/**
 * One SRT cue. Times are milliseconds from the start of the media, which is what the
 * frontend's sync engine wants — converting once here beats converting on every frame.
 */
public record SubtitleCue(int index, long startMs, long endMs, List<String> lines) {
}
```

### `SubtitleService`

Constructor-inject nothing; build its own `RestTemplate` with timeouts exactly as
`ThumbnailService` does. Follow that class closely — it is the nearest neighbour and it
already settled the questions about timeouts and cache bounding.

Public surface:

```java
/** The subtitle URL implied by a video resource URL, or null if there is no sensible one. */
static String deriveSubtitleUrl(String videoUrl)

/** Cues for this item, or an empty list when the NAS has no subtitle for it. */
List<SubtitleCue> getCues(String serverId, String itemId, String videoUrl)

/** True if the NAS answers a HEAD for the derived URL. Cheap enough for a browse listing. */
boolean isAvailable(String videoUrl)
```

Four pieces of real work:

**1. Derive the URL.** Replace the final extension with `.srt`:

```java
static String deriveSubtitleUrl(String videoUrl) {
    if (videoUrl == null || videoUrl.isEmpty()) return null;
    int lastSlash = videoUrl.lastIndexOf('/');
    int dot = videoUrl.lastIndexOf('.');
    // A dot before the last slash belongs to the host or a path segment, not the filename.
    if (dot <= lastSlash) return null;
    // Do not let a query string end up in the middle of the derived name.
    if (videoUrl.indexOf('?', dot) >= 0) return null;
    return videoUrl.substring(0, dot) + ".srt";
}
```

**2. Decode the bytes.** Fetch as `byte[]`, never as `String` — `RestTemplate` would guess
the charset from a `Content-Type` that says `application/x-srt` with no charset, and guess
wrong. Sniff the BOM:

| Leading bytes | Charset |
|---------------|---------|
| `EF BB BF` | UTF-8 (skip 3) |
| `FF FE` | UTF-16LE (skip 2) |
| `FE FF` | UTF-16BE (skip 2) |
| anything else | try UTF-8 strictly; on `CharacterCodingException`, fall back to `windows-1252` |

The strict-UTF-8-then-CP1252 fallback matters: a lot of older SRTs are CP1252 and decoding
them as UTF-8 leniently turns accented characters into replacement chars silently. Use
`CharsetDecoder` with `CodingErrorAction.REPORT` so the failure is detectable.

**3. Parse SRT.** Split on blank lines; each block is an optional index, a timing line, then
one or more text lines. Requirements that come from real files:

- Accept both `\r\n` and `\n`.
- The timing line is `HH:MM:SS,mmm --> HH:MM:SS,mmm`. Accept `.` as the decimal separator
  too; both occur. Ignore any trailing position payload (`X1:… Y1:…`) after the end time.
- A block with an unparseable timing line is skipped, not fatal — one malformed cue must
  not lose the other two thousand.
- Strip the common inline tags (`<i>`, `<b>`, `<u>`, `<font …>` and closing forms) — they
  are markup for a renderer we do not have. Leave the text otherwise untouched.
- Keep the lines as a list. Do not join them; the UI decides how to break them, and the
  read-aloud in step-08 wants to speak them as one utterance.
- Sort by `startMs` at the end. Most files are already ordered; some are not, and step-06's
  binary search requires it.

**4. Cache.** A bounded LRU keyed on `(serverId, itemId)`, holding the parsed cue list.
Copy the `Collections.synchronizedMap(new LinkedHashMap<>(…, true) { removeEldestEntry })`
pattern from `ThumbnailService`. A cap of **32 entries** is plenty — cues for a two-hour
film are perhaps 150 KB in memory and nobody has more than a couple of films open. Cache
the empty list too, so the 20-of-30 titles with no subtitle do not re-hit the NAS every
time the panel re-renders.

Log a miss at `debug`, not `info` — this sits behind a polling UI. See
`step-21-quiet-the-hot-path-logging` in the previous plan for why that matters here.

## Do not

- Do not touch `ContentBrowseService` — availability lands on the item in step-04.
- Do not add a controller. That is step-03.
- Do not implement WebVTT conversion here; the service's job is cues. Step-03 renders them.
- Do not try to support `.ass`/`.ssa`/`.sub`. The NAS serves `.srt` and nothing else was
  observed. If open question 1 in `FINDINGS.md` turns up something else, plan it separately.
- Do not add a subtitle-language parameter. One track is all the NAS exposes; a selector
  with one option is worse than no selector.

## Verify

```bash
cd backend && mvn test
```

Unit tests, house style (`given`/`when`/`then` blocks,
`method_scenario_expectedOutcome` names). Cover at minimum:

- `deriveSubtitleUrl_mp4Url_swapsExtensionToSrt`
- `deriveSubtitleUrl_urlWithNoExtension_returnsNull`
- `deriveSubtitleUrl_dotOnlyInHost_returnsNull` — e.g. `http://10.0.0.60:50002/v/NDLNA/x`
- `parse_utf16LeWithBom_decodesText` — the *Aliens* case; assert the text has no NUL chars
- `parse_cp1252Bytes_decodesAccentedCharacters`
- `parse_crlfLineEndings_producesCues`
- `parse_cueWithMalformedTiming_skipsThatCueOnly`
- `parse_italicTags_areStripped`
- `parse_outOfOrderCues_areSortedByStart`
- `getCues_secondCall_doesNotRefetch` — mock the `RestTemplate`, verify one call

Then against the real NAS (it is always on — no need to wait for the Xbox):

```bash
cd backend && mvn spring-boot:run
```

```bash
# Street Kings: expect ~1200 cues, first around 00:03:09, Romanian text
curl -s "http://10.0.0.60:50002/v/NDLNA/38318.srt" | head -20

# Aliens: the UTF-16LE one. Confirm your decoder produces clean text from this.
curl -s "http://10.0.0.60:50002/v/NDLNA/34812.srt" | head -c 200 | xxd | head -4
```

The item IDs above were valid on 2026-08-25; if the NAS has been re-indexed, re-derive them
by browsing to the folder and reading `resourceName`.
