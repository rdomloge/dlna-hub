# Step 05 — API client, types, and the position-precision check

**Phase:** 2 — The second screen
**Files:** `frontend/src/types/subtitles.ts` (new), `frontend/src/api/subtitles.ts` (new),
`frontend/src/types/media.ts`
**Depends on:** step-03, step-04
**Needs the Xbox powered on** for the measurement at the end.

## Change

### Types

```ts
export interface SubtitleCue {
  index: number;
  startMs: number;
  endMs: number;
  lines: string[];
}

export interface SubtitleTrack {
  available: boolean;
  cueCount: number;
  cues: SubtitleCue[];
}
```

Add to `BrowsableItem` in `types/media.ts`:

```ts
  /** null when it was never checked — browse listings do not pay for the check. */
  subtitleAvailable?: boolean | null;
```

### API client

`api/subtitles.ts`, matching the shape of `api/browse.ts`:

```ts
export async function getSubtitles(serverId: string, itemId: string): Promise<SubtitleTrack>
```

Encode both path segments with `encodeURIComponent` — item IDs contain `$` and `@`. Check
whether `api/browse.ts` already has a helper for this and reuse it rather than adding a
second encoding convention.

## The measurement

This is the actual point of the step, and it decides how step-06 is built.

`FINDINGS.md` open question 3 assumes the Xbox reports `RelTime` in whole seconds. Confirm
it, because if the Xbox reports milliseconds the sync engine gets considerably simpler and
step-06's calibration logic is unnecessary.

With something playing on the Xbox:

```bash
for i in $(seq 1 20); do
  curl -s "http://localhost:9100/api/players/<xbox-id>/status" \
    | grep -o '"trackPosition":"[^"]*"'
  sleep 0.25
done
```

Read the output:

- **Values like `0:05:12` that repeat about four times each, then step by one** — whole-second
  granularity. This is the expected case. Build step-06 as written.
- **Values carrying a fraction** — record it in `FINDINGS.md` and simplify step-06: drop the
  calibration burst and anchor directly on each reading.
- **A value that does not advance while the film is clearly playing** — stop. The whole
  approach depends on the renderer's clock and this needs understanding before any UI is
  built.

Record the answer in `FINDINGS.md` under open question 3 either way.

## Do not

- Do not add subtitle state to `usePlaybackStore`. Cues belong to the panel that displays
  them; the store is persisted to `localStorage` (see its `partialize`) and a 1500-cue array
  has no business being written there on every state change.
- Do not fetch subtitles from `PlaybackPage` in this step. Step-07 owns when the fetch
  happens.
- Do not change the polling interval yet — step-06 does that, deliberately and temporarily.

## Verify

```bash
cd frontend && npm run typecheck && npm test && npm run lint
```

Then in the dev server, with a film that has a subtitle:

```js
// browser console
await (await fetch('/api/servers/<srv>/subtitles/<itemId>')).json()
// expect: { available: true, cueCount: ~1200, cues: [...] }
```

Confirm `cues[0].startMs` is a number in milliseconds (Street Kings: about `189273`), not a
string and not seconds.
