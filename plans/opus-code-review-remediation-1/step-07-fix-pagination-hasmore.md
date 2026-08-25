# Step 07 — Base `hasMore` on items received, not the requested count

**Phase:** 1 — Correctness
**Severity:** Medium (report: M1)
**Files:** `frontend/src/pages/BrowsePage.tsx`
**Depends on:** —

## Problem

`BrowsePage.tsx` line 96:

```tsx
setHasMore(index + result.count < result.total);
```

`result.count` is the count the client **requested** (the backend echoes it back
unchanged), not the number of items actually returned. When a server returns a short page —
common for DLNA servers near the end of a container, and for the client-sort path — the
page advances by `items.length` on the next fetch but `hasMore` was computed from the
larger requested count. The result is overlapping fetches: duplicate items appended to the
list, duplicate React keys, and items that are never shown.

## Change

Replace line 96 with:

```tsx
        const received = index + result.items.length;
        setItems((prev) => (index === 0 ? result.items : [...prev, ...result.items]));
        setHasMore(result.items.length > 0 && received < result.total);
```

…replacing **both** the existing `setItems` line and the existing `setHasMore` line (the
`setItems` call moves below the `received` calculation). The final block reads:

```tsx
        if (requestId !== latestRequestRef.current) return;
        isSearchingRef.current = searching;
        const received = index + result.items.length;
        setItems((prev) => (index === 0 ? result.items : [...prev, ...result.items]));
        setHasMore(result.items.length > 0 && received < result.total);
        setError(null);
```

Note the added `setError(null)` — a successful fetch should clear a previous error, which
it currently never does.

## Do not

- Do not change the intersection-observer effect; `items.length` is already the correct
  offset for the next page.
- Do not change `PAGE_SIZE`.

## Verify

```bash
cd frontend && npm run typecheck && npm run build
```

Then, in the app: open a folder with more than 100 items, scroll to the bottom, and confirm
(a) no item title appears twice, and (b) no React duplicate-key warning appears in the
browser console.
