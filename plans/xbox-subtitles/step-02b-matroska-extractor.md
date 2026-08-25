# Step 02b — Matroska track listing and cue extraction

**Phase:** 1 — The subtitle backend
**Status:** **Done** — `EbmlReader`, `MatroskaSubtitleParser`, and the embedded path in
`SubtitleService`
**Files:** `backend/src/main/java/com/dlnahub/subtitle/EbmlReader.java`,
`backend/src/main/java/com/dlnahub/subtitle/MatroskaSubtitleParser.java`,
`backend/src/test/java/com/dlnahub/subtitle/MatroskaSubtitleParserTest.java`
**Depends on:** step-02

## Problem

Most of the library is MKV with the subtitle carried as a track inside the container, and
the NAS will not extract it — eight MKV items were probed for a served sidecar at six
different extensions and every one 404'd (`FINDINGS.md` §3). Sidecars alone cover about a
third of the library; embedded tracks cover most of the rest.

## What was built

No transcoding and no ffmpeg. Matroska is EBML, and `S_TEXT/UTF8` stores the cue text
verbatim — this is a container parse, not a media operation.

### `EbmlReader`

The primitives: variable-length element IDs and sizes, plus `readBytes` / `skip` over a
sequential `InputStream`. Sequential by design — random access over HTTP would mean one
range request per block, and there are tens of thousands of blocks in a file.

Two details that bite if missed:

- IDs keep their marker bit (it is part of the identity); sizes have it stripped (it is only
  a length prefix).
- A size with every value bit set means "unknown — runs until the parent ends". Clusters
  legitimately use this. The walker handles it by treating the parent's end as the boundary
  and recognising a nested `Cluster` ID as the start of the next one.

### `MatroskaSubtitleParser`

One recursive walker serving two very different jobs:

| Operation | Cost | How |
|-----------|------|-----|
| `readTracks` | sub-second | `Tracks` sits at about byte 300; the walk stops at the first cluster |
| `extractCues` | full-file scan | subtitle blocks are interleaved through every cluster |

The scan stays cheap because a block's track number is the first field of its payload: for
anything that is not the target track the payload is skipped, never copied out of the
stream. Only subtitle blocks — a few hundred KB across the whole file — are materialised.

Three things that came out of real files rather than the spec:

- **Timing.** `startMs = (clusterTimestamp + blockRelative) × TimestampScale ÷ 1e6`. The
  scale is not always the 1 ms default, so it is read from `Info`.
- **Duration.** `BlockGroup` carries `BlockDuration`; `SimpleBlock` carries nothing. One cue
  is buffered so an open-ended one can be closed at the next cue's start, falling back to a
  3 s assumption for the last cue.
- **ASS/SSA.** Those blocks are not bare text — Matroska stores the `Dialogue` fields
  comma-separated with the line itself last. `S_TEXT/UTF8` needs no such handling.

Lacing is detected and skipped with a warning. Subtitles effectively never use it, and
guessing at the frame boundaries would corrupt the text rather than fail cleanly.

### Async, progressive extraction

Nine seconds is far too long to block a request from the phone, so `SubtitleService` runs
extraction on a two-thread pool and publishes cues as they are found. Because the scan is a
forward pass from the start of the file, cues arrive in playback order and the opening of
the film is on screen within a second:

```
  0.0s  status=EXTRACTING  cues=0
  1.5s  status=EXTRACTING  cues=54
  4.6s  status=EXTRACTING  cues=312
  9.2s  status=EXTRACTING  cues=839
 10.7s  status=READY       cues=840
```

The nine seconds is never actually waited on.

## Do not

- Do not add ffmpeg. Nothing here needs it, and it is the thing that makes step-09 expensive.
- Do not try to skip video blocks with HTTP range requests. There are tens of thousands of
  them; reading and discarding at 76 MB/s beats a round trip each.
- Do not offer `S_HDMV/PGS` or `S_VOBSUB` as readable. They are bitmaps — flagged
  `textBased: false` and filtered out of the default pick, rather than returning empty cues.
- Do not raise the extraction pool above two threads. It is bound by how fast the NAS serves
  bytes; a third scan only steals throughput from the other two.

## Verify

```bash
cd backend && mvn test
```

11 tests build synthetic Matroska files in memory and parse them back — a video block that
must be skipped, a `SimpleBlock` with no duration, a non-default timestamp scale, an ASS
payload, a bitmap codec, and a truncated stream.

Live, against the NAS:

```bash
SRV=b31ca191-a364-38bd-b881-10e0d2460e16

# Lanterns S01E01 — one English S_TEXT/UTF8 track
curl -s "http://localhost:9100/api/servers/$SRV/subtitles/44%24%4038337/tracks"

# Beverly Hills Cop: Axel F — 38 tracks, three of them English
curl -s "http://localhost:9100/api/servers/$SRV/subtitles/44%24%4034957/tracks"

# Extraction, polled until complete
curl -s "http://localhost:9100/api/servers/$SRV/subtitles/44%24%4038337"
```

Expected for Lanterns: `embedded:3`, `S_TEXT/UTF8`, `eng`, and 840 cues once complete, the
first at 16225 ms reading "Tonight, a 60 Minutes exclusive." — italics stripped.
