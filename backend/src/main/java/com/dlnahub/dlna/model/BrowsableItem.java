package com.dlnahub.dlna.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class BrowsableItem {

    private final String id;
    private final String parentId;
    private final String title;
    private final String artist;
    private final String album;
    private final String duration;
    private final String resolution;
    private final String mimeType;
    private final String size;
    private final String protocolInfo;
    private final boolean isContainer;
    private final String thumbnailUrl;
    private final String classType;
    private final String description;
    private final String date;
    private final String effectiveDate;
    private final String resourceName;

    public BrowsableItem(String id, String parentId, String title, String artist,
            String album, String duration, String resolution, String mimeType,
            String size, String protocolInfo, boolean isContainer, String thumbnailUrl,
            String classType, String description, String date, String effectiveDate,
            String resourceName) {
        this.id = id;
        this.parentId = parentId;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.resolution = resolution;
        this.mimeType = mimeType;
        this.size = size;
        this.protocolInfo = protocolInfo;
        this.isContainer = isContainer;
        this.thumbnailUrl = thumbnailUrl;
        this.classType = classType;
        this.description = description;
        this.date = date;
        this.effectiveDate = effectiveDate;
        this.resourceName = resourceName;
    }

    public String getId() { return id; }
    public String getParentId() { return parentId; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public String getDuration() { return duration; }
    public String getResolution() { return resolution; }
    public String getMimeType() { return mimeType; }
    public String getSize() { return size; }
    public String getProtocolInfo() { return protocolInfo; }
    @JsonProperty("isContainer")
    public boolean isContainer() { return isContainer; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public String getClassType() { return classType; }
    public String getDescription() { return description; }
    public String getDate() { return date; }
    public String getEffectiveDate() { return effectiveDate; }
    public String getResourceName() { return resourceName; }
}
