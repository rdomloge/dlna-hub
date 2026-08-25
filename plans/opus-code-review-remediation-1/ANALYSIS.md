# DLNA Hub — Code Analysis Report

**Date:** 2026-08-24
**Scope:** Full repository — backend (Java 21 / Spring Boot 3.3.5, ~4.6k LOC), frontend
(React 18 / TS / Vite, ~2.4k LOC), Docker, Kubernetes, docs.
**Baseline:** `mvn test` green (backend), `vitest run` green (27 frontend tests).

---

## 1. Executive summary

The project works and the architecture is sound: discovery, browse, playback and TMDB
enrichment are cleanly separated, the Synology workarounds (client-side sort, effective
dates, search fallback) are well reasoned and well documented, and the sorting/date logic
has genuinely good unit tests.

The problems are concentrated in three places:

1. **Secrets are committed to git.** A live TMDB API key and read-access JWT sit in
   `k8s/secret.yml`; a live Discord webhook sits in `AGENTS.md`. Both are in history.
2. **A one-character indexing bug silently breaks media typing.** `extractMimeType`
   returns the wrong field of `protocolInfo`, so every item's MIME type is `"*"`. The UI
   labels every file "File", and — more seriously — every item is announced to renderers
   as `object.item.audioItem.musicTrack`, including videos.
3. **The client-side sort/search paths re-fetch the entire container on every page.**
   Combined with a 10-second Axios timeout, date-sorted browsing of a large folder times
   out in the browser while the backend keeps crawling.

Below that sits a consistent pattern typical of LLM-generated code: near-duplicate methods
(`browse` / `browseInternal`), fabricated fallbacks (invented renderer capabilities),
loops that do nothing, defensive checks with inverted logic, and documentation describing
features that were never wired up (`/subscribe`).

**Counts:** 3 critical, 8 high, 20 medium, 12 low.

---

## 2. Critical

### C1 — Live credentials committed to the repository

`k8s/secret.yml` is tracked in git and contains a real TMDB API key and a real
read-access JWT. `AGENTS.md` contains a live Discord webhook URL. Anyone with repo access
— or anyone who ever clones it — has both.

*Fix:* rotate both credentials, replace the values with placeholders, and load them from
outside the repo (sealed secret / `kubectl create secret` / SOPS).
→ `step-01`, `step-02`

### C2 — `extractMimeType` reads the wrong `protocolInfo` field

`ContentBrowseService.java:1046`

```java
String[] parts = protocolInfo.split(":");
if (parts.length >= 2) return parts[1];   // "*"
```

DLNA `protocolInfo` is `<protocol>:<network>:<contentFormat>:<extra>` — e.g.
`http-get:*:video/x-matroska:DLNA.ORG_PN=AVC_MKV`. `parts[1]` is the network field (`*`);
the MIME type is `parts[2]`.

Two consequences, both user-visible:

- `BrowsePage.mediaType()` never matches `video/`, `audio/`, or `image/`, so **every media
  item is labelled "File"**.
- `DidlUtils.generateSimpleMetadataXml` picks `upnp:class` from the MIME type, so **every
  item — video included — is sent to the renderer as
  `object.item.audioItem.musicTrack`**. Strict renderers may refuse or mishandle it.

→ `step-05`

### C3 — Docker builds fail from a clean clone

`.gitignore:31` ignores `package-lock.json`, so `frontend/package-lock.json` is untracked.
Both `frontend/Dockerfile` and the root `Dockerfile` run `npm ci`, which **requires** a
lock file. A fresh clone cannot build the frontend image.

→ `step-03`

---

## 3. High

### H1 — Client-side sort and in-memory search re-fetch everything, per page

`ContentBrowseService.browse()` → `fetchAllChildren()` pulls the whole container (500 at a
time, cap 50 000), sorts it, and returns one 50-item slice. Nothing is cached. Scrolling
ten pages of a 5 000-item folder issues **ten full container fetches** — 100 Browse calls
for 500 items of value. `searchInMemory` is worse: it re-crawls per page *and* per
keystroke burst.

Meanwhile `api/axios.ts` sets `timeout: 10000` while `nginx.conf` allows 60 s. A date sort
that triggers subtree crawling routinely exceeds 10 s, so the browser aborts and retries
while the backend is still crawling — multiplying the load it was already struggling with.

→ `step-18`

### H2 — In-memory search only searches direct children

`searchInMemory` (`ContentBrowseService.java:262`) calls `browseInternal(serverId,
containerId, ...)` in a page loop. `BrowseDirectChildren` returns one level. There is no
recursion. README claims *"it browses the entire container tree"* — it does not. On the
Synology (which answers `Search` with UPnP 501, so this is the *only* path), searching
from the root finds nothing but top-level folder names.

→ `step-23`

### H3 — Effective-date enrichment serialises every browse for a server

`enrichContainerDates` takes `synchronized (lock)` on a per-server monitor and holds it for
the entire crawl — up to 2 000 items of network I/O. Every other date-sorted browse of that
server blocks behind it, and the frontend's 10 s timeout fires while they wait.

→ `step-20`

### H4 — XXE: two XML parsers are unhardened

`DidlUtils.extractTitleFromMetadata` correctly disables DTDs and external entities.
`ContentBrowseService.parseBrowseResult` (line 926) and `parseSortCaps` (line 456) do not —
they parse attacker-influenceable XML from arbitrary LAN devices with a default
`DocumentBuilderFactory`. The inconsistency shows the hardening was applied in one place
and forgotten in the others.

→ `step-13`

### H5 — CORS allows any origin *with credentials*

`CorsConfig.java`: `setAllowCredentials(true)` + `addAllowedOriginPattern("*")`. Any web
page the user visits can issue authenticated cross-origin requests to the hub and drive
playback, enumerate the media library, or read the thumbnail proxy. For a device that
sits on a home LAN with no auth, this is a real DNS-rebinding / CSRF exposure.

→ `step-14`

### H6 — `/subscribe` and `/unsubscribe` do nothing

`DiscoveryManager.subscribe/unsubscribe` write to a `subscriptions` map. `isSubscribed`
is never called anywhere in the codebase (verified by grep). No jUPnP event listener
consults it. Both README and AGENTS.md describe event-driven `SystemUpdateID` handling
that does not exist. The frontend's `subscribeToServer()` is likewise never called.

→ `step-24`

### H7 — Unbounded caches under a 640 Mi memory limit

`ThumbnailService.thumbnailCache` grows one entry per (server, item) seen and is **never
evicted** — browsing a large library leaks steadily. `containerDateCache` at least has a
cap, but its eviction is `cache.clear()` at 10 000 entries, discarding every warm entry at
once and triggering a full re-crawl storm.

→ `step-19`

### H8 — Hot-path logging at INFO floods the log

The frontend polls `/status` every second. Each poll produces, at INFO:
`"Status request for player"`, `"GetTransportInfo inputs/outputs"` (×2),
`"GetPositionInfo inputs/outputs"` (×2) — each of the last four serialising the full
`ActionArgument[]` array. That is ~5 INFO lines per second per viewer, forever.

→ `step-21`

---

## 4. Medium

| # | Finding | Where |
|---|---------|-------|
| M1 | `setHasMore(index + result.count < result.total)` uses the *requested* count, not `result.items.length`. When a server returns a short page, the next fetch starts at `items.length` and overlaps — duplicate React keys, skipped items. | `BrowsePage.tsx:96` |
| M2 | The initial-browse effect depends on `doBrowse`, which changes whenever `sortBy` changes — so `handleSortChange` fetches once and the effect fetches again. Every sort change is a double round-trip. | `BrowsePage.tsx:128` |
| M3 | The poll effect depends on `pollStatus`, which is recreated whenever `playingPending`/`playingPendingSince` change. Every play/pause transition tears down the interval, sets `reconnecting = true`, dims the whole UI to `opacity-40 pointer-events-none`, and fires a duplicate immediate poll. | `PlaybackPage.tsx:215` |
| M4 | `handleVolumeChange` PUTs on every `onChange` — dragging the slider fires dozens of UPnP `SetVolume` calls. No debounce. | `PlaybackPage.tsx:329` |
| M5 | Line 120 nulls the router state, so `navItem` becomes `undefined`. `handlePlayPause` then falls through to `play(id, '')` — a bare `Play` with no URI — instead of using the persisted `activeItem`. Resuming from STOPPED does nothing. | `PlaybackPage.tsx:253` |
| M6 | `if ((artist == null \|\| artist.isEmpty()) && (description == null \|\| description.isEmpty())) artist = findNsText(itemEl, "artist");` — the `description` condition is meaningless; a described item never gets its artist. The `album` fallback re-calls the identical lookup and is a no-op. | `ContentBrowseService.java:1005` |
| M7 | When protocol/capability extraction yields nothing, the code **invents** capabilities: a fake `http-get:*:video/mpeg` protocol list and a full `Play/Pause/Stop/Seek/Next/Previous/...` action set. Downstream capability checks are then reasoning about a device that may support none of it. | `RendererDiscoveryManager.java:139,160` |
| M8 | `for (Action a : service.getActions()) { if ("GetProtocolInfo".equals(a.getName())) break; }` — an empty loop with no body. Dead. | `RendererDiscoveryManager.java:126` |
| M9 | `Map.of("error", e.getMessage())` throws `NullPointerException` when the exception has no message (e.g. a bare NPE) — the error handler itself fails. Appears 7×. | `BrowseController`, `GlobalExceptionHandler` |
| M10 | The thumbnail endpoint declares `produces = MediaType.IMAGE_JPEG_VALUE` but returns `image/png`, `image/gif`, `image/webp` and JSON error bodies. Spring content negotiation will reject the mismatched cases. | `BrowseController.java:104` |
| M11 | No request validation. Negative `count` → `matching.subList(from, to)` with `to < from` → `IllegalArgumentException` → 500. Negative `seconds` → `formatTime(-5)` → `"00:00:-5"` sent to the renderer. `Integer.parseInt(totalMatchesStr)` is unguarded. | Controllers, `AvTransportService:257` |
| M12 | `IllegalArgumentException` → 404 in `BrowseController`, → 400 in `GlobalExceptionHandler`. The same condition returns different statuses depending on which endpoint you hit. | Both |
| M13 | `browse()` and `browseInternal()` share ~40 identical lines. `ContentBrowseService` is 1 050 lines covering browse, search, sort probing, date enrichment, crawling, and XML parsing. | `ContentBrowseService` |
| M14 | No TMDB caching. Every playback page load issues up to 2 searches + 3 detail fetches + a season-credits fetch, all sequential. Re-opening the same title repeats all of it. | `TmdbService` |
| M15 | `forward()` adds 10 s with no upper clamp against `TrackDuration` — it can seek past the end of the track. | `AvTransportService.java:174` |
| M16 | `npm run lint` invokes `eslint`, but there is no eslint dependency and no config file — the script fails. There are no CI workflows at all (`.github` contains only modernize hooks). | `package.json`, `.github/` |
| M17 | Shipped defaults are one developer's environment: `network-interface: 10.0.0.150`, a specific renderer UDN, and a 5 s `static-device-check-interval` that port-scans 1024–2048 every 60 s when that device is absent. | `application.yml` |
| M18 | Thumbnails are parsed, cached, and proxied by a dedicated endpoint — and never displayed. `BrowsePage` draws generic SVG icons; `PlaybackPage` has `const [thumbnailUrl] = useState('')`, permanently empty, guarding dead `<img>` markup. | `BrowsePage`, `PlaybackPage.tsx:50` |
| M19 | Dead code: `utils/apiUrls.ts` (whole file), `components/Layout.tsx`, `DidlUtils.generateMetadataXml`, `getMetadata`/`getVolume`/`subscribeToServer`/`unsubscribeFromServer`/`getPlayer`, the root `Dockerfile`, the Lombok dependency (declared, zero usages), and `TMDB_API_KEY` (configured, never read). | Various |
| M20 | `getThumbnail()` and `apiUrls.ts` hardcode the `/api` prefix, ignoring `VITE_API_URL`. Any deployment that relocates the API breaks thumbnails. | `api/browse.ts:44` |

---

## 5. Low

- **L1 — Documentation drift.** README/AGENTS.md describe a `HomePage` at `/` (it routes to
  `ServerSelectPage`); claim Lombok is in use; claim search covers the whole tree; claim
  browse errors carry `upnpErrorCode` (`ContentBrowseService.executeSync` throws a bare
  `RuntimeException`, never `DlnaException`); and give two *identical* `docker buildx`
  commands for backend and frontend, both pointing at the stale root `Dockerfile`.
- **L2** — `formatDate`'s docstring describes the opposite of what the code does.
- **L3** — `parseTime("1:2:x")` returns `NaN`; no guard.
- **L4** — `parallelStream()` to collect a handful of discovered devices — common-pool
  overhead for no gain (`DiscoveryManager:81`, `RendererDiscoveryManager:180`).
- **L5** — `descriptorURL.getPort()` returns `-1` for default-port URLs; renderers then
  report `host:-1`.
- **L6** — TS types overstate guarantees: `PlaybackStatus.state` omits `UNKNOWN` and
  `NO_MEDIA_PRESENT` (both returned); `TmdbMediaInfo.overview/tagline/releaseYear/runtime`
  are non-optional but nullable server-side.
- **L7** — `key={member.name}` in the cast grid collides on duplicate names;
  `CastMemberDto` exposes no id.
- **L8** — Test coverage is narrow: sorting and date logic are well covered; XML parsing,
  controllers, discovery, and playback have none. The frontend tests only `cleanMediaTitle`.
- **L9** — K8s: no `securityContext` (no `runAsNonRoot`, no dropped capabilities, writable
  rootfs) despite `hostNetwork: true`; nginx serves `index.html` with no `no-store`, so
  clients can pin a stale SPA across redeploys.
- **L10** — `probeSortSupport` never closes its streams, skips `disconnect()` on the error
  path, and truncates the response at 8 192 bytes.
- **L11** — The scrubber only seeks on `mouseup`/`touchend`; keyboard arrow adjustments
  change the displayed time but never issue a seek.
- **L12** — `ServerSelectPage` and `PlayerSelectPage` poll every 10 s even when the tab is
  hidden. `useVisibility` exists and is used only by `PlaybackPage`.

---

## 6. What is done well

Worth preserving as-is:

- The Synology workarounds are correct and, unusually, *explain themselves* — the comment
  blocks in `ContentBrowseService` record why the cheaper `Search`-based approach was
  rejected, and `AGENTS.md` marks the NAS facts as established so they are not re-derived.
- `isFresh`'s stale-while-revalidate grace is a well-judged response to a measured quirk
  (`SystemUpdateID` churning every 30–90 s), and it is tested.
- Nulls-last-in-both-directions comparators are implemented correctly (operand swapping
  rather than negation) and tested for both directions.
- Crawl budgets are separated into per-call latency budget vs. per-subtree hard cap, and
  partial results are deliberately not cached.
- `cleanMediaTitle` is a genuinely careful piece of parsing with 27 tests behind it.
- `resolveArgName`'s `A_ARG_TYPE_*` handling is real-world DLNA knowledge, not guesswork.

---

## 7. Remediation plan

30 step-files in `plans/`, each scoped to one concern, 1–3 files, with exact before/after
code and a verification command. See `plans/README.md` for the running order.

| Phase | Steps | Theme |
|-------|-------|-------|
| 0 | 01–04 | Blockers: secrets, build |
| 1 | 05–12 | Correctness bugs users can see |
| 2 | 13–17 | Security and robustness |
| 3 | 18–22 | Performance and resource limits |
| 4 | 23–30 | Features, dead code, docs, CI |
