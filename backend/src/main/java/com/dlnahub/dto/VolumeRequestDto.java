package com.dlnahub.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class VolumeRequestDto {

    @Min(value = 0, message = "volume must be between 0 and 100")
    @Max(value = 100, message = "volume must be between 0 and 100")
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
