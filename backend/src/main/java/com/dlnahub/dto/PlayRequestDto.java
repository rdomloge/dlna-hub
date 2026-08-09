package com.dlnahub.dto;

public class PlayRequestDto {

    private String uri;
    private String metadataXml;
    private String title;
    private String artist;
    private String album;
    private String duration;
    private String mimeType;
    private String protocolInfo;

    public PlayRequestDto() {
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getMetadataXml() {
        return metadataXml;
    }

    public void setMetadataXml(String metadataXml) {
        this.metadataXml = metadataXml;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getProtocolInfo() {
        return protocolInfo;
    }

    public void setProtocolInfo(String protocolInfo) {
        this.protocolInfo = protocolInfo;
    }
}
