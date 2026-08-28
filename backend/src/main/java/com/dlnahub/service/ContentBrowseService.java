package com.dlnahub.service;

import com.dlnahub.dlna.UpnpServiceManager;
import com.dlnahub.dlna.model.BrowseResult;
import com.dlnahub.dlna.model.BrowsableItem;
import com.dlnahub.dlna.model.DateEvent;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
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
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /* Effective-date stream (SSE) limits. The blocking browse keeps ENRICH_MAX_ITEMS_PER_CALL as
     * its latency budget; the background completion stream is deliberately far above it so that, in
     * practice, every container in a folder finishes. The per-subtree hard caps (CRAWL_MAX_TOTAL_ITEMS,
     * CRAWL_MAX_DEPTH) and the wall-clock deadline remain the safety rails. */
    private static final int STREAM_ITEM_BUDGET = CRAWL_MAX_TOTAL_ITEMS * 20; // 1,000,000 items
    private static final long STREAM_DEADLINE_MS = 8 * 60 * 1000L;           // wall-clock cap for one stream
    private static final long STREAM_EMITTER_TIMEOUT_MS = 10 * 60 * 1000L;   // SseEmitter timeout (above the deadline)
    private static final String STREAM_CHILDREN_FILTER = "dc:title,upnp:class,dc:date";

    /**
     * Small pool for background effective-date stream jobs. Two threads are plenty for a LAN
     * hub: streams are per (server, folder) and the owner browses one folder at a time; the
     * pool just keeps the stream work off the request thread.
     */
    private final ExecutorService dateStreamPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "date-stream");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdownDateStreamPool() {
        dateStreamPool.shutdownNow();
    }


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

    /** Per-container crawl locks, so two concurrent requests do not crawl the same subtree twice. */
    private final Map<String, Object> containerDateLocks = new ConcurrentHashMap<>();

    private static final int MAX_TRACKED_CONTAINER_LOCKS = 4_096;

    private Object crawlLock(String serverId, String containerId) {
        if (containerDateLocks.size() > MAX_TRACKED_CONTAINER_LOCKS) {
            // Bound the lock map. Dropping locks is safe: the worst case is two requests
            // crawling the same subtree concurrently, which is correct, just wasteful.
            containerDateLocks.clear();
        }
        return containerDateLocks.computeIfAbsent(serverId + "/" + containerId, k -> new Object());
    }

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

    /**
     * Fully-fetched, sorted result sets, so that paging through a client-sorted container or
     * an in-memory search does not re-fetch the whole container for every 50-item page.
     * Deliberately short-lived: this is a paging aid, not a library cache. Cross-check with
     * containerDateCache, which has its own (SystemUpdateID-based) invalidation.
     */
    private static final long SORTED_PAGE_CACHE_TTL_MS = 60_000L;
    private static final int SORTED_PAGE_CACHE_MAX_ENTRIES = 32;

    private record SortedSet(List<BrowsableItem> items, long computedAt) {
    }

    private final Map<String, SortedSet> sortedPageCache = new ConcurrentHashMap<>();

    private static String sortedCacheKey(String serverId, String scope, String objectId, String sortBy) {
        return serverId + "\u0000" + scope + "\u0000" + objectId + "\u0000" + (sortBy == null ? "" : sortBy);
    }

    /** Returns the cached set if it is still within its TTL, otherwise null. */
    private List<BrowsableItem> cachedSortedSet(String key) {
        SortedSet cached = sortedPageCache.get(key);
        if (cached == null) return null;
        if (System.currentTimeMillis() - cached.computedAt() >= SORTED_PAGE_CACHE_TTL_MS) {
            sortedPageCache.remove(key);
            return null;
        }
        return cached.items();
    }

    private void cacheSortedSet(String key, List<BrowsableItem> items) {
        if (sortedPageCache.size() >= SORTED_PAGE_CACHE_MAX_ENTRIES) {
            // Drop the oldest entry rather than clearing everything, so an active browse keeps its set.
            sortedPageCache.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().computedAt()))
                    .ifPresent(e -> sortedPageCache.remove(e.getKey()));
        }
        sortedPageCache.put(key, new SortedSet(List.copyOf(items), System.currentTimeMillis()));
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
        return browse(serverId, objectId, index, count, filter, sortBy, false);
    }

    /**
     * Browses a container. When {@code skipEnrich} is set and the sort is by date, the expensive
     * effective-date enrichment ({@link #enrichContainerDates}) is skipped so the first page
     * returns immediately; the client then opens the SSE date stream to fill in dates in the
     * background. With {@code skipEnrich} false (or a non-date sort) the behavior is unchanged.
     */
    public BrowseResult browse(String serverId, String objectId, int index, int count,
                               String filter, String sortBy, boolean skipEnrich) {
        // Container effective-date enrichment is a date-ordering workaround: it costs an extra
        // GetSystemUpdateID call (and possibly subtree crawls), so it only runs when the
        // requested order is by date. Every other sort uses the server's order as-is.
        boolean dateSort = isDateSort(sortBy);
        boolean clientSort = needsClientSort(sortBy) && !supportsServerSort(serverId);
        if (clientSort) {
            // Server does not sort on its side (e.g. Synology silently ignores SortCriteria) —
            // we fetch the whole container, sort in memory and page locally. The sorted set is
            // cached briefly so that scrolling does not re-fetch the container for every page.
            String cacheKey = sortedCacheKey(serverId, "browse", objectId, sortBy + (!skipEnrich ? "|E" : "|N"));
            List<BrowsableItem> all = cachedSortedSet(cacheKey);
            if (all == null) {
                String effectiveFilter = dateSort ? ensureFilterField(filter, "dc:date") : filter;
                all = fetchAllChildren(serverId, objectId, effectiveFilter, sortBy);
                if (dateSort) {
                    all = enrichContainerDates(serverId, all, !skipEnrich);
                }
                all = new ArrayList<>(all);
                sortItems(all, sortBy);
                cacheSortedSet(cacheKey, all);
            }
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

        int totalMatches = parseTotalMatches(totalMatchesStr);

        List<BrowsableItem> items = parseBrowseResult(resultXml, serverId);
        if (dateSort) {
            items = enrichContainerDates(serverId, items, !skipEnrich);
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

        int totalMatches = parseTotalMatches(totalMatchesStr);

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
                    String cacheKey = sortedCacheKey(serverId, "searchAction:" + query.toLowerCase(), containerId, sortBy);
                    List<BrowsableItem> all = cachedSortedSet(cacheKey);
                    if (all == null) {
                        all = fetchAllSearchResults(serverId, contentDir, containerId, query, filter, sortBy);
                        if (isDateSort(sortBy)) {
                            all = enrichContainerDates(serverId, all);
                        }
                        all = new ArrayList<>(all);
                        sortItems(all, sortBy);
                        cacheSortedSet(cacheKey, all);
                    }
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

        int totalMatches = parseTotalMatches(totalMatchesStr);
        return new BrowseResult(parseBrowseResult(resultXml, serverId), totalMatches, index, count, updateIdValue);
    }

    /**
     * Filters the direct children of the container the user is currently looking at.
     *
     * Deliberately NOT recursive. A subtree walk was tried and reverted: the browse list shows
     * only a title per row, with no room for a path, so hits from three folders down read as
     * items the current folder does not contain — and tapping one appended it to the current
     * breadcrumb, inventing a parent/child relationship that does not exist. Searching from the
     * library root also crawled the whole NAS on every distinct query. Search here narrows what
     * is on screen; that is the behaviour the UI can actually present.
     *
     * Note this differs from the ContentDirectory Search action (searchViaAction), which is
     * subtree-scoped by the UPnP spec and cannot be asked for direct children only. Servers that
     * answer Search therefore still search recursively. The test Synology answers UPnP 501, so
     * this path is the one in use here.
     */
    private BrowseResult searchInMemory(String serverId, String containerId, String query, int index, int count,
                                         String filter, String sortBy) {
        String cacheKey = sortedCacheKey(serverId, "search:" + query.toLowerCase(), containerId, sortBy);
        List<BrowsableItem> cached = cachedSortedSet(cacheKey);
        if (cached != null) {
            return pagedResult(serverId, cached, index, count);
        }
        List<BrowsableItem> allItems = fetchAllChildren(serverId, containerId, filter, sortBy);

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

        cacheSortedSet(cacheKey, matching);
        return pagedResult(serverId, matching, index, count);
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
     * Sink abstraction for the effective-date stream: the production sink writes SSE events;
     * tests collect events. Throwing {@link IOException} signals that the client went away and
     * the stream must stop.
     */
    @FunctionalInterface
    interface DateStreamSink {
        void send(DateEvent event) throws IOException;
    }

    /**
     * Opens an SSE stream that completes the container effective-date cache for one folder.
     *
     * <p>The blocking browse response (see {@link #browse}) returns quickly with a capped,
     * best-effort set of container dates. This stream finishes crawling any containers whose
     * dates are still unknown — off the request path, with a much larger budget and a
     * wall-clock deadline — and pushes each newly-known date as an SSE event, ending with a
     * terminal {@code allDone} event. The client splices each date in and re-sorts live.
     *
     * <p>For a non-date sort there is nothing to stream: the stream completes immediately with
     * the terminal event.
     */
    public SseEmitter openDateStream(String serverId, String objectId, String sortBy) {
        SseEmitter emitter = new SseEmitter(STREAM_EMITTER_TIMEOUT_MS);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(t -> cancelled.set(true));
        DateStreamSink sink = emitterSink(emitter, cancelled);

        if (!isDateSort(sortBy)) {
            // Nothing to stream for a non-date sort; emit the terminal event and complete.
            try {
                sink.send(DateEvent.terminalEvent());
            } catch (IOException e) {
                log.debug("Date stream for server={} container={} could not emit allDone: {}",
                        serverId, objectId, e.getMessage());
            }
            emitter.complete();
            return emitter;
        }

        dateStreamPool.execute(() -> runDateStreamJob(serverId, objectId, emitter, sink, cancelled));
        return emitter;
    }

    /** Wraps an {@link SseEmitter} as a sink that refuses to send once the stream was cancelled. */
    private DateStreamSink emitterSink(SseEmitter emitter, AtomicBoolean cancelled) {
        return event -> {
            if (cancelled.get()) {
                throw new IOException("date stream closed");
            }
            emitter.send(SseEmitter.event().name("date").data(event));
        };
    }

    private void runDateStreamJob(String serverId, String objectId,
                                  SseEmitter emitter, DateStreamSink sink, AtomicBoolean cancelled) {
        try {
            // runDateStream ends by sending the terminal allDone event.
            runDateStream(serverId, objectId, sink);
            emitter.complete();
        } catch (IOException e) {
            // The client disconnected mid-stream (or the emitter was cancelled); stop quietly.
            log.debug("Date stream for server={} container={} ended: {}", serverId, objectId, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Date stream for server={} container={} failed: {}", serverId, objectId, e.getMessage());
            try {
                sink.send(DateEvent.terminalEvent());
            } catch (IOException ignored) {
                // best effort: the client is probably already gone
            }
            emitter.completeWithError(e);
        }
    }

    /**
     * Runs the completion crawl for one folder: fetches the folder's direct children, then crawls
     * any container whose effective date is not already fresh in the cache, emitting a
     * {@link DateEvent} per newly-known date and a terminal event at the end.
     *
     * <p>Containers already fresh in the cache produce no event. The crawl runs under a
     * deliberately large shared budget (see {@link #STREAM_ITEM_BUDGET}) and is bounded by the
     * per-subtree caps and a wall-clock deadline.
     */
    void runDateStream(String serverId, String objectId, DateStreamSink sink) throws IOException {
        List<BrowsableItem> children = fetchAllChildren(serverId, objectId, STREAM_CHILDREN_FILTER, "");
        String updateId = getSystemUpdateId(serverId);
        Map<String, ContainerDateEntry> cache =
                containerDateCache.computeIfAbsent(serverId, k -> new ConcurrentHashMap<>());
        long deadline = System.currentTimeMillis() + STREAM_DEADLINE_MS;
        CrawlBudget budget = new CrawlBudget(STREAM_ITEM_BUDGET);

        for (BrowsableItem item : children) {
            if (System.currentTimeMillis() > deadline) {
                break;
            }
            if (!item.isContainer()) {
                continue;
            }
            ContainerDateEntry fresh = crawlIfStale(serverId, item.getId(), updateId, budget, cache);
            if (fresh == null) {
                continue; // already known, or the crawl did not complete; retry on a later stream
            }
            String date = fresh.latestDate() != null ? fresh.latestDate().toString() : null;
            sink.send(DateEvent.dateEvent(item.getId(), date, true));
        }
        sink.send(DateEvent.terminalEvent());
    }

    /**
     * Crawls one container's subtree under its per-container lock with the given budget and,
     * when the crawl completes, replaces the cache entry and returns it. Returns null when the
     * entry is already fresh or the crawl did not complete (the caller must not emit an event
     * and the next stream/request will retry).
     */
    private ContainerDateEntry crawlIfStale(String serverId, String containerId, String updateId,
                                            CrawlBudget budget, Map<String, ContainerDateEntry> cache) {
        synchronized (crawlLock(serverId, containerId)) {
            ContainerDateEntry current = cache.get(containerId);
            if (isFresh(current, updateId, System.currentTimeMillis())) {
                return null;
            }
            CrawlResult result = crawlSubtree(serverId, containerId, updateId, 0, budget, cache);
            if (!result.complete) {
                return null;
            }
            ContainerDateEntry fresh = new ContainerDateEntry(updateId, result.date, System.currentTimeMillis());
            cache.put(containerId, fresh);
            return fresh;
        }
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
        return enrichContainerDates(serverId, items, true);
    }

    /**
     * Enriches container items with their effective dates so date sorting and date display work
     * for folders. When {@code crawl} is true, unknown or stale dates are filled in by crawling
     * the container subtrees (the expensive path). When {@code crawl} is false, only dates already
     * in the cache are applied, so the call is a cheap cache read (no UPnP traffic); this lets a
     * date-sorted browse return instantly on a revisit while the SSE stream does the crawling.
     * Never fails the browse: on any error the unenriched list is returned.
     */
    List<BrowsableItem> enrichContainerDates(String serverId, List<BrowsableItem> items, boolean crawl) {
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

        String updateId = crawl ? getSystemUpdateId(serverId) : null;
        long now = System.currentTimeMillis();

        try {
            Map<String, ContainerDateEntry> cache =
                    containerDateCache.computeIfAbsent(serverId, k -> new ConcurrentHashMap<>());
            CrawlBudget budget = crawl ? new CrawlBudget(ENRICH_MAX_ITEMS_PER_CALL) : null;
            int crawls = 0;
            List<BrowsableItem> out = new ArrayList<>(items.size());
            for (BrowsableItem item : items) {
                if (!item.isContainer()) {
                    out.add(item);
                    continue;
                }
                ContainerDateEntry entry = cache.get(item.getId());
                if (crawl && !isFresh(entry, updateId, now) && crawls < ENRICH_MAX_CRAWLS_PER_CALL) {
                    crawls++;
                    entry = crawlAndCache(serverId, item.getId(), updateId, now, budget, cache, entry);
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

    /**
     * Crawls one container's subtree under a per-container lock, so two concurrent requests do
     * not duplicate the work. The lock covers only this container's crawl — never the whole
     * enrichment pass, which would serialise every date-sorted browse of the server.
     * Re-checks the cache inside the lock: the request we queued behind may have just filled it.
     */
    private ContainerDateEntry crawlAndCache(String serverId, String containerId, String updateId,
                                             long now, CrawlBudget budget,
                                             Map<String, ContainerDateEntry> cache,
                                             ContainerDateEntry stale) {
        synchronized (crawlLock(serverId, containerId)) {
            ContainerDateEntry current = cache.get(containerId);
            if (isFresh(current, updateId, System.currentTimeMillis())) {
                return current;
            }
            CrawlResult result = crawlSubtree(serverId, containerId, updateId, 0, budget, cache);
            // Only complete crawls replace the entry; partial (budget-exhausted) results keep
            // the previous one so the last known date keeps being served while a later request
            // finishes the subtree.
            if (result.complete) {
                ContainerDateEntry fresh = new ContainerDateEntry(updateId, result.date, now);
                cache.put(containerId, fresh);
                return fresh;
            }
            return stale;
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

    /** TotalMatches from a misbehaving server may be absent or non-numeric; treat it as 0. */
    private static int parseTotalMatches(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            log.warn("Server returned a non-numeric TotalMatches: {}", value);
            return 0;
        }
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
