package com.dlnahub.service;

import com.dlnahub.dlna.model.BrowsableItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentBrowseServiceTest {

    private static BrowsableItem item(String id, String title, String artist, String date, boolean container) {
        return item(id, title, artist, date, container, null);
    }

    private static BrowsableItem item(String id, String title, String artist, String date,
                                      boolean container, String effectiveDate) {
        return new BrowsableItem(
                id, "0", title, artist, null, null, null,
                container ? null : "video/mp4", null, null,
                container, null,
                container ? "object.container" : "object.item.videoItem",
                null, date, effectiveDate, null);
    }

    private static List<String> titles(List<BrowsableItem> items) {
        List<String> out = new ArrayList<>();
        for (BrowsableItem i : items) {
            out.add(i.getTitle());
        }
        return out;
    }

    private static List<BrowsableItem> mutable(List<BrowsableItem> items) {
        return new ArrayList<>(items);
    }

    @Test
    void sortByTitleIsCaseInsensitiveAscending() {
        List<BrowsableItem> items = mutable(List.of(
                item("1", "banana", null, null, false),
                item("2", "Apple", null, null, false),
                item("3", "cherry", null, null, false)));
        ContentBrowseService.sortItems(items, "dc:title");
        assertEquals(List.of("Apple", "banana", "cherry"), titles(items));
    }

    @Test
    void sortByTitleDescending() {
        List<BrowsableItem> items = mutable(List.of(
                item("1", "banana", null, null, false),
                item("2", "Apple", null, null, false),
                item("3", "cherry", null, null, false)));
        ContentBrowseService.sortItems(items, "-dc:title");
        assertEquals(List.of("cherry", "banana", "Apple"), titles(items));
    }

    @Test
    void sortByDateAscendingPutsMissingDatesLast() {
        List<BrowsableItem> items = mutable(List.of(
                item("1", "mid", null, "2020-09-07T19:34:42", false),
                item("2", "newest", null, "2023-06-09T13:40:01", false),
                item("3", "no date", null, null, false),
                item("4", "oldest", null, "2019-12-09T01:38:37", false)));
        ContentBrowseService.sortItems(items, "dc:date");
        assertEquals(List.of("oldest", "mid", "newest", "no date"), titles(items));
    }

    @Test
    void sortByDateDescendingKeepsMissingDatesLast() {
        List<BrowsableItem> items = mutable(List.of(
                item("1", "mid", null, "2020-09-07T19:34:42", false),
                item("2", "newest", null, "2023-06-09T13:40:01", false),
                item("3", "no date", null, null, false),
                item("4", "oldest", null, "2019-12-09T01:38:37", false)));
        ContentBrowseService.sortItems(items, "-dc:date");
        assertEquals(List.of("newest", "mid", "oldest", "no date"), titles(items));
    }

    @Test
    void sortByDateHandlesTimezoneOffsets() {
        List<BrowsableItem> items = mutable(List.of(
                item("1", "utc-naive", null, "2020-09-07T19:34:42", false),
                item("2", "utc-zulu", null, "2020-09-07T19:34:42Z", false),
                item("3", "plus-two", null, "2020-09-07T21:34:42+02:00", false),
                item("4", "earlier", null, "2020-09-07T05:00:00Z", false)));
        ContentBrowseService.sortItems(items, "dc:date");
        assertEquals(List.of("earlier", "utc-naive", "utc-zulu", "plus-two"), titles(items));
    }

    @Test
    void sortByCreatorSortsByArtistWithNullsLast() {
        List<BrowsableItem> items = mutable(List.of(
                item("1", "a", "Zed", null, false),
                item("2", "b", "amy", null, false),
                item("3", "c", null, null, false)));
        ContentBrowseService.sortItems(items, "dc:creator");
        assertEquals(List.of("b", "a", "c"), titles(items));
        ContentBrowseService.sortItems(items, "-dc:creator");
        assertEquals(List.of("a", "b", "c"), titles(items));
    }

    @Test
    void containersAreKeptBeforeItems() {
        List<BrowsableItem> items = mutable(List.of(
                item("1", "zeta", null, null, false),
                item("2", "Alpha", null, null, true),
                item("3", "beta", null, null, false),
                item("4", "Zulu", null, null, true)));
        ContentBrowseService.sortItems(items, "dc:title");
        assertEquals(List.of("Alpha", "Zulu", "beta", "zeta"), titles(items));
    }

    @Test
    void unknownCriterionFallsBackToTitle() {
        List<BrowsableItem> items = mutable(List.of(
                item("1", "banana", null, null, false),
                item("2", "Apple", null, null, false)));
        ContentBrowseService.sortItems(items, "dc:duration");
        assertEquals(List.of("Apple", "banana"), titles(items));
    }

    @Test
    void nullAndEmptySortByAreNoOps() {
        List<BrowsableItem> items = mutable(List.of(
                item("1", "banana", null, null, false),
                item("2", "Apple", null, null, false)));
        ContentBrowseService.sortItems(items, null);
        ContentBrowseService.sortItems(items, "");
        assertEquals(List.of("banana", "Apple"), titles(items));
        ContentBrowseService.sortItems(new ArrayList<>(), "dc:title");
    }

    @Test
    void parsesDatetimeWithoutTimezoneAsUtc() {
        assertEquals(Instant.parse("2020-09-07T19:34:42Z"), ContentBrowseService.parseDateInstant("2020-09-07T19:34:42"));
    }

    @Test
    void parsesDatetimeWithZuluSuffix() {
        assertEquals(Instant.parse("2020-09-07T19:34:42Z"), ContentBrowseService.parseDateInstant("2020-09-07T19:34:42Z"));
    }

    @Test
    void parsesDatetimeWithExplicitOffset() {
        assertEquals(Instant.parse("2020-09-07T19:34:42Z"), ContentBrowseService.parseDateInstant("2020-09-07T21:34:42+02:00"));
    }

    @Test
    void parsesDatetimeWithoutSeconds() {
        assertEquals(Instant.parse("2020-09-07T19:34:00Z"), ContentBrowseService.parseDateInstant("2020-09-07T19:34"));
    }

    @Test
    void parsesDateOnly() {
        assertEquals(Instant.parse("2020-09-07T00:00:00Z"), ContentBrowseService.parseDateInstant("2020-09-07"));
    }

    @Test
    void returnsNullForUnparseableDates() {
        assertNull(ContentBrowseService.parseDateInstant(null));
        assertNull(ContentBrowseService.parseDateInstant(""));
        assertNull(ContentBrowseService.parseDateInstant("   "));
        assertNull(ContentBrowseService.parseDateInstant("not-a-date"));
        assertNull(ContentBrowseService.parseDateInstant("07/09/2020"));
    }

    @Test
    void effectiveDateTakesLatestOfChildFilesAndSubContainers() {
        List<BrowsableItem> children = List.of(
                item("f1", "clip", null, "2020-01-01T00:00:00Z", false),
                item("f2", "movie", null, "2023-06-09T13:40:01Z", false),
                item("c1", "show", null, null, true),
                item("c2", "empty show", null, null, true));
        java.util.Map<String, Instant> subDates = new java.util.HashMap<>();
        subDates.put("c1", Instant.parse("2022-05-05T00:00:00Z"));
        // c2 has no known effective date (e.g. not crawled yet or no media)

        assertEquals(Instant.parse("2023-06-09T13:40:01Z"),
                ContentBrowseService.effectiveDate(children, subDates));
    }

    @Test
    void effectiveDateIsNullWhenNoDescendantHasADate() {
        List<BrowsableItem> children = List.of(
                item("f1", "no date", null, null, false),
                item("c1", "no date", null, null, true));
        assertNull(ContentBrowseService.effectiveDate(children, null));
        assertNull(ContentBrowseService.effectiveDate(List.of(), new java.util.HashMap<>()));
    }

    @Test
    void sortDatePrefersEffectiveDateForContainers() {
        BrowsableItem containerWithEffective = item("c1", "show", null, null, true, "2024-03-01T12:00:00Z");
        BrowsableItem containerWithoutEffective = item("c2", "older", null, "2019-01-01T00:00:00Z", true);
        BrowsableItem file = item("f1", "clip", null, "2021-07-07T00:00:00Z", false);

        assertEquals(Instant.parse("2024-03-01T12:00:00Z"), ContentBrowseService.sortDate(containerWithEffective));
        assertEquals(Instant.parse("2019-01-01T00:00:00Z"), ContentBrowseService.sortDate(containerWithoutEffective));
        assertEquals(Instant.parse("2021-07-07T00:00:00Z"), ContentBrowseService.sortDate(file));
        assertNull(ContentBrowseService.sortDate(item("f2", "none", null, null, false)));
    }

    @Test
    void sortByDateDescendingUsesEffectiveDateForContainers() {
        List<BrowsableItem> items = mutable(List.of(
                item("c1", "Old show", null, null, true, "2019-02-01T00:00:00Z"),
                item("f1", "clip", null, "2023-06-09T13:40:01Z", false),
                item("c2", "New show", null, null, true, "2025-01-15T00:00:00Z"),
                item("c3", "Empty show", null, null, true)));
        ContentBrowseService.sortItems(items, "-dc:date");
        assertEquals(List.of("New show", "clip", "Old show", "Empty show"), titles(items));

        items = mutable(List.of(
                item("c1", "Old show", null, null, true, "2019-02-01T00:00:00Z"),
                item("f1", "clip", null, "2023-06-09T13:40:01Z", false),
                item("c2", "New show", null, null, true, "2025-01-15T00:00:00Z"),
                item("c3", "Empty show", null, null, true)));
        ContentBrowseService.sortItems(items, "dc:date");
        assertEquals(List.of("Old show", "clip", "New show", "Empty show"), titles(items));
    }

    @Test
    void withEffectiveDateCopiesItemAndSetsDate() {
        BrowsableItem original = item("c1", "show", "Artist", null, true);
        BrowsableItem enriched = ContentBrowseService.withEffectiveDate(
                original, Instant.parse("2024-03-01T12:00:00Z"));

        assertEquals("c1", enriched.getId());
        assertEquals("show", enriched.getTitle());
        assertEquals("Artist", enriched.getArtist());
        assertTrue(enriched.isContainer());
        assertNull(original.getEffectiveDate());
        assertEquals("2024-03-01T12:00:00Z", enriched.getEffectiveDate());
    }

    @Test
    void isFreshWhenComputedUnderCurrentSystemUpdateId() {
        long now = System.currentTimeMillis();
        var fresh = new ContentBrowseService.ContainerDateEntry(
                "63593", Instant.parse("2024-01-01T00:00:00Z"), now - 3 * 60 * 60 * 1000L);

        // Matching updateId is fresh no matter how old the entry is.
        assertTrue(ContentBrowseService.isFresh(fresh, "63593", now));
        assertFalse(ContentBrowseService.isFresh(null, "63593", now));
    }

    @Test
    void isFreshToleratesSystemUpdateIdChurnWithinGrace() {
        long now = System.currentTimeMillis();
        // NAS bumped the id since this entry was computed, but the entry is young.
        var churned = new ContentBrowseService.ContainerDateEntry(
                "63592", Instant.parse("2024-01-01T00:00:00Z"), now - 60_000L);
        var churnedLongAgo = new ContentBrowseService.ContainerDateEntry(
                "63592", Instant.parse("2024-01-01T00:00:00Z"), now - 3 * 60 * 1000L);

        // Id changed but entry is within the 2-minute grace: still usable (stale-while-revalidate).
        assertTrue(ContentBrowseService.isFresh(churned, "63593", now));
        // Id changed and the grace has passed: stale, must be re-crawled.
        assertFalse(ContentBrowseService.isFresh(churnedLongAgo, "63593", now));
    }

    @Test
    void isFreshFallsBackToTtlWithoutSystemUpdateId() {
        long now = System.currentTimeMillis();
        var recent = new ContentBrowseService.ContainerDateEntry(null, null, now - 60_000L);
        var expired = new ContentBrowseService.ContainerDateEntry(null, null, now - 11 * 60_000L);

        assertTrue(ContentBrowseService.isFresh(recent, null, now));
        assertTrue(ContentBrowseService.isFresh(recent, "", now));
        assertFalse(ContentBrowseService.isFresh(expired, null, now));
    }

    @Test
    void isDateSortOnlyMatchesDateCriteria() {
        assertTrue(ContentBrowseService.isDateSort("dc:date"));
        assertTrue(ContentBrowseService.isDateSort("-dc:date"));
        assertFalse(ContentBrowseService.isDateSort("dc:title"));
        assertFalse(ContentBrowseService.isDateSort("-dc:title"));
        assertFalse(ContentBrowseService.isDateSort("dc:creator"));
        assertFalse(ContentBrowseService.isDateSort(null));
        assertFalse(ContentBrowseService.isDateSort(""));
    }
}
