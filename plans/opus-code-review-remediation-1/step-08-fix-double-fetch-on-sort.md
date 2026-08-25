# Step 08 — Stop BrowsePage double-fetching on every sort change

**Phase:** 1 — Correctness
**Severity:** Medium (report: M2)
**Files:** `frontend/src/pages/BrowsePage.tsx`
**Depends on:** step-07 (same file; do them in order)

## Problem

```tsx
const fetchItems = useCallback(async (...) => { ... }, [selectedServer, sortBy]);
const doBrowse  = useCallback((...) => { ... },      [fetchItems, updateBrowseState]);

useEffect(() => {
  if (selectedServer) {
    doBrowse(browseState.objectId, 0, undefined, browseState.breadcrumb);
  } else {
    navigate('/servers');
  }
}, [selectedServer, doBrowse, navigate]);
```

`handleSortChange` calls `doBrowse(objectId, 0, value)` **and** writes `sortBy` to the
store. The store write changes `sortBy`, which recreates `fetchItems`, which recreates
`doBrowse`, which re-runs the effect — a second identical browse. On the client-sort path
each browse can be a full container fetch, so this doubles the most expensive request in
the app.

## Change

Guard the mount effect so it only fires when the *server* changes, not on every
`doBrowse` identity change.

### 1. Add a ref near the other refs (around line 63)

```tsx
  const lastLoadedServerRef = useRef<string | null>(null);
```

### 2. Replace the effect at line 128 with

```tsx
  useEffect(() => {
    if (!selectedServer) {
      lastLoadedServerRef.current = null;
      navigate('/servers');
      return;
    }
    if (lastLoadedServerRef.current === selectedServer.id) return;
    lastLoadedServerRef.current = selectedServer.id;
    doBrowse(browseState.objectId, 0, undefined, browseState.breadcrumb);
    // doBrowse / browseState are intentionally not dependencies: this effect is the
    // initial load for a newly selected server. Every later navigation, sort change and
    // search goes through its own explicit doBrowse / fetchItems call.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedServer, navigate]);
```

## Do not

- Do not remove the explicit `doBrowse` calls in `handleSortChange`,
  `handleBreadcrumbClick` or `handleNavigate` — those are now the only trigger for
  re-fetching, and all three are needed.
- Do not convert `fetchItems`/`doBrowse` into refs; the `useCallback` structure is fine.

## Verify

```bash
cd frontend && npm run typecheck && npm run build
```

Then, in the app with the browser Network tab open: change the sort option and confirm
**exactly one** `/browse` request is issued (currently two). Navigate into a folder and
back out and confirm one request per navigation.
