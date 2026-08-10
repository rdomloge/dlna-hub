package com.dlnahub.service;

import com.dlnahub.dlna.UpnpServiceManager;
import com.dlnahub.dlna.model.BrowseResult;
import com.dlnahub.dlna.model.BrowsableItem;
import org.jupnp.controlpoint.ActionCallback;
import org.jupnp.controlpoint.ControlPoint;
import org.jupnp.model.action.ActionArgumentValue;
import org.jupnp.model.action.ActionException;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.meta.Action;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        RemoteDevice device = serverBrowseService.getDevice(serverId);
        if (device == null) {
            throw new IllegalArgumentException("Server not found: " + serverId);
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

        for (BrowsableItem item : items) {
            if (item.getThumbnailUrl() != null && !item.getThumbnailUrl().isEmpty()) {
                thumbnailService.cache(serverId, item.getId(), item.getThumbnailUrl());
            }
        }

        return new BrowseResult(items, totalMatches, index, count, updateIdValue);
    }

    public BrowseResult search(String serverId, String containerId, String query, int index, int count,
                                String filter, String sortBy) {
        if (query == null || query.trim().isEmpty()) {
            return new BrowseResult(new ArrayList<>(), 0, index, count, "");
        }

        RemoteDevice device = serverBrowseService.getDevice(serverId);
        if (device == null) {
            throw new IllegalArgumentException("Server not found: " + serverId);
        }

        RemoteService contentDir = device.findService(new UDAServiceType("ContentDirectory"));
        if (contentDir == null) {
            throw new IllegalStateException("ContentDirectory service not found on server: " + serverId);
        }

        Action searchAction = contentDir.getAction("Search");
        if (searchAction != null) {
            return searchViaAction(serverId, contentDir, containerId, query, index, count, filter, sortBy);
        }

        log.info("Search action not available on {}, falling back to in-memory search", serverId);
        return searchInMemory(serverId, containerId, query, index, count, filter, sortBy);
    }

    private BrowseResult searchViaAction(String serverId, RemoteService contentDir, String containerId,
                                          String query, int index, int count, String filter, String sortBy) {
        String searchCriteria = buildSearchCriteria(query);

        Action searchAction = contentDir.getAction("Search");
        ActionInvocation invocation = new ActionInvocation(searchAction);
        invocation.setInput("containerID", containerId);
        invocation.setInput("searchCriteria", searchCriteria);
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

        for (BrowsableItem item : items) {
            if (item.getThumbnailUrl() != null && !item.getThumbnailUrl().isEmpty()) {
                thumbnailService.cache(serverId, item.getId(), item.getThumbnailUrl());
            }
        }

        return new BrowseResult(items, totalMatches, index, count, updateIdValue);
    }

    private BrowseResult searchInMemory(String serverId, String containerId, String query, int index, int count,
                                         String filter, String sortBy) {
        List<BrowsableItem> allItems = new ArrayList<>();
        int startIdx = 0;
        int pageSize = 500;

        while (true) {
            BrowseResult page = browse(serverId, containerId, startIdx, pageSize, filter, sortBy);
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

    public List<BrowsableItem> browseMetadata(String serverId, String itemId, String filter) {
        RemoteDevice device = serverBrowseService.getDevice(serverId);
        if (device == null) {
            throw new IllegalArgumentException("Server not found: " + serverId);
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
        List<BrowsableItem> items = parseBrowseResult(resultXml, serverId);

        for (BrowsableItem item : items) {
            if (item.getThumbnailUrl() != null && !item.getThumbnailUrl().isEmpty()) {
                thumbnailService.cache(serverId, item.getId(), item.getThumbnailUrl());
            }
        }

        return items;
    }

    private void executeSync(ActionInvocation invocation) {
        ControlPoint controlPoint = upnpServiceManager.getUpnpService().getControlPoint();
        new ActionCallback.Default(invocation, controlPoint).run();

        ActionException failure = invocation.getFailure();
        if (failure != null) {
            throw new RuntimeException("DLNA Browse action failed: " + failure.getMessage(), failure);
        }
    }

    private String getOutputString(ActionInvocation invocation, String name) {
        ActionArgumentValue output = invocation.getOutput(name);
        if (output == null) return null;
        Object value = output.getValue();
        if (value == null) return null;
        return value.toString();
    }

    private List<BrowsableItem> parseBrowseResult(String xml, String serverId) {
        List<BrowsableItem> items = new ArrayList<>();

        if (xml == null || xml.trim().isEmpty()) {
            return items;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new java.io.ByteArrayInputStream(xml.getBytes("UTF-8")));
            doc.getDocumentElement().normalize();

            XPath xpath = XPathFactory.newInstance().newXPath();
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

            boolean isThumb = proto != null && proto.contains("albumart");

            if (isThumb) {
                if (thumbnailUrl == null) {
                    thumbnailUrl = resText;
                }
            } else if (mimeType == null) {
                mimeType = extractMimeType(proto);
                protocolInfo = proto;
                resourceName = resText;
                if (resResolution != null && !resResolution.isEmpty()) {
                    resolution = resResolution;
                }
                if (resSize != null && !resSize.isEmpty()) {
                    size = resSize;
                }
            }
        }

        if ((artist == null || artist.isEmpty()) && (description == null || description.isEmpty())) {
            artist = findNsText(itemEl, "artist");
        }
        if (album == null || album.isEmpty()) {
            album = findNsText(itemEl, "album");
        }

        return new BrowsableItem(id, parentId, title, artist, album, duration,
                resolution, mimeType, size, protocolInfo, isContainer,
                thumbnailUrl, classType, description, date, resourceName);
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

    private String extractMimeType(String protocolInfo) {
        if (protocolInfo == null) return null;
        String[] parts = protocolInfo.split(":");
        if (parts.length >= 2) {
            return parts[1];
        }
        return null;
    }
}
