package com.dlnahub.service;

import com.dlnahub.dlna.UpnpServiceManager;
import com.dlnahub.dlna.model.BrowsableItem;
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

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        int browseCalls = 0;

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
                    server.browseCalls++;
                    String objectID = String.valueOf(invocation.getInput("objectID").getValue());
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
    void runDateStream_mixedKnownAndUnknown_emitsOnlyForUnknownThenTerminal() throws Exception {
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
        // Warm c1 into the cache via the enrichment path so the stream skips it (c2 stays unknown).
        svc.enrichContainerDates("srv", List.of(item("c1", "Known", null, null, true)));
        CollectingSink sink = new CollectingSink();

        // when
        runStreamSync(svc, sink);

        // then
        assertEquals(2, sink.events.size(), "one date event for c2 plus the terminal event");
        assertEquals("c2", sink.events.get(0).id());
        assertEquals("2022-06-06T00:00:00Z", sink.events.get(0).effectiveDate());
        assertTrue(sink.events.get(0).complete());
        assertFalse(sink.events.get(0).allDone());
        assertTrue(sink.events.get(1).allDone(), "last event is the terminal allDone");
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
        assertEquals(0, server.browseCalls, "a non-date sort streams nothing and crawls nothing");
    }
}
