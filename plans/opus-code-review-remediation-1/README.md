# DLNA Hub — Remediation Plan

The findings are in [`ANALYSIS.md`](ANALYSIS.md). This file is the running order.

## How to work this plan

Each `step-NN-*.md` is self-contained and sized for one focused session:

- **1–3 files touched**, exact before/after code, no cross-file archaeology required.
- A **Do not** section that fences the scope — read it; it is there to stop the change
  spreading.
- A **Verify** section with runnable commands and expected output.

Rules for whoever (or whatever) works a step:

1. **One step per commit.** Message: `step-NN: <the step title>`.
2. **Run the verification before committing.** If it does not pass, the step is not done.
3. **Do not fix things the step did not ask for.** If you spot something, add a line to
   the "Discovered along the way" section at the bottom of this file and move on.
4. **Test locally against the real NAS/Xbox before building images** — see `AGENTS.md`.
5. The full test suite must stay green throughout:
   ```bash
   cd backend  && mvn test
   cd frontend && npm run typecheck && npm test
   ```

## Running order

Phases are ordered by risk. Within a phase, steps are mostly independent — the
`Depends on` line in each file is authoritative.

### Phase 0 — Blockers

Nothing else matters until these are done. Steps 01 and 02 involve **rotating live
credentials** and need a human.

| Step | Title | Severity |
|------|-------|----------|
| [01](step-01-rotate-and-remove-tmdb-secrets.md) | Remove committed TMDB credentials | Critical |
| [02](step-02-remove-discord-webhook.md) | Remove the Discord webhook from AGENTS.md | Critical |
| [03](step-03-track-package-lock.md) | Track `package-lock.json` so Docker builds work | Critical |
| [04](step-04-delete-stale-root-dockerfile.md) | Delete the stale root Dockerfile, fix README build commands | Medium |

### Phase 1 — Correctness bugs users can see

| Step | Title | Severity |
|------|-------|----------|
| [05](step-05-fix-protocolinfo-mimetype.md) | Fix the MIME type extracted from `protocolInfo` | Critical |
| [06](step-06-fix-artist-album-fallbacks.md) | Fix the artist and album fallbacks in `parseItem` | Medium |
| [07](step-07-fix-pagination-hasmore.md) | Base `hasMore` on items received, not requested | Medium |
| [08](step-08-fix-double-fetch-on-sort.md) | Stop double-fetching on every sort change | Medium |
| [09](step-09-fix-resume-play-after-state-consumed.md) | Fix play after the router state is consumed | Medium |
| [10](step-10-debounce-volume-slider.md) | Debounce the volume slider | Medium |
| [11](step-11-fix-poll-effect-churn.md) | Stop the poll effect restarting on every state change | Medium |
| [12](step-12-clamp-seek-targets.md) | Clamp seek targets to a valid range | Medium |

Steps 07 and 08 touch the same file — do them in order. Steps 09, 10 and 11 all touch
`PlaybackPage.tsx`; they are independent but sequencing them avoids conflicts.

### Phase 2 — Security and robustness

| Step | Title | Severity |
|------|-------|----------|
| [13](step-13-harden-xml-parsers.md) | Harden the remaining XML parsers against XXE | High |
| [14](step-14-lock-down-cors.md) | Lock down CORS | High |
| [15](step-15-validate-request-parameters.md) | Validate request parameters | Medium |
| [16](step-16-unify-error-handling.md) | Unify error handling, fix the null-message NPE | Medium |
| [17](step-17-fix-thumbnail-content-type.md) | Fix the thumbnail endpoint's content type | Medium |

Do **16 before 15** if you would rather edit `GlobalExceptionHandler` once.
17 depends on 16.

### Phase 3 — Performance and resource limits

| Step | Title | Severity |
|------|-------|----------|
| [18](step-18-cache-sorted-result-sets.md) | Cache sorted result sets so paging stops re-fetching | High |
| [19](step-19-bound-the-thumbnail-cache.md) | Bound the thumbnail URL cache | High |
| [20](step-20-narrow-the-enrichment-lock.md) | Narrow the effective-date enrichment lock | High |
| [21](step-21-quiet-the-hot-path-logging.md) | Quiet the hot-path logging | High |
| [22](step-22-cache-tmdb-lookups.md) | Cache TMDB lookups | Medium |

18 → 20 in that order (20 assumes 18's caching is in place). 19, 21, 22 are independent.

### Phase 4 — Features, cleanup, docs, CI

| Step | Title | Severity |
|------|-------|----------|
| [23](step-23-make-in-memory-search-recursive.md) | Make the in-memory search actually search the subtree | High |
| [24](step-24-remove-the-dead-subscribe-endpoints.md) | Remove the dead subscribe/unsubscribe endpoints | High |
| [25](step-25-stop-fabricating-renderer-capabilities.md) | Stop fabricating renderer capabilities | Medium |
| [26](step-26-delete-dead-code.md) | Delete dead code | Medium |
| [27](step-27-show-thumbnails-in-the-ui.md) | Actually show the thumbnails | Medium |
| [28](step-28-add-lint-and-ci.md) | Fix the lint script and add CI | Medium |
| [29](step-29-remove-personal-defaults-from-config.md) | Remove personal environment details from defaults | Medium |
| [30](step-30-reconcile-the-docs.md) | Reconcile the documentation with the code | Low |

23 depends on 18. 27 depends on 17 and 26. 28 depends on 03. **30 is the closing pass** —
it fixes documentation for changes made in 04, 16, 18, 23, 24 and 26, so run it last.

## If you only have time for a few

01, 02, 03, 05, 18. Those cover both credential leaks, the broken build, the bug that
mistypes every media item, and the one that makes large folders unusable.

## Not in this plan

Deliberately out of scope — worth doing, but each is a design decision rather than a fix:

- **Authentication.** The API is unauthenticated. Step-14 narrows CORS, which addresses the
  drive-by-browser vector, but anyone on the LAN can still control playback.
- **GENA event subscription.** Would let the effective-date cache invalidate on a real
  `ContainerUpdateIDs` event rather than polling `GetSystemUpdateID` (see step-24).
- **Splitting `ContentBrowseService`** (1 050 lines, five responsibilities) and removing the
  `browse` / `browseInternal` duplication. Worth doing, but it touches everything and would
  conflict with most of Phase 1–3. Do it after this plan lands, on a green test suite.
- **Rewriting git history** to purge the leaked credentials. Rotation (steps 01–02) is what
  removes the risk; history rewriting is a separate, disruptive operation.
- **Broader test coverage** — nothing covers DIDL-Lite parsing, the controllers, or
  discovery. Individual steps add tests for what they touch; a coverage push is its own task.
- **Playlists / next / previous track**, and keyboard support for the scrubber.

## Discovered along the way

Add anything you find while working a step, rather than fixing it inline.

- _(nothing yet)_
