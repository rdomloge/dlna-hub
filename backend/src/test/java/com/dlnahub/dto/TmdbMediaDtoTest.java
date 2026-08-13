package com.dlnahub.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TmdbMediaDtoTest {

    @Test
    void collectionsDefaultToEmpty() {
        TmdbMediaDto dto = new TmdbMediaDto();

        assertNotNull(dto.getGenres());
        assertTrue(dto.getGenres().isEmpty());
        assertNotNull(dto.getCast());
        assertTrue(dto.getCast().isEmpty());
        assertNotNull(dto.getCrew());
        assertTrue(dto.getCrew().isEmpty());
    }
}
