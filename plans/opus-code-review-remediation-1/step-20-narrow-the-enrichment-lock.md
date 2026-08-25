# Step 20 — Narrow the effective-date enrichment lock

**Phase:** 3 — Performance
**Severity:** High (report: H3)
**Files:** `backend/src/main/java/com/dlnahub/service/ContentBrowseService.java`
**Depends on:** step-18

## Problem

```java
Object lock = containerDateLocks.computeIfAbsent(serverId, k -> new Object());
synchronized (lock) {
    // ... up to ENRICH_MAX_CRAWLS_PER_CALL subtree crawls, each up to
    //     ENRICH_MAX_ITEMS_PER_CALL items of network I/O ...
}
```

The monitor is held **per server** for the whole enrichment pass — potentially 2 000 items
of blocking UPnP round-trips. Any other date-sorted browse of the same server queues behind
it. With one NAS in the network, that is effectively a global lock on the slowest operation
in the application.

The lock exists to stop two concurrent requests crawling the same container twice. That
goal only needs a **per-container** lock, and even then only around the crawl itself — the
cache is already a `ConcurrentHashMap`, so reads and writes need no external synchronisation.

## Change

### 1. Replace the lock map

```java
    /** Per-container crawl locks, so two concurrent requests do not crawl the same subtree twice. */
    private final Map<String, Object> containerDateLocks = new ConcurrentHashMap<>();

    private static final int MAX_TRACKED_CONTAINER_LOCKS = 4_096;

    private Object crawlLock(String serverId, String containerId) {
        if (containerDateLocks.size() > MAX_TRACKED_CONTAINER_LOCKS) {
            // Bound the lock map. Dropping locks is safe: the worst case is two requests
            // crawling the same subtree concurrently, which is correct, just wasteful.
            containerDateLocks.clear();
        }
        return containerDateLocks.computeIfAbsent(serverId + "/" + containerId, k -> new Object());
    }
```

The map already exists with this name and type — only the helper is new. The key changes
from `serverId` to `serverId + "/" + containerId`.

### 2. Remove the outer `synchronized` from `enrichContainerDates`

The method body becomes (unchanged parts elided):

```java
        String updateId = getSystemUpdateId(serverId);
        long now = System.currentTimeMillis();

        try {
            Map<String, ContainerDateEntry> cache =
                    containerDateCache.computeIfAbsent(serverId, k -> new ConcurrentHashMap<>());
            CrawlBudget budget = new CrawlBudget(ENRICH_MAX_ITEMS_PER_CALL);
            int crawls = 0;
            List<BrowsableItem> out = new ArrayList<>(items.size());
            for (BrowsableItem item : items) {
                if (!item.isContainer()) {
                    out.add(item);
                    continue;
                }
                ContainerDateEntry entry = cache.get(item.getId());
                if (!isFresh(entry, updateId, now) && crawls < ENRICH_MAX_CRAWLS_PER_CALL) {
                    crawls++;
                    entry = crawlAndCache(serverId, item.getId(), updateId, now, budget, cache, entry);
                }
                if (entry != null && entry.latestDate() != null) {
                    out.add(withEffectiveDate(item, entry.latestDate()));
                } else {
                    out.add(item);
                }
            }
            if (cache.size() > CACHE_MAX_ENTRIES) {
                cache.clear();
            }
            return out;
        } catch (Exception e) {
            log.warn("Container date enrichment failed for server {}: {}", serverId, e.getMessage());
            return items;
        }
```

### 3. Add the narrow per-container crawl helper

```java
    /**
     * Crawls one container's subtree under a per-container lock, so two concurrent requests do
     * not duplicate the work. The lock covers only this container's crawl — never the whole
     * enrichment pass, which would serialise every date-sorted browse of the server.
     * Re-checks the cache inside the lock: the request we queued behind may have just filled it.
     */
    private ContainerDateEntry crawlAndCache(String serverId, String containerId, String updateId,
                                             long now, CrawlBudget budget,
                                             Map<String, ContainerDateEntry> cache,
                                             ContainerDateEntry stale) {
        synchronized (crawlLock(serverId, containerId)) {
            ContainerDateEntry current = cache.get(containerId);
            if (isFresh(current, updateId, System.currentTimeMillis())) {
                return current;
            }
            CrawlResult result = crawlSubtree(serverId, containerId, updateId, 0, budget, cache);
            // Only complete crawls replace the entry; partial (budget-exhausted) results keep
            // the previous one so the last known date keeps being served while a later request
            // finishes the subtree.
            if (result.complete) {
                ContainerDateEntry fresh = new ContainerDateEntry(updateId, result.date, now);
                cache.put(containerId, fresh);
                return fresh;
            }
            return stale;
        }
    }
```

## Do not

- Do not remove the budgets (`ENRICH_MAX_ITEMS_PER_CALL`, `ENRICH_MAX_CRAWLS_PER_CALL`) —
  they are the latency guarantee and are deliberately separate from `CRAWL_MAX_TOTAL_ITEMS`.
- Do not cache partial crawl results. The existing `result.complete` check is correct and
  intentional; preserve it exactly.
- Do not change `isFresh` or the stale-while-revalidate grace — both are measured against
  real NAS behaviour and are tested.

## Verify

```bash
cd backend && mvn test
```

Against the real NAS, with the backend running, issue two date-sorted browses of
**different** large folders at the same time:

```bash
curl -s "http://localhost:9100/api/servers/<id>/browse?objectId=<folderA>&sortBy=-dc:date" > /dev/null &
curl -s "http://localhost:9100/api/servers/<id>/browse?objectId=<folderB>&sortBy=-dc:date" > /dev/null &
time wait
```

They should overlap. Before this change the second waits for the first to finish entirely.
