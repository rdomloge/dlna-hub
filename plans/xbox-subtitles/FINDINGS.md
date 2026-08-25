# Findings — subtitles on the Xbox

**Date:** 2026-08-25
**Method:** live probing of the real NAS (10.0.0.60) and the real Xbox (10.0.0.106),
plus a survey of published DLNA/vendor behaviour.

Everything below was measured, not assumed. The commands are reproducible — keep them if
you need to re-check after a DSM or Xbox firmware update.

---

## 1. The NAS does not advertise subtitles — but it does serve them

Raw `Browse` of the *Street Kings* folder (`ObjectID=44$15353`, `Filter=*`) returns exactly
one resource and no caption metadata of any kind:

```xml
<item id="44$@38318" parentID="44$15353" restricted="1">
  <dc:title>Street Kings (2008) 1080p-H264-AC 3 (DolbyDigital-5.1) &amp; nickarad</dc:title>
  <upnp:class>object.item.videoItem</upnp:class>
  <dc:date>2026-07-18T23:26:04</dc:date>
  <res protocolInfo="http-get:*:video/mp4:*" resolution="1920x800" size="2050489717"
       bitrate="314105" duration="1:48:48.000" nrAudioChannels="6" sampleFrequency="48000"
  >http://10.0.0.60:50002/v/NDLNA/38318.mp4</res>
</item>
```

No `<sec:CaptionInfoEx>`, no `pv:subtitleFileUri`, no second `<res>` with `text/srt`. The
`sec:` namespace is *declared* on the DIDL-Lite root but never used.

This is not a client-profile effect. The same `Browse` was repeated with four `User-Agent`
values — `SEC_HHP_Samsung TV/1.0`, `DLNADOC/1.50 SEC_HHP_[TV]Samsung`, an Xbox UA, and
`PLAYSTATION 3` — and the DIDL came back identical every time. Synology's media server has
no subtitle-advertising profile we can trigger.

**But the file is reachable.** Swap the extension on the resource URL and the NAS serves it:

```
GET http://10.0.0.60:50002/v/NDLNA/38318.srt
  -> 200 OK
     Content-Type: application/x-srt
     Content-Length: 92173
     Accept-Ranges: bytes
     transferMode.dlna.org: Streaming
```

Only this pattern works. `/s/NDLNA/...`, `/subtitle/NDLNA/...` and `/c/NDLNA/...` all 404.

**This is the load-bearing discovery for the whole feature:** the subtitle URL is a pure
string transform of the video URL we already parse into `BrowsableItem.resourceName`. No
extra NAS API, no SMB, no credentials.

### Coverage

30 movie folders sampled, first video item in each, `HEAD` on the derived `.srt` URL:

| Result | Count |
|--------|-------|
| SRT served | 10 |
| 404 | 20 |

So **roughly a third of the library** has a subtitle the NAS will hand over. "No subtitles
for this title" is the common case, not an edge case — design for it.

### Encoding is not consistent

| Title | Encoding observed |
|-------|-------------------|
| Street Kings (2008) | UTF-8, CRLF |
| 21 Jump Street (2012) | UTF-8 |
| Aliens DC (1986) | **UTF-16LE with BOM** |

A parser that assumes UTF-8 produces NUL-interleaved garbage on the third one. BOM sniffing
(UTF-8 / UTF-16LE / UTF-16BE) with a single-byte fallback is mandatory, not polish.

**The fallback charset cannot be inferred, and CP1252 is not always right.** The Street
Kings sidecar turned out not to be UTF-8 at all — the row above was corrected after reading
the bytes:

```
GEMENE DISP c3 52 55 54 45      "GEMENE DISP<C3>RUTE"
```

`0xC3` followed by `R` is invalid UTF-8, so this is a single-byte encoding. In ISO-8859-2 it
is `Ă` — "GEMENE DISPĂRUTE", correct Romanian. In windows-1252 the same byte is `Ã`, which
is what you get if you guess Western European. Romanian, Polish, Czech and Hungarian
subtitles are typically Latin-2 and collide with Latin-1 across the whole upper range, so no
amount of byte inspection separates them reliably.

The code therefore defaults to windows-1252 (right for an English-language library) and
exposes `subtitles.fallback-charset` / `SUBTITLE_FALLBACK_CHARSET` for the rest. Files with
a BOM, and valid UTF-8 files, never reach the fallback and are unaffected.

### Language is not guaranteed

The *Street Kings* sidecar is **Romanian**:

```
2
00:04:10,959 --> 00:04:12,250
Salut !
```

The NAS exposes exactly one `.srt` per video ID and offers no way to ask for another. If the
folder also holds an English track, DLNA will not reveal it. See
[Open questions](#open-questions) — this one may change step-02's scope.

---

## 2. The Xbox cannot receive subtitles over DLNA. This is settled.

The Xbox One presents as `urn:schemas-upnp-org:device:MediaRenderer:1`,
`dlna:X_DLNADOC = DMR-1.50`, with the three standard services and no vendor extensions.

`ConnectionManager::GetProtocolInfo` returns a Sink list of **271 DLNA profiles across 27
MIME types**. Here is every one of those types:

```
audio/3gpp   audio/3gpp2  audio/L16    audio/eac3   audio/mp4    audio/mpeg
audio/vnd.dlna.adts       audio/vnd.dolby.dd-raw    audio/wav    audio/x-flac
audio/x-matroska          audio/x-ms-wma
image/gif    image/jpeg   image/png
video/3gpp   video/3gpp2  video/avi    video/mp4    video/mpeg   video/quicktime
video/vnd.dlna.mpeg-tts   video/x-matroska          video/x-matroska-3d
video/x-ms-asf            video/x-ms-wmv            video/x-msvideo
```

**Not one subtitle or text format.** No `text/srt`, no `smi/caption`, no `text/vtt`, no
`application/ttml+xml`. A DLNA renderer that does not declare a sink for a format will not
accept a resource in that format, so an SRT `<res>` advertised to this device has nothing to
bind to.

The SCPDs were dumped for all three services. `AVTransport` exposes the stock UPnP action
set and nothing more; `RenderingControl` is `SetVolume`/`SetMute`/presets only. There is no
`SetSubtitle`, no `X_SetSubtitle`, no vendor action of any kind to aim at.

### What this rules out

The vendor extensions that make external subtitles work elsewhere all need renderer-side
cooperation the Xbox does not have:

| Mechanism | Who honours it | Xbox |
|-----------|----------------|------|
| `<sec:CaptionInfoEx>` + `CaptionInfo.sec` response header | Samsung TVs | No sink, no extension |
| `<pv:subtitleFileUri>` (PacketVideo) | Panasonic Viera, some apps | No sink, no extension |
| Second `<res>` with `http-get:*:text/srt:*` | Renderers declaring a `text/srt` sink | Sink absent |

This matches the field reports: the Xbox Media Player app and the Xbox VLC app both play
DLNA video fine and both fail to show external subtitles.

### Step-01 spike result — 2026-08-25

Confirmed on the device. `SetAVTransportURI` was sent directly to the Xbox's AVTransport
control URL with DIDL carrying **all four** mechanisms at once — a second `<res>` with
`http-get:*:text/srt:*`, `<sec:CaptionInfoEx>`, `<sec:CaptionInfo>`, and
`<pv:subtitleFileUri>` + `<pv:subtitleFileType>` — all pointing at the NAS's
`.../38318.srt`, followed by `Play`.

- `SetAVTransportURI` → **HTTP 200**
- `Play` → **HTTP 200**
- Renderer state → `PLAYING`, `Street Kings`, duration `1:48:48`, position advancing
- Subtitles displayed → **no**
- Xbox subtitle/CC menu offered a track → **no**

Playback ran well past the first cue (00:03:09) and through continuous dialogue from
00:04:26 onward. Nothing rendered.

Note the Xbox **accepted** the enriched DIDL rather than rejecting it — it did not error on
the unknown elements, it simply ignored them. That is the expected behaviour for a renderer
with no sink for the format, and it means there is no malformed-XML explanation to chase.
Option A is closed.

### The one thing the Xbox *does* do natively

The built-in **Media Player app reads sidecar `.srt` files when it browses a filesystem** —
USB media, or an SMB/UNC network share. It does *not* read subtitle tracks embedded inside
MKV, and it does not read anything over DLNA. That asymmetry is the basis of
[Option D](#option-d--sidestep-dlna-entirely-zero-code).

---

## 3. Most of the library carries subtitles *inside* the MKV — 2026-08-25

The sidecar figure of 33% badly understates what is available, because much of the library
is MKV with the subtitle embedded as a track. The NAS does not extract those: eight MKV
items were probed for a served sidecar at `.srt`, `.ass`, `.ssa`, `.vtt`, `.sub` and `.smi`,
and **every one 404'd**. If we want them, we extract them ourselves.

We can. Matroska is EBML, and the `Tracks` element sits near the front of the file:

```
Lanterns.2026.S01E01 (687 MB)
  Segment @40
    SeekHead  size=67    @52
    Info      size=96    @213
    Tracks    size=2632  @314      <- 2.6 KB, at byte 314
      TrackEntry  TrackNumber 1  V_MPEGH/ISO/HEVC   type 1  (video)
      TrackEntry  TrackNumber 2  A_AC3       [eng]  type 2  (audio)
      TrackEntry  TrackNumber 3  S_TEXT/UTF8 [eng]  type 17 (subtitle)
    Cluster   FIRST CLUSTER @3308
```

**`S_TEXT/UTF8` is SRT.** The cue text is stored verbatim in the block payloads; the timings
come from the cluster timestamp plus the block's relative offset, scaled by
`TimestampScale`. There is no transcoding involved — it is a container parse.

### The two costs are very different

| Operation | Cost | Method |
|-----------|------|--------|
| **List the tracks** | one 400 KB range request, well under a second | `Tracks` is at the front |
| **Extract the cues** | full-file read | subtitle blocks are interleaved through every cluster |

Measured on Lanterns E01, streaming and discarding everything that is not the target track:

```
read 687.5 MB in 9.1s  (76 MB/s)
cues found: 840
```

Nine seconds, and the text comes out clean and correctly timed:

```
00:00:20,062 --> 00:00:24,815
<i>Ten years ago, no one on Earth was aware
we were already under the protection</i>
```

Two consequences for the design:

- **Listing is cheap, extraction is not.** Split them. The track list can be synchronous;
  extraction cannot be, and must not block a request from the phone.
- **Cues arrive in playback order.** Extraction is a forward scan from the start of the
  file, so the opening cues are ready in the first second. The panel can start displaying
  almost immediately and fill in behind — the nine seconds is never actually waited on.

### Track selection is mandatory, not a nicety

Codecs found across eight sampled MKVs — seven have an English `S_TEXT/UTF8` track:

| File | Subtitle tracks |
|------|-----------------|
| Lanterns S01E01 / S01E02 | 1 × `S_TEXT/UTF8` [eng] |
| 57 Seconds, Alien, Anchorman 2, Argylle, BlackBerry | 1 × `S_TEXT/UTF8` [eng] |
| **Beverly Hills Cop: Axel F** | **38 tracks**, `S_TEXT/UTF8`, incl. **3 × [eng]** (default, plain, SDH) |
| 28 Days Later | none — video + 2 audio (one a director commentary) |

Thirty-eight tracks with three English variants — one flagged default and named after the
release group, one plain, one SDH — means "pick the English one" is not a rule that works.
Surface the list, pick a sensible default, and let it be changed.

### What is not supported, and why

`S_HDMV/PGS` and `S_VOBSUB` store subtitles as **bitmaps**, not text. Turning those into
readable cues needs OCR, which is a different project. None appeared in the sample, but the
code must detect them and say so plainly rather than returning empty cues.



### Option A — advertise the subtitle to the renderer

Enrich the DIDL we send in `SetAVTransportURI` with all three vendor mechanisms and serve
the SRT from the hub. **The evidence says this cannot work on the Xbox** — no sink to accept
it, no action to drive it.

Kept in the plan as `step-01`, as a *timeboxed spike*: it is one SOAP call, it costs an
hour, and a negative result on the real device is worth more than an inference from a
capability list. It has residual value too — the mpv renderer already on the network can
take subtitles, so the plumbing is not wasted if a second renderer ever matters.

**Do not write production code for this before the spike reports back.**

### Option B — burn the subtitles into the picture

Guaranteed to display, because by the time the Xbox sees them they are pixels.

- **B1, pre-baked.** An ffmpeg job writes a hardsubbed copy, the NAS indexes it, you play it
  normally. Reliable, no real-time risk, no seek problems. Costs a full re-encode (tens of
  minutes) and a second copy of the file. Viable as "queue it now, watch it tonight".
- **B2, live transcode proxy.** ffmpeg with a `subtitles=` filter streamed to the Xbox on
  demand. This is what Plex/Emby/Jellyfin do and it is a genuinely large piece of
  engineering: ffmpeg in the backend image, a real-time 1080p encode inside a 3-core CPU
  limit, and — the hard part — byte-range seeking against an output with no
  `Content-Length`. Seeking is the feature that breaks first and the one you use most.

B2 is **out of scope**. B1 is specified in `step-09` as an optional escalation, to be built
only if the second screen proves not to be enough.

### Option C — the second screen  <-- recommended, and the bulk of this plan

Show the subtitles on the phone, synced to the renderer's own clock, with a read-aloud
button. This is the stated fallback and the only path certain to work, because it depends on
nothing the Xbox has to agree to. The hub already polls `GetPositionInfo` every second for
the scrubber, so the timing source is built and proven.

It also degrades gracefully. At 33% coverage the panel is often empty, and an empty panel is
a non-event — whereas a transcoder that only fires on a third of the library is a lot of
machinery for a little benefit.

### Option D — sidestep DLNA entirely (zero code)

Open the file from the SMB share in the Xbox's own Media Player app instead of pushing it
from the hub. The Xbox sees the real folder, finds the `.srt` next to the video, and renders
subtitles natively — on the TV, with no hub involvement.

The catch: the hub cannot drive the Media Player app's file browser, so navigation is manual,
and you lose the hub's browsing, TMDB metadata and remote control.

**Worth one manual test before any code is written.** If it works and the navigation is
tolerable, it may simply be the answer for the handful of films where subtitles matter, and
Option C becomes a convenience rather than a necessity. Five minutes to try: Xbox → Media
Player → network share → the film's folder.

---

## Open questions

1. **Is the Romanian sidecar for *Street Kings* the only one in that folder?** Check the
   folder on the NAS directly. If there is an English `.srt` that Synology is not exposing,
   the derived-URL approach will always pick the wrong one and we need a second source (a
   manual override, or reading the folder over SMB). This changes step-02's scope.
2. **Does the hub need to handle multiple subtitle tracks at all?** Deferred until (1) is
   answered.
3. ~~**Does `GetPositionInfo` on the Xbox report whole seconds only?**~~ **Answered
   2026-08-25 — yes.** Sampled at 250 ms while *Street Kings* played:

   ```
     0.03s  0:04:33      2.87s  0:04:36
     0.32s  0:04:33      3.15s  0:04:36
     0.60s  0:04:33      3.43s  0:04:36
     0.90s  0:04:34      3.71s  0:04:36
     1.18s  0:04:34      3.98s  0:04:37
   ```

   Whole seconds, no fractional component, advancing in real time. So step-06's design
   stands: interpolation is required, and the calibration burst is worth building. The good
   news is that the transitions are clean and land within one 280 ms sample — the burst will
   resolve the anchor to roughly a quarter of a second, comfortably inside the ~400 ms
   target step-06 sets for itself.

---

## Reproducing the probes

```bash
# 1. Raw DIDL for a folder, Filter=* — shows exactly what the NAS advertises
#    Browse / BrowseDirectChildren / ObjectID=44$15353 / Filter=*
curl -s -X POST "http://10.0.0.60:50001/ContentDirectory/control" \
  -H 'Content-Type: text/xml; charset="utf-8"' \
  -H 'SOAPAction: "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"' \
  --data-binary @browse.xml

# 2. The subtitle that is served but never advertised
curl -s -D - -o /dev/null "http://10.0.0.60:50002/v/NDLNA/38318.srt"

# 3. Find the Xbox descriptor (its LOCATION changes between boots)
#    M-SEARCH, ST: urn:schemas-upnp-org:device:MediaRenderer:1

# 4. The decisive one — what the Xbox will accept
curl -s -X POST "http://10.0.0.106:2869/upnphost/udhisapi.dll?control=uuid:<udn>+urn:upnp-org:serviceId:ConnectionManager" \
  -H 'Content-Type: text/xml; charset="utf-8"' \
  -H 'SOAPAction: "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo"' \
  --data-binary @gpi.xml | grep -o 'http-get:\*:[^:]*:' | sort -u
```
