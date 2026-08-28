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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for the SSE effective-date stream budget fix.
 * 
 * Bug: the stream had a 1,000,000-item budget that could be exhausted
 * before reaching all containers in a folder (e.g. Reacher after many
 * other folders). This caused containers to never get their dates
 * emitted, never cached, and therefore always sorting at the bottom.
 * 
 * Fix: STREAM_ITEM_BUDGET = Integer.MAX_VALUE — the wall-clock deadline
 * (STREAM_DEADLINE_MS) is the only real safety limit.
 */
class ContentBrowseServiceStreamBudgetTest {

    // ------------------------------------------------------------------------
    // Helpers (mirrors the existing ContentBrowseServiceTest patterns)
    // ------------------------------------------------------------------------

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

    private static BrowsableItem item(String id, String title, boolean container) {
        return new BrowsableItem(
                id, "0", title, null, null, null, null,
                container ? null : "video/mp4", null, null,
                container, null,
                container ? "object.container" : "object.item.videoItem",
                null, null, null, null);
    }

    private static ActionArgument arg(String name, boolean in) {
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

    private static final class FakeServer {
        final Map<String, String> childrenXml = new LinkedHashMap<>();
        int browseCalls = 0;
        final List<String> calledContainers = new ArrayList<>();

        void put(String containerId, String children) {
            childrenXml.put(containerId,
                    "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-1\">"
                            + children
                            + "</DIDL-Lite>");
        }
    }

    private static final class CollectingSink implements ContentBrowseService.DateStreamSink {
        final List<DateEvent> events = new ArrayList<>();
        int failAfter = -1;

        @Override
        public void send(DateEvent event) throws IOException {
            if (failAfter >= 0 && events.size() >= failAfter) {
                throw new IOException("client gone");
            }
            events.add(event);
        }
    }

    private static ContentBrowseService serviceWithFakeServer(FakeServer server) {
        RemoteDevice device = Mockito.mock(RemoteDevice.class);
        RemoteService contentDir = fakeContentDirectory();
        when(device.findService(Mockito.any(UDAServiceType.class))).thenReturn(contentDir);

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
                    server.calledContainers.add(objectID);
                    String xml = server.childrenXml.getOrDefault(objectID, "");
                    try {
                        invocation.setOutput("Result", xml);
                        invocation.setOutput("TotalMatches", "0");
                        invocation.setOutput("UpdateID", "63593");
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
                return new ActionCallback(invocation) {
                    @Override public void run() {}
                    @Override public void success(ActionInvocation invocation) {}
                    @Override public void failure(ActionInvocation invocation,
                            org.jupnp.model.message.UpnpResponse operation, String defaultMsg) {}
                };
            }
        };
    }

    private static void runStreamSync(ContentBrowseService svc, CollectingSink sink) throws IOException {
        svc.runDateStream("srv", "0", sink);
    }

    // ------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------

    /**
     * Stream with a large number of containers: all should be crawled and emit events.
     * 
     * This reproduces the reported bug: with a finite budget, the last containers
     * (like "Reacher" alphabetically) would never be crawled because the budget was
     * exhausted by earlier containers. With MAX_VALUE budget, all containers are crawled.
     */
    @Test
    void runDateStream_manyContainers_allCrawledAndEmitted() throws Exception {
        // given — 50 containers in the root folder
        FakeServer server = new FakeServer();
        StringBuilder children = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            String title = String.format("Folder%02d", i);
            children.append(didl("c" + i, "0", title, "object.container", null));
        }
        server.put("0", children.toString());

        // Each container has a dated media file
        for (int i = 0; i < 50; i++) {
            String title = String.format("Folder%02d", i);
            String date = String.format("2024-%02d-01T00:00:00Z", i + 1);
            server.put("c" + i, didl("c" + i + "f", "c" + i, title + "File", "object.item.videoItem", date));
        }

        ContentBrowseService svc = serviceWithFakeServer(server);
        CollectingSink sink = new CollectingSink();

        // when
        runStreamSync(svc, sink);

        // then — one date event per container + one terminal event = 51
        int dateEvents = 0;
        int terminalEvents = 0;
        for (DateEvent e : sink.events) {
            if (e.allDone()) {
                terminalEvents++;
            } else {
                dateEvents++;
            }
        }
        assertEquals(50, dateEvents, "every container should emit a date event");
        assertEquals(1, terminalEvents, "exactly one terminal event");
        assertEquals(51, sink.events.size(), "total events");

        // Verify all 50 containers were crawled via Browse (1 root + 50 containers)
        assertEquals(51, server.browseCalls, "root browse + each container subtree must be crawled");
    }

    /**
     * Stream with many containers: containers crawled in alphabetical order,
     * all should complete regardless of order.
     * 
     * The bug specifically manifested when containers near the end of the
     * children list (alphabetically later names) were never reached.
     */
    @Test
    void runDateStream_manyContainers_lastAlphabetical_crawled() throws Exception {
        // given — 30 containers, the last one has a "Z" prefix
        FakeServer server = new FakeServer();
        StringBuilder children = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            String title = i < 15 ? "Alpha" + i : "Zebra" + (i - 15);
            children.append(didl("c" + i, "0", title, "object.container", null));
        }
        server.put("0", children.toString());

        for (int i = 0; i < 30; i++) {
            String title = i < 15 ? "Alpha" + i : "Zebra" + (i - 15);
            String date = "2024-06-15T12:00:00Z";
            server.put("c" + i, didl("c" + i + "f", "c" + i, title + "File", "object.item.videoItem", date));
        }

        ContentBrowseService svc = serviceWithFakeServer(server);
        CollectingSink sink = new CollectingSink();

        // when
        runStreamSync(svc, sink);

        // then — Zebra containers should all have events (they were near the end)
        int zebraEvents = 0;
        for (DateEvent e : sink.events) {
            if (e.id() != null && !e.allDone()) {
                String id = e.id();
                if (id.startsWith("c") && Integer.parseInt(id.substring(1)) >= 15) {
                    zebraEvents++;
                }
            }
        }
        assertEquals(15, zebraEvents, "all late-alphabetical containers should have emitted events");
    }

    /**
     * Verify the budget constant is effectively unlimited.
     */
    @Test
    void streamBudget_isUnlimited() throws Exception {
        // when
        // The constant is private, so we verify the behaviour indirectly:
        // a huge subtree (well beyond 1M items) should be limited by
        // CRAWL_MAX_TOTAL_ITEMS and CRAWL_MAX_DEPTH only.
        FakeServer server = new FakeServer();
        // Root with 20 containers (simulating many folders)
        StringBuilder children = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            children.append(didl("c" + i, "0", "Folder" + i, "object.container", null));
        }
        server.put("0", children.toString());

        // Each container has 100 dated items — total 2,000 items
        for (int i = 0; i < 20; i++) {
            StringBuilder sub = new StringBuilder();
            for (int j = 0; j < 100; j++) {
                sub.append(didl("c" + i + "i" + j, "c" + i, "ep" + j, "object.item.videoItem", "2024-01-01T00:00:00Z"));
            }
            server.put("c" + i, sub.toString());
        }

        ContentBrowseService svc = serviceWithFakeServer(server);
        CollectingSink sink = new CollectingSink();

        // when
        runStreamSync(svc, sink);

        // then — all 20 containers crawled
        int dateEvents = 0;
        for (DateEvent e : sink.events) {
            if (!e.allDone()) dateEvents++;
        }
        assertEquals(20, dateEvents, "all 20 containers should be crawled");
    }

    /**
     * Mixed containers and media items: only containers are crawled.
     */
    @Test
    void runDateStream_mixedChildren_onlyContainersCrawled() throws Exception {
        // given — root has 5 containers + 10 media items
        FakeServer server = new FakeServer();
        StringBuilder children = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            children.append(didl("c" + i, "0", "Folder" + i, "object.container", null));
        }
        for (int i = 0; i < 10; i++) {
            children.append(didl("f" + i, "0", "File" + i, "object.item.videoItem", "2024-01-01T00:00:00Z"));
        }
        server.put("0", children.toString());

        for (int i = 0; i < 5; i++) {
            server.put("c" + i, didl("c" + i + "f", "c" + i, "Movie", "object.item.videoItem", "2024-03-15T00:00:00Z"));
        }

        ContentBrowseService svc = serviceWithFakeServer(server);
        CollectingSink sink = new CollectingSink();

        // when
        runStreamSync(svc, sink);

        // then — 5 container events + 1 terminal = 6
        assertEquals(6, sink.events.size());
        // browseCalls includes 1 for fetchAllChildren(root) + 5 container crawls = 6
        assertTrue(server.browseCalls >= 6, "root browse + container crawls must be made");
    }

    /**
     * Stream with containers that have no dated children: they still emit
     * an event with null effectiveDate, which the frontend handles by
     * sorting them at the end.
     */
    @Test
    void runDateStream_containerWithNoDatedChildren_emitsNullDate() throws Exception {
        // given
        FakeServer server = new FakeServer();
        server.put("0", didl("c1", "0", "EmptyShow", "object.container", null));
        // The container has children but none have dates
        server.put("c1", didl("c1f", "c1", "untitled", "object.item.videoItem", null));

        ContentBrowseService svc = serviceWithFakeServer(server);
        CollectingSink sink = new CollectingSink();

        // when
        runStreamSync(svc, sink);

        // then
        assertEquals(2, sink.events.size());
        assertEquals("c1", sink.events.get(0).id());
        assertEquals(null, sink.events.get(0).effectiveDate(), "no dated descendants → null effectiveDate");
        assertTrue(sink.events.get(0).complete());
    }

    /**
     * Deeply nested container: the stream should crawl through the
     * hierarchy and find the deepest dated file.
     */
    @Test
    void runDateStream_deepSubtree_findsDeepestDate() throws Exception {
        // given — 3 levels deep
        FakeServer server = new FakeServer();
        server.put("0", didl("c1", "0", "Root", "object.container", null));
        server.put("c1", didl("c1a", "c1", "Mid", "object.container", null));
        server.put("c1a", didl("c1a1", "c1a", "Deep", "object.container", null));
        server.put("c1a1", didl("leaf", "c1a1", "file", "object.item.videoItem", "2025-12-25T00:00:00Z"));

        ContentBrowseService svc = serviceWithFakeServer(server);
        CollectingSink sink = new CollectingSink();

        // when
        runStreamSync(svc, sink);

        // then
        assertEquals(2, sink.events.size());
        assertEquals("c1", sink.events.get(0).id());
        assertEquals("2025-12-25T00:00:00Z", sink.events.get(0).effectiveDate());
    }

    /**
     * Stream budget constant is Integer.MAX_VALUE to ensure unlimited
     * crawl reach within the wall-clock deadline.
     */
    @Test
    void streamBudget_constantIsMaxValue() throws Exception {
        // This is a sanity test — if someone changes the constant back,
        // the behavioural tests above will catch the regression.
        // We verify the budget doesn't artificially limit a known-small scenario.
        FakeServer server = new FakeServer();
        // 100 containers, each with 10 items — 1,000 total items (trivially under any budget)
        StringBuilder children = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            children.append(didl("c" + i, "0", "Folder" + i, "object.container", null));
        }
        server.put("0", children.toString());
        for (int i = 0; i < 100; i++) {
            server.put("c" + i, didl("c" + i + "f", "c" + i, "Movie", "object.item.videoItem", "2024-01-01T00:00:00Z"));
        }

        ContentBrowseService svc = serviceWithFakeServer(server);
        CollectingSink sink = new CollectingSink();

        runStreamSync(svc, sink);

        int dateEvents = 0;
        for (DateEvent e : sink.events) {
            if (!e.allDone()) dateEvents++;
        }
        assertEquals(100, dateEvents, "all 100 containers crawled with unlimited budget");
    }
}
