package com.dlnahub.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvTransportServiceTest {

    @Test
    void formatsSecondsAsHoursMinutesSeconds() {
        assertEquals("00:00:00", AvTransportService.formatTime(0));
        assertEquals("00:00:09", AvTransportService.formatTime(9));
        assertEquals("00:01:05", AvTransportService.formatTime(65));
        assertEquals("01:00:00", AvTransportService.formatTime(3600));
        assertEquals("02:03:04", AvTransportService.formatTime(7384));
    }

    @Test
    void formatTimeClampsNegativesToZero() {
        assertEquals("00:00:00", AvTransportService.formatTime(-1));
        assertEquals("00:00:00", AvTransportService.formatTime(-3600));
    }

    @Test
    void parsesTimeStringsInEveryAcceptedShape() {
        assertEquals(7384, AvTransportService.parseTimeSeconds("02:03:04"));
        assertEquals(125, AvTransportService.parseTimeSeconds("2:05"));
        assertEquals(42, AvTransportService.parseTimeSeconds("42"));
    }

    @Test
    void parseTimeSecondsReturnsZeroForUnusableInput() {
        assertEquals(0, AvTransportService.parseTimeSeconds(null));
        assertEquals(0, AvTransportService.parseTimeSeconds(""));
        assertEquals(0, AvTransportService.parseTimeSeconds("not:a:time"));
    }
}
