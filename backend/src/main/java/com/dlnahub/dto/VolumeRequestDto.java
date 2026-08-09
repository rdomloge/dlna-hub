package com.dlnahub.dto;

public class VolumeRequestDto {

    private int volume;

    public VolumeRequestDto() {
    }

    public VolumeRequestDto(int volume) {
        this.volume = volume;
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }
}
