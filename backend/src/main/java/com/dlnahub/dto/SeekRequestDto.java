package com.dlnahub.dto;

public class SeekRequestDto {

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
