# Step 04 — Flag subtitle availability on the item

**Phase:** 1 — The subtitle backend
**Files:** `backend/src/main/java/com/dlnahub/dlna/model/BrowsableItem.java`,
`backend/src/main/java/com/dlnahub/controller/BrowseController.java`
**Depends on:** step-02

## Problem

Two thirds of the library has no subtitle. Without a flag the UI has two bad options: show
a subtitle button on everything and let a third of them work, or fetch cues for every item
on the browse page to find out. The first is confusing, the second is 50 HEAD requests to
the NAS per page.

## Change

### The narrow version — metadata only

Add `subtitleAvailable` to `BrowsableItem` as a **nullable `Boolean`**:

```java
private final Boolean subtitleAvailable;   // null = not checked, not "no"
```

`null` serialises as JSON `null`, which the frontend reads as "unknown". That distinction is
what keeps this cheap: browse listings leave it `null`, and only the metadata endpoint —
one item, fetched when the user has already committed to playing something — pays for the
check.

In `BrowseController.metadata`, after `browseMetadata` returns and before responding:

```java
BrowsableItem item = items.get(0);
if (!item.isContainer() && item.getResourceName() != null) {
    item = item.withSubtitleAvailable(subtitleService.isAvailable(item.getResourceName()));
}
return item;
```

`BrowsableItem` is immutable with a long constructor, so add a copy method next to the
getters rather than a setter:

```java
public BrowsableItem withSubtitleAvailable(boolean available) {
    return new BrowsableItem(id, parentId, title, artist, album, duration, resolution,
            mimeType, size, protocolInfo, isContainer, thumbnailUrl, classType,
            description, date, effectiveDate, resourceName, available);
}
```

Every existing call site needs the new trailing argument. There is one construction site in
`ContentBrowseService.parseItem` — pass `null` there.

### Why not do it on browse listings

Because it costs one HTTP request per item against the NAS, on the hot path, for
information the user has not asked for yet. `ContentBrowseService` already carries one
expensive per-item enrichment (`enrichContainerDates`) that is deliberately gated to the
one sort that needs it, and the comment there is explicit about keeping it that way. Do not
add a second ungated one.

If a subtitle badge in the browse list is genuinely wanted later, the honest design is a
separate opt-in endpoint that takes a list of item IDs and checks them concurrently — plan
it then, with a caller that actually needs it.

## Do not

- Do not make `subtitleAvailable` a primitive `boolean`. Defaulting to `false` on browse
  listings would tell the UI "this film has no subtitles" about films that do.
- Do not call `subtitleService.isAvailable` from `parseItem`. That runs once per item per
  browse.
- Do not fetch and parse the cues here — `isAvailable` is a HEAD, and the panel fetches the
  cues when it opens.
- Do not add the field to `PlayRequestDto` or the DIDL sent to the renderer. Step-01
  established the renderer does not care.

## Verify

```bash
cd backend && mvn test
```

`ContentBrowseServiceTest` constructs `BrowsableItem` — update those call sites and confirm
the existing assertions still pass unchanged.

Add:

- `metadata_itemWithSubtitle_reportsAvailableTrue`
- `metadata_itemWithoutSubtitle_reportsAvailableFalse`
- `metadata_container_leavesSubtitleAvailableNull`

Then live:

```bash
cd backend && mvn spring-boot:run
```

```bash
SRV=b31ca191-a364-38bd-b881-10e0d2460e16

# Street Kings — has one
curl -s "http://localhost:9100/api/servers/$SRV/browse/44%24%4038318/metadata" \
  | grep -o '"subtitleAvailable":[a-z]*'
# expect: "subtitleAvailable":true

# A browse listing must NOT have paid for the check
curl -s "http://localhost:9100/api/servers/$SRV/browse?objectId=44%2413350&count=5" \
  | grep -o '"subtitleAvailable":[a-z]*' | sort -u
# expect: "subtitleAvailable":null  (and the response should come back as fast as before)
```

Time the browse call before and after this step. If the listing got slower, the check has
leaked onto the hot path and the step is wrong.
