# Step 09 — Fix "play" after the router state has been consumed

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
