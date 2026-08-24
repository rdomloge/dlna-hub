package com.dlnahub.service;

import com.dlnahub.dlna.UpnpServiceManager;
import com.dlnahub.dlna.model.BrowseResult;
import com.dlnahub.dlna.model.BrowsableItem;
import com.dlnahub.exception.DeviceNotFoundException;
import com.dlnahub.exception.DlnaException;
import org.jupnp.controlpoint.ActionCallback;
import org.jupnp.controlpoint.ControlPoint;
import org.jupnp.model.action.ActionArgumentValue;
import org.jupnp.model.action.ActionException;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.meta.Action;
import org.jupnp.model.meta.ActionArgument;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.model.types.UDAServiceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Service
public class ContentBrowseService {

    private static final Logger log = LoggerFactory.getLogger(ContentBrowseService.class);

    private static final List<String> NS = Arrays.asList(
            "http://purl.org/dc/elements/1.1/",
            "urn:schemas-upnp-org:metadata-1-0/",
            "urn:schemas-dlna-org:metadata-1-0/"
    );

    private final ServerBrowseService serverBrowseService;
    private final ThumbnailService thumbnailService;
    private final UpnpServiceManager upnpServiceManager;

    /**
     * Cached result of the GetSortCapabilities probe, keyed by server id: whether the server
     * actually sorts on its side. Only successful probes are cached; failed probes are retried.
     */
    private final Map<String, Boolean> serverSortSupport = new ConcurrentHashMap<>();

    /* Container effective dates (see the section below): budget and cache limits. */
    private static final int CRAWL_PAGE_SIZE = 500;
    private static final int CRAWL_MAX_TOTAL_ITEMS = 50_000;   // hard cap for a single subtree crawl
    private static final int CRAWL_MAX_DEPTH = 50;
    private static final int ENRICH_MAX_ITEMS_PER_CALL = 2_000; // items visited per enrich call (latency budget)
    private static final int ENRICH_MAX_CRAWLS_PER_CALL = 300;  // top-level subtrees crawled per enrich call
    private static final int CACHE_MAX_ENTRIES = 10_000;        // per-server cap; beyond it the cache is cleared
    private static final long NO_UPDATE_ID_TTL_MS = 10 * 60 * 1000L; // freshness fallback when no SystemUpdateID
    /**
     * Grace period for SystemUpdateID churn. Measured on the test Synology NAS: the global
     * SystemUpdateID bumps roughly every 30-90 s with zero library changes, so strict
     * updateId equality would invalidate the whole cache between nearly every request.
     * Entries are therefore kept usable for a short time after the updateId changes
     * (stale-while-revalidate); a complete re-crawl under the new id replaces them.
     */
    private static final long STALE_GRACE_MS = 2 * 60 * 1000L;

    /** container-id -> latest descendant media date, keyed per server and invalidated by SystemUpdateID. */
    private final Map<String, Map<String, ContainerDateEntry>> containerDateCache = new ConcurrentHashMap<>();
    private final Map<String, Object> containerDateLocks = new ConcurrentHashMap<>();

    record ContainerDateEntry(String updateId, Instant latestDate, long computedAt) {
    }

    private static final class CrawlResult {
        final Instant date;
        final boolean complete;

        CrawlResult(Instant date, boolean complete) {
            this.date = date;
            this.complete = complete;
        }
    }

    private static final class CrawlBudget {
        final int max;
        int visited;

        CrawlBudget(int max) {
            this.max = max;
        }

        boolean exhausted() {
            return visited >= max;
        }
    }

    @Autowired
    public ContentBrowseService(ServerBrowseService serverBrowseService,
                                 ThumbnailService thumbnailService,
                                 UpnpServiceManager upnpServiceManager) {
        this.serverBrowseService = serverBrowseService;
        this.thumbnailService = thumbnailService;
        this.upnpServiceManager = upnpServiceManager;
    }

    public BrowseResult browse(String serverId, String objectId, int index, int count,
                               String filter, String sortBy) {
        // Container effective-date enrichment is a date-ordering workaround: it costs an extra
        // GetSystemUpdateID call (and possibly subtree crawls), so it only runs when the
        // requested order is by date. Every other sort uses the server's order as-is.
        boolean dateSort = isDateSort(sortBy);
        boolean clientSort = needsClientSort(sortBy) && !supportsServerSort(serverId);
        if (clientSort) {
            // Server does not sort on its side (e.g. Synology silently ignores SortCriteria) —
            // we fetch the whole container, sort in memory and page locally. See sortItems().
            String effectiveFilter = dateSort ? ensureFilterField(filter, "dc:date") : filter;
            List<BrowsableItem> all = fetchAllChildren(serverId, objectId, effectiveFilter, sortBy);
            if (dateSort) {
                all = enrichContainerDates(serverId, all);
            }
            sortItems(all, sortBy);
            return pagedResult(serverId, all, index, count);
        }
        RemoteDevice device = serverBrowseService.getDevice(serverId);
        if (device == null) {
            throw new DeviceNotFoundException("Server not found: " + serverId);
        }

        RemoteService contentDir = device.findService(new UDAServiceType("ContentDirectory"));
        if (contentDir == null) {
            throw new IllegalStateException("ContentDirectory service not found on server: " + serverId);
        }

        Action browseAction = contentDir.getAction("Browse");
        if (browseAction == null) {
            throw new IllegalStateException("Browse action not found on ContentDirectory service");
        }

        ActionInvocation invocation = new ActionInvocation(browseAction);
        invocation.setInput("objectID", objectId);
        invocation.setInput("browseFlag", "BrowseDirectChildren");
        invocation.setInput("Filter", filter != null ? filter : "");
        invocation.setInput("StartingIndex", String.valueOf(index));
        invocation.setInput("RequestedCount", String.valueOf(count));
        invocation.setInput("SortCriteria", sortBy != null ? sortBy : "");

        executeSync(invocation);

        String resultXml = getOutputString(invocation, "Result");
        String totalMatchesStr = getOutputString(invocation, "TotalMatches");
        String updateIdValue = getOutputString(invocation, "UpdateID");

        int totalMatches = totalMatchesStr != null ? Integer.parseInt(totalMatchesStr) : 0;

        List<BrowsableItem> items = parseBrowseResult(resultXml, serverId);
        if (dateSort) {
            items = enrichContainerDates(serverId, items);
        }

        for (BrowsableItem item : items) {
            if (item.getThumbnailUrl() != null && !item.getThumbnailUrl().isEmpty()) {
                thumbnailService.cache(serverId, item.getId(), item.getThumbnailUrl());
            }
        }

        return new BrowseResult(items, totalMatches, index, count, updateIdValue);
    }

    private BrowseResult browseInternal(String serverId, String objectId, int index, int count,
                                         String filter, String sortBy) {
        RemoteDevice device = serverBrowseService.getDevice(serverId);
        if (device == null) {
            throw new DeviceNotFoundException("Server not found: " + serverId);
        }

        RemoteService contentDir = device.findService(new UDAServiceType("ContentDirectory"));
        if (contentDir == null) {
            throw new IllegalStateException("ContentDirectory service not found on server: " + serverId);
        }

        Action browseAction = contentDir.getAction("Browse");
        if (browseAction == null) {
            throw new IllegalStateException("Browse action not found on ContentDirectory service");
        }

        ActionInvocation invocation = new ActionInvocation(browseAction);
        invocation.setInput("objectID", objectId);
        invocation.setInput("browseFlag", "BrowseDirectChildren");
        invocation.setInput("Filter", filter != null ? filter : "");
        invocation.setInput("StartingIndex", String.valueOf(index));
        invocation.setInput("RequestedCount", String.valueOf(count));
        invocation.setInput("SortCriteria", sortBy != null ? sortBy : "");

        executeSync(invocation);

        String resultXml = getOutputString(invocation, "Result");
        String totalMatchesStr = getOutputString(invocation, "TotalMatches");
        String updateIdValue = getOutputString(invocation, "UpdateID");

        int totalMatches = totalMatchesStr != null ? Integer.parseInt(totalMatchesStr) : 0;

        List<BrowsableItem> items = parseBrowseResult(resultXml, serverId);

        return new BrowseResult(items, totalMatches, index, count, updateIdValue);
    }

    public BrowseResult search(String serverId, String containerId, String query, int index, int count,
                                String filter, String sortBy) {
        if (query == null || query.trim().isEmpty()) {
            return new BrowseResult(new ArrayList<>(), 0, index, count, "");
        }

        RemoteDevice device = serverBrowseService.getDevice(serverId);
        if (device == null) {
            throw new DeviceNotFoundException("Server not found: " + serverId);
        }

        RemoteService contentDir = device.findService(new UDAServiceType("ContentDirectory"));
        if (contentDir == null) {
            throw new IllegalStateException("ContentDirectory service not found on server: " + serverId);
        }

        Action searchAction = contentDir.getAction("Search");
        if (searchAction != null) {
            try {
                if (needsClientSort(sortBy) && !supportsServerSort(serverId)) {
                    List<BrowsableItem> all = fetchAllSearchResults(serverId, contentDir, containerId, query, filter, sortBy);
                    if (isDateSort(sortBy)) {
                        all = enrichContainerDates(serverId, all);
                    }
                    sortItems(all, sortBy);
                    return pagedResult(serverId, all, index, count);
                }
                return searchViaAction(serverId, contentDir, containerId, query, index, count, filter, sortBy);
            } catch (RuntimeException e) {
                // e.g. Synology declares Search in its SCPD but answers with UPnP error 501
                log.warn("Search action failed on {} ({}), falling back to in-memory search",
                        serverId, e.getMessage());
            }
        } else {
            log.info("Search action not available on {}, falling back to in-memory search", serverId);
        }
        return searchInMemory(serverId, containerId, query, index, count, filter, sortBy);
    }

    private BrowseResult searchViaAction(String serverId, RemoteService contentDir, String containerId,
                                          String query, int index, int count, String filter, String sortBy) {
        BrowseResult page = searchPage(serverId, contentDir, containerId, query, index, count, filter, sortBy);
        for (BrowsableItem item : page.getItems()) {
            if (item.getThumbnailUrl() != null && !item.getThumbnailUrl().isEmpty()) {
                thumbnailService.cache(serverId, item.getId(), item.getThumbnailUrl());
            }
        }
        return page;
    }

    private BrowseResult searchPage(String serverId, RemoteService contentDir, String containerId,
                                     String query, int index, int count, String filter, String sortBy) {
        Action searchAction = contentDir.getAction("Search");
        ActionInvocation invocation = new ActionInvocation(searchAction);
        invocation.setInput("containerID", containerId);
        invocation.setInput("searchCriteria", buildSearchCriteria(query));
        invocation.setInput("Filter", filter != null ? filter : "");
        invocation.setInput("StartingIndex", String.valueOf(index));
        invocation.setInput("RequestedCount", String.valueOf(count));
        invocation.setInput("SortCriteria", sortBy != null ? sortBy : "");

        executeSync(invocation);

        String resultXml = getOutputString(invocation, "Result");
        String totalMatchesStr = getOutputString(invocation, "TotalMatches");
        String updateIdValue = getOutputString(invocation, "UpdateID");

        int totalMatches = totalMatchesStr != null ? Integer.parseInt(totalMatchesStr) : 0;
        return new BrowseResult(parseBrowseResult(resultXml, serverId), totalMatches, index, count, updateIdValue);
    }

    private BrowseResult searchInMemory(String serverId, String containerId, String query, int index, int count,
                                         String filter, String sortBy) {
        List<BrowsableItem> allItems = new ArrayList<>();
        int startIdx = 0;
        int pageSize = 500;

        while (true) {
            BrowseResult page = browseInternal(serverId, containerId, startIdx, pageSize, filter, sortBy);
            allItems.addAll(page.getItems());
            if (page.getItems().size() < pageSize) {
                break;
            }
            startIdx += page.getItems().size();
            if (startIdx > 50000) {
                log.warn("In-memory search exceeded item limit for server={}, container={}", serverId, containerId);
                break;
            }
        }

        String q = query.toLowerCase();
        List<BrowsableItem> matching = allItems.stream()
                .filter(item -> {
                    String title = item.getTitle() != null ? item.getTitle().toLowerCase() : "";
                    String artist = item.getArtist() != null ? item.getArtist().toLowerCase() : "";
                    String album = item.getAlbum() != null ? item.getAlbum().toLowerCase() : "";
                    return title.contains(q) || artist.contains(q) || album.contains(q);
                })
                .collect(java.util.stream.Collectors.toList());

        if (isDateSort(sortBy)) {
            matching = enrichContainerDates(serverId, matching);
        }
        if (needsClientSort(sortBy)) {
            sortItems(matching, sortBy);
        }

        int total = matching.size();
        int from = Math.min(index, total);
        int to = Math.min(from + count, total);
        List<BrowsableItem> paged = matching.subList(from, to);

        for (BrowsableItem item : paged) {
            if (item.getThumbnailUrl() != null && !item.getThumbnailUrl().isEmpty()) {
                thumbnailService.cache(serverId, item.getId(), item.getThumbnailUrl());
            }
        }

        return new BrowseResult(paged, total, index, count, "");
    }

    private String buildSearchCriteria(String query) {
        String escaped = query.replace("\\", "\\\\").replace("'", "\\'");
        return "(dc:title contains '" + escaped + "' OR dc:creator contains '" + escaped
                + "' OR upnp:album contains '" + escaped + "')";
    }

    /* ##################################################################################################### */
    /* Client-side sorting                                                                                 */
    /* Some DLNA servers (notably Synology) silently ignore SortCriteria: GetSortCapabilities returns      */
    /* empty and item order never changes regardless of the requested sort. When a sort is requested we   */
    /* therefore fetch the whole container (or search result set), sort it in memory and page it locally. */
    /* ##################################################################################################### */

    private static boolean needsClientSort(String sortBy) {
        return sortBy != null && !sortBy.isEmpty();
    }

    /**
     * Whether the requested sort orders by date (dc:date or -dc:date). Container
     * effective-date enrichment is a workaround for date ordering only and is skipped
     * for every other sort, which uses the server's order as-is.
     */
    static boolean isDateSort(String sortBy) {
        return sortBy != null && sortBy.contains("dc:date");
    }

    /**
     * Asks the server (once, cached) whether it sorts on its own side via GetSortCapabilities.
     * Servers that return an empty capability list (e.g. Synology) silently ignore SortCriteria,
     * so for those we sort in memory instead. Failed probes are not cached and are retried next
     * time, degrading to client-side sorting in the meantime.
     */
    private boolean supportsServerSort(String serverId) {
        Boolean cached = serverSortSupport.get(serverId);
        if (cached != null) {
            return cached;
        }
        Boolean result = probeSortSupport(serverId);
        if (result != null) {
            serverSortSupport.put(serverId, result);
        }
        return result != null ? result : false;
    }

    /**
     * Calls GetSortCapabilities on the server's ContentDirectory service via raw SOAP.
     * @return true if the server reports sort capabilities, false if the list is empty, null on failure.
     */
    private Boolean probeSortSupport(String serverId) {
        try {
            RemoteDevice device = serverBrowseService.getDevice(serverId);
            if (device == null) return null;
            RemoteService contentDir = device.findService(new UDAServiceType("ContentDirectory"));
            if (contentDir == null) return null;

            // Build full URL from device descriptor location + relative control URI
            String deviceLocation = device.getIdentity().getDescriptorURL() != null
                    ? device.getIdentity().getDescriptorURL().toString() : null;
            String controlUri = contentDir.getControlURI().toString();
            String controlUrl;
            if (deviceLocation != null && controlUri.startsWith("/")) {
                try {
                    controlUrl = new java.net.URL(new java.net.URL(deviceLocation), controlUri).toString();
                } catch (Exception e) {
                    log.warn("Could not build full control URL for {}: {}; probe skipped", serverId, e.getMessage());
                    return null;
                }
            } else {
                controlUrl = controlUri;
            }

            String soapEnvelope = String.join("\n",
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">",
                    "  <s:Body>",
                    "    <u:GetSortCapabilities xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\"></u:GetSortCapabilities>",
                    "  </s:Body>",
                    "</s:Envelope>"
            );

            java.net.URL url = new java.net.URL(controlUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#GetSortCapabilities\"");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.getOutputStream().write(soapEnvelope.getBytes("UTF-8"));

            int status = conn.getResponseCode();
            String responseBody;
            if (status >= 200 && status < 300) {
                responseBody = new String(conn.getInputStream().readNBytes(8192), "UTF-8");
            } else {
                log.warn("GetSortCapabilities returned HTTP {}: {}", status, controlUrl);
                return null;
            }
            conn.disconnect();

            boolean supported = parseSortCaps(responseBody);
            log.info("Server {} {} SortCriteria (GetSortCapabilities={})",
                    serverId, supported ? "supports" : "does not support", supported ? "non-empty" : "<empty>");
            return supported;
        } catch (Exception e) {
            log.warn("GetSortCapabilities probe failed for server {}: {}; assuming no server-side sort",
                    serverId, e.getMessage());
            return null;
        }
    }

    /**
     * Parses the GetSortCapabilities SOAP response to determine if the server reports sort capabilities.
     * The response contains a <SortCaps> element — if empty or absent, the server does not support sorting.
     */
    private static boolean parseSortCaps(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) return false;
        try {
            DocumentBuilder builder = secureDocumentBuilderFactory(false).newDocumentBuilder();
            Document doc = builder.parse(new java.io.ByteArrayInputStream(responseBody.getBytes("UTF-8")));

            // Look for SortCaps anywhere in the response (namespace-agnostic)
            NodeList all = doc.getElementsByTagName("SortCaps");
            if (all.getLength() == 0) return false;
            String caps = all.item(0).getTextContent();
            return caps != null && !caps.trim().isEmpty();
        } catch (Exception e) {
            log.warn("Failed to parse GetSortCapabilities response: {}", e.getMessage());
            return false;
        }
    }

    private static String ensureFilterField(String filter, String field) {
        if (filter == null || filter.trim().isEmpty() || "*".equals(filter.trim()) || filter.contains(field)) {
            return filter;
        }
        return filter + "," + field;
    }

    private List<BrowsableItem> fetchAllChildren(String serverId, String objectId, String filter, String sortBy) {
        List<BrowsableItem> all = new ArrayList<>();
        final int pageSize = 500;
        int start = 0;
        while (true) {
            BrowseResult page = browseInternal(serverId, objectId, start, pageSize, filter, sortBy);
            all.addAll(page.getItems());
            if (page.getItems().size() < pageSize) {
                break;
            }
            start += page.getItems().size();
            if (start > 50000) {
                log.warn("Client-side sort exceeded item limit for server={}, container={}", serverId, objectId);
                break;
            }
        }
        return all;
    }

    private List<BrowsableItem> fetchAllSearchResults(String serverId, RemoteService contentDir, String containerId,
                                                       String query, String filter, String sortBy) {
        List<BrowsableItem> all = new ArrayList<>();
        final int pageSize = 500;
        int start = 0;
        while (true) {
            BrowseResult page = searchPage(serverId, contentDir, containerId, query, start, pageSize, filter, sortBy);
            all.addAll(page.getItems());
            if (page.getItems().isEmpty()) {
                break;
            }
            if (page.getTotal() > 0 && all.size() >= page.getTotal()) {
                break;
            }
            start += page.getItems().size();
            if (start > 50000) {
                log.warn("Client-side sort exceeded item limit for server={}, query={}", serverId, query);
                break;
            }
        }
        return all;
    }

    private BrowseResult pagedResult(String serverId, List<BrowsableItem> all, int index, int count) {
        int total = all.size();
        int from = Math.max(0, Math.min(index, total));
        int to = Math.max(from, Math.min(from + count, total));
        List<BrowsableItem> page = new ArrayList<>(all.subList(from, to));
        for (BrowsableItem item : page) {
            if (item.getThumbnailUrl() != null && !item.getThumbnailUrl().isEmpty()) {
                thumbnailService.cache(serverId, item.getId(), item.getThumbnailUrl());
            }
        }
        return new BrowseResult(page, total, index, count, "");
    }

    /**
     * Sorts items in place by the given UPnP sort criterion (dc:title, dc:creator, dc:date,
     * optionally negated with a leading '-'). Containers are kept before items; for dc:date,
     * containers sort by their effective (newest descendant media) date when known, otherwise
     * by their own date; entries with missing values are placed last regardless of direction.
     * Unknown criteria fall back to title.
     */
    static void sortItems(List<BrowsableItem> items, String sortBy) {
        if (items == null || items.isEmpty() || sortBy == null || sortBy.isEmpty()) {
            return;
        }
        boolean desc = sortBy.startsWith("-");
        String field = desc ? sortBy.substring(1) : sortBy;

        Comparator<BrowsableItem> byField;
        boolean containersFirst = "dc:title".equals(field);
        switch (field) {
            case "dc:creator":
                byField = stringKeyComparator(BrowsableItem::getArtist, desc);
                break;
            case "dc:date":
                byField = dateComparator(desc);
                break;
            case "dc:title":
            default:
                byField = stringKeyComparator(BrowsableItem::getTitle, desc);
                break;
        }
        if (containersFirst) {
            items.sort(Comparator.comparing(BrowsableItem::isContainer, Comparator.reverseOrder()).thenComparing(byField));
        } else {
            items.sort(byField);
        }
    }

    private static Comparator<BrowsableItem> stringKeyComparator(Function<BrowsableItem, String> key, boolean desc) {
        return (a, b) -> {
            String va = key.apply(a);
            String vb = key.apply(b);
            if (va == null && vb == null) return 0;
            if (va == null) return 1;
            if (vb == null) return -1;
            // Compare with operands swapped for descending, preserving nulls-last
            return desc
                    ? String.CASE_INSENSITIVE_ORDER.compare(vb, va)
                    : String.CASE_INSENSITIVE_ORDER.compare(va, vb);
        };
    }

    private static Comparator<BrowsableItem> dateComparator(boolean desc) {
        return (a, b) -> {
            Instant da = sortDate(a);
            Instant db = sortDate(b);
            if (da == null && db == null) return 0;
            if (da == null) return 1;
            if (db == null) return -1;
            // Swap operands instead of negating, so nulls-last is preserved for both directions
            int c = desc ? db.compareTo(da) : da.compareTo(db);
            return c;
        };
    }

    /**
     * Parses the date formats DLNA servers emit for dc:date (W3C datetime with or without
     * timezone, without seconds, or plain dates). Naive values are interpreted as UTC.
     * Returns null if the value is missing or unparseable.
     */
    static Instant parseDateInstant(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeException e) {
            // fall through
        }
        try {
            return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
        } catch (DateTimeException e) {
            // fall through
        }
        try {
            return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeException e) {
            // fall through
        }
        return null;
    }

    /* ##################################################################################################### */
    /* Container effective dates                                                                            */
    /* ESTABLISHED FACT (do not re-verify; see AGENTS.md "Test NAS facts"): the test Synology NAS exposes   */
    /* no date data for folders, so a plain date sort would push every folder to the bottom. We therefore   */
    /* compute each container's "effective date": the latest dc:date among all of its descendant media      */
    /* items. Computing it requires recursively crawling                                                   */
    /* the container's subtree with BrowseDirectChildren, so results are cached per (server, container) and */
    /* invalidated by the ContentDirectory's global SystemUpdateID: while it is unchanged, cached dates are */
    /* trusted; when it changes, dates are recomputed for containers as they are encountered. (The cheaper  */
    /* Search-based alternative was investigated first: the testable NAS answers UPnP 501 to any Search     */
    /* criteria and reports no search capabilities, so the Browse fallback is the only viable path.)        */
    /* The enrichment only runs for date-based sorts (isDateSort); other sorts use the server's order.      */
    /* ##################################################################################################### */

    /**
     * Returns the ContentDirectory's global SystemUpdateID for a server, or null if the server
     * does not expose it. Used as the cache-invalidation key for container effective dates.
     * Note: the UpdateID echoed by individual Browse responses is unreliable for sub-containers
     * (the testable NAS answers 0/1 there), so the global value is used instead.
     */
    private String getSystemUpdateId(String serverId) {
        try {
            RemoteDevice device = serverBrowseService.getDevice(serverId);
            if (device == null) return null;
            RemoteService contentDir = device.findService(new UDAServiceType("ContentDirectory"));
            if (contentDir == null) return null;
            Action action = contentDir.getAction("GetSystemUpdateID");
            if (action == null) return null;
            ActionInvocation invocation = new ActionInvocation(action);
            executeSync(invocation);
            return getOutputString(invocation, "Id");
        } catch (Exception e) {
            log.warn("GetSystemUpdateID failed for server {}: {}", serverId, e.getMessage());
            return null;
        }
    }

    /**
     * A cache entry is fresh when it was computed under the current SystemUpdateID. Because some
     * servers (measured: the test Synology NAS) bump their global SystemUpdateID every 30-90 s
     * without any library change, an entry is also kept usable for STALE_GRACE_MS after the id
     * changed — stale-while-revalidate: the next request re-crawls and replaces it. When the
     * server reports no SystemUpdateID at all, the fallback TTL applies.
     */
    static boolean isFresh(ContainerDateEntry entry, String updateId, long now) {
        if (entry == null) return false;
        long age = now - entry.computedAt();
        if (updateId != null && !updateId.isEmpty()) {
            return updateId.equals(entry.updateId()) || age < STALE_GRACE_MS;
        }
        return age < NO_UPDATE_ID_TTL_MS;
    }

    /**
     * The effective date of a set of direct children: files contribute their own dc:date,
     * sub-containers contribute their (already computed) effective dates. Returns the latest of
     * all of them, or null when no descendant has a date.
     */
    static Instant effectiveDate(List<BrowsableItem> children, Map<String, Instant> subContainerDates) {
        Instant latest = null;
        for (BrowsableItem child : children) {
            Instant d = child.isContainer()
                    ? (subContainerDates != null ? subContainerDates.get(child.getId()) : null)
                    : parseDateInstant(child.getDate());
            if (d != null && (latest == null || d.isAfter(latest))) {
                latest = d;
            }
        }
        return latest;
    }

    /**
     * The date used by dc:date sorting: containers sort by their effective (newest descendant
     * media) date when known, otherwise by their own (often missing) date.
     */
    static Instant sortDate(BrowsableItem item) {
        if (item.isContainer() && item.getEffectiveDate() != null) {
            return parseDateInstant(item.getEffectiveDate());
        }
        return parseDateInstant(item.getDate());
    }

    /** Returns a copy of the item with its effective (newest-descendant-media) date attached. */
    static BrowsableItem withEffectiveDate(BrowsableItem item, Instant date) {
        return new BrowsableItem(item.getId(), item.getParentId(), item.getTitle(), item.getArtist(),
                item.getAlbum(), item.getDuration(), item.getResolution(), item.getMimeType(),
                item.getSize(), item.getProtocolInfo(), item.isContainer(), item.getThumbnailUrl(),
                item.getClassType(), item.getDescription(), item.getDate(),
                date != null ? date.toString() : null, item.getResourceName());
    }

    /**
     * Enriches container items with their effective dates so date sorting and date display work
     * for folders. Only invoked for date-ordering requests (see isDateSort): every call costs a
     * GetSystemUpdateID round-trip (plus possibly subtree crawls), so non-date sorts must not
     * pay for it. Fresh cached dates are reused; otherwise up to a per-call budget of the
     * subtree is crawled, so the first visit stays responsive and the cache warms up across
     * successive requests. Never fails the browse: on any error the unenriched list is returned.
     */
    List<BrowsableItem> enrichContainerDates(String serverId, List<BrowsableItem> items) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        boolean hasContainer = false;
        for (BrowsableItem item : items) {
            if (item.isContainer()) { hasContainer = true; break; }
        }
        if (!hasContainer) {
            return items;
        }

        String updateId = getSystemUpdateId(serverId);
        long now = System.currentTimeMillis();

        Object lock = containerDateLocks.computeIfAbsent(serverId, k -> new Object());
        synchronized (lock) {
            try {
                Map<String, ContainerDateEntry> cache =
                        containerDateCache.computeIfAbsent(serverId, k -> new ConcurrentHashMap<>());
                CrawlBudget budget = new CrawlBudget(ENRICH_MAX_ITEMS_PER_CALL);
                int crawls = 0;
                List<BrowsableItem> out = new ArrayList<>(items.size());
                for (BrowsableItem item : items) {
                    if (!item.isContainer()) {
                        out.add(item);
                        continue;
                    }
                    ContainerDateEntry entry = cache.get(item.getId());
                    if (!isFresh(entry, updateId, now)) {
                        if (crawls < ENRICH_MAX_CRAWLS_PER_CALL) {
                            CrawlResult result = crawlSubtree(serverId, item.getId(), updateId, 0, budget, cache);
                            crawls++;
                            // Only complete crawls replace the entry; partial (budget-exhausted)
                            // results keep the previous one so the last known date keeps being
                            // served while a later request finishes the subtree.
                            if (result.complete) {
                                entry = new ContainerDateEntry(updateId, result.date, now);
                                cache.put(item.getId(), entry);
                            }
                        }
                    }
                    if (entry != null && entry.latestDate() != null) {
                        out.add(withEffectiveDate(item, entry.latestDate()));
                    } else {
                        out.add(item);
                    }
                }
                if (cache.size() > CACHE_MAX_ENTRIES) {
                    cache.clear();
                }
                return out;
            } catch (Exception e) {
                log.warn("Container date enrichment failed for server {}: {}", serverId, e.getMessage());
                return items;
            }
        }
    }

    /**
     * Recursively crawls a container's subtree (BrowseDirectChildren, paged) and computes the
     * effective date of the container and of every container encountered along the way. Every
     * fully crawled container is stored in the cache under the given SystemUpdateID. Returns an
     * incomplete result (not to be cached) when the budget is exhausted or the depth cap hit.
     */
    private CrawlResult crawlSubtree(String serverId, String containerId, String updateId, int depth,
                                      CrawlBudget budget, Map<String, ContainerDateEntry> cache) {
        if (depth > CRAWL_MAX_DEPTH || budget.exhausted()) {
            return new CrawlResult(null, false);
        }

        List<BrowsableItem> children = fetchCrawlChildren(serverId, containerId, budget);

        Map<String, Instant> subDates = new HashMap<>();
        for (BrowsableItem child : children) {
            if (!child.isContainer()) {
                continue;
            }
            ContainerDateEntry existing = cache.get(child.getId());
            if (isFresh(existing, updateId, System.currentTimeMillis())) {
                subDates.put(child.getId(), existing.latestDate());
                continue;
            }
            CrawlResult childResult = crawlSubtree(serverId, child.getId(), updateId, depth + 1, budget, cache);
            if (childResult.complete) {
                cache.put(child.getId(), new ContainerDateEntry(updateId, childResult.date, System.currentTimeMillis()));
                subDates.put(child.getId(), childResult.date);
            }
        }

        // If the budget ran out mid-crawl the children list (or a sub-date) is partial:
        // the result is then only an upper bound and must not be cached.
        boolean complete = !budget.exhausted();
        return new CrawlResult(effectiveDate(children, subDates), complete);
    }

    /**
     * Fetches all direct children of a container in pages, counting every visited item against
     * the shared budget. Stops early when the budget is exhausted (the result is then partial).
     */
    private List<BrowsableItem> fetchCrawlChildren(String serverId, String containerId, CrawlBudget budget) {
        List<BrowsableItem> all = new ArrayList<>();
        int start = 0;
        while (!budget.exhausted()) {
            BrowseResult page = browseInternal(serverId, containerId, start, CRAWL_PAGE_SIZE,
                    "dc:title,upnp:class,dc:date", "");
            for (BrowsableItem item : page.getItems()) {
                budget.visited++;
                all.add(item);
            }
            if (page.getItems().size() < CRAWL_PAGE_SIZE) {
                break;
            }
            start += page.getItems().size();
            if (start >= CRAWL_MAX_TOTAL_ITEMS) {
                break;
            }
        }
        return all;
    }

    public List<BrowsableItem> browseMetadata(String serverId, String itemId, String filter) {
        RemoteDevice device = serverBrowseService.getDevice(serverId);
        if (device == null) {
            throw new DeviceNotFoundException("Server not found: " + serverId);
        }

        RemoteService contentDir = device.findService(new UDAServiceType("ContentDirectory"));
        if (contentDir == null) {
            throw new IllegalStateException("ContentDirectory service not found on server: " + serverId);
        }

        Action browseAction = contentDir.getAction("Browse");
        if (browseAction == null) {
            throw new IllegalStateException("Browse action not found on ContentDirectory service");
        }

        ActionInvocation invocation = new ActionInvocation(browseAction);
        invocation.setInput("objectID", itemId);
        invocation.setInput("browseFlag", "BrowseMetadata");
        invocation.setInput("Filter", filter != null ? filter : "*");
        invocation.setInput("StartingIndex", "0");
        invocation.setInput("RequestedCount", "1");
        invocation.setInput("SortCriteria", "");

        executeSync(invocation);

        String resultXml = getOutputString(invocation, "Result");
        // No effective-date enrichment here: metadata fetches have no ordering, and the
        // enrichment (date-ordering workaround) must not add UPnP traffic to them.
        List<BrowsableItem> items = parseBrowseResult(resultXml, serverId);

        for (BrowsableItem item : items) {
            if (item.getThumbnailUrl() != null && !item.getThumbnailUrl().isEmpty()) {
                thumbnailService.cache(serverId, item.getId(), item.getThumbnailUrl());
            }
        }

        return items;
    }

    private void executeSync(ActionInvocation invocation) {
        ActionCallback callback = createCallback(invocation);
        callback.run();

        ActionException failure = invocation.getFailure();
        if (failure != null) {
            int errorCode = failure.getErrorCode() > 0 ? failure.getErrorCode() : -1;
            throw new DlnaException("ContentDirectory action failed: " + failure.getMessage(), errorCode);
        }
    }

    protected ActionCallback createCallback(ActionInvocation invocation) {
        ControlPoint controlPoint = upnpServiceManager.getUpnpService().getControlPoint();
        return new ActionCallback.Default(invocation, controlPoint);
    }

    private String getOutputString(ActionInvocation invocation, String name) {
        ActionArgumentValue output = invocation.getOutput(name);
        if (output == null) return null;
        Object value = output.getValue();
        if (value == null) return null;
        return value.toString();
    }

    private String preprocessMalformedXml(String xml) {
        if (xml == null) return null;
        String cleaned = xml.replaceAll("xmlns:\\w*=\"\"", "");
        log.debug("Preprocessed XML, removed empty namespace declarations");
        return cleaned;
    }

    private List<BrowsableItem> parseBrowseResult(String xml, String serverId) {
        List<BrowsableItem> items = new ArrayList<>();

        if (xml == null || xml.trim().isEmpty()) {
            return items;
        }

        try {
            xml = preprocessMalformedXml(xml);
            DocumentBuilder builder = secureDocumentBuilderFactory(true).newDocumentBuilder();
            Document doc = builder.parse(new java.io.ByteArrayInputStream(xml.getBytes("UTF-8")));
            doc.getDocumentElement().normalize();

            XPathFactory xpathFactory = XPathFactory.newInstance();
            xpathFactory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            XPath xpath = xpathFactory.newXPath();
            NodeList nodeList = (NodeList) xpath.evaluate(
                    "//*[local-name()='item' or local-name()='container']",
                    doc.getDocumentElement(),
                    XPathConstants.NODESET
            );

            for (int i = 0; i < nodeList.getLength(); i++) {
                Element item = (Element) nodeList.item(i);
                BrowsableItem browsable = parseItem(item, serverId);
                if (browsable != null) {
                    items.add(browsable);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse browse result XML", e);
        }

        return items;
    }

    private BrowsableItem parseItem(Element itemEl, String serverId) {
        String id = itemEl.getAttribute("id");
        String parentId = itemEl.getAttribute("parentID");
        String classType = findNsText(itemEl, "class");

        boolean isContainer = classType != null && classType.startsWith("object.container");

        String title = findNsText(itemEl, "title");
        String artist = findNsText(itemEl, "creator");
        String album = findNsText(itemEl, "album");
        String description = findNsText(itemEl, "description");
        String date = findNsText(itemEl, "date");
        String duration = findNsText(itemEl, "duration");

        String resolution = null;
        String mimeType = null;
        String size = null;
        String protocolInfo = null;
        String resourceName = null;
        String thumbnailUrl = null;

        NodeList resources = itemEl.getElementsByTagName("res");
        for (int i = 0; i < resources.getLength(); i++) {
            Element res = (Element) resources.item(i);
            String proto = res.getAttribute("protocolInfo");
            String resText = res.getTextContent();
            String resResolution = res.getAttribute("resolution");
            String resSize = res.getAttribute("size");
            String resDuration = res.getAttribute("duration");

            boolean isThumb = proto != null && proto.contains("albumart");

            if (isThumb) {
                if (thumbnailUrl == null) {
                    thumbnailUrl = resText;
                }
            } else if (mimeType == null) {
                mimeType = extractMimeType(proto);
                protocolInfo = proto;
                resourceName = resText;
                if (resDuration != null && !resDuration.isEmpty()) {
                    duration = resDuration;
                }
                if (resResolution != null && !resResolution.isEmpty()) {
                    resolution = resResolution;
                }
                if (resSize != null && !resSize.isEmpty()) {
                    size = resSize;
                }
            }
        }

        // upnp:artist is the fallback when the server omits dc:creator.
        if (artist == null || artist.isEmpty()) {
            artist = findNsText(itemEl, "artist");
        }

        return new BrowsableItem(id, parentId, title, artist, album, duration,
                resolution, mimeType, size, protocolInfo, isContainer,
                thumbnailUrl, classType, description, date, null, resourceName);
    }

    private String findNsText(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                Element child = (Element) children.item(i);
                if (localName.equals(child.getLocalName()) && isSupportedNs(child.getNamespaceURI())) {
                    String text = child.getTextContent();
                    if (text != null && !text.trim().isEmpty()) {
                        return text.trim();
                    }
                }
            }
        }
        return null;
    }

    private boolean isSupportedNs(String nsUri) {
        if (nsUri == null || nsUri.isEmpty()) return false;
        for (String supported : NS) {
            if (nsUri.startsWith(supported)) return true;
        }
        return false;
    }

    /**
     * Extracts the content format (MIME type) from a DLNA protocolInfo string, which has the
     * shape {@code <protocol>:<network>:<contentFormat>:<additionalInfo>} — e.g.
     * {@code http-get:*:video/x-matroska:DLNA.ORG_PN=AVC_MKV}. The third field is the MIME type;
     * the second is the network field and is almost always "*".
     */
    static String extractMimeType(String protocolInfo) {
        if (protocolInfo == null) return null;
        String[] parts = protocolInfo.split(":");
        if (parts.length >= 3) {
            String contentFormat = parts[2].trim();
            return contentFormat.isEmpty() || "*".equals(contentFormat) ? null : contentFormat;
        }
        return null;
    }

    /**
     * A DocumentBuilderFactory with DTDs and external entities disabled. DIDL-Lite and SOAP
     * responses come from arbitrary devices on the local network, so they are untrusted input.
     * Mirrors the hardening already applied in DidlUtils.extractTitleFromMetadata.
     */
    private static DocumentBuilderFactory secureDocumentBuilderFactory(boolean namespaceAware)
            throws javax.xml.parsers.ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }
}
