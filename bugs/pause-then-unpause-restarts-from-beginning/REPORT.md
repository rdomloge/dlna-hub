# Bug: unpause after a few seconds restarts playback from the beginning

**Date:** 2026-08-25
**Status:** **Fixed** — see §Fix applied. Not live-tested against the Xbox yet (§Verification).
**Severity:** High (user-visible: playback position discarded)
**Introduced by:** `a267603` — `step-09: Fix "play" after the router state has been consumed`
**Affected renderer:** Xbox One
**Files:** `frontend/src/pages/PlaybackPage.tsx`, `frontend/src/store/usePlaybackStore.ts`,
`frontend/src/utils/resolvePlayAction.ts`

> **Revision note.** The first version of this report diagnosed the mechanism correctly but
> got one inference wrong, and that inference drove the whole proposed solution. It assumed
> the Xbox *releases* the paused stream. It does not. The correction, and how production
> proves it, are in §Root cause. The originally proposed fix (URI re-send + `Seek` back to
> the pause point) was **not** implemented and is not needed.

---

## Symptom

On the Playback page:

- Pause, then immediately unpause → **works** (resumes from the pause point).
- Pause, wait a few seconds (~5 s), then unpause → **starts from the beginning** (00:00:00).

Secondary symptom: once the renderer stops reporting the paused stream, the progress bar
also snaps back to 00:00:00.

## Reproduce

1. Play any item (autoplay from Browse, or the play button).
2. Press pause.
3. Wait ~5 s — long enough for one paused-state status poll to run.
4. Press play.
5. Playback restarts from 00:00:00.

## Root cause

### The mechanism

The pause/resume decision in `handlePlayPause` was keyed off `playbackStatus.state`:

```tsx
if (playbackStatus?.state === 'PAUSED_PLAYBACK') {
  await play(selectedPlayer.id, '');                            // resume
} else if (item?.resourceName) {
  await play(selectedPlayer.id, item.resourceName, { ... });     // rebuild → starts at 0
}
```

While paused, `isPlaying` is false, so the poll interval is 5 000 ms
(`PlaybackPage.tsx:231`). A few seconds after a pause the Xbox stops reporting
`PAUSED_PLAYBACK`; the next poll writes that into the store via `setPlaybackStatus(status)`,
and the unpause then takes the second branch. Neither `play` nor `SetAVTransportURI` carries
a start position — the codebase has no `AVTransportStartPosition` support anywhere — so
playback begins at 00:00:00.

The 5 s threshold in the symptom matches the paused poll cadence exactly.

### What the first version got wrong

It concluded:

> The Xbox does **not** hold a paused stream indefinitely: after a few seconds it releases it.

**That is false, and production disproves it.**

The deployed build does not have this bug, and the reason is instructive. Before `a267603`,
the middle branch read `else if (navItem?.resourceName)`. `navItem` comes from
`location.state`, which the mount effect deliberately clears:

```tsx
// Router state survives an iOS tab reload, so consume this one-time play request.
navigate(location.pathname, { replace: true, state: null });   // line 131
```

So by the time the user can press any button, `navItem` is always `undefined` — the middle
branch was **unreachable dead code**. Every unpause in production, whatever state the
renderer reported, fell through to the final `else` and sent a **bare `Play`** — and it
resumes correctly. Therefore the Xbox is still holding the URI. Only the *reported transport
state* changes.

### What actually introduced it

`a267603` (step-09) changed one word:

```diff
-        } else if (navItem?.resourceName) {
+        } else if (item?.resourceName) {
```

`item` is `navItem ?? activeItem`, and `activeItem` is persisted to localStorage, so it is
always populated. That resurrected the dead branch — and the branch is wrong for the pause
case. Step-09 was aimed at a genuine but much narrower problem (reload the page while
stopped, press play, renderer has no URI). It traded a rare edge case for a common one.

Everything else the first version implicated — the 5 s cadence, `setPlaybackStatus(status)`
overwriting the state — is byte-identical to production and is not at fault. Neither is the
backend: it faithfully mirrors the renderer.

## Fix applied

The renderer keeps the stream, so the fix is to stop asking the renderer whether the user
paused, and remember it instead. No seek, no URI re-send, no extra round trip.

**1. `usePlaybackStore`** — added `userPaused: boolean` (+ setter), deliberately **not**
persisted: it records an intent within one session, and after a reload we no longer know
what the renderer is holding. Cleared by `reset()`.

**2. `resolvePlayAction`** (new, `frontend/src/utils/resolvePlayAction.ts`) — the decision
extracted as a pure function so it can be tested:

```ts
export function resolvePlayAction({ reportedState, userPaused, hasResource }): PlayAction {
  if (reportedState === 'PAUSED_PLAYBACK' || userPaused) {
    return 'resume';
  }
  return hasResource ? 'restart' : 'bare';
}
```

**3. `PlaybackPage`** — delegates to it, and maintains the flag:

| Event | Action |
|---|---|
| pause pressed | `setUserPaused(true)` |
| resume succeeds | `setUserPaused(false)` |
| Stop pressed | `setUserPaused(false)` |
| a different item is loaded | `setUserPaused(false)` |

**4. Scrubber** — while `userPaused`, a reported position or duration of `0` is ignored, so
a renderer that has gone quiet cannot snap the scrubber to the start. The position cannot
advance on its own while paused, so nothing is lost.

### Why this is correct

- **Quick pause → unpause:** `PAUSED_PLAYBACK` → resume. Unchanged, one round trip.
- **Pause → wait → unpause:** `userPaused` → resume. Bare `Play`, exactly what production
  does today, at the pause point.
- **Stop → play:** `userPaused` false → rebuild from the start. Step-09's intent preserved.
- **Restored session → play:** `userPaused` false → rebuild. The case step-09 targeted.

### Rejected: the original proposal

URI re-send + `Seek(pausedAt)` was **not** implemented. It was solving for a released stream
that is not released, and it carried real costs: `Seek` immediately after
`SetAVTransportURI` + `Play` races the renderer's `TRANSITIONING` state and is commonly
answered with UPnP 701; playback would visibly start at 00:00 and jump; and it needed an
extra `getStatus` round trip inside the same `try`, so one blip would leave the play button
doing nothing. The `pausedAt` field and the atomic backend `POST /resume` it implied are all
unnecessary. Keep this in mind only if live testing ever shows a bare `Play` failing.

## Verification

**Automated** — `frontend/src/utils/resolvePlayAction.test.ts`, 9 cases covering the whole
truth table. Confirmed to be a real guard by mutation: removing `|| userPaused` (i.e.
reintroducing the regression) fails 4 of the 9.

```
npm test        36 passed (was 27)
npm run typecheck / lint / build   clean
```

**Live, still outstanding** (needs the Xbox on):

1. Play → pause → wait ≥ 10 s → unpause → resumes at the pause point; scrubber never reads 0.
   - Worth capturing what the renderer actually reports while paused:
     `while true; do curl -s .../status | jq -r .state; sleep 1; done`
     The fix does not depend on the answer, but it would close out the one inference here
     that is still unconfirmed.
2. Play → pause → unpause immediately → resumes in place.
3. Play → Stop → Play → restarts from 00:00:00 (regression guard for step-09).
4. Play a new item while paused → fresh start, flag cleared.

## Lesson

`REPORT.md` §7 flagged steps 09–11 as *"code correct; not live-tested"* because the Xbox was
off. This is exactly that hole: a change that reads correctly, typechecks, lints, and is
wrong against real hardware. The tell was available without hardware, though — the branch
being modified had been unreachable, so "fixing" it changed behaviour that had never
actually run. **A step that makes dead code live is a behaviour change, not a repair, and
should be treated as one.**
