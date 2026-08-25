# Step 11 — Stop the status-poll effect from restarting on every state change

**Phase:** 1 — Correctness
**Severity:** Medium (report: M3)
**Files:** `frontend/src/pages/PlaybackPage.tsx`
**Depends on:** —

## Problem

```tsx
const pollStatus = useCallback(async () => { ... },
  [..., playingPending, playingPendingSince, ...]);

useEffect(() => {
  if (isVisible) {
    setReconnecting(true);        // <-- dims the entire UI
    pollStatus();                 // <-- immediate extra poll
    const interval = (isPlaying || playingPending) ? 1000 : 5000;
    pollIntervalRef.current = setInterval(pollStatus, interval);
  }
  ...
}, [selectedPlayer, isVisible, pollStatus, setReconnecting, isPlaying, playingPending]);
```

`pollStatus` is rebuilt whenever `playingPending` or `playingPendingSince` changes, and
the effect depends on it *and* on `isPlaying` / `playingPending` directly. Every playback
transition therefore tears down and rebuilds the interval and sets `reconnecting = true`,
which applies `opacity-40 pointer-events-none` to the whole control panel. Pressing Play
makes the UI grey out and flash the Connecting badge twice — once when pending is set,
once when the first PLAYING status clears it.

## Change

Keep the latest `pollStatus` in a ref so the interval effect no longer depends on it, and
only show the Connecting badge on a genuine (re)connection.

### 1. Add a ref that always holds the latest `pollStatus`

Immediately after the `pollStatus` `useCallback` block:

```tsx
  const pollStatusRef = useRef(pollStatus);
  useEffect(() => {
    pollStatusRef.current = pollStatus;
  }, [pollStatus]);
```

### 2. Replace the interval effect

```tsx
  const pollIntervalMs = (isPlaying || playingPending) ? 1000 : 5000;

  useEffect(() => {
    if (!selectedPlayer || !isVisible) {
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
        pollIntervalRef.current = null;
      }
      return;
    }

    // The interval calls through the ref, so a new pollStatus identity (which changes on
    // every playingPending transition) does not tear the interval down and re-show the
    // Connecting overlay.
    pollIntervalRef.current = setInterval(() => pollStatusRef.current(), pollIntervalMs);
    return () => {
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
        pollIntervalRef.current = null;
      }
    };
  }, [selectedPlayer, isVisible, pollIntervalMs]);
```

### 3. Move the reconnect state and the immediate poll into their own effect

```tsx
  // Fires only when the player changes or the tab becomes visible again — a real
  // (re)connection, not an ordinary play/pause transition.
  useEffect(() => {
    if (!selectedPlayer || !isVisible) return;
    setReconnecting(true);
    consecutiveErrorsRef.current = 0;
    pollStatusRef.current();
  }, [selectedPlayer, isVisible, setReconnecting]);
```

## Do not

- Do not change the body of `pollStatus` — it is correct.
- Do not remove the reconnecting overlay; it is genuinely useful when returning to a
  backgrounded tab.

## Verify

```bash
cd frontend && npm run typecheck && npm run build
```

Then, in the app: press Play and watch the control panel. It must **not** grey out or flash
the Connecting badge. Switch to another browser tab and back — the badge *should* appear
once. With the Network tab open, confirm the poll settles to one request per second while
playing.
