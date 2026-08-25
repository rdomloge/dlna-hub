package com.dlnahub.service;

import com.dlnahub.dlna.model.BrowsableItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    void sortItems_mixedCaseTitles_ascendingSortedCaseInsensitive() {
        // given
        List<BrowsableItem> items = mutable(List.of(
                item("1", "banana", null, null, false),
                item("2", "Apple", null, null, false),
                item("3", "cherry", null, null, false)));

        // when
        ContentBrowseService.sortItems(items, "dc:title");

        // then
        assertEquals(List.of("Apple", "banana", "cherry"), titles(items));
    }

    @Test
    void sortItems_mixedCaseTitles_descendingSortedCaseInsensitive() {
        // given
        List<BrowsableItem> items = mutable(List.of(
                item("1", "banana", null, null, false),
                item("2", "Apple", null, null, false),
                item("3", "cherry", null, null, false)));

        // when
        ContentBrowseService.sortItems(items, "-dc:title");

        // then
        assertEquals(List.of("cherry", "banana", "Apple"), titles(items));
    }

    @Test
    void sortItems_missingDates_ascendingSortedLast() {
        // given
        List<BrowsableItem> items = mutable(List.of(
                item("1", "mid", null, "2020-09-07T19:34:42", false),
                item("2", "newest", null, "2023-06-09T13:40:01", false),
                item("3", "no date", null, null, false),
                item("4", "oldest", null, "2019-12-09T01:38:37", false)));

        // when
        ContentBrowseService.sortItems(items, "dc:date");

        // then
        assertEquals(List.of("oldest", "mid", "newest", "no date"), titles(items));
    }

    @Test
    void sortItems_missingDates_descendingRemainLast() {
        // given
        List<BrowsableItem> items = mutable(List.of(
                item("1", "mid", null, "2020-09-07T19:34:42", false),
                item("2", "newest", null, "2023-06-09T13:40:01", false),
                item("3", "no date", null, null, false),
                item("4", "oldest", null, "2019-12-09T01:38:37", false)));

        // when
        ContentBrowseService.sortItems(items, "-dc:date");

        // then
        assertEquals(List.of("newest", "mid", "oldest", "no date"), titles(items));
    }

    @Test
    void sortItems_mixedTimezones_ascendingSortedChronologically() {
        // given
        List<BrowsableItem> items = mutable(List.of(
                item("1", "utc-naive", null, "2020-09-07T19:34:42", false),
                item("2", "utc-zulu", null, "2020-09-07T19:34:42Z", false),
                item("3", "plus-two", null, "2020-09-07T21:34:42+02:00", false),
                item("4", "earlier", null, "2020-09-07T05:00:00Z", false)));

        // when
        ContentBrowseService.sortItems(items, "dc:date");

        // then
        assertEquals(List.of("earlier", "utc-naive", "utc-zulu", "plus-two"), titles(items));
    }

    @Test
    void sortItems_nullArtist_ascendingCreatorSortedLast() {
        // given
        List<BrowsableItem> items = mutable(List.of(
                item("1", "a", "Zed", null, false),
                item("2", "b", "amy", null, false),
                item("3", "c", null, null, false)));

        // when
        ContentBrowseService.sortItems(items, "dc:creator");

        // then
        assertEquals(List.of("b", "a", "c"), titles(items));
    }

    @Test
    void sortItems_nullArtist_descendingCreatorSortedLast() {
        // given
        List<BrowsableItem> items = mutable(List.of(
                item("1", "a", "Zed", null, false),
                item("2", "b", "amy", null, false),
                item("3", "c", null, null, false)));

        // when
        ContentBrowseService.sortItems(items, "-dc:creator");

        // then
        assertEquals(List.of("a", "b", "c"), titles(items));
    }

    @Test
    void sortItems_mixedContainersAndItems_containersFirst() {
        // given
        List<BrowsableItem> items = mutable(List.of(
                item("1", "zeta", null, null, false),
                item("2", "Alpha", null, null, true),
                item("3", "beta", null, null, false),
                item("4", "Zulu", null, null, true)));

        // when
        ContentBrowseService.sortItems(items, "dc:title");

        // then
        assertEquals(List.of("Alpha", "Zulu", "beta", "zeta"), titles(items));
    }

    @Test
    void sortItems_unknownCriterion_fallsBackToTitle() {
        // given
        List<BrowsableItem> items = mutable(List.of(
                item("1", "banana", null, null, false),
                item("2", "Apple", null, null, false)));

        // when
        ContentBrowseService.sortItems(items, "dc:duration");

        // then
        assertEquals(List.of("Apple", "banana"), titles(items));
    }

    @Test
    void sortItems_nullOrEmptySortBy_listUnchanged() {
        // given
        List<BrowsableItem> items = mutable(List.of(
                item("1", "banana", null, null, false),
                item("2", "Apple", null, null, false)));

        // when
        ContentBrowseService.sortItems(items, null);
        ContentBrowseService.sortItems(items, "");

        // then
        assertEquals(List.of("banana", "Apple"), titles(items));
    }

    @Test
    void sortItems_emptyList_sortedWithoutError() {
        // given
        List<BrowsableItem> items = new ArrayList<>();

        // when
        ContentBrowseService.sortItems(items, "dc:title");

        // then
        assertTrue(items.isEmpty());
    }

    @Test
    void parseDateInstant_dateTimeWithoutTimezone_parsedAsUtc() {
        // given
        String value = "2020-09-07T19:34:42";

        // when
        Instant result = ContentBrowseService.parseDateInstant(value);

        // then
        assertEquals(Instant.parse("2020-09-07T19:34:42Z"), result);
    }

    @Test
    void parseDateInstant_zuluSuffix_parsed() {
        // given
        String value = "2020-09-07T19:34:42Z";

        // when
        Instant result = ContentBrowseService.parseDateInstant(value);

        // then
        assertEquals(Instant.parse("2020-09-07T19:34:42Z"), result);
    }

    @Test
    void parseDateInstant_explicitOffset_normalizedToUtc() {
        // given
        String value = "2020-09-07T21:34:42+02:00";

        // when
        Instant result = ContentBrowseService.parseDateInstant(value);

        // then
        assertEquals(Instant.parse("2020-09-07T19:34:42Z"), result);
    }

    @Test
    void parseDateInstant_noSecondsAssumedZero() {
        // given
        String value = "2020-09-07T19:34";

        // when
        Instant result = ContentBrowseService.parseDateInstant(value);

        // then
        assertEquals(Instant.parse("2020-09-07T19:34:00Z"), result);
    }

    @Test
    void parseDateInstant_dateOnly_midnightUtc() {
        // given
        String value = "2020-09-07";

        // when
        Instant result = ContentBrowseService.parseDateInstant(value);

        // then
        assertEquals(Instant.parse("2020-09-07T00:00:00Z"), result);
    }

    @Test
    void parseDateInstant_nullEmptyOrUnparseable_returnsNull() {
        // given

        // when
        Instant nullInput = ContentBrowseService.parseDateInstant(null);
        Instant emptyInput = ContentBrowseService.parseDateInstant("");
        Instant blankInput = ContentBrowseService.parseDateInstant("   ");
        Instant notADate = ContentBrowseService.parseDateInstant("not-a-date");
        Instant dmyFormat = ContentBrowseService.parseDateInstant("07/09/2020");

        // then
        assertNull(nullInput);
        assertNull(emptyInput);
        assertNull(blankInput);
        assertNull(notADate);
        assertNull(dmyFormat);
    }

    @Test
    void effectiveDate_mixedChildrenAndSubContainers_returnsLatest() {
        // given
        List<BrowsableItem> children = List.of(
                item("f1", "clip", null, "2020-01-01T00:00:00Z", false),
                item("f2", "movie", null, "2023-06-09T13:40:01Z", false),
                item("c1", "show", null, null, true),
                item("c2", "empty show", null, null, true));
        Map<String, Instant> subDates = new java.util.HashMap<>();
        subDates.put("c1", Instant.parse("2022-05-05T00:00:00Z"));
        // c2 has no known effective date (e.g. not crawled yet or no media)

        // when
        Instant result = ContentBrowseService.effectiveDate(children, subDates);

        // then
        assertEquals(Instant.parse("2023-06-09T13:40:01Z"), result);
    }

    @Test
    void effectiveDate_noDescendantHasDate_returnsNull() {
        // given
        List<BrowsableItem> children = List.of(
                item("f1", "no date", null, null, false),
                item("c1", "no date", null, null, true));

        // when
        Instant noMap = ContentBrowseService.effectiveDate(children, null);
        Instant noChildren = ContentBrowseService.effectiveDate(List.of(), new java.util.HashMap<>());

        // then
        assertNull(noMap);
        assertNull(noChildren);
    }

    @Test
    void sortDate_containerWithEffectiveDate_returnsEffectiveDate() {
        // given
        BrowsableItem container = item("c1", "show", null, null, true, "2024-03-01T12:00:00Z");

        // when
        Instant result = ContentBrowseService.sortDate(container);

        // then
        assertEquals(Instant.parse("2024-03-01T12:00:00Z"), result);
    }

    @Test
    void sortDate_containerWithoutEffectiveDate_returnsOwnDate() {
        // given
        BrowsableItem container = item("c2", "older", null, "2019-01-01T00:00:00Z", true);

        // when
        Instant result = ContentBrowseService.sortDate(container);

        // then
        assertEquals(Instant.parse("2019-01-01T00:00:00Z"), result);
    }

    @Test
    void sortDate_fileItem_returnsOwnDate() {
        // given
        BrowsableItem file = item("f1", "clip", null, "2021-07-07T00:00:00Z", false);

        // when
        Instant result = ContentBrowseService.sortDate(file);

        // then
        assertEquals(Instant.parse("2021-07-07T00:00:00Z"), result);
    }

    @Test
    void sortDate_itemWithoutDate_returnsNull() {
        // given
        BrowsableItem noDate = item("f2", "none", null, null, false);

        // when
        Instant result = ContentBrowseService.sortDate(noDate);

        // then
        assertNull(result);
    }

    @Test
    void sortItems_effectiveDates_descendingDateSortedByEffectiveDate() {
        // given
        List<BrowsableItem> items = mutable(List.of(
                item("c1", "Old show", null, null, true, "2019-02-01T00:00:00Z"),
                item("f1", "clip", null, "2023-06-09T13:40:01Z", false),
                item("c2", "New show", null, null, true, "2025-01-15T00:00:00Z"),
                item("c3", "Empty show", null, null, true)));

        // when
        ContentBrowseService.sortItems(items, "-dc:date");

        // then
        assertEquals(List.of("New show", "clip", "Old show", "Empty show"), titles(items));
    }

    @Test
    void sortItems_effectiveDates_ascendingDateSortedByEffectiveDate() {
        // given
        List<BrowsableItem> items = mutable(List.of(
                item("c1", "Old show", null, null, true, "2019-02-01T00:00:00Z"),
                item("f1", "clip", null, "2023-06-09T13:40:01Z", false),
                item("c2", "New show", null, null, true, "2025-01-15T00:00:00Z"),
                item("c3", "Empty show", null, null, true)));

        // when
        ContentBrowseService.sortItems(items, "dc:date");

        // then
        assertEquals(List.of("Old show", "clip", "New show", "Empty show"), titles(items));
    }

    @Test
    void withEffectiveDate_originalItem_copiesItemAndSetsDate() {
        // given
        BrowsableItem original = item("c1", "show", "Artist", null, true);
        Instant date = Instant.parse("2024-03-01T12:00:00Z");

        // when
        BrowsableItem enriched = ContentBrowseService.withEffectiveDate(original, date);

        // then
        assertEquals("c1", enriched.getId());
        assertEquals("show", enriched.getTitle());
        assertEquals("Artist", enriched.getArtist());
        assertTrue(enriched.isContainer());
        assertNull(original.getEffectiveDate());
        assertEquals("2024-03-01T12:00:00Z", enriched.getEffectiveDate());
    }

    @Test
    void isFresh_matchingUpdateId_fresh() {
        // given
        long now = System.currentTimeMillis();
        var entry = new ContentBrowseService.ContainerDateEntry(
                "63593", Instant.parse("2024-01-01T00:00:00Z"), now - 3 * 60 * 60 * 1000L);

        // when
        // Matching updateId is fresh no matter how old the entry is.
        boolean result = ContentBrowseService.isFresh(entry, "63593", now);

        // then
        assertTrue(result);
    }

    @Test
    void isFresh_nullEntry_notFresh() {
        // given
        long now = System.currentTimeMillis();

        // when
        boolean result = ContentBrowseService.isFresh(null, "63593", now);

        // then
        assertFalse(result);
    }

    @Test
    void isFresh_updateIdChurnedWithinGrace_stillFresh() {
        // given
        long now = System.currentTimeMillis();
        // NAS bumped the id since this entry was computed, but the entry is young.
        var entry = new ContentBrowseService.ContainerDateEntry(
                "63592", Instant.parse("2024-01-01T00:00:00Z"), now - 60_000L);

        // when
        // Id changed but entry is within the 2-minute grace: still usable (stale-while-revalidate).
        boolean result = ContentBrowseService.isFresh(entry, "63593", now);

        // then
        assertTrue(result);
    }

    @Test
    void isFresh_updateIdChurnedBeyondGrace_stale() {
        // given
        long now = System.currentTimeMillis();
        var entry = new ContentBrowseService.ContainerDateEntry(
                "63592", Instant.parse("2024-01-01T00:00:00Z"), now - 3 * 60 * 1000L);

        // when
        // Id changed and the grace has passed: stale, must be re-crawled.
        boolean result = ContentBrowseService.isFresh(entry, "63593", now);

        // then
        assertFalse(result);
    }

    @Test
    void isFresh_noUpdateIdWithinTtl_fresh() {
        // given
        long now = System.currentTimeMillis();
        var recent = new ContentBrowseService.ContainerDateEntry(null, null, now - 60_000L);

        // when
        boolean result = ContentBrowseService.isFresh(recent, null, now);

        // then
        assertTrue(result);
    }

    @Test
    void isFresh_emptyUpdateIdWithinTtl_fresh() {
        // given
        long now = System.currentTimeMillis();
        var recent = new ContentBrowseService.ContainerDateEntry(null, null, now - 60_000L);

        // when
        boolean result = ContentBrowseService.isFresh(recent, "", now);

        // then
        assertTrue(result);
    }

    @Test
    void isFresh_noUpdateIdExpiredTtl_stale() {
        // given
        long now = System.currentTimeMillis();
        var expired = new ContentBrowseService.ContainerDateEntry(null, null, now - 11 * 60_000L);

        // when
        boolean result = ContentBrowseService.isFresh(expired, null, now);

        // then
        assertFalse(result);
    }

    @Test
    void isDateSort_variousCriteria_onlyDateCriteriaMatch() {
        // given

        // when
        boolean date = ContentBrowseService.isDateSort("dc:date");
        boolean negatedDate = ContentBrowseService.isDateSort("-dc:date");
        boolean title = ContentBrowseService.isDateSort("dc:title");
        boolean negatedTitle = ContentBrowseService.isDateSort("-dc:title");
        boolean creator = ContentBrowseService.isDateSort("dc:creator");
        boolean nullInput = ContentBrowseService.isDateSort(null);
        boolean emptyInput = ContentBrowseService.isDateSort("");

        // then
        assertTrue(date);
        assertTrue(negatedDate);
        assertFalse(title);
        assertFalse(negatedTitle);
        assertFalse(creator);
        assertFalse(nullInput);
        assertFalse(emptyInput);
    }

    @Test
    void extractMimeType_validProtocolInfo_returnsMimeType() {
        // given
        String video = "http-get:*:video/x-matroska:DLNA.ORG_PN=AVC_MKV";
        String audio = "http-get:*:audio/mpeg:*";
        String image = "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN";

        // when
        String videoType = ContentBrowseService.extractMimeType(video);
        String audioType = ContentBrowseService.extractMimeType(audio);
        String imageType = ContentBrowseService.extractMimeType(image);

        // then
        assertEquals("video/x-matroska", videoType);
        assertEquals("audio/mpeg", audioType);
        assertEquals("image/jpeg", imageType);
    }

    @Test
    void extractMimeType_nullOrMalformedProtocolInfo_returnsNull() {
        // given

        // when
        String nullResult = ContentBrowseService.extractMimeType(null);
        String empty = ContentBrowseService.extractMimeType("http-get:*");
        String star = ContentBrowseService.extractMimeType("http-get:*:*:*");

        // then
        assertNull(nullResult);
        assertNull(empty);
        assertNull(star);
    }
}
