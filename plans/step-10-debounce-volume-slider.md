# Step 10 — Debounce the volume slider

**Phase:** 1 — Correctness
**Severity:** Medium (report: M4)
**Files:** `frontend/src/pages/PlaybackPage.tsx`
**Depends on:** —

## Problem

```tsx
const handleVolumeChange = async (e) => {
  const vol = parseInt(e.target.value);
  setVolumeState(vol);
  try { await setVolume(selectedPlayer.id, vol); } catch { ... }
};
```

`onChange` on a range input fires on every pointer movement. Dragging the slider from 0 to
100 issues dozens of HTTP requests, each one a synchronous UPnP `SetVolume` round-trip to
the renderer. Requests arrive out of order, so the final volume is not necessarily the one
the user released on, and the renderer is hammered.

## Change

### 1. Add a ref alongside the other refs (around line 59)

```tsx
  const volumeTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
```

### 2. Replace `handleVolumeChange`

```tsx
  const VOLUME_DEBOUNCE_MS = 200;

  const handleVolumeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!selectedPlayer) return;
    const vol = parseInt(e.target.value, 10);
    if (Number.isNaN(vol)) return;
    // Update the slider immediately so it stays responsive, but only send the last
    // value once the user stops dragging — each send is a UPnP round-trip.
    setVolumeState(vol);
    if (volumeTimeoutRef.current) clearTimeout(volumeTimeoutRef.current);
    volumeTimeoutRef.current = setTimeout(() => {
      setVolume(selectedPlayer.id, vol).catch(() => {
        setPlayerError('Failed to set volume');
      });
    }, VOLUME_DEBOUNCE_MS);
  };
```

### 3. Clear the timer on unmount

Add a new effect near the other effects:

```tsx
  useEffect(() => {
    return () => {
      if (volumeTimeoutRef.current) clearTimeout(volumeTimeoutRef.current);
    };
  }, []);
```

## Do not

- Do not debounce the scrubber — it already only seeks on `mouseup` / `touchend`.
  (Keyboard scrubbing is a separate gap, tracked in the report as L11.)
- Do not change the polling code that writes `status.volume` back into the store.

## Verify

```bash
cd frontend && npm run typecheck && npm run build
```

Then, in the app with the Network tab open: drag the volume slider across its full range.
Expect **one** `PUT /volume` request after you release, not dozens.
