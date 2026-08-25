package com.dlnahub.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvTransportServiceTest {

    @Test
    void formatTime_variousDurations_returnsHoursMinutesSeconds() {
        // given

        // when
        String zero = AvTransportService.formatTime(0);
        String nine = AvTransportService.formatTime(9);
        String sixtyFive = AvTransportService.formatTime(65);
        String hour = AvTransportService.formatTime(3600);
        String arbitrary = AvTransportService.formatTime(7384);

        // then
        assertEquals("00:00:00", zero);
        assertEquals("00:00:09", nine);
        assertEquals("00:01:05", sixtyFive);
        assertEquals("01:00:00", hour);
        assertEquals("02:03:04", arbitrary);
    }

    @Test
    void formatTime_negativeSeconds_returnsZeroTime() {
        // given
        int negativeOne = -1;
        int negativeHour = -3600;

        // when
        String one = AvTransportService.formatTime(negativeOne);
        String hour = AvTransportService.formatTime(negativeHour);

        // then
        assertEquals("00:00:00", one);
        assertEquals("00:00:00", hour);
    }

    @Test
    void parseTimeSeconds_hhmmssAndShortForms_returnsTotalSeconds() {
        // given
        String hhmmss = "02:03:04";
        String mss = "2:05";
        String bareSeconds = "42";

        // when
        int hhmmssSeconds = AvTransportService.parseTimeSeconds(hhmmss);
        int mssSeconds = AvTransportService.parseTimeSeconds(mss);
        int bareSecondsTotal = AvTransportService.parseTimeSeconds(bareSeconds);

        // then
        assertEquals(7384, hhmmssSeconds);
        assertEquals(125, mssSeconds);
        assertEquals(42, bareSecondsTotal);
    }

    @Test
    void parseTimeSeconds_nullEmptyOrInvalidInput_returnsZero() {
        // given

        // when
        int nullResult = AvTransportService.parseTimeSeconds(null);
        int emptyResult = AvTransportService.parseTimeSeconds("");
        int invalidResult = AvTransportService.parseTimeSeconds("not:a:time");

        // then
        assertEquals(0, nullResult);
        assertEquals(0, emptyResult);
        assertEquals(0, invalidResult);
    }
}
