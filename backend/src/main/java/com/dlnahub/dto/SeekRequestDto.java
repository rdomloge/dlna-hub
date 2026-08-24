package com.dlnahub.dto;

import jakarta.validation.constraints.Min;

public class SeekRequestDto {

    @Min(value = 0, message = "seconds must not be negative")
    private int seconds;

    public SeekRequestDto() {
    }

    public SeekRequestDto(int seconds) {
        this.seconds = seconds;
    }

    public int getSeconds() {
        return seconds;
    }

    public void setSeconds(int seconds) {
        this.seconds = seconds;
    }
}
