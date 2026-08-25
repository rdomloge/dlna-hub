# DLNA Hub — Subtitles

**Goal:** be able to read the subtitles for a film while it plays on the Xbox.

The evidence is in [`FINDINGS.md`](FINDINGS.md). Read it first — it is short, it was
gathered from the real devices, and it decides the shape of everything below. The two facts
that matter:

1. **The NAS serves the SRT** at the video URL with the extension swapped
   (`.../38318.mp4` → `.../38318.srt`), even though it never advertises it. About a third
   of the library has one.
2. **The Xbox cannot display it.** Its DLNA sink declares 271 profiles and not one is a
   subtitle format, and its AVTransport has no vendor subtitle action. Subtitles on the TV
   itself are only reachable by burning them into the picture (Option B) or by leaving DLNA
   behind (Option D).

So the plan builds the **second screen**: the subtitle for the current timecode on your
phone, tracking the Xbox's own clock, with a read-aloud button.

## How to work this plan

Each `step-NN-*.md` is self-contained and sized for one focused session:

- **1–3 files touched**, exact code, no cross-file archaeology.
- A **Do not** section that fences the scope — read it; it is there to stop the change
  spreading.
- A **Verify** section with runnable commands and expected output.

Rules for whoever (or whatever) works a step:

1. **One step per commit.** Message: `step-NN: <the step title>`.
2. **Run the verification before committing.** If it does not pass, the step is not done.
3. **Do not fix things the step did not ask for.** If you spot something, add a line to
   "Discovered along the way" at the bottom of this file and move on.
4. **Test locally against the real NAS before building images** — see `AGENTS.md`. The Xbox
   is only on sporadically; steps needing it say so in their **Verify** section, and it is
   legitimate to mark those as skipped and come back.
5. The suite stays green throughout:
   ```bash
   cd backend  && mvn test
   cd frontend && npm run typecheck && npm test
   ```

## Running order

### Phase 0 — Kill or confirm the ideal path

One hour, no production code. Do not skip it and do not expand it.

| Step | Title | Outcome |
|------|-------|---------|
| [01](step-01-spike-advertise-subtitles-to-the-renderer.md) | Spike: advertise the subtitle to the Xbox | **Done 2026-08-25 — negative, as expected** |

Ran on the real device: DIDL carrying all four subtitle mechanisms was accepted (`HTTP 200`,
film played through several minutes of dialogue) and **nothing rendered**. Option A is
closed — see `FINDINGS.md` §2. Phase 2 is the plan.

### Phase 1 — The subtitle backend

Shared foundation. Everything else sits on this, including Option B if you ever escalate.

Subtitles come from **two** sources, and the second one carries most of the library —
see `FINDINGS.md` §3:

- **Sidecar `.srt`** served by the NAS at the video URL with the extension swapped. Cheap,
  synchronous, about a third of items.
- **Embedded `S_TEXT/UTF8` track inside the MKV**, which the NAS will not extract. Listing
  the tracks is a sub-second range request; pulling the cues is a ~9 s full-file scan, so it
  has to be asynchronous and progressive.

| Step | Title | Status |
|------|-------|--------|
| [02](step-02-subtitle-service.md) | SRT parsing and the sidecar source | **Done** — `SrtParser`, `SubtitleCue`, `SubtitleTrackInfo` |
| [02b](step-02b-matroska-extractor.md) | Matroska track listing and cue extraction | **Done** — `EbmlReader`, `MatroskaSubtitleParser` |
| [03](step-03-subtitle-endpoints.md) | REST endpoints — tracks, cues, WebVTT | **Done** — `SubtitleController` |
| [04](step-04-surface-availability-in-metadata.md) | Flag availability on the item so the UI knows | **Superseded** — see below |

Step 04 was dropped. It existed so the UI could avoid offering a subtitle button on the two
thirds of items that have none, without paying for a check on every browse row. The panel
now calls `/tracks` once when the playback page mounts and renders nothing when the list
comes back empty, which gets the same result without touching `BrowsableItem` or the browse
hot path at all. Reinstate it only if a subtitle badge is wanted in the browse *listing*.

### Phase 2 — The second screen

| Step | Title | Status |
|------|-------|--------|
| [05](step-05-frontend-api-and-types.md) | API client, types, and the position-precision check | **Done** — precision answered in FINDINGS |
| [06](step-06-the-sync-engine.md) | The sync engine — interpolate, re-anchor, offset | **Done** — `subtitleSync.ts`, 25 tests |
| [07](step-07-subtitle-panel-ui.md) | The subtitle panel on the playback page | **Done** — needs the manual watch-through |
| [08](step-08-read-aloud.md) | Read aloud via the Web Speech API | **Done** — needs a real phone |

Step 06 is the one with the actual difficulty in it. It is pure logic with no I/O, so it is
unit-testable end to end — do that rather than testing it by watching a film.

### Phase 3 — Optional escalation

Build only if Phase 2 lands and still is not enough.

| Step | Title | Status |
|------|-------|--------|
| [09](step-09-optional-hardsub-burn-in.md) | Pre-baked hardsub burn-in | Specified, not scheduled |

Live transcoding (Option B2) is **out of scope** — `FINDINGS.md` says why.

## Before you start

Two things cost five minutes each and could change the plan:

- **Try Option D.** Xbox → Media Player → the SMB share → a film folder that has an `.srt`.
  If the Xbox picks the subtitle up there, subtitles on the TV are already solved for the
  cases you care about, and Phase 2 is a convenience rather than the answer.
- **Answer open question 1** in `FINDINGS.md` — look in the *Street Kings* folder on the
  NAS and see whether the Romanian `.srt` is the only one. If there is an English track the
  NAS is hiding, step-02 needs a way to override the choice, and that is much cheaper to
  design now than to retrofit.

## Discovered along the way

<!-- Append findings here rather than widening a step. -->
