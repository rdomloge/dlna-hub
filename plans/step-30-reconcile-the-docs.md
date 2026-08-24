# Step 30 — Reconcile the documentation with the code

**Phase:** 4 — Features and cleanup
**Severity:** Low (report: L1, L2, L6, L12)
**Files:** `README.md`, `AGENTS.md`, `frontend/src/utils/formatDate.ts`,
`frontend/src/types/playback.ts`, `frontend/src/types/tmdb.ts`,
`frontend/src/pages/ServerSelectPage.tsx`, `frontend/src/pages/PlayerSelectPage.tsx`
**Depends on:** every preceding step — this is the closing pass

## Problem

`README.md` and `AGENTS.md` describe several things that are not true of the code, and two
TypeScript types promise guarantees the backend does not keep. Documentation that lies is
worse than none, because the next person (or model) reasons from it.

## Change — work the checklist

### 1. `HomePage` does not exist

Both docs describe:

```
/         -> HomePage
```

`App.tsx` routes `/` to `ServerSelectPage`; there is no `HomePage.tsx`. Fix the routing
tables in both files:

```
/         -> ServerSelectPage
/servers  -> ServerSelectPage
/players  -> PlayerSelectPage
/browse   -> BrowsePage
/playback -> PlaybackPage
```

Also delete the "**Home** (`/`) → landing page with CTA to select a server" bullet from the
README's **UI Navigation Flow** and renumber the remaining steps.

### 2. Add a 404 route

While in `App.tsx`, add a catch-all so an unknown path does not render blank:

```tsx
          <Route path="*" element={<ServerSelectPage />} />
```

### 3. Stale claims to correct

| Claim | Reality | Where |
|-------|---------|-------|
| "Lombok" in the stack | Removed in step-26; was never used | `AGENTS.md` Stack |
| Search "browses the entire container tree" | Fixed in step-23; update to the bounded subtree walk | `README.md` Search |
| Browse errors carry `upnpErrorCode` | True only after step-16 | `README.md` Error Handling |
| UPnP errors map to 400 | Changed to 502 in step-16 | `README.md` Error Handling |
| `TMDB_API_KEY` is required | Removed in step-26; only the bearer token is used | `README.md` Secrets table |
| Identical `docker buildx` commands | Fixed in step-04 | `README.md` |
| `/subscribe`, `/unsubscribe` endpoints | Removed in step-24 | Both endpoint tables |
| Frontend polls status every 1s / 5s | Still true — keep | — |
| Axios 10-second timeout | Changed to 60s in step-18 | `README.md` State Management |
| Stage 1-8 / `PLAN.md` / `stage-N.md` | Those files now live in `plans/archive/` | `AGENTS.md` Stages |

### 4. Fix the `formatDate` docstring

The comment says the opposite of what the function does:

```ts
/**
 * Formats an ISO/W3C datetime (or plain date) for display, e.g.
 * "2023-06-09T13:40:01Z" -> "Jun 2023" (older than this year) or "2023" (older than a year).
 */
```

The code returns day+month for the current month, month+year for the current year, and
year alone for anything older. Replace with:

```ts
/**
 * Formats an ISO/W3C datetime (or plain date) for display, at a precision that decreases
 * with age: this month -> "9 Jun", earlier this year -> "Jun 2023", older -> "2022".
 * Returns null when the value is missing or unparseable.
 */
```

### 5. Make the TypeScript types honest

`frontend/src/types/playback.ts` — the backend returns `"UNKNOWN"` when
`GetTransportInfo` yields nothing, and renderers commonly report `NO_MEDIA_PRESENT`:

```ts
export type TransportState =
  | 'STOPPED'
  | 'PLAYING'
  | 'PAUSED_PLAYBACK'
  | 'PAUSED_RECORDING'
  | 'TRANSITIONING'
  | 'RECORDING'
  | 'NO_MEDIA_PRESENT'
  | 'UNKNOWN';

export interface PlaybackStatus {
  state: TransportState;
  trackTitle?: string;
  trackDuration?: string;
  trackPosition?: string;
  trackUri?: string;
  volume?: number | null;
}
```

`frontend/src/types/tmdb.ts` — `overview`, `tagline`, `releaseYear` and `runtime` are all
nullable server-side (`TmdbMediaDto` leaves them null when TMDB omits them):

```ts
export interface TmdbMediaInfo {
  tmdbId: string;
  type: 'movie' | 'tv';
  title: string;
  overview: string | null;
  tagline: string | null;
  posterPath: string | null;
  backdropPath: string | null;
  posterUrl: string | null;
  backdropUrl: string | null;
  releaseYear: string | null;
  genres: string[];
  runtime: string | null;
  cast: CastMember[];
  crew: CrewMember[];
}
```

`TmdbMediaPanel` already guards all four with truthiness checks, so no component change is
needed — `npm run typecheck` will confirm.

### 6. Stop polling hidden tabs

`ServerSelectPage` and `PlayerSelectPage` poll every 10 seconds regardless of visibility.
`useVisibility` already exists and `PlaybackPage` uses it. Apply it to both:

```tsx
import { useVisibility } from '@/hooks/useVisibility';
...
  const isVisible = useVisibility();

  useEffect(() => {
    if (!isVisible) return;
    fetchServers();
    const interval = setInterval(fetchServers, 10000);
    return () => clearInterval(interval);
  }, [fetchServers, isVisible]);
```

(For `PlayerSelectPage`, keep its existing `if (!selectedServer) return;` guard and add
`isVisible` alongside it.)

## Do not

- Do not delete the "Test NAS facts" section of `AGENTS.md` — it is the most valuable part
  of the file, and marking those facts as established stops them being re-derived.
- Do not delete the Business Rules section of the README. Correct it; it is a genuinely
  good record of why the workarounds exist.
- Do not move `plans/archive/` — the stage files are history, and they are fine where
  they are.

## Verify

```bash
cd frontend && npm run typecheck && npm run build && npm run lint
```

Then read `README.md` and `AGENTS.md` top to bottom against the code and confirm every
endpoint table row, route, and env-var name matches. Spot-check:

```bash
grep -c "HomePage" README.md AGENTS.md      # expect 0
grep -c "Lombok" AGENTS.md                  # expect 0
grep -c "subscribe" README.md AGENTS.md     # expect only the GENA follow-up note
```
