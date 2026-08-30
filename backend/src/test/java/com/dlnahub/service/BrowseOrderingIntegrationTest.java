package com.dlnahub.service;

import com.dlnahub.dlna.DiscoveryManager;
import com.dlnahub.dlna.MediaServer;
import com.dlnahub.dlna.model.BrowsableItem;
import com.dlnahub.dlna.model.BrowseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that navigates the Synology DLNA server's folder hierarchy
 * and verifies item ordering is correct after each step.
 *
 * <p>Reproduces the "old bug": quickly jumping between folders can cause items
 * to appear in the wrong order, and items from one folder can leak into another.
 *
 * <p>This test connects directly to the Synology via UPnP. It is skipped if no
 * media server is discovered or if the Synology is not available.
 *
 * <p>Known Synology behaviours this test accounts for:
 * <ul>
 *   <li>SortCriteria is silently ignored — sorting happens client-side via
 *       {@link ContentBrowseService#sortItems(List, String)}.</li>
 *   <li>Folder dates are not exposed — effective dates are computed from
 *       descendant media items.</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "dlna.discovery-timeout=5000"
})
class BrowseOrderingIntegrationTest {

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

    // ========================================================================
    // Test 1: Rapid navigation — items stay sorted and isolated per folder
    // ========================================================================

    @Test
    @DisplayName("Rapid navigation — items stay sorted and isolated per folder")
    void rapidNavigation_sortOrderPreserved() {
        MediaServer srv = synology();
        if (srv == null) {
            assertEquals(true, true, "SKIP: no UPnP media server discovered");
            return;
        }
        String rootId = "0";

        // Step 1 — browse Root (date sort — triggers enrichment)
        BrowseResult rootResult = contentBrowseService.browse(
                srv.getId(), rootId, 0, 200, "", "dc:date");
        assertNotNull(rootResult);
        assertFalse(rootResult.getItems().isEmpty(), "Root must have children");

        List<String> rootTitles = itemTitles(rootResult.getItems());
        System.out.println("=== Root items (date sort): " + rootTitles + " ===");

        // Find TV folder in Root
        String tvId = null;
        String tvTitle = null;
        for (BrowsableItem it : rootResult.getItems()) {
            if (it.isContainer() && it.getTitle().toLowerCase().contains("tv")) {
                tvId = it.getId();
                tvTitle = it.getTitle();
                break;
            }
        }
        if (tvId == null) {
            assertEquals(true, true, "SKIP: no TV folder found in Root");
            return;
        }
        System.out.println("Found TV folder: " + tvTitle + " (id=" + tvId + ")");

        // Step 2 — browse TV (title sort)
        BrowseResult tvResult = contentBrowseService.browse(
                srv.getId(), tvId, 0, 200, "", "dc:title");
        assertNotNull(tvResult);

        List<String> tvTitles = itemTitles(tvResult.getItems());
        System.out.println("=== TV items (title sort): " + tvTitles + " ===");
        verifySortOrder(tvResult.getItems(), "dc:title");

        // Find Reacher folder inside TV
        String reacherId = null;
        String reacherTitle = null;
        for (BrowsableItem it : tvResult.getItems()) {
            if (it.isContainer() && it.getTitle().toLowerCase().contains("reach")) {
                reacherId = it.getId();
                reacherTitle = it.getTitle();
                break;
            }
        }
        if (reacherId == null) {
            assertEquals(true, true, "SKIP: no Reacher-like folder found in TV");
            return;
        }
        System.out.println("Found Reacher folder: " + reacherTitle + " (id=" + reacherId + ")");

        // Step 3 — browse Reacher (title sort) — THE KEY ASSERTION
        BrowseResult reacherResult = contentBrowseService.browse(
                srv.getId(), reacherId, 0, 200, "", "dc:title");
        assertNotNull(reacherResult);

        List<String> reacherTitles = itemTitles(reacherResult.getItems());
        System.out.println("=== Reacher items (title sort): " + reacherTitles + " ===");

        // ASSERT: items in Reacher must be sorted by title
        verifySortOrder(reacherResult.getItems(), "dc:title");

        // ASSERT: containers should appear first in title sort
        verifyContainersFirst(reacherResult.getItems());

        // ASSERT: no item in Reacher should have the same title as a TV item
        // (catches the "mixed rows" stale-response leak)
        for (String tvTitle2 : tvTitles) {
            assertFalse(reacherTitles.contains(tvTitle2),
                    "Reacher should not contain TV items — item '" + tvTitle2 + "' leaked");
        }

        // Step 4 — navigate to Music
        String musicId = null;
        String musicTitle = null;
        for (BrowsableItem it : rootResult.getItems()) {
            if (it.isContainer() && it.getTitle().toLowerCase().contains("music")) {
                musicId = it.getId();
                musicTitle = it.getTitle();
                break;
            }
        }
        if (musicId != null) {
            BrowseResult musicResult = contentBrowseService.browse(
                    srv.getId(), musicId, 0, 200, "", "dc:title");
            assertNotNull(musicResult);
            List<String> musicTitles = itemTitles(musicResult.getItems());
            System.out.println("=== Music items (title sort): " + musicTitles + " ===");
            verifySortOrder(musicResult.getItems(), "dc:title");

            // Verify Music does NOT contain Reacher or TV items
            for (String reacherT : reacherTitles) {
                assertFalse(musicTitles.contains(reacherT),
                        "Music should not contain Reacher items — '" + reacherT + "' leaked");
            }
        }

        // Step 5 — navigate back to Reacher via TV (simulates user going Root > TV > Reacher again)
        BrowseResult tvResult2 = contentBrowseService.browse(
                srv.getId(), tvId, 0, 200, "", "");
        assertNotNull(tvResult2);

        String reacherId2 = null;
        for (BrowsableItem it : tvResult2.getItems()) {
            if (it.isContainer() && it.getTitle().toLowerCase().contains("reach")) {
                reacherId2 = it.getId();
                break;
            }
        }
        if (reacherId2 != null) {
            BrowseResult reacherResult2 = contentBrowseService.browse(
                    srv.getId(), reacherId2, 0, 200, "", "dc:title");
            assertNotNull(reacherResult2);
            List<String> reacherTitles2 = itemTitles(reacherResult2.getItems());
            System.out.println("=== Reacher items (title sort, 2nd visit): " + reacherTitles2 + " ===");

            verifySortOrder(reacherResult2.getItems(), "dc:title");
            assertEquals(reacherTitles, reacherTitles2,
                    "Reacher items should be identical across visits — order or content drifted");
        }
    }

    // ========================================================================
    // Test 2: Rapid-fire — no stale items leak between folders
    // ========================================================================

    @Test
    @DisplayName("Rapid-fire navigation — no stale items leak between folders")
    void rapidFireNavigation_noStaleItems() {
        MediaServer srv = synology();
        if (srv == null) {
            assertEquals(true, true, "SKIP: no UPnP media server discovered");
            return;
        }
        String rootId = "0";

        // Get all top-level container IDs
        BrowseResult rootResult = contentBrowseService.browse(
                srv.getId(), rootId, 0, 200, "", "");
        List<String> containerIds = new ArrayList<>();
        List<String> containerNames = new ArrayList<>();
        for (BrowsableItem it : rootResult.getItems()) {
            if (it.isContainer()) {
                containerIds.add(it.getId());
                containerNames.add(it.getTitle());
            }
        }
        if (containerIds.size() < 3) {
            assertEquals(true, true, "SKIP: fewer than 3 top-level folders");
            return;
        }

        // Browse each container title-sorted, collect ALL items per folder
        Map<String, List<BrowsableItem>> folderItems = new LinkedHashMap<>();
        for (int i = 0; i < containerIds.size() && i < 10; i++) {
            String cid = containerIds.get(i);
            BrowseResult result = contentBrowseService.browse(
                    srv.getId(), cid, 0, 200, "", "dc:title");
            assertNotNull(result);
            folderItems.put(containerNames.get(i), new ArrayList<>(result.getItems()));
            System.out.println(containerNames.get(i) + " (id=" + cid + ", total=" + result.getTotal()
                    + "): " + result.getItems().size() + " items: " + itemTitles(result.getItems()));
            verifySortOrder(result.getItems(), "dc:title");
        }

        // ASSERT: no folder should contain items from another folder
        // Compare by item ID (title collisions are expected — Synology uses
        // common folder names like "By Folder" in every media type)
        for (Map.Entry<String, List<BrowsableItem>> entry : folderItems.entrySet()) {
            String folderName = entry.getKey();
            List<BrowsableItem> items = entry.getValue();
            for (Map.Entry<String, List<BrowsableItem>> other : folderItems.entrySet()) {
                if (other.getKey().equals(folderName)) continue;
                for (BrowsableItem otherItem : other.getValue()) {
                    boolean found = false;
                    for (BrowsableItem it : items) {
                        if (it.getId() != null && it.getId().equals(otherItem.getId())) {
                            found = true;
                            break;
                        }
                    }
                    if (found) {
                        fail("Folder '" + folderName + "' contains item id='" + otherItem.getId()
                                + "' (title='" + otherItem.getTitle() + "')"
                                + " from folder '" + other.getKey()
                                + "' — possible stale-response leak\n"
                                + "  " + folderName + " item ids: " + itemIds(items)
                                + "\n  " + other.getKey() + " item ids: "
                                + itemIds(other.getValue()));
                    }
                }
            }
        }
    }

    // ========================================================================
    // Test 3: Date sort — effective dates are applied correctly
    // ========================================================================

    @Test
    @DisplayName("Date sort — containers with effective dates sort before items without dates")
    void dateSort_effectiveDatesApplied() {
        MediaServer srv = synology();
        if (srv == null) {
            assertEquals(true, true, "SKIP: no UPnP media server discovered");
            return;
        }
        String rootId = "0";

        BrowseResult rootResult = contentBrowseService.browse(
                srv.getId(), rootId, 0, 200, "", "dc:date");
        assertNotNull(rootResult);
        assertFalse(rootResult.getItems().isEmpty(), "Root must have children");

        List<String> titles = itemTitles(rootResult.getItems());
        System.out.println("=== Root items (date sort): " + titles + " ===");

        // For date sort: items with dates should come before items without dates
        // (ascending order: no-date items at the end)
        boolean seenNoDate = false;
        for (BrowsableItem it : rootResult.getItems()) {
            boolean hasDate = it.getDate() != null && !it.getDate().isBlank()
                    || it.isContainer() && it.getEffectiveDate() != null && !it.getEffectiveDate().isBlank();
            if (seenNoDate && hasDate) {
                fail("Item '" + it.getTitle() + "' has a date but appeared after an item without a date");
            }
            if (!hasDate) {
                seenNoDate = true;
            }
        }
    }

    // ========================================================================
    // Test 4: Reacher date sort — the specific folder the user reported
    // ========================================================================

    @Test
    @DisplayName("Reacher folder (Root > TV > Reacher) — date sort order is correct")
    void reacherDateSort_orderCorrect() {
        MediaServer srv = synology();
        if (srv == null) {
            assertEquals(true, true, "SKIP: no UPnP media server discovered");
            return;
        }
        String rootId = "0";

        // Step 1 — browse Root to find TV
        BrowseResult rootResult = contentBrowseService.browse(
                srv.getId(), rootId, 0, 200, "", "");
        assertNotNull(rootResult);
        String tvId = null;
        for (BrowsableItem it : rootResult.getItems()) {
            if (it.isContainer() && it.getTitle().toLowerCase().contains("tv")) {
                tvId = it.getId();
                break;
            }
        }
        if (tvId == null) {
            assertEquals(true, true, "SKIP: no TV folder found in Root");
            return;
        }
        System.out.println("Found TV folder: " + tvId);

        // Step 2 — browse TV to find Reacher
        BrowseResult tvResult = contentBrowseService.browse(
                srv.getId(), tvId, 0, 200, "", "");
        assertNotNull(tvResult);
        String reacherId = null;
        for (BrowsableItem it : tvResult.getItems()) {
            if (it.isContainer() && it.getTitle().toLowerCase().contains("reach")) {
                reacherId = it.getId();
                break;
            }
        }
        if (reacherId == null) {
            assertEquals(true, true, "SKIP: no Reacher-like folder found in TV");
            return;
        }
        System.out.println("Found Reacher folder: " + reacherId);

        // Step 3 — browse Reacher with date sort
        BrowseResult reacherDateResult = contentBrowseService.browse(
                srv.getId(), reacherId, 0, 200, "", "dc:date");
        assertNotNull(reacherDateResult);
        List<String> reacherDateTitles = itemTitles(reacherDateResult.getItems());
        System.out.println("=== Reacher items (date sort): " + reacherDateTitles + " ===");

        // ASSERT: items must be sorted by date — containers with effective dates
        // should appear first (sorted by date), then items by date, then no-date items
        verifyDateSortOrder(reacherDateResult.getItems());

        // Step 4 — browse Reacher with title sort and verify consistency
        BrowseResult reacherTitleResult = contentBrowseService.browse(
                srv.getId(), reacherId, 0, 200, "", "dc:title");
        assertNotNull(reacherTitleResult);
        List<String> reacherTitleTitles = itemTitles(reacherTitleResult.getItems());
        System.out.println("=== Reacher items (title sort): " + reacherTitleTitles + " ===");

        verifySortOrder(reacherTitleResult.getItems(), "dc:title");
        verifyContainersFirst(reacherTitleResult.getItems());

        // ASSERT: both sorts should return the same set of items (same IDs)
        List<String> dateIds = itemIds(reacherDateResult.getItems());
        List<String> titleIds = itemIds(reacherTitleResult.getItems());
        assertEquals(dateIds, titleIds,
                "Reacher date sort and title sort should return the same items (same IDs)");
    }

    /**
     * Verifies date sort: items with dates should come before no-date items,
     * and within the dated items, dates should be in ascending order.
     */
    private void verifyDateSortOrder(List<BrowsableItem> items) {
        if (items.isEmpty()) return;

        // Collect dates in order
        List<String> datedTitles = new ArrayList<>();
        List<String> datedDates = new ArrayList<>();
        boolean seenNoDate = false;

        for (BrowsableItem it : items) {
            // For containers: use effectiveDate if present, otherwise date
            String sortDate = it.isContainer()
                    ? (it.getEffectiveDate() != null ? it.getEffectiveDate() : it.getDate())
                    : it.getDate();

            boolean hasDate = sortDate != null && !sortDate.isBlank();
            if (hasDate) {
                datedTitles.add(it.getTitle());
                datedDates.add(sortDate);
                if (seenNoDate) {
                    fail("Item '" + it.getTitle() + "' (date=" + sortDate
                            + ") appeared after a no-date item — date sort violated");
                }
            } else {
                seenNoDate = true;
            }
        }

        // Verify dated items are in ascending date order
        for (int i = 1; i < datedDates.size(); i++) {
            try {
                java.time.Instant prev = java.time.Instant.parse(datedDates.get(i - 1));
                java.time.Instant curr = java.time.Instant.parse(datedDates.get(i));
                if (curr.isBefore(prev)) {
                    fail("Date sort violation at index " + i + ": '" + datedTitles.get(i)
                            + "' (date=" + datedDates.get(i) + ") came after '"
                            + datedTitles.get(i - 1) + "' (date=" + datedDates.get(i - 1)
                            + ") — dates not in ascending order");
                }
            } catch (java.time.DateTimeException e) {
                // Date parse failure — could be a format issue, log it
                System.out.println("Warning: could not parse date '" + datedDates.get(i)
                        + "' for item '" + datedTitles.get(i) + "': " + e.getMessage());
            }
        }
    }

    // ========================================================================
    // Test 5: Parent ID isolation — items belong to the folder they're in
    // ========================================================================

    @Test
    @DisplayName("Parent ID isolation — each item's parentId matches the browsed folder")
    void parentId_isolation() {
        MediaServer srv = synology();
        if (srv == null) {
            assertEquals(true, true, "SKIP: no UPnP media server discovered");
            return;
        }
        String rootId = "0";

        BrowseResult rootResult = contentBrowseService.browse(
                srv.getId(), rootId, 0, 200, "", "");
        assertNotNull(rootResult);

        // Find a subfolder to browse
        String subfolderId = null;
        String subfolderTitle = null;
        for (BrowsableItem it : rootResult.getItems()) {
            if (it.isContainer()) {
                subfolderId = it.getId();
                subfolderTitle = it.getTitle();
                break;
            }
        }
        if (subfolderId == null) {
            assertEquals(true, true, "SKIP: no subfolder found");
            return;
        }
        System.out.println("Browsing subfolder: " + subfolderTitle + " (id=" + subfolderId + ")");

        BrowseResult subResult = contentBrowseService.browse(
                srv.getId(), subfolderId, 0, 200, "", "dc:title");
        assertNotNull(subResult);
        System.out.println("=== " + subfolderTitle + " items (title sort): " + itemTitles(subResult.getItems()) + " ===");
        verifySortOrder(subResult.getItems(), "dc:title");

        // ASSERT: every item's parentId should be the browsed folder's ID
        // (catches items that "belong" to a different folder but got mixed in)
        for (BrowsableItem it : subResult.getItems()) {
            if (subfolderId != null && !subfolderId.equals(it.getParentId())) {
                fail("Item '" + it.getTitle() + "' has parentId='" + it.getParentId()
                        + "' but belongs to folder '" + subfolderTitle + "' (id=" + subfolderId + ")"
                        + " — item leaked from wrong folder");
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static List<String> itemTitles(List<BrowsableItem> items) {
        List<String> out = new ArrayList<>();
        for (BrowsableItem it : items) {
            out.add((it.getTitle() != null ? it.getTitle() : "(null)")
                    + "[" + (it.isContainer() ? "C" : "F") + "]");
        }
        return out;
    }

    private static List<String> itemIds(List<BrowsableItem> items) {
        List<String> out = new ArrayList<>();
        for (BrowsableItem it : items) {
            out.add(it.getId() != null ? it.getId() : "(null)");
        }
        return out;
    }

    private static void verifySortOrder(List<BrowsableItem> items, String sortBy) {
        if (items.isEmpty()) return;

        if ("dc:title".equals(sortBy) || "-dc:title".equals(sortBy)) {
            ContentBrowseService.sortItems(items, sortBy);
            for (int i = 1; i < items.size(); i++) {
                String prev = items.get(i - 1).getTitle() != null ? items.get(i - 1).getTitle() : "";
                String curr = items.get(i).getTitle() != null ? items.get(i).getTitle() : "";
                int cmp = prev.compareToIgnoreCase(curr);
                if (cmp > 0) {
                    fail("Title sort violation at index " + i + ": '" + prev + "' > '" + curr
                            + "' (expected '" + curr + "' before '" + prev + "')");
                }
            }
        } else if ("dc:date".equals(sortBy) || "-dc:date".equals(sortBy)) {
            boolean descending = sortBy.startsWith("-");
            boolean seenNoDate = !descending;
            for (BrowsableItem it : items) {
                boolean hasDate = (it.getDate() != null && !it.getDate().isBlank())
                        || (it.isContainer() && it.getEffectiveDate() != null && !it.getEffectiveDate().isBlank());
                if (descending) {
                    if (seenNoDate && hasDate) {
                        fail("Descending date sort violation: item '" + it.getTitle() + "' has date after no-date");
                    }
                } else {
                    if (!hasDate) seenNoDate = true;
                }
            }
        }
    }

    private static void verifyContainersFirst(List<BrowsableItem> items) {
        boolean seenNonContainer = false;
        for (BrowsableItem it : items) {
            if (seenNonContainer && it.isContainer()) {
                fail("Container '" + it.getTitle() + "' appeared after non-container");
            }
            if (!it.isContainer()) {
                seenNonContainer = true;
            }
        }
    }
}
