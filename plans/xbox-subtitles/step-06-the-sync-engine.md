# Step 06 — The sync engine

**Phase:** 2 — The second screen
**Files:** `frontend/src/utils/subtitleSync.ts` (new),
`frontend/src/utils/subtitleSync.test.ts` (new)
**Depends on:** step-05

This is the step with the difficulty in it. It is pure logic with no I/O and no React, so it
is fully unit-testable — test it that way rather than by watching a film and squinting.

## Problem

The renderer reports its position once a second, in whole seconds. Subtitles need better
than that: a cue that appears a second late reads as broken, and a cue read aloud a second
late talks over the next line.

Three separate errors stack up:

1. **Quantisation.** `0:05:12` means the true position is somewhere in `[5:12, 5:13)`. Up to
   1000 ms of error, and it is a *bias* — the reading is always at or behind the truth.
2. **Latency.** The reading travelled Xbox → backend → phone. Tens of milliseconds, and
   variable.
3. **Poll spacing.** Between polls there is no information at all.

Error 3 is the easy one: run a local clock between polls. Errors 1 and 2 are what the design
below is actually for.

## Design

### Anchor and free-run

Hold an anchor — a media position paired with the local clock reading at which it was true:

```ts
export interface SyncAnchor {
  mediaMs: number;      // position in the media
  clockMs: number;      // performance.now() when that was true
}

export function estimate(anchor: SyncAnchor, nowMs: number, playing: boolean): number
```

While playing, `estimate` is `anchor.mediaMs + (nowMs - anchor.clockMs)`. While paused it is
just `anchor.mediaMs`. The UI ticks off `requestAnimationFrame` and reads `estimate` — the
network is out of the render path entirely.

### Killing the quantisation bias: the calibration burst

The one moment when the renderer's whole-second reading is precise is the instant it
*changes*. If a poll at clock time `T` is the first to report `0:05:13`, then the media
crossed 5:13 somewhere in the interval between that poll and the one before it. Poll every
250 ms for a couple of seconds and that interval is 250 ms wide instead of 1000 ms.

So:

```ts
export function calibrate(
  samples: Array<{ seconds: number; clockMs: number }>
): SyncAnchor | null
```

Walk the samples, find the first index where `seconds` increases, and anchor on it:

```ts
{ mediaMs: sample.seconds * 1000, clockMs: sample.clockMs }
```

If no transition appears in the burst, return `null` and let the caller fall back to
anchoring on the latest reading — a 500 ms average error, which is still usable.

Run the burst when the panel opens, and again after any seek. **Do not run it continuously**
— each poll is a UPnP round trip to the Xbox, and four a second sustained is rude to a
device that also has to decode video.

### Staying locked: reconcile

Normal 1 s polls continue (the scrubber needs them anyway). Each one is a chance to check
the local clock has not drifted:

```ts
export function reconcile(
  anchor: SyncAnchor,
  reported: { seconds: number; clockMs: number },
  playing: boolean
): { anchor: SyncAnchor; recalibrate: boolean }
```

Compute `estimated = estimate(anchor, reported.clockMs, playing)`. The reported value is a
floor, so the honest comparison is against the second the estimate falls in:

```ts
const estimatedSecond = Math.floor(estimated / 1000);
const drift = reported.seconds - estimatedSecond;   // in whole seconds
```

Three cases:

| Drift | Meaning | Action |
|-------|---------|--------|
| `0` | locked | keep the anchor untouched |
| `±1` | ordinary jitter at the boundary | nudge: shift `anchor.clockMs` by 100 ms towards the reading |
| `> 1` or `< -1` | a seek, a skip, or a new item | `recalibrate: true` |

The nudge matters. Snapping the anchor on every small disagreement makes cues twitch
backwards and forwards; moving it a tenth of a second at a time converges within a few
seconds and is invisible.

### The pause trap

There is a known Xbox behaviour documented in
`bugs/pause-then-unpause-restarts-from-beginning`: a few seconds after a pause the Xbox
stops reporting the paused stream, and the position **snaps to 0** while the film is still
loaded. If `reconcile` believes that, the subtitles jump to the opening credits.

Guard explicitly:

```ts
// A drop to zero from well inside the film is the renderer forgetting, not a seek to the
// start. See bugs/pause-then-unpause-restarts-from-beginning.
if (reported.seconds === 0 && estimated > 5000) {
  return { anchor, recalibrate: false };   // hold what we have
}
```

### Finding the cue

Cues are sorted by `startMs` (step-02 guarantees it), so binary search:

```ts
export function findCueIndex(cues: SubtitleCue[], positionMs: number): number
```

Return the index of the cue whose `[startMs, endMs)` contains the position, or `-1` in the
gaps between cues. Gaps are normal and common — silence is most of a film.

Two more, for the UI:

```ts
/** The cue at or before this position — what to show during a gap. */
export function findCurrentOrPreviousIndex(cues: SubtitleCue[], positionMs: number): number

/** Index of the first cue starting at or after this position. */
export function findNextIndex(cues: SubtitleCue[], positionMs: number): number
```

### User offset

One number, applied at lookup time, never baked into the anchor:

```ts
export function applyOffset(positionMs: number, offsetMs: number): number
```

Positive means the subtitles are behind and should move earlier. Keep it out of `estimate`
so that a change to the offset does not disturb the lock.

## Do not

- Do not import React in this file. It is pure functions; that is what makes it testable.
- Do not use `Date.now()`. It jumps when the system clock is corrected. `performance.now()`
  is monotonic, which is the whole requirement.
- Do not run the calibration burst on a timer. Panel-open and post-seek only.
- Do not smooth by averaging the last N readings. With whole-second data the average of a
  sawtooth is still a sawtooth, half a second behind. The transition edge carries the
  information; the values in between do not.
- Do not put the offset in `usePlaybackStore` — that store is persisted whole and the offset
  is per-item. `localStorage` under a per-item key, read in step-07.

## Verify

```bash
cd frontend && npm test && npm run typecheck
```

The tests are the deliverable. Feed synthetic sample arrays — no mocking of timers needed,
since every function takes its clock reading as an argument. Cover:

- `estimate_playing_advancesWithTheClock`
- `estimate_paused_returnsAnchorPosition`
- `calibrate_burstWithTransition_anchorsOnTheTransition`
- `calibrate_burstWithNoTransition_returnsNull`
- `reconcile_readingMatchesEstimate_keepsAnchor`
- `reconcile_oneSecondDrift_nudgesAnchorWithoutJumping`
- `reconcile_largeForwardJump_requestsRecalibration`
- `reconcile_zeroReadingMidFilm_holdsAnchor` — the pause trap
- `findCueIndex_positionInsideCue_returnsThatCue`
- `findCueIndex_positionInGap_returnsMinusOne`
- `findCueIndex_positionBeforeFirstCue_returnsMinusOne`
- `findCueIndex_emptyCues_returnsMinusOne`
- `applyOffset_positiveOffset_selectsAnEarlierCue`

A useful extra: build a 200-cue fixture, simulate 60 s of 1 Hz whole-second readings with a
40 ms latency jitter, and assert the estimate stays within 400 ms of truth throughout. That
single test is worth more than the rest combined, because it is the actual claim being made.
