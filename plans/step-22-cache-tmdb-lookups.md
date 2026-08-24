# Step 22 — Cache TMDB lookups

**Phase:** 3 — Performance
**Severity:** Medium (report: M14)
**Files:** `backend/src/main/java/com/dlnahub/service/TmdbService.java`,
`backend/src/test/java/com/dlnahub/service/TmdbServiceTest.java`
**Depends on:** —

## Problem

`TmdbService.searchByTitle` performs, on every call:

- up to 2 search requests (movie + TV),
- then up to 3 detail requests, **sequentially**,
- plus, for TV, a season-credits request each.

Nothing is cached. Opening the same episode twice repeats all of it, and the frontend
re-queries whenever the parsed title changes — which happens on navigation and on the first
status poll that returns a track title. TMDB rate-limits, and each round-trip adds latency
to the playback page.

## Change

Add a small bounded, time-limited cache keyed on the search arguments. This caches the
*final result* — the list of `TmdbMediaDto` — so one hit avoids every downstream request.

### 1. Add the cache to `TmdbService`

Next to the existing fields:

```java
    /**
     * Result cache keyed on the search arguments. A single searchByTitle can cost up to six
     * sequential TMDB round-trips, and the frontend re-queries whenever the parsed title
     * changes. Bounded and time-limited: TMDB metadata is stable, but this is a process-local
     * convenience cache, not a datastore.
     */
    private static final long TMDB_CACHE_TTL_MS = 6 * 60 * 60 * 1000L;   // 6 hours
    private static final int TMDB_CACHE_MAX_ENTRIES = 256;

    private record CachedSearch(List<TmdbMediaDto> results, long cachedAt) {
    }

    private final Map<String, CachedSearch> searchCache = Collections.synchronizedMap(
            new LinkedHashMap<String, CachedSearch>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedSearch> eldest) {
                    return size() > TMDB_CACHE_MAX_ENTRIES;
                }
            });

    private static String searchCacheKey(String title, Integer yearHint, Boolean tvHint) {
        return title.trim().toLowerCase() + "|" + yearHint + "|" + tvHint;
    }
```

Add imports for `java.util.Collections` and `java.util.LinkedHashMap`.

### 2. Wrap the body of `searchByTitle`

Rename the existing method to `searchByTitleUncached` (make it `private`) and add a new
public entry point:

```java
    public List<TmdbMediaDto> searchByTitle(String title, Integer yearHint, Boolean tvHint) {
        if (!tmdbConfig.isEnabled() || title == null || title.isBlank()) {
            return List.of();
        }

        String key = searchCacheKey(title, yearHint, tvHint);
        CachedSearch cached = searchCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.cachedAt() < TMDB_CACHE_TTL_MS) {
            log.debug("TMDB cache hit for '{}'", title);
            return cached.results();
        }

        List<TmdbMediaDto> results = searchByTitleUncached(title, yearHint, tvHint);
        // Cache misses too: a title TMDB does not know will not start being known, and an
        // empty result is exactly the case the frontend retries most often.
        searchCache.put(key, new CachedSearch(results, System.currentTimeMillis()));
        return results;
    }
```

Delete the now-duplicated `isEnabled` guard from the top of `searchByTitleUncached`.

### 3. Fetch the detail pages in parallel

Still in `searchByTitleUncached`, replace the sequential detail loop:

```java
        List<TmdbMediaDto> results = new ArrayList<>();
        for (SearchCandidate c : top) {
            TmdbMediaDto detail;
            if ("movie".equals(c.type)) {
                detail = getMovieDetails(c.id);
            } else {
                detail = getTvDetails(c.id);
            }
            if (detail != null) {
                results.add(detail);
            }
        }
        return results;
```

with:

```java
        // Up to three independent detail fetches; run them concurrently rather than adding
        // three round-trips of latency to the playback page. Order is preserved so the
        // scoring done above still decides which candidate the UI shows first.
        return top.parallelStream()
                .map(c -> "movie".equals(c.type) ? getMovieDetails(c.id) : getTvDetails(c.id))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());
```

`getMovieDetails` / `getTvDetails` already swallow their own exceptions and return null, so
no additional error handling is needed.

## Add a test

Append to `TmdbServiceTest.java` — assert that a repeated search issues no new HTTP calls.
Follow the existing mocking style in that file (it already constructs `TmdbService` with an
injected `RestTemplate`); verify the mock is invoked the same number of times for the
second call as for the first.

## Do not

- Do not add Spring Cache / Caffeine as a dependency for this. The bounded `LinkedHashMap`
  is enough and keeps the build lean.
- Do not cache at the `callTmdb` level — caching the final DTO list is what actually saves
  the round-trips.

## Verify

```bash
cd backend && mvn test -Dtest=TmdbServiceTest
cd backend && mvn test
```

With the app running and TMDB configured, open the same title twice and confirm the second
playback page renders its metadata panel without a visible delay.
