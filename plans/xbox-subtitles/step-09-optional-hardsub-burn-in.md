# Step 09 — Pre-baked hardsub burn-in (optional)

**Phase:** 3 — Optional escalation
**Status:** Specified, not scheduled. Build only if Phase 2 lands and is not enough.
**Files:** would add an ffmpeg-capable image and a job service — see below
**Depends on:** Phase 2 complete and lived with for a while

## When to build this

Only when the answer to "did the second screen solve it?" is no, and you can say why. The
likely reasons, and whether this step actually fixes them:

| Complaint after Phase 2 | Does burn-in fix it? |
|-------------------------|----------------------|
| "I don't want to hold a phone" | Yes — this is the case it is for |
| "Others in the room can't see the subtitles" | Yes |
| "The subtitle is in the wrong language" | **No** — that is a source problem, not a delivery problem |
| "Only a third of films have subtitles" | **No** — same source problem |
| "It drifts" | No — fix step-06 instead |

Two of those five are not delivery problems at all. Check which one you have before
spending days on an encoder.

## The shape of it

Burn the subtitle into the video and hand the Xbox an ordinary file. It works because by the
time the Xbox sees them the subtitles are pixels, and `FINDINGS.md` §2 confirms the Xbox
takes `video/mp4` and H.264 without complaint.

**Pre-baked, not live.** The job writes a new file, the NAS indexes it, and you play it
through the hub exactly as you play anything else. No proxying, no streaming, no seeking
problems — the hub's involvement ends when the file exists.

```
POST /api/servers/{serverId}/subtitles/{itemId}/burn   -> 202 + jobId
GET  /api/jobs/{jobId}                                 -> { state, percent, outputPath }
```

```bash
ffmpeg -i input.mp4 \
  -vf "subtitles=input.srt:force_style='FontSize=24,OutlineColour=&H80000000,BorderStyle=3'" \
  -c:a copy -c:v libx264 -preset medium -crf 20 \
  output.subbed.mp4
```

Audio is copied, not re-encoded — only the video has to change.

### What makes this real work

- **The backend image needs ffmpeg.** `backend/Dockerfile` currently carries a JRE and
  nothing else. Adding ffmpeg is a significant image-size increase across two architectures.
- **Where does the output go?** The hub reaches the NAS over HTTP through DLNA, which is
  read-only. Writing the result next to the original needs a *different* access path — an
  SMB mount into the pod, or an NFS volume. **This is the part that will take the longest
  and it is nothing to do with subtitles.** Resolve it before starting anything else.
- **Encode time.** A 1080p feature at `preset medium` inside a 3-core CPU limit is well
  over an hour. It is a "queue it now, watch it tomorrow" feature, not a "press play"
  feature, and the UI has to say so honestly.
- **Disk.** A second copy of every burned film. The 2 GB Street Kings encode becomes 4 GB
  on disk.
- **Job survival.** Pod restarts happen. Either persist job state or accept that a restart
  loses the work and say so in the UI.

## Why live transcoding is not here

Option B2 in `FINDINGS.md`. It seems like the better feature — press play, subtitles appear
— and it is how Plex and Jellyfin do it. It is out of scope because of one thing:

**Seeking.** A live ffmpeg output has no `Content-Length` and no seekable index. The Xbox
seeks by HTTP byte range. Supporting it means mapping a requested byte offset to a timestamp,
restarting the encoder at that timestamp, and synthesising a response the renderer accepts —
and getting it wrong means seeking silently breaks, on every file, in the way you notice
most. That is the core of a media server, not a feature of a DLNA remote control.

If this is ever genuinely wanted, the proportionate answer is to put Jellyfin on the NAS and
point the hub at it, not to grow a transcoder inside this application.

## Do not

- Do not start this before the storage-write path is solved. Everything else is
  straightforward; that is the part that decides whether it is feasible at all.
- Do not build it speculatively "while we are in here". It is the largest item in the plan
  by an order of magnitude and it may fix a problem you do not have.
- Do not burn in on the fly as a "quick version" of this. See above.
- Do not write the output back into the same folder without a suffix. The NAS will index it
  as a second copy of the film, and the browse list will grow confusing duplicates.
