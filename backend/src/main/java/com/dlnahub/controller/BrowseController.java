package com.dlnahub.controller;

import com.dlnahub.dlna.model.BrowseResult;
import com.dlnahub.dlna.model.BrowsableItem;
import com.dlnahub.exception.DeviceNotFoundException;
import com.dlnahub.service.ContentBrowseService;
import com.dlnahub.service.ThumbnailService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/servers")
public class BrowseController {

    private static final Logger log = LoggerFactory.getLogger(BrowseController.class);

    private static final String DEFAULT_FILTER = "dc:title,upnp:class,dc:date,dc:creator,res,res@duration,res@resolution,res@size,dc:description,upnp:artist,upnp:album,upnp:genre,dlna:profileID,refID,protocolInfo";

    private final ContentBrowseService contentBrowseService;
    private final ThumbnailService thumbnailService;

    @Autowired
    public BrowseController(ContentBrowseService contentBrowseService, ThumbnailService thumbnailService) {
        this.contentBrowseService = contentBrowseService;
        this.thumbnailService = thumbnailService;
    }

    @GetMapping("/{serverId}/browse")
    public BrowseResult browse(
            @PathVariable String serverId,
            @RequestParam(value = "objectId", defaultValue = "0") String objectId,
            @RequestParam(value = "index", defaultValue = "0") @Min(0) int index,
            @RequestParam(value = "count", defaultValue = "50") @Min(1) @Max(500) int count,
            @RequestParam(value = "filter", defaultValue = DEFAULT_FILTER) String filter,
            @RequestParam(value = "sortBy", defaultValue = "") String sortBy) {
        log.debug("Browse request: server={}, objectId={}, index={}, count={}",
                serverId, objectId, index, count);
        return contentBrowseService.browse(serverId, objectId, index, count, filter, sortBy);
    }

    @GetMapping("/{serverId}/search")
    public BrowseResult search(
            @PathVariable String serverId,
            @RequestParam(value = "containerId", defaultValue = "0") String containerId,
            @RequestParam @NotBlank String query,
            @RequestParam(value = "index", defaultValue = "0") @Min(0) int index,
            @RequestParam(value = "count", defaultValue = "50") @Min(1) @Max(500) int count,
            @RequestParam(value = "filter", defaultValue = DEFAULT_FILTER) String filter,
            @RequestParam(value = "sortBy", defaultValue = "") String sortBy) {
        log.debug("Search request: server={}, container={}, query={}, index={}, count={}",
                serverId, containerId, query, index, count);
        return contentBrowseService.search(serverId, containerId, query, index, count, filter, sortBy);
    }

    /**
     * Opens an SSE stream that completes the container effective-date cache for a folder.
     *
     * <p>After a date-sorted browse returns, the client opens this stream; the backend finishes
     * crawling any folders whose effective dates are still unknown and pushes each newly-known
     * date as a {@code date} event, ending with a terminal {@code allDone} event.
     */
    @GetMapping(value = "/{serverId}/browse/{objectId}/dates", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter browseDates(
            @PathVariable String serverId,
            @PathVariable String objectId,
            @RequestParam(value = "sortBy", defaultValue = "") String sortBy) {
        log.debug("Date stream request: server={}, objectId={}, sortBy={}", serverId, objectId, sortBy);
        return contentBrowseService.openDateStream(serverId, objectId, sortBy);
    }

    @GetMapping("/{serverId}/browse/{itemId}/metadata")
    public BrowsableItem metadata(
            @PathVariable String serverId,
            @PathVariable String itemId,
            @RequestParam(value = "filter", defaultValue = "*") String filter) {
        log.debug("Metadata request: server={}, item={}", serverId, itemId);
        List<BrowsableItem> items = contentBrowseService.browseMetadata(serverId, itemId, filter);
        if (items.isEmpty()) {
            throw new DeviceNotFoundException("Item not found: " + itemId);
        }
        return items.get(0);
    }

    @GetMapping("/{serverId}/thumbnail/{itemId}")
    public ResponseEntity<byte[]> thumbnail(
            @PathVariable String serverId,
            @PathVariable String itemId) {
        String url = thumbnailService.getUrl(serverId, itemId);
        if (url == null) {
            // Not an error worth a JSON body: the browser asked for an <img> src. A bare 404
            // lets the alt text / placeholder render. The content type is decided from the
            // fetched bytes below, so this handler declares no static `produces` type.
            throw new DeviceNotFoundException("Thumbnail not found for item: " + itemId);
        }

        byte[] data = thumbnailService.fetch(url);
        if (data == null || data.length == 0) {
            throw new DeviceNotFoundException("Thumbnail could not be fetched for item: " + itemId);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(thumbnailService.detectContentType(url, data)));
        headers.setCacheControl("public, max-age=3600");
        headers.setContentLength(data.length);
        return new ResponseEntity<>(data, headers, HttpStatus.OK);
    }
}
