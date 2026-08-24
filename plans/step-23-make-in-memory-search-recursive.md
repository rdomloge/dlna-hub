# Step 23 — Make the in-memory search actually search the subtree

**Phase:** 4 — Features and cleanup
**Severity:** High (report: H2)
**Files:** `backend/src/main/java/com/dlnahub/service/ContentBrowseService.java`,
`README.md`
**Depends on:** step-18 (the sorted-page cache keeps this affordable)

## Problem

```java
private BrowseResult searchInMemory(String serverId, String containerId, String query, ...) {
    while (true) {
        BrowseResult page = browseInternal(serverId, containerId, startIdx, pageSize, filter, sortBy);
        allItems.addAll(page.getItems());
        ...
    }
    // filter allItems by title/artist/album
```

`BrowseDirectChildren` returns **one level**. There is no recursion, so the search only
ever matches the direct children of the container the user is standing in.

On the test Synology this is the *only* search path (the server answers `Search` with UPnP
501), so searching from the root matches nothing but the handful of top-level folder names.
README claims it "browses the entire container tree" — it does not.

## Change

Replace `searchInMemory` with a bounded recursive walk that reuses the existing crawl
infrastructure.

### 1. Add search budget constants next to the other limits

```java
    /* In-memory search: the whole subtree has to be walked, so it needs its own budget. */
    private static final int SEARCH_MAX_ITEMS = 20_000;   // items visited per search
    private static final int SEARCH_MAX_DEPTH = 10;       // folder depth walked
```

`SEARCH_MAX_DEPTH` is lower than `CRAWL_MAX_DEPTH` (50) on purpose: a search is
interactive, a date crawl is not.

### 2. Add the recursive collector

```java
    /**
     * Walks a container subtree breadth-first, collecting every descendant. Bounded by
     * SEARCH_MAX_ITEMS and SEARCH_MAX_DEPTH: this runs on an interactive request, and some
     * servers expose libraries far larger than it is reasonable to walk per keystroke.
     * Returns whatever was collected when a bound is hit — a partial search beats none.
     */
    private List<BrowsableItem> collectSubtree(String serverId, String containerId, String filter) {
        List<BrowsableItem> collected = new ArrayList<>();
        Deque<String> frontier = new ArrayDeque<>();
        Map<String, Integer> depthOf = new HashMap<>();
        Set<String> visited = new HashSet<>();
        frontier.add(containerId);
        depthOf.put(containerId, 0);

        while (!frontier.isEmpty() && collected.size() < SEARCH_MAX_ITEMS) {
            String current = frontier.poll();
            if (!visited.add(current)) {
                continue;   // some servers expose the same container under several parents
            }
            int depth = depthOf.getOrDefault(current, 0);

            int start = 0;
            while (collected.size() < SEARCH_MAX_ITEMS) {
                BrowseResult page = browseInternal(serverId, current, start, CRAWL_PAGE_SIZE, filter, "");
                List<BrowsableItem> children = page.getItems();
                for (BrowsableItem child : children) {
                    collected.add(child);
                    if (child.isContainer() && depth < SEARCH_MAX_DEPTH) {
                        frontier.add(child.getId());
                        depthOf.put(child.getId(), depth + 1);
                    }
                }
                if (children.size() < CRAWL_PAGE_SIZE) {
                    break;
                }
                start += children.size();
            }
        }

        if (collected.size() >= SEARCH_MAX_ITEMS) {
            log.warn("In-memory search hit the {}-item budget for server={}, container={}; results are partial",
                    SEARCH_MAX_ITEMS, serverId, containerId);
        }
        return collected;
    }
```

Add imports: `java.util.ArrayDeque`, `java.util.Deque`, `java.util.HashSet`, `java.util.Set`.

### 3. Use it in `searchInMemory`

Replace the flat paging loop at the top of `searchInMemory` with a single call:

```java
        List<BrowsableItem> allItems = collectSubtree(serverId, containerId, filter);
```

Leave the rest of the method — the lower-cased title/artist/album filter, the date
enrichment, the sort, and (after step-18) the cache write and `pagedResult` call —
unchanged.

### 4. Fix the README

In the "Search" section, the sentence currently reads:

> it browses the entire container tree (paginated, 500 per page, 50 000 cap)

Replace with:

> it walks the container subtree breadth-first (paginated, 500 per page), bounded to 20 000
> visited items and 10 folder levels, filters the results client-side by title/artist/album,
> then applies sorting. Results beyond those bounds are not searched, and a warning is logged.

## Do not

- Do not remove the bounds or raise them "to be safe". An unbounded interactive walk of a
  large NAS library is exactly the failure mode being avoided.
- Do not touch `crawlSubtree` — it serves the date enrichment and has different budgets for
  good reasons.
- Do not change `searchViaAction` / `searchPage`; servers that genuinely support `Search`
  should keep using it.

## Verify

```bash
cd backend && mvn test
```

Against the real NAS, search from the root for a string you know appears only in a
**nested** folder:

```bash
curl -s "http://localhost:9100/api/servers/<id>/search?containerId=0&query=<nestedTitle>" \
  | grep -o '"title":"[^"]*"' | head
```

Before this change the result is empty; after it, the nested item appears.
