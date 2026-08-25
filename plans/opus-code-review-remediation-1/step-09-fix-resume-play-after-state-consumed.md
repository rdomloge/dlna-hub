# Step 09 — Fix "play" after the router state has been consumed

> ⚠️ **This step caused a regression. Read before relying on it.**
>
> Applied as `a267603`, then corrected on 2026-08-25. Changing `navItem` to `item` made a
> branch live that had been **unreachable dead code** — and that branch is wrong when the
> user has merely paused: it re-sends the URI and restarts playback from 00:00:00.
>
> The premise below ("pressing Play from a STOPPED state sends a bare `Play` ... and nothing
> happens") is **wrong for the Xbox**. That renderer keeps the URI loaded, so the bare `Play`
> production had been sending all along resumes correctly. The genuine case this step targets
> is narrower: a restored session where the renderer really has no URI.
>
> The change was **kept**, but is now gated on `userPaused` via
> `frontend/src/utils/resolvePlayAction.ts`, so it only fires when there is no paused stream
> to resume. Full analysis:
> [`bugs/pause-then-unpause-restarts-from-beginning/REPORT.md`](../../bugs/pause-then-unpause-restarts-from-beginning/REPORT.md).
>
> **Lesson for future steps:** making dead code live is a behaviour change, not a repair.
> Before "fixing" an unreachable branch, establish what the reachable path actually does --
> here it was already doing the right thing.

**Phase:** 1 — Correctness
**Severity:** Medium (report: M5)
**Files:** `frontend/src/pages/PlaybackPage.tsx`
**Depends on:** —

## Problem

Line 120 deliberately clears the one-time router state:

```tsx
navigate(location.pathname, { replace: true, state: null });
```

After that, `navItem` is `undefined` for the rest of the page's life. But
`handlePlayPause` still keys off `navItem`:

```tsx
} else if (navItem?.resourceName) {
  await play(selectedPlayer.id, navItem.resourceName, { ... });
} else {
  await play(selectedPlayer.id, '');       // <-- always taken
}
```

So pressing Play from a STOPPED state sends a bare `Play` with no URI. The renderer has
nothing loaded and nothing happens. The item *is* available — it is persisted as
`activeItem` and already computed into the local `item` variable on line 67 — it is just
not used here.

## Change

In `handlePlayPause`, replace the three-way branch with one that uses `item`:

```tsx
      } else {
        setPlayingPending(true);
        setPlayingPendingSince(Date.now());
        if (playbackStatus?.state === 'PAUSED_PLAYBACK') {
          // Resume: the renderer still holds the URI, a bare Play is correct.
          await play(selectedPlayer.id, '');
        } else if (item?.resourceName) {
          // Restart from stopped: re-send the URI. `item` is navItem ?? activeItem, so this
          // still works after the one-time router state has been consumed.
          await play(selectedPlayer.id, item.resourceName, {
            title: cleanMediaTitle(item.title || '').cleansedTitle,
            artist: item.artist,
            album: item.album,
            duration: item.duration,
            mimeType: item.mimeType,
            protocolInfo: item.protocolInfo,
          });
        } else {
          await play(selectedPlayer.id, '');
        }
      }
```

`item` is already defined at line 67 as `navItem || activeItem` — no new variable is
needed.

## Do not

- Do not remove the `navigate(..., state: null)` call on line 120 — it exists to stop iOS
  tab reloads from re-triggering autoplay, which is correct.
- Do not change the autoplay effect; it correctly uses `navItem` (a fresh navigation is
  exactly what should trigger autoplay).

## Verify

```bash
cd frontend && npm run typecheck && npm run build
```

Then, in the app: play an item, press Stop, then press Play. Playback should restart the
same item. Confirm the POST body contains a non-empty `uri`.
