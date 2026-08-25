# Step 01 — Spike: advertise the subtitle to the Xbox

**Phase:** 0 — Kill or confirm the ideal path
**Timebox:** 1 hour. When it expires, write the result down and move to step-02.
**Files:** none — this step produces a paragraph in `FINDINGS.md`, not code.
**Depends on:** nothing
**Needs the Xbox powered on.**

## Why this step exists

`FINDINGS.md` already shows the Xbox declares no subtitle sink and no vendor subtitle
action, so this is expected to fail. It is still worth an hour: a negative result on the
real device is stronger than an inference from a capability list, and if it *does* work the
rest of this plan is unnecessary.

The value is in the answer, not the code. **Write no production code in this step.**

## What to do

Drive the Xbox directly with `curl`, not through the hub. You are testing the renderer, and
going through `AvTransportService` only adds a variable.

1. Find the Xbox's descriptor — the `LOCATION` changes between boots:

   ```
   M-SEARCH * HTTP/1.1
   HOST: 239.255.255.250:1900
   MAN: "ssdp:discover"
   MX: 3
   ST: urn:schemas-upnp-org:device:MediaRenderer:1
   ```

   Take the AVTransport `controlURL` out of the descriptor it points at.

2. Build DIDL that offers the subtitle three different ways at once. All three vendor
   mechanisms in one document — if any of them is going to fire, this triggers it:

   ```xml
   <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
              xmlns:dc="http://purl.org/dc/elements/1.1/"
              xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
              xmlns:sec="http://www.sec.co.kr/"
              xmlns:pv="http://www.pv.com/pvns/">
     <item id="item_1" parentID="0" restricted="1">
       <dc:title>Street Kings</dc:title>
       <upnp:class>object.item.videoItem</upnp:class>
       <res protocolInfo="http-get:*:video/mp4:*"
         >http://10.0.0.60:50002/v/NDLNA/38318.mp4</res>
       <res protocolInfo="http-get:*:text/srt:*"
         >http://10.0.0.60:50002/v/NDLNA/38318.srt</res>
       <sec:CaptionInfoEx sec:type="srt"
         >http://10.0.0.60:50002/v/NDLNA/38318.srt</sec:CaptionInfoEx>
       <sec:CaptionInfo sec:type="srt"
         >http://10.0.0.60:50002/v/NDLNA/38318.srt</sec:CaptionInfo>
       <pv:subtitleFileUri>http://10.0.0.60:50002/v/NDLNA/38318.srt</pv:subtitleFileUri>
       <pv:subtitleFileType>srt</pv:subtitleFileType>
     </item>
   </DIDL-Lite>
   ```

   XML-escape the whole thing into the `CurrentURIMetaData` argument of
   `SetAVTransportURI`, then `Play` with `Speed=1`.

3. Watch the TV. Then, whatever happened, try the Xbox's own subtitle/CC control on the
   media overlay and see whether a track is listed.

4. If — and only if — anything above shows a flicker of life, one follow-up is worth the
   remaining time. The Samsung mechanism can also be driven by a **response header** rather
   than DIDL, which needs the video to be served through something we control:

   ```
   CaptionInfo.sec: http://<hub>:9100/api/servers/<id>/subtitles/<itemId>.srt
   ```

   That means proxying the video through the hub, which is a real piece of work — so do not
   start it inside this timebox. Note it and stop.

## Do not

- Do not modify `DidlUtils.generateSimpleMetadataXml`. If the spike succeeds, that change
  gets planned properly with tests; if it fails, the change is dead weight.
- Do not add subtitle fields to `PlayRequestDto`.
- Do not try Option B here. Burning in subtitles is step-09 and it is a different animal.
- Do not extend the timebox because it feels nearly there. It is not nearly there.

## Verify

There is no test to run. The step is done when `FINDINGS.md` gains a short section:

```markdown
### Step-01 spike result (date)

Pushed DIDL carrying res@text/srt, sec:CaptionInfoEx, sec:CaptionInfo, pv:subtitleFileUri
to the Xbox via SetAVTransportURI.

- Video played: yes/no
- Subtitles appeared: yes/no
- Xbox subtitle menu offered a track: yes/no
- Notes: ...
```

**Expected:** the video plays and no subtitles appear anywhere. Record it and move on —
that is a successful step, not a failed one.

If subtitles *do* appear: stop, do not start Phase 1, and re-plan. The feature collapses to
"enrich the DIDL we already send", which is perhaps 50 lines.

## Optional, if the mpv renderer is up

`mpv DLNA Renderer` (10.0.0.150) is on the network and mpv itself renders SRT happily. If
the Xbox fails, retrying the same DIDL against mpv tells you whether the DIDL was
well-formed or whether you were testing your own typo. Useful, but not required to close
this step.
