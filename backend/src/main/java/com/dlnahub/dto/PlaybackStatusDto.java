package com.dlnahub.dto;

public class PlaybackStatusDto {

    private String state;
    private String trackTitle;
    private String trackDuration;
    private String trackPosition;
    private String trackUri;
    private int volume;

    public PlaybackStatusDto() {
    }

    public PlaybackStatusDto(String state, String trackTitle, String trackDuration,
            String trackPosition, String trackUri, int volume) {
        this.state = state;
        this.trackTitle = trackTitle;
        this.trackDuration = trackDuration;
        this.trackPosition = trackPosition;
        this.trackUri = trackUri;
        this.volume = volume;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getTrackTitle() {
        return trackTitle;
    }

    public void setTrackTitle(String trackTitle) {
        this.trackTitle = trackTitle;
    }

    public String getTrackDuration() {
        return trackDuration;
    }

    public void setTrackDuration(String trackDuration) {
        this.trackDuration = trackDuration;
    }

    public String getTrackPosition() {
        return trackPosition;
    }

    public void setTrackPosition(String trackPosition) {
        this.trackPosition = trackPosition;
    }

    public String getTrackUri() {
        return trackUri;
    }

    public void setTrackUri(String trackUri) {
        this.trackUri = trackUri;
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }
}
