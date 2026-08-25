# Step 07 — The subtitle panel

**Phase:** 2 — The second screen
**Files:** `frontend/src/components/SubtitlePanel.tsx` (new),
`frontend/src/pages/PlaybackPage.tsx`
**Depends on:** step-06

## Problem

Wire the engine to the screen. The constraint that shapes the design: this is read in a
dark room, at arm's length, while the actual film is on the television. It is a
teleprompter, not a document viewer.

## Change

### Where it lives

A collapsible panel on `PlaybackPage`, below the transport controls, in the manner of the
existing `TmdbMediaPanel`. Follow that component for how a panel on this page is structured.

Show the toggle only when there is something to show — `activeItem.subtitleAvailable`
(step-04) is the gate. When it is `false`, render nothing at all; not a disabled button, not
an empty panel. When it is `null` (unknown) treat it as false: `PlaybackPage` reaches this
page with an item it fetched metadata for, so the flag is populated in practice.

### Fetching

Fetch cues once, when the panel is first opened — not on mount. Two thirds of items have
nothing, the panel is closed by default, and the request costs a NAS round trip and a parse.

Key the fetch on `activeItem.id` and discard in-flight results if the item changes.

### The display

```
┌──────────────────────────────────────┐
│  … previous line, dimmed             │
│                                      │
│  THE CURRENT LINE, LARGE             │
│  second line of the same cue         │
│                                      │
│  next line, dimmed                   │
├──────────────────────────────────────┤
│  ⏴ −0.25s   in sync   +0.25s ⏵   [🔊] │
└──────────────────────────────────────┘
```

- **Current cue large** — `text-2xl` or larger, high contrast, centred, and the layout must
  not reflow when the number of lines changes. Give the current-cue area a fixed minimum
  height of two lines; text that jumps vertically every cue is unreadable in motion.
- **Previous and next dimmed** — `text-gray-400`, smaller. They give you somewhere to look
  during silence, and they make a sync error obvious at a glance.
- **Gaps** — when `findCueIndex` returns `-1`, keep showing the previous cue dimmed rather
  than blanking. A panel that empties every few seconds reads as broken.
- Respect the existing dark-header/light-body conventions in `AGENTS.md`, but the cue area
  itself should be dark with light text. It is being read in a dark room next to a lit
  television.

### The clock

Drive updates from `requestAnimationFrame`, reading `estimate()` each frame and only
re-rendering when the cue index actually changes:

```tsx
const rafRef = useRef<number>();
const lastIndexRef = useRef(-2);

useEffect(() => {
  if (!open) return;
  const tick = () => {
    const pos = applyOffset(estimate(anchorRef.current, performance.now(), isPlaying), offset);
    const idx = findCurrentOrPreviousIndex(cues, pos);
    if (idx !== lastIndexRef.current) {
      lastIndexRef.current = idx;
      setCueIndex(idx);
    }
    rafRef.current = requestAnimationFrame(tick);
  };
  rafRef.current = requestAnimationFrame(tick);
  return () => { if (rafRef.current) cancelAnimationFrame(rafRef.current); };
}, [open, cues, offset, isPlaying]);
```

Setting state only on cue change is the point: 60 renders a second of the same text would
flatten the phone's battery for nothing.

Stop the loop when the panel is closed, and when `useVisibility` reports the tab hidden —
`PlaybackPage` already uses that hook for its polling and the same reasoning applies.

### Feeding the anchor

`PlaybackPage` owns the status poll. Pass the panel what it needs rather than giving it its
own poll:

- the latest `{ seconds, clockMs }` reading
- `isPlaying`
- a `requestCalibration()` callback that makes `PlaybackPage` run the 250 ms burst

Run the burst on panel open and after any seek — `PlaybackPage` already knows when a seek
happens (`handleSeek`, `forward`, `backward`, and the scrubber release).

### The offset control

Two buttons, ±0.25 s, with the current value shown between them and a tap-to-reset. Persist
per item:

```ts
localStorage.setItem(`subtitle-offset:${itemId}`, String(offsetMs));
```

Per item, because the offset corrects for a mismatch between *this* subtitle file and *this*
encode. It is not a global preference and a global value would be wrong on the next film.

Touch targets at least 48×48 px per the house conventions.

## Do not

- Do not add a full scrolling transcript in this step. It is a different interaction (you
  would want tap-to-seek, search, follow-mode) and it will swallow the step. Note it in
  "Discovered along the way" if you want it.
- Do not re-render on every animation frame. Only on cue change.
- Do not put the cue array in `usePlaybackStore` — see step-05's **Do not**.
- Do not auto-open the panel. It is opt-in; most films have no subtitle and most viewing
  does not want one.
- Do not add the read-aloud button's *behaviour* here. Leave a slot for it; step-08 fills it.

## Verify

```bash
cd frontend && npm run typecheck && npm test && npm run lint
```

Then, against a real film — this one genuinely needs the Xbox on:

1. Play a title with a subtitle (Street Kings, or *21 Jump Street* for English).
2. Open the panel. Cues should appear within a second and track the film.
3. **The real test:** watch for two full minutes without touching anything. Drift shows up
   over time, not immediately. Lines should still land with the dialogue at the end of it.
4. Skip forward 10 s. The panel should re-lock within about a second.
5. Pause for 10 s, then resume. The panel must not jump to the start of the film — that is
   the pause trap from step-06, and this is the only way to see it fail.
6. Lock the phone, wait 30 s, unlock. It should re-lock rather than resuming from a stale
   clock.

If cues run consistently early or late by the same amount on a title, that is the subtitle
file's own offset, not a sync bug — that is exactly what the ±0.25 s control is for.
