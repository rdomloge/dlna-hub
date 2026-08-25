# Step 23 — In-memory search scope

**Phase:** 4 — Features and cleanup
**Status:** ⛔ **REVERTED — do not re-apply.** Implemented 2026-08-25, reverted the same day
on the owner's decision.

---

## Do not redo this

This step made the in-memory search fallback walk the container subtree breadth-first. It
worked, and it was verified against the NAS. It was still the wrong change, and it has been
backed out. `searchInMemory` now filters the **direct children** of the container the user
is viewing, which is what it did before this step.

If a future analysis flags "the in-memory search is not recursive" as a bug — **it is not**.
It is a deliberate product decision, recorded here and in the README's Search section.

## Why it was reverted

The original reasoning was that the fallback disagreed with the README and with the
ContentDirectory `Search` action (which is subtree-scoped by the UPnP spec), so the same
search box behaved differently depending on the server. That inconsistency is real, but
fixing it in this direction produced a worse experience:

1. **Results had no location.** The browse list renders one line per item — a title, and
   nothing else. A hit from three folders down looked like an item the current folder
   contained. Standing in a folder showing three entries and searching produced a page of
   results that were visibly not any of those three.
2. **It corrupted the breadcrumb.** `handleNavigate` appends the tapped item to the *current*
   trail. With a recursive hit that produced `Root > Music > Silo` when Silo is not in Music
   — a trail claiming a parent/child relationship that does not exist, whose back-navigation
   then went somewhere unrelated. This was a genuine bug introduced by this step.
3. **It was expensive.** The result cache is keyed on the query string, so every distinct
   query re-walked the subtree. Searching from the library root crawled the whole NAS, and
   the 400 ms search debounce means one word can produce several such walks.

The owner's model is that search **narrows what is on screen**. That is what the UI can
present, so that is what it does.

## What the revert consists of

- `searchInMemory` calls `fetchAllChildren(serverId, containerId, filter, sortBy)` again
  instead of `collectSubtree`, reusing the existing flat pager rather than duplicating it.
- `collectSubtree` and the `SEARCH_MAX_ITEMS` / `SEARCH_MAX_DEPTH` constants are deleted,
  along with the four imports that only they used.
- The method carries a comment explaining why it is not recursive, so the next reader does
  not "fix" it.
- The README Search section describes the folder-scoped behaviour, and states plainly that
  the `Search`-action path remains subtree-scoped because the UPnP spec gives no way to ask
  it for direct children only.

The sorted-set paging cache from step-18 is **kept** — it still saves re-fetching the
container for every page of a long result list.

## If you ever want library-wide search

Do not reopen this step. It needs UI work first, and only then a backend change:

1. Each result row must show its folder path, so a hit reads as "somewhere else in the
   library" rather than "in this folder".
2. Tapping a result must rebuild the breadcrumb to that item's **real** path, not append to
   the current one.
3. The backend should cache the collected subtree per `(server, container)` and filter it
   per query, so the crawl is paid once per folder rather than once per keystroke burst.
4. Ideally offer both scopes explicitly — "This folder" / "Everywhere" — defaulting to this
   folder.

## Verify the revert

```bash
grep -c "collectSubtree\|SEARCH_MAX_ITEMS\|SEARCH_MAX_DEPTH" \
  backend/src/main/java/com/dlnahub/service/ContentBrowseService.java   # expect 0
cd backend && mvn test
```

Live: browse into a folder, search for a string that only matches something in a *different*
folder, and confirm you get no hits.
