package com.dlnahub.service;

import com.dlnahub.dlna.UpnpServiceManager;
import com.dlnahub.dlna.model.BrowsableItem;
import com.dlnahub.dlna.model.BrowseResult;
import com.dlnahub.dlna.model.DateEvent;
import org.jupnp.controlpoint.ActionCallback;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.meta.Action;
import org.jupnp.model.meta.ActionArgument;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.model.meta.StateVariable;
import org.jupnp.model.meta.StateVariableTypeDetails;
import org.jupnp.model.types.Datatype;
import org.jupnp.model.types.UDAServiceType;
import org.jupnp.model.types.ServiceId;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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

    // ------------------------------------------------------------------------
    // Effective-date stream (SSE) tests
    // ------------------------------------------------------------------------

    /**
     * Builds DIDL-Lite XML for a single item or container, as a fake ContentDirectory Browse
     * response would return it.
     */
    private static String didl(String id, String parent, String title, String classType, String date) {
        boolean container = classType.startsWith("object.container");
        StringBuilder sb = new StringBuilder();
        sb.append(container ? "<container " : "<item ")
                .append("id=\"").append(id).append('"')
                .append(" parentID=\"").append(parent).append('"')
                .append(">\n");
        sb.append("<dc:title xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")
                .append(title).append("</dc:title>\n");
        sb.append("<upnp:class xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/\">")
                .append(classType).append("</upnp:class>\n");
        if (date != null) {
            sb.append("<dc:date xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")
                    .append(date).append("</dc:date>\n");
        }
        sb.append(container ? "</container>\n" : "</item>\n");
        return sb.toString();
    }

    /**
     * A fake ContentDirectory device backed by an in-memory container tree. Each key is a
     * container id; the value is the DIDL-Lite for its direct children.
     */
    private static final class FakeServer {
        final Map<String, String> childrenXml = new LinkedHashMap<>();
        final AtomicInteger browseCalls = new AtomicInteger();
        /** Optional hook invoked with the objectID of every Browse call (lets a test pause a crawl). */
        volatile Consumer<String> onBrowse;

        /**
         * Stores the container's direct children, wrapped in a {@code <DIDL-Lite>} root so the
         * concatenated item/container elements form well-formed XML (real servers return a single
         * DIDL-Lite document per Browse).
         */
        void put(String containerId, String children) {
            childrenXml.put(containerId,
                    "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-1\">"
                            + children
                            + "</DIDL-Lite>");
        }
    }

    /**
     * Wires a {@link ContentBrowseService} whose {@code createCallback} is replaced with a fake
     * that answers Browse and GetSystemUpdateID from an in-memory tree, so no real UPnP traffic
     * is needed.
     */
    private static ActionArgument arg(String name, boolean in) {
        // The related state variable is named after the argument so the service can resolve a
        // STRING datatype for it (jUPnP looks up datatypes via the related state variable).
        return new ActionArgument(name, name, in ? ActionArgument.Direction.IN : ActionArgument.Direction.OUT);
    }

    private static Action browseAction() {
        return new Action("Browse", new ActionArgument[] {
                arg("objectID", true), arg("browseFlag", true), arg("Filter", true),
                arg("StartingIndex", true), arg("RequestedCount", true), arg("SortCriteria", true),
                arg("Result", false), arg("NumberReturned", false),
                arg("TotalMatches", false), arg("UpdateID", false)
        });
    }

    private static Action updateIdAction() {
        return new Action("GetSystemUpdateID", new ActionArgument[] { arg("Id", false) });
    }

    private static StateVariable sv(String name) {
        return new StateVariable(name, new StateVariableTypeDetails(Datatype.Builtin.STRING.getDatatype()));
    }

    /**
     * Builds a real (mocked-control) ContentDirectory service whose actions and state variables are
     * wired the way jUPnP expects, so {@code ActionInvocation.setInput}/{@code getOutput} work.
     */
    private static RemoteService fakeContentDirectory() {
        try {
            return new RemoteService(
                    new UDAServiceType("ContentDirectory"),
                    new ServiceId("urn-upnp-org-serviceid-ContentDirectory", "ContentDirectory"),
                    URI.create("http://nas/desc.xml"),
                    URI.create("http://nas/control"),
                    URI.create("http://nas/event"),
                    new Action[] { browseAction(), updateIdAction() },
                    new StateVariable[] {
                            sv("objectID"), sv("browseFlag"), sv("Filter"), sv("StartingIndex"),
                            sv("RequestedCount"), sv("SortCriteria"), sv("Result"), sv("NumberReturned"),
                            sv("TotalMatches"), sv("UpdateID"), sv("Id")
                    });
        } catch (org.jupnp.model.ValidationException e) {
            throw new IllegalStateException("could not build fake ContentDirectory service", e);
        }
    }

    private static ContentBrowseService serviceWithFakeServer(FakeServer server) {
        RemoteDevice device = Mockito.mock(RemoteDevice.class);
        RemoteService contentDir = fakeContentDirectory();
        when(device.findService(any(UDAServiceType.class))).thenReturn(contentDir);

        ServerBrowseService serverBrowseService = Mockito.mock(ServerBrowseService.class);
        when(serverBrowseService.getDevice("srv")).thenReturn(device);
        ThumbnailService thumbnailService = Mockito.mock(ThumbnailService.class);
        UpnpServiceManager upnpServiceManager = Mockito.mock(UpnpServiceManager.class);

        return new ContentBrowseService(serverBrowseService, thumbnailService, upnpServiceManager) {
            @Override
            protected ActionCallback createCallback(ActionInvocation invocation) {
                String actionName = invocation.getAction().getName();
                if ("Browse".equals(actionName)) {
                    String objectID = String.valueOf(invocation.getInput("objectID").getValue());
                    server.browseCalls.incrementAndGet();
                    if (server.onBrowse != null) {
                        server.onBrowse.accept(objectID);
                    }
                    String xml = server.childrenXml.getOrDefault(objectID, "");
                    try {
                        invocation.setOutput("Result", xml);
                        invocation.setOutput("TotalMatches", "0");
                        invocation.setOutput("UpdateID", "1");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                } else if ("GetSystemUpdateID".equals(actionName)) {
                    try {
                        invocation.setOutput("Id", "63593");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                // No-op callback: the outputs (or failure) are already set on the invocation.
                return new ActionCallback(invocation) {
                    @Override
                    public void run() {
                    }

                    @Override
                    public void success(ActionInvocation invocation) {
                    }

                    @Override
                    public void failure(ActionInvocation invocation,
                                        org.jupnp.model.message.UpnpResponse operation, String defaultMsg) {
                    }
                };
            }
        };
    }

    /** A sink that records events. */
    private static final class CollectingSink implements ContentBrowseService.DateStreamSink {
        final List<DateEvent> events = new ArrayList<>();
        int failAfter = -1; // throw IOException once this many sends have succeeded

        @Override
        public void send(DateEvent event) throws IOException {
            if (failAfter >= 0 && events.size() >= failAfter) {
                throw new IOException("client gone");
            }
            events.add(event);
        }
    }

    /**
     * Runs the stream job synchronously on the calling thread. {@code runDateStream} is already
     * synchronous (only {@code openDateStream} dispatches to a pool), so this just invokes it
     * directly.
     */
    private static void runStreamSync(ContentBrowseService svc, CollectingSink sink) throws IOException {
        svc.runDateStream("srv", "0", sink);
    }

    @Test
    void runDateStream_mixedKnownAndUnknown_emitsForAllContainersThenTerminal() throws Exception {
        // given
        FakeServer server = new FakeServer();
        // Folder 0 holds two containers and one loose dated file.
        server.put("0",
                didl("c1", "0", "Known", "object.container", null) +
                        didl("c2", "0", "Unknown", "object.container", null) +
                        didl("f1", "0", "clip", "object.item.videoItem", "2020-01-01T00:00:00Z"));
        // Pre-populate the cache so c1 is already fresh (known); c2 is unknown.
        ContentBrowseService svc = serviceWithFakeServer(server);
        // c1's subtree has a dated file; c2's subtree has a dated file too.
        server.put("c1", didl("c1f", "c1", "movie", "object.item.videoItem", "2021-05-05T00:00:00Z"));
        server.put("c2", didl("c2f", "c2", "movie", "object.item.videoItem", "2022-06-06T00:00:00Z"));
        // Warm c1 into the cache via the enrichment path so the stream emits it from cache
        // (c2 stays unknown and will be crawled).
        svc.enrichContainerDates("srv", List.of(item("c1", "Known", null, null, true)));
        CollectingSink sink = new CollectingSink();

        // when
        runStreamSync(svc, sink);

        // then
        // c1 is emitted from cache (already fresh), c2 is emitted from the fresh crawl,
        // followed by the terminal allDone event.
        assertEquals(3, sink.events.size(), "one event from cache (c1), one crawled (c2), and the terminal");
        // Events are emitted in children order: c1 first (from cache), then c2 (crawled).
        assertEquals("c1", sink.events.get(0).id(), "cached container c1 is emitted first");
        assertEquals("2021-05-05T00:00:00Z", sink.events.get(0).effectiveDate());
        assertTrue(sink.events.get(0).complete());
        assertFalse(sink.events.get(0).allDone());
        assertEquals("c2", sink.events.get(1).id(), "crawled container c2 is emitted second");
        assertEquals("2022-06-06T00:00:00Z", sink.events.get(1).effectiveDate());
        assertTrue(sink.events.get(1).complete());
        assertFalse(sink.events.get(1).allDone());
        assertTrue(sink.events.get(2).allDone(), "last event is the terminal allDone");
    }

    @Test
    void runDateStream_unknownSubtree_emitsTrueSubtreeMaxDate() throws Exception {
        // given
        FakeServer server = new FakeServer();
        server.put("0", didl("c1", "0", "Show", "object.container", null));
        server.put("c1",
                didl("a", "c1", "old", "object.item.videoItem", "2019-01-01T00:00:00Z") +
                        didl("b", "c1", "new", "object.item.videoItem", "2026-08-26T20:52:43Z"));
        ContentBrowseService svc = serviceWithFakeServer(server);
        CollectingSink sink = new CollectingSink();

        // when
        runStreamSync(svc, sink);

        // then
        assertEquals(2, sink.events.size());
        assertEquals("c1", sink.events.get(0).id());
        assertEquals("2026-08-26T20:52:43Z", sink.events.get(0).effectiveDate(),
                "the container's effective date is the latest descendant date");
        assertTrue(sink.events.get(0).complete());
        assertTrue(sink.events.get(1).allDone());
    }

    @Test
    void runDateStream_uncappedBudget_completesSubtreeLargerThanRequestCap() throws Exception {
        // given
        FakeServer server = new FakeServer();
        server.put("0", didl("big", "0", "BigShow", "object.container", null));
        // Build a subtree of 2_200 dated items — more than the blocking request cap (2_000).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2_200; i++) {
            String date = String.format("2020-01-01T%02d:%02d:%02dZ", i / 3600 % 24, i / 60 % 60, i % 60);
            sb.append(didl("i" + i, "big", "ep" + i, "object.item.videoItem", date));
        }
        server.put("big", sb.toString());
        ContentBrowseService svc = serviceWithFakeServer(server);
        CollectingSink sink = new CollectingSink();

        // when
        runStreamSync(svc, sink);

        // then
        assertEquals(2, sink.events.size(), "the stream still completes a subtree past the request cap");
        assertEquals("big", sink.events.get(0).id());
        // The latest timestamp in the generated set is i=2_199 -> 2020-01-01T00:36:39Z
        assertEquals("2020-01-01T00:36:39Z", sink.events.get(0).effectiveDate());
        assertTrue(sink.events.get(0).complete());
        assertTrue(sink.events.get(1).allDone());
    }

    @Test
    void runDateStream_clientDisconnects_stopsWithoutTerminalEvent() {
        // given
        FakeServer server = new FakeServer();
        server.put("0",
                didl("c1", "0", "One", "object.container", null) +
                        didl("c2", "0", "Two", "object.container", null) +
                        didl("c3", "0", "Three", "object.container", null));
        server.put("c1", didl("c1f", "c1", "m", "object.item.videoItem", "2021-01-01T00:00:00Z"));
        server.put("c2", didl("c2f", "c2", "m", "object.item.videoItem", "2022-01-01T00:00:00Z"));
        server.put("c3", didl("c3f", "c3", "m", "object.item.videoItem", "2023-01-01T00:00:00Z"));
        ContentBrowseService svc = serviceWithFakeServer(server);
        CollectingSink sink = new CollectingSink();
        sink.failAfter = 1; // the client goes away after the first date event

        // when
        assertThrows(IOException.class, () -> runStreamSync(svc, sink));

        // then
        assertEquals(1, sink.events.size(), "no further events are sent after the client is gone");
        assertEquals("c1", sink.events.get(0).id());
        assertFalse(sink.events.stream().anyMatch(DateEvent::allDone), "no terminal event after a disconnect");
    }

    @Test
    void openDateStream_nonDateSort_completesImmediatelyWithTerminalEvent() {
        // given
        FakeServer server = new FakeServer();
        ContentBrowseService svc = serviceWithFakeServer(server);

        // when
        svc.openDateStream("srv", "0", "dc:title");

        // then
        // A non-date sort must not run any crawl: no Browse calls are made.
        assertEquals(0, server.browseCalls.get(), "a non-date sort streams nothing and crawls nothing");
    }

    // ------------------------------------------------------------------------
    // Reproductions of the prod "Reacher looks old" race. These drive runDateStreamJob — the
    // exact path production takes — rather than runDateStream, because the client-disconnect
    // handling lives in the job wrapper.
    // ------------------------------------------------------------------------

    /** A Video/TV-shaped tree in miniature: {@code tv} holds three shows, Reacher the newest. */
    private static void putTvTree(FakeServer server) {
        server.put("tv",
                didl("s1", "tv", "s1", "object.container", null)
                        + didl("s2", "tv", "s2", "object.container", null)
                        + didl("reacher", "tv", "Reacher", "object.container", null));
        server.put("s1", didl("s1f", "s1", "ep", "object.item.videoItem", "2020-01-01T00:00:00Z"));
        server.put("s2", didl("s2f", "s2", "ep", "object.item.videoItem", "2021-01-01T00:00:00Z"));
        server.put("reacher", didl("rf", "reacher", "ep", "object.item.videoItem", "2026-08-26T20:52:43Z"));
    }

    /** The browse the UI issues in stream mode for a date-descending folder view. */
    private static BrowseResult streamModeBrowse(ContentBrowseService svc, String folder) {
        return svc.browse("srv", folder, 0, 50, "", "-dc:date", true);
    }

    private static List<String> ids(List<DateEvent> events) {
        List<String> out = new ArrayList<>();
        for (DateEvent e : events) {
            out.add(e.id());
        }
        return out;
    }

    /** Titles of the containers in a browse result that carry no effective date. */
    private static List<String> undatedContainers(BrowseResult result) {
        List<String> out = new ArrayList<>();
        for (BrowsableItem it : result.getItems()) {
            if (it.isContainer() && it.getEffectiveDate() == null) {
                out.add(it.getTitle());
            }
        }
        return out;
    }

    @Test
    void runDateStreamJob_clientDisconnectsMidStream_completedDatesRemainCached() {
        // given
        FakeServer server = new FakeServer();
        putTvTree(server);
        ContentBrowseService svc = serviceWithFakeServer(server);
        CollectingSink sink = new CollectingSink();
        // The client navigates away after two events. By the time the third send fails, all
        // three shows have been crawled and stored (each send happens after its crawl).
        sink.failAfter = 2;

        // when
        svc.runDateStreamJob("srv", "tv", new SseEmitter(), sink, new AtomicBoolean(false));
        BrowseResult revisit = streamModeBrowse(svc, "tv");

        // then
        assertEquals(List.of("s1", "s2"), ids(sink.events), "two events reached the client before it left");
        assertFalse(sink.events.stream().anyMatch(DateEvent::allDone), "no terminal event after a disconnect");
        assertEquals(List.of(), undatedContainers(revisit),
                "shows that lost their effective date because a client disconnected mid-stream");
        assertEquals(List.of("Reacher", "s2", "s1"), titles(revisit.getItems()),
                "the revisit must be ordered by the dates the stream had already computed");
    }

    @Test
    void runDateStreamJob_concurrentZombieDisconnects_liveStreamDatesSurviveInCache() throws Exception {
        // given
        FakeServer server = new FakeServer();
        putTvTree(server);
        // A second folder the user has already left; its stream is still crawling (a "zombie").
        server.put("video", didl("movies", "video", "Movies", "object.container", null));
        server.put("movies", didl("mf", "movies", "film", "object.item.videoItem", "2024-01-01T00:00:00Z"));
        ContentBrowseService svc = serviceWithFakeServer(server);
        // Pause the live TV stream the first time it browses s1: it has taken its reference to
        // the server's date-cache map by then, but has not stored anything yet.
        CountDownLatch insideCrawl = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean firstS1 = new AtomicBoolean(true);
        server.onBrowse = id -> {
            if ("s1".equals(id) && firstS1.getAndSet(false)) {
                insideCrawl.countDown();
                try {
                    resume.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        CollectingSink liveSink = new CollectingSink();
        CollectingSink zombieSink = new CollectingSink();
        zombieSink.failAfter = 0; // the zombie's client is already gone: its first send fails

        // when
        Thread live = new Thread(
                () -> svc.runDateStreamJob("srv", "tv", new SseEmitter(), liveSink, new AtomicBoolean(false)),
                "live-tv-stream");
        live.start();
        assertTrue(insideCrawl.await(10, TimeUnit.SECONDS), "the live stream should reach its first crawl");
        svc.runDateStreamJob("srv", "video", new SseEmitter(), zombieSink, new AtomicBoolean(false));
        resume.countDown();
        live.join(10_000);
        BrowseResult revisit = streamModeBrowse(svc, "tv");

        // then
        assertFalse(live.isAlive(), "the live stream should finish");
        assertEquals(4, liveSink.events.size(), "three shows plus the terminal event");
        assertEquals(List.of("s1", "s2", "reacher"), ids(liveSink.events.subList(0, 3)),
                "the live stream emitted every show");
        assertTrue(liveSink.events.get(3).allDone());
        assertEquals(List.of(), undatedContainers(revisit),
                "shows whose dates the live stream computed but a zombie's disconnect threw away");
        assertEquals(List.of("Reacher", "s2", "s1"), titles(revisit.getItems()));
    }

    @Test
    void runDateStreamJob_cancelledBeforeFirstSend_stopsCrawlingWithinOneCall() {
        // given
        FakeServer server = new FakeServer();
        // Video/TV in miniature: one folder whose single child has a ten-show subtree, so the
        // stream's first send only happens after the whole subtree has been crawled.
        StringBuilder shows = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            shows.append(didl("show" + i, "tv", "Show " + i, "object.container", null));
            server.put("show" + i, didl("ep" + i, "show" + i, "ep", "object.item.videoItem",
                    "2020-01-0" + (i % 9 + 1) + "T00:00:00Z"));
        }
        server.put("video", didl("tv", "video", "TV", "object.container", null));
        server.put("tv", shows.toString());
        ContentBrowseService svc = serviceWithFakeServer(server);
        // The client has already gone away (the emitter's callbacks flipped the flag); like the
        // production sink, this one refuses to send once that is so.
        AtomicBoolean cancelled = new AtomicBoolean(true);
        ContentBrowseService.DateStreamSink sink = event -> {
            if (cancelled.get()) {
                throw new IOException("date stream closed");
            }
        };

        // when
        svc.runDateStreamJob("srv", "video", new SseEmitter(), sink, cancelled);

        // then
        assertTrue(server.browseCalls.get() <= 2,
                "a cancelled stream must stop within one UPnP call, but made " + server.browseCalls.get()
                        + " Browse calls: it crawled the whole subtree before noticing the client was gone");
    }

    @Test
    void runDateStream_finished_evictsFolderSnapshot() throws Exception {
        // given
        FakeServer server = new FakeServer();
        putTvTree(server);
        ContentBrowseService svc = serviceWithFakeServer(server);
        // The UI's first, cold browse: nothing is dated yet, so the folder's cached sorted
        // snapshot is in server order.
        BrowseResult cold = streamModeBrowse(svc, "tv");
        assertEquals(List.of("s1", "s2", "Reacher"), titles(cold.getItems()), "cold snapshot is in server order");

        // when
        svc.runDateStream("srv", "tv", new CollectingSink());
        BrowseResult reloaded = streamModeBrowse(svc, "tv");

        // then
        // Without eviction the 60 s snapshot would still answer in cold order; the client's
        // post-allDone reload must instead see every folder dated and in true order.
        assertEquals(List.of(), undatedContainers(reloaded));
        assertEquals(List.of("Reacher", "s2", "s1"), titles(reloaded.getItems()));
    }

    @Test
    void runDateStreamJob_crawlFails_noTerminalEvent() {
        // given
        FakeServer server = new FakeServer();
        putTvTree(server);
        // The NAS falls over while the second show is being crawled.
        server.onBrowse = id -> {
            if ("s2".equals(id)) {
                throw new IllegalStateException("NAS unavailable");
            }
        };
        ContentBrowseService svc = serviceWithFakeServer(server);
        CollectingSink sink = new CollectingSink();

        // when
        svc.runDateStreamJob("srv", "tv", new SseEmitter(), sink, new AtomicBoolean(false));

        // then
        // A failed stream must not claim the list is final: the client takes its error path
        // instead of believing every folder now has a date.
        assertEquals(List.of("s1"), ids(sink.events), "only the show crawled before the failure was emitted");
        assertFalse(sink.events.stream().anyMatch(DateEvent::allDone), "no allDone after a crawl failure");
    }
}
