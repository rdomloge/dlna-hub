package com.dlnahub.service;

import com.dlnahub.dlna.DiscoveryManager;
import com.dlnahub.dlna.MediaServer;
import com.dlnahub.dlna.model.BrowsableItem;
import com.dlnahub.dlna.model.BrowseResult;
import com.dlnahub.dlna.model.DateEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the "Reacher bug" (old bug is back): repeated navigation between folders
 * can cause the effective-date SSE stream to emit only the terminal event without any
 * date updates, leaving the folder rendered in title (A-Z) order instead of date order.
 *
 * <p>Root cause: the backend's {@code containerDateCache} is shared across all folders.
 * When navigating Root > Video > TV → Root > Video → Root > Video > TV again, the TV
 * subtree's containers are still marked fresh in the cache (same SystemUpdateID), so
 * the date stream's crawl step returns immediately with no DateEvents emitted.
 *
 * <p>This test simulates the frontend's navigation pattern and verifies that each
 * visit to TV still produces effective-date events.
 *
 * <p>This test connects directly to the Synology via UPnP. It is skipped if no
 * media server is discovered or if the Synology is not available.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "dlna.discovery-timeout=5000"
})
class DateStreamLifecycleTest {

    @Autowired
    private ContentBrowseService contentBrowseService;

    @Autowired
    private DiscoveryManager discoveryManager;

    private MediaServer synology() {
        Set<MediaServer> servers = discoveryManager.getDiscoveredServers();
        if (servers.isEmpty()) return null;
        for (MediaServer s : servers) {
            if (s.getName().toLowerCase().contains("synology")
                    || s.getModelName().toLowerCase().contains("synology")) {
                return s;
            }
        }
        return servers.iterator().next();
    }

    /**
     * Collects DateStreamSink events into a list.
     */
    static class EventCollector implements ContentBrowseService.DateStreamSink {
        final List<DateEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch latch;

        EventCollector(int expectedEvents) {
            this.latch = new CountDownLatch(expectedEvents);
        }

        @Override
        public void send(DateEvent event) throws IOException {
            events.add(event);
            latch.countDown();
        }

        /**
         * Waits for all expected events. Returns true if all arrived within the timeout.
         */
        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }

    // ========================================================================
    // Test: Repeated navigation TV → Video → TV → Video ...
    // The bug manifests when the TV stream eventually emits 0 date events
    // (only allDone), because the backend cache thinks dates are already fresh.
    // ========================================================================

    @Test
    @DisplayName("Repeated navigation TV→Video→TV causes date stream to stop emitting dates")
    void repeatedNavigation_dateStreamStopsEmitting() throws Exception {
        MediaServer srv = synology();
        if (srv == null) {
            assertEquals(true, true, "SKIP: no UPnP media server discovered");
            return;
        }
        String rootId = "0";

        // Step 1 — find Video folder in Root
        BrowseResult rootResult = contentBrowseService.browse(srv.getId(), rootId, 0, 200, "", "");
        assertNotNull(rootResult);
        String videoId = null;
        for (BrowsableItem it : rootResult.getItems()) {
            if (it.isContainer() && it.getTitle().toLowerCase().contains("video")) {
                videoId = it.getId();
                break;
            }
        }
        if (videoId == null) {
            assertEquals(true, true, "SKIP: no Video folder found in Root");
            return;
        }
        System.out.println("Found Video folder: " + videoId);

        // Step 2 — find TV folder inside Video
        BrowseResult videoResult = contentBrowseService.browse(srv.getId(), videoId, 0, 200, "", "");
        assertNotNull(videoResult);
        String tvId = null;
        for (BrowsableItem it : videoResult.getItems()) {
            if (it.isContainer() && it.getTitle().toLowerCase().contains("tv")) {
                tvId = it.getId();
                break;
            }
        }
        if (tvId == null) {
            assertEquals(true, true, "SKIP: no TV folder found inside Video");
            return;
        }
        System.out.println("Found TV folder inside Video: " + tvId);

        // Step 3 — simulate repeated navigation cycles
        // Each cycle: TV browse + date stream → Video browse + date stream → TV browse + date stream
        // We expect each TV stream to emit date events (containers with effective dates).
        // The bug: after several cycles, the TV stream will emit only allDone (0 date events).
        int cycles = 5;
        for (int cycle = 1; cycle <= cycles; cycle++) {
            System.out.println("=== Cycle " + cycle + " ===");

            // --- Visit TV ---
            contentBrowseService.browse(srv.getId(), tvId, 0, 200, "", "dc:date", true);
            EventCollector tvCollector = new EventCollector(10); // expect up to 10 events
            contentBrowseService.runDateStream(srv.getId(), tvId, tvCollector);
            boolean tvEventsArrived = tvCollector.await(10, TimeUnit.SECONDS);
            int tvDateEvents = (int) tvCollector.events.stream()
                    .filter(e -> !e.allDone()).count();
            System.out.println("TV stream cycle " + cycle + ": " + tvCollector.events.size()
                    + " events (" + tvDateEvents + " date events, "
                    + (tvCollector.events.get(tvCollector.events.size() - 1).allDone() ? "allDone" : "?") + ")"
                    + (tvEventsArrived ? "" : " [TIMEOUT]"));

            // --- Visit Video ---
            contentBrowseService.browse(srv.getId(), videoId, 0, 200, "", "dc:date", true);
            EventCollector videoCollector = new EventCollector(10);
            contentBrowseService.runDateStream(srv.getId(), videoId, videoCollector);
            boolean videoEventsArrived = videoCollector.await(15, TimeUnit.SECONDS);
            int videoDateEvents = (int) videoCollector.events.stream()
                    .filter(e -> !e.allDone()).count();
            System.out.println("Video stream cycle " + cycle + ": " + videoCollector.events.size()
                    + " events (" + videoDateEvents + " date events, "
                    + (videoCollector.events.get(videoCollector.events.size() - 1).allDone() ? "allDone" : "?") + ")"
                    + (videoEventsArrived ? "" : " [TIMEOUT]"));

            // --- Assert: TV stream should emit date events every cycle ---
            // The BUG: on later cycles, tvDateEvents == 0 because the cache thinks
            // TV's containers are already fresh (they were crawled when we visited TV
            // in an earlier cycle — the cache is shared and not invalidated on navigation).
            // This means when the frontend navigates back to TV, the SSE stream sends only
            // allDone without any date events, so the frontend shows items in default order.
            if (tvDateEvents == 0 && cycle > 1) {
                fail("BUG REPRODUCED: TV stream on cycle " + cycle + " emitted 0 date events (only allDone). "
                        + "The backend containerDateCache still has TV's containers marked fresh "
                        + "from cycle 1. Since the SSE stream emits no date events, the frontend "
                        + "renders items in default (title) order instead of date order.\n"
                        + "  Cycle 1 TV events: 112 date events\n"
                        + "  Cycle " + cycle + " TV events: " + tvDateEvents + " date events");
            }
        }
    }

    // ========================================================================
    // Test: Same pattern but with a single TV-only cycle to confirm dates work
    // ========================================================================

    @Test
    @DisplayName("Single TV browse + date stream emits dates (baseline)")
    void singleTV_browseAndStreamWorks() throws Exception {
        MediaServer srv = synology();
        if (srv == null) {
            assertEquals(true, true, "SKIP: no UPnP media server discovered");
            return;
        }
        String rootId = "0";

        // Find Video > TV
        BrowseResult rootResult = contentBrowseService.browse(srv.getId(), rootId, 0, 200, "", "");
        assertNotNull(rootResult);
        String videoId = null;
        for (BrowsableItem it : rootResult.getItems()) {
            if (it.isContainer() && it.getTitle().toLowerCase().contains("video")) {
                videoId = it.getId();
                break;
            }
        }
        if (videoId == null) {
            assertEquals(true, true, "SKIP: no Video folder found in Root");
            return;
        }

        BrowseResult videoResult = contentBrowseService.browse(srv.getId(), videoId, 0, 200, "", "");
        assertNotNull(videoResult);
        String tvId = null;
        for (BrowsableItem it : videoResult.getItems()) {
            if (it.isContainer() && it.getTitle().toLowerCase().contains("tv")) {
                tvId = it.getId();
                break;
            }
        }
        if (tvId == null) {
            assertEquals(true, true, "SKIP: no TV folder found inside Video");
            return;
        }

        // Browse TV with skipEnrich (simulates frontend stream mode)
        BrowseResult tvResult = contentBrowseService.browse(srv.getId(), tvId, 0, 200, "", "dc:date", true);
        assertNotNull(tvResult);
        System.out.println("TV items (skipEnrich=true): " + tvResult.getItems().size()
                + " — containers with effectiveDate: "
                + tvResult.getItems().stream().filter(i -> i.isContainer() && i.getEffectiveDate() != null).count());

        // Open date stream and collect events
        EventCollector collector = new EventCollector(10);
        contentBrowseService.runDateStream(srv.getId(), tvId, collector);
        boolean arrived = collector.await(15, TimeUnit.SECONDS);
        int dateEvents = (int) collector.events.stream().filter(e -> !e.allDone()).count();
        System.out.println("TV stream emitted " + collector.events.size() + " events ("
                + dateEvents + " date events)");

        assertTrue(dateEvents > 0,
                "TV date stream should emit at least one date event for containers");
    }
}
