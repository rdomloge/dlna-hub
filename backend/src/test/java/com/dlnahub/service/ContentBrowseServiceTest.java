package com.dlnahub.service;

import com.dlnahub.dlna.model.BrowsableItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContentBrowseServiceTest {

    private static BrowsableItem item(String id, String title, String artist, String date, boolean container) {
        return new BrowsableItem(
                id, "0", title, artist, null, null, null,
                container ? null : "video/mp4", null, null,
                container, null,
                container ? "object.container" : "object.item.videoItem",
                null, date, null);
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
}
