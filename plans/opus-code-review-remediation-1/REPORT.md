# Remediation Verification Report

**Date:** 2026-08-25
**Reviewing:** 30 remediation steps (`plans/step-01` … `step-30`) implemented against
[`plans/ANALYSIS.md`](plans/ANALYSIS.md)
**Range:** `5de9dfd..a769e85` — 34 commits, working tree clean

---

## 1. Verdict

**All 30 steps are implemented, and they work.** Every step was checked against its
specification, the full build is green, and the headline fixes were verified live against
the real Synology DS918+ and a live DLNA renderer. The measured results are decisive —
paging on a date-sorted folder went from 2.44 s per page to 4 ms, and a status poll that
used to emit five log lines now emits none.

Implementation fidelity is high: the code matches the specs closely, the explanatory
comments were carried across rather than dropped, and the deliberate constraints (crawl
budgets, partial-results-not-cached, stale-while-revalidate) survived intact. Nothing was
silently skipped, and no step was "done" in name only.

Five issues are worth your attention. One is a latent behaviour change introduced by a
spec I wrote; one is a performance characteristic that will bite on larger searches; three
are documentation and process nits.

| | |
|---|---|
| Steps implemented to spec | **30 / 30** |
| Backend `mvn verify` | ✅ pass — 45 tests (was ~30) |
| Frontend typecheck / test / build | ✅ pass — 27 tests, clean build |
| `npm ci` from lockfile | ✅ works, lockfile in sync |
| ESLint | ✅ 0 errors, 4 warnings |
| Live-verified against real hardware | 13 behaviours |
| New issues found | 5 (1 medium-latent, 1 medium-perf, 3 low) |
| Steps reverted after review | **1** — step-23, on the owner's decision (§9) |

---

## 2. How this was verified

Three passes, in increasing strength of evidence:

1. **Spec conformance** — each step file read against the code it names, checking the
   specific constructs it asked for and confirming the "Do not" fences were respected.
2. **Build and test** — `mvn verify`, `tsc -b`, `vitest run`, `eslint`, `vite build`,
   `npm ci --dry-run`.
3. **Live** — backend started against the real network with the shipped defaults, exercised
   against the Synology DS918+ (`10.0.0.60`) and an mpv DLNA renderer (`10.0.0.150:8099`).
   Renderer interaction was kept read-only.

---

## 3. Live evidence

This is the part that matters most — the fixes were measured, not just read.

### C2 — MIME type extraction (the bug that mistyped every media item)

Real item from `movies/21 Jump Street (2012) [1080p]`:

```
protocolInfo = 'http-get:*:video/mp4:*'
mimeType     = 'video/mp4'        ← was '*' before the fix
```

The UI will now label it "Video" rather than "File", and `generateSimpleMetadataXml` will
announce `object.item.videoItem` rather than `object.item.audioItem.musicTrack`.

### H1 — Paging no longer re-fetches the whole container

511-item folder, three sequential pages, `count=50`:

| Sort | Page 1 | Page 2 | Page 3 |
|------|--------|--------|--------|
| `dc:title` (client sort) | 150 ms | **4.3 ms** | 4.6 ms |
| `-dc:date` (client sort + effective-date enrichment) | 2 443 ms | **4.8 ms** | 3.8 ms |

Before the fix every page paid the page-1 cost. On the date sort that is a **~600×**
improvement for pages 2+, and it is the difference between scrolling working and the old
10 s Axios timeout firing mid-crawl.

Effective-date enrichment still produces correct results — folders get an `effectiveDate`
from their newest descendant, files use their own `dc:date`, and the ordering is right:

```
Lock, Stock and Two Smoking Barrels   date=None       effectiveDate=2026-08-22T18:18:43Z
Pulp Fiction (1994) [1080p]           date=None       effectiveDate=2026-08-22T18:12:53Z
Masters.of.the.Universe.2026.1080p    date=2026-08-04 effectiveDate=None
```

### H2 — In-memory search is genuinely recursive now *(since reverted — see §9)*

Searching `containerId=44$13350` (the movies folder) for `Street.2012` — a string that
matches a **file one level below**, and matches no folder name:

```
total matches: 1
  container=False | 21.Jump.Street.2012.1080p.BluRay.x264.YIFY
```

The old flat search returned nothing here. The backend log confirms the fallback path was
the one exercised (`Search action not available … falling back to in-memory search`), so
`collectSubtree` was genuinely under test. It completed in **2.7 s**.

### H8 / step-21 — Log flood eliminated

20 consecutive `/status` polls (≈20 s of frontend polling):

```
log lines added by 20 polls: 0        (pre-fix: ~100)
```

Total backend log after ~15 minutes of heavy browsing, sorting and searching: **44 lines**.

### Step-25 — Renderer capabilities are honest now

Live renderer response:

```json
"supportedProtocols":    [],
"transportCapabilities": ["Play","Pause","Stop","GetCurrentConnectionID","LastChange",
                          "GetTransportInfo","GetPositionInfo","SetAVTransportURI","Seek"],
"port": 8099
```

An honest empty protocol list rather than the fabricated `MPEG_PS PAL` entry, the device's
real SCPD action set rather than the invented `Next`/`Previous`/`GetDeviceCapabilities`
list, and a real port rather than `-1`.

### Steps 14–17 — Security and error handling

| Check | Result |
|-------|--------|
| `Origin: http://evil.example` | no `Access-Control-Allow-Origin` ✅ |
| `Origin: http://localhost:5173` | header present ✅ |
| `browse?count=-1` | `400` ✅ (was 500) |
| `browse?count=9999` | `400` ✅ |
| unknown server id | `404` ✅ (was inconsistent 400/404) |
| `seek {"seconds":-5}` | `400` ✅ |
| `volume {"volume":500}` | `400` ✅ |
| `POST /servers/{id}/subscribe` | `404` ✅ (endpoint removed) |
| thumbnail 404 with `Accept: application/json` | `404`, `content_type=application/json` ✅ (was 406) |

### Step-29 — Shipped defaults work on an unconfigured machine

```
No dlna.network-interface configured; letting jUPnP select interfaces automatically
```

…and the NAS was still discovered. No port-scan line appeared, confirming
`static-devices: []` defaults to inert.

---

## 4. Step-by-step conformance

All 30 verified against their spec. Compact summary:

| Steps | Area | Result |
|-------|------|--------|
| 01–02 | Secrets removed from tracked files; webhook → `DISCORD_WEBHOOK_URL` | ✅ `git grep` finds zero leaked values in any tracked file |
| 03 | `frontend/package-lock.json` tracked | ✅ `npm ci` succeeds, lockfile in sync with `package.json` |
| 04 | Stale root `Dockerfile` deleted, README build commands fixed | ✅ both `-f backend/` and `-f frontend/` present, once each |
| 05 | `extractMimeType` → `parts[2]`, package-private, 2 new tests | ✅ verified live (§3) — *see finding F1* |
| 06 | `description` gate removed, dead album fallback deleted | ✅ |
| 07 | `hasMore` from `items.length`; `setError(null)` on success | ✅ |
| 08 | `lastLoadedServerRef` guard on the mount effect | ✅ |
| 09 | `handlePlayPause` uses `item` (navItem ?? activeItem) | ✅ code correct; not live-tested |
| 10 | Volume debounced at 200 ms, timer cleared on unmount | ✅ code correct; not live-tested |
| 11 | `pollStatusRef` + split reconnect effect | ✅ implemented exactly as specced |
| 12 | `formatTime` clamps, `forward` clamps to duration−1, `SKIP_SECONDS` | ✅ + 4 new unit tests |
| 13 | Both parsers use `secureDocumentBuilderFactory`; XPath `FEATURE_SECURE_PROCESSING` | ✅ only one `DocumentBuilderFactory.newInstance()` remains, inside the helper |
| 14 | Explicit origin list, `allowCredentials(false)`, scoped `/api/**` | ✅ verified live |
| 15 | `@Validated`/`@Min`/`@Max`/`@NotBlank`/`@Valid`, `parseTotalMatches` at all 3 sites | ✅ verified live |
| 16 | `DeviceNotFoundException`, `body()` helper, `DlnaException` from `executeSync`, controller try/catch removed | ✅ 4 throw sites converted; Synology-501 fallback still works |
| 17 | `produces` removed, `ResponseEntity<byte[]>`, 404 via exception | ✅ verified live |
| 18 | Sorted-set cache on all three client-sort paths; Axios 60 s | ✅ verified live (§3) |
| 19 | Bounded access-order LRU, `ThumbnailKey` → record, 2 new tests | ✅ |
| 20 | Per-container lock, `crawlAndCache`, re-check inside lock | ✅ no nested locking → no deadlock path |
| 21 | 5 argument-dump logs deleted, status/TMDB → DEBUG, axios `import.meta.env.DEV` | ✅ verified live (§3) |
| 22 | Bounded TTL cache, `searchByTitleUncached`, parallel detail fetch | ✅ + a real cache-assertion test |
| 23 | `collectSubtree` BFS with `visited` set and both budgets | ✅ verified live (§3) — *see finding F2* |
| 24 | Endpoints, `subscriptions` map, and frontend API functions all removed | ✅ zero references remain |
| 25 | Fabricated fallbacks and empty loop deleted; `getDefaultPort` | ✅ verified live (§3) |
| 26 | `apiUrls.ts`, `Layout.tsx`, `generateMetadataXml`, 4 API fns, Lombok, `TMDB_API_KEY` | ✅ all gone; `getThumbnail` honours `VITE_API_URL` |
| 27 | `getThumbnail` + `loading="lazy"` in BrowsePage; `useMemo` in PlaybackPage | ✅ code correct; not live-tested (no albumart items found) |
| 28 | `eslint.config.js`, deps installed, `.github/workflows/ci.yml` | ✅ lint runs clean; CI steps all reproduce locally |
| 29 | Auto interface, `static-devices: []`, 30 s interval, README Configuration table | ✅ verified live (§3) |
| 30 | Routes, catch-all, `TransportState`, nullable TMDB fields, `useVisibility` on both select pages | ✅ — *see finding F3* |

**Notable quality signals:** `crawlSubtree` was left untouched (correct — it was already
right); the budget/completeness semantics survived; `visited`-set cycle protection in
`collectSubtree` was implemented as specced; and the `eslint-disable` comments carry
stated reasons rather than being blanket suppressions.

---

## 5. Findings

### F1 — Latent: wildcard `protocolInfo` now changes which `<res>` wins *(Medium)*

`ContentBrowseService.parseItem` — introduced by step-05, and it is my spec's fault, not
the implementation's.

The resource loop picks the first non-thumbnail `<res>` by keying off `mimeType == null`:

```java
} else if (mimeType == null) {
    mimeType = extractMimeType(proto);   // step-05: now returns null for "*"
    protocolInfo = proto;
    resourceName = resText;              // ← the playback URL
    ...
}
```

Step-05 correctly made `extractMimeType` return `null` for a wildcard content format
(`http-get:*:*:*`). But that leaves the guard open, so a **second** such `<res>` overwrites
`protocolInfo`, `resourceName`, `duration`, `resolution` and `size`. Previously
`extractMimeType` returned the non-null `"*"`, so the first resource always won.

**Impact:** for a multi-resource item whose first resource has a wildcard content format,
the *last* such resource now supplies the playback URL instead of the first. Your Synology
emits real MIME types (`http-get:*:video/mp4:*`), so this does not bite today — but it is a
real behaviour change waiting for a server that does.

**Fix** — decouple the "first resource wins" decision from the MIME type:

```java
boolean primaryResChosen = false;
...
} else if (!primaryResChosen) {
    primaryResChosen = true;
    mimeType = extractMimeType(proto);
    protocolInfo = proto;
    resourceName = resText;
    ...
}
```

### F2 — Every distinct search query re-walks the whole subtree *(Medium — RESOLVED by the §9 revert)*

Steps 18 and 23 interact. The sorted-set cache is keyed on the query string
(`"search:" + query.toLowerCase()`), so it only helps *paging within one query*. Each new
query performs a fresh `collectSubtree` walk.

With the frontend's 400 ms debounce, a user typing `matrix` with natural pauses can trigger
several full subtree walks — `mat`, `matri`, `matrix`. Measured cost on the movies folder
was 2.7 s per walk, which is tolerable; but `SEARCH_MAX_ITEMS` is 20 000, so a search from
the library root has a far higher ceiling.

**Resolved** — step-23 was reverted (§9), so there is no subtree walk left to repeat. The
folder-scoped search pages one container, which is cheap, and step-18's cache still covers
paging within a result list. If library-wide search is ever revisited, this caching approach
is the one to use — it is recorded in `plans/step-23`.

### F3 — Documentation drift reintroduced by steps 17, 18 and 19 *(Low — 2 of 3 now fixed)*

Step-30 reconciled the docs, but three statements went stale in the same batch of work:

| `README.md` | Says | Actually |
|---|---|---|
| line 171 | thumbnails cached "in a `ConcurrentHashMap`" | bounded access-order LRU, 5 000 entries (step-19) |
| line 273 | `Thumbnail proxy (image/jpeg)` | content type sniffed from magic bytes (step-17) |
| Client-Side Sorting | *(no mention)* | 60 s / 32-entry sorted-set cache (step-18) |

The endpoint tables, routing tables and Axios timeout are all accurate — this was a narrow
miss, not a systemic one. Lines 171 and 273 were corrected while making the §9 revert; the
sorted-page cache is still undocumented in the Client-Side Sorting section.

### F4 — Two `Map.of("error", …)` bypass the null-safe helper *(Low, nit)*

`GlobalExceptionHandler` lines 50 and 61 — the two validation handlers build their body with
`Map.of` rather than the `body()` helper that step-16 introduced specifically to avoid the
null-message NPE. Both are safe in practice (`ConstraintViolationException.getMessage()` is
always populated; the other uses `.orElse(...)`), so this is consistency, not a bug — and
again it is what my step-15 spec asked for. Worth folding into `body()` next time either
file is touched.

### F5 — The "Discovered along the way" log went unused *(Low, process)*

`plans/README.md` asked for incidental findings to be recorded rather than fixed inline.
The section is still `_(nothing yet)_`, yet F1–F4 were all discoverable while working the
steps. The discipline of not fixing them inline was followed; the discipline of recording
them was not.

---

## 6. Scope additions

Three commits outside the plan (`6f1f82c`, `29ffef3`, `a4c3136`) added documentation, not
code. Two record operational facts about the test renderer being intermittently available —
useful and accurate.

The third adds a **"Security Posture"** section to both `README.md` and `AGENTS.md`
declaring the app LAN-only with no authentication by design, and stating that cross-origin
access from public websites "is not a threat model". Flagging it because it partly reframes
finding H5 from the original analysis:

- The CORS lockdown **was** still implemented (step-14, verified live), so the exposure is
  closed either way.
- The posture note describes it as "a cheap baseline, not a core control" and says not to
  add auth/CSRF/rate limiting unless asked.

That is a legitimate product decision and it is now written down rather than implicit —
which is an improvement. Two things worth keeping in view: DNS rebinding can reach a
LAN-only service from a public page regardless of the intended threat model (which is what
the CORS restriction now blocks — so keep it), and the posture holds only as long as the hub
is never exposed through a tunnel, a reverse proxy, or a router port-forward.

---

## 7. Not verified

Stated plainly so the coverage claim is honest:

- **Renderer-dependent playback behaviour** — steps 09, 10, 11 and the live half of 12. The
  Xbox was off (per `AGENTS.md`, that is expected and must not be waited for). An mpv
  renderer was available and used for read-only checks, but playback, seeking and volume
  *writes* were deliberately not exercised on someone else's device. These four steps are
  verified by code inspection and unit tests only.
- **Thumbnail rendering (step-27)** — the 404 path was verified live, but no item with an
  `albumart` resource turned up in the folders sampled, so the 200-with-image path and the
  lazy-loading behaviour were not exercised.
- **Frontend behaviour under test** — the frontend still has 27 tests, all of them for
  `cleanMediaTitle`. Steps 07–11, 27 and 30 changed real logic (pagination arithmetic, effect
  dependencies, debouncing, playback branching) with **no test coverage**. The plan did not
  ask for frontend tests, so this is not a deviation — but it is where the residual risk
  sits, and it is the strongest candidate for the next piece of work.
- **C1 rotation status** — whether the TMDB credentials and the Discord webhook were actually
  rotated is not observable from here. The repo-side work is complete and verified; if
  rotation has not happened, the leaked values in git history are still live.

---

## 8. Recommended next steps

1. **Confirm C1 rotation** — the single highest-value item, and the only one still carrying
   real risk.
2. **F1** — the `primaryResChosen` flag. Small, contained, prevents a future playback bug.
3. **F4 and the remaining half of F3** — fold into the next edit of those files.
4. **Frontend tests** — `hasMore` arithmetic, the debounce, and `handlePlayPause`'s branching
   are all pure enough to test cheaply and are where untested logic now concentrates.

Out of scope by design and still open from the original analysis: authentication, GENA event
subscription, splitting the 1 050-line `ContentBrowseService`, and rewriting git history.

---

## 9. Post-review change: step-23 reverted

**Date:** 2026-08-25, after review. **Decision:** the owner's.

### What prompted it

The owner reported the symptom directly: *"a screen shows 3 folders and I search for 'Silo'
and I get a bunch of hits, even though Silo is not one of the 3 folders."*

That is exactly what step-23 made the app do, and it is right that it reads as broken.

### Why the original change was wrong

Step-23's reasoning — that the in-memory fallback disagreed with both the README and the
subtree-scoped ContentDirectory `Search` action, and was therefore the odd one out — was
sound as far as it went. The mistake was reasoning from the documentation rather than from
the interface, and never asking how a recursive result would be *presented*:

1. **Results carry no location.** The browse list renders one line per item: a title, and
   nothing else. A hit from three folders down looks like an item the current folder
   contains.
2. **Navigating a hit corrupted the breadcrumb.** `handleNavigate` appends the tapped item
   to the *current* trail, producing `Root > Music > Silo` when Silo is not in Music — a
   trail asserting a parent/child relationship that does not exist, whose back-navigation
   then goes somewhere unrelated. This was a real bug introduced by step-23 and **missed in
   the first pass of this review**; it surfaced only when the owner questioned the UX.
3. **It was expensive**, per finding F2.

The owner's model — search narrows what is on screen — is what the UI can actually present.

### The revert

- `searchInMemory` calls the existing `fetchAllChildren` again rather than `collectSubtree`,
  reusing the flat pager instead of duplicating it.
- `collectSubtree`, `SEARCH_MAX_ITEMS`, `SEARCH_MAX_DEPTH` and four imports deleted.
- The method carries a comment explaining why it is not recursive, and noting that the
  `Search`-action path stays subtree-scoped because the UPnP spec offers no way to request
  direct children only.
- `plans/step-23` rewritten as a **do-not-re-apply** record with the reasoning and, if
  library-wide search is ever wanted, the four things that must come first.
- README Search section rewritten; F3's two stale statements fixed in the same pass.
- The step-18 paging cache is **kept** — it still saves re-fetching a container per page.

### Verified live against the NAS

```
Root shows:                Music, Photo, Video

search "Silo"   from Root   → total = 0     ← the reported symptom, gone
search "Video"  from Root   → total = 1     ← the folder actually on screen
search "Street.2012" in movies → total = 0  ← step-23's nested hit, gone
search "Jump Street" in movies → total = 1  ← still found where it actually lives
```

The breadcrumb bug disappears with it: flat results genuinely *are* children of the current
folder, so appending them to the trail is correct again. No separate fix was needed.

`mvn test` green; frontend untouched and still typechecks.

### Worth carrying forward

Two of the five findings in this report (F2, and the breadcrumb bug folded into it) came from
one step that was specified from the documentation rather than from the running interface.
When a step changes what the user sees, "does the code now match the docs?" is the wrong
acceptance test — the docs were one of the things under suspicion.
