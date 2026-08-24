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

import java.util.List;
import java.util.Map;

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

    @GetMapping(value = "/{serverId}/thumbnail/{itemId}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<?> thumbnail(
            @PathVariable String serverId,
            @PathVariable String itemId) {
        try {
            String url = thumbnailService.getUrl(serverId, itemId);
            if (url == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Thumbnail URL not found for item: " + itemId));
            }

            byte[] data = thumbnailService.fetch(url);
            if (data == null || data.length == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Failed to fetch thumbnail"));
            }

            String contentType = thumbnailService.detectContentType(url, data);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.setCacheControl("max-age=3600");

            return new ResponseEntity<>(data, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Thumbnail proxy failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
