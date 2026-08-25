package com.dlnahub.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TmdbMediaDtoTest {

    @Test
    void getGenres_newDto_returnsEmptyList() {
        // given
        TmdbMediaDto dto = new TmdbMediaDto();

        // when
        var genres = dto.getGenres();

        // then
        assertNotNull(genres);
        assertTrue(genres.isEmpty());
    }

    @Test
    void getCast_newDto_returnsEmptyList() {
        // given
        TmdbMediaDto dto = new TmdbMediaDto();

        // when
        var cast = dto.getCast();

        // then
        assertNotNull(cast);
        assertTrue(cast.isEmpty());
    }

    @Test
    void getCrew_newDto_returnsEmptyList() {
        // given
        TmdbMediaDto dto = new TmdbMediaDto();

        // when
        var crew = dto.getCrew();

        // then
        assertNotNull(crew);
        assertTrue(crew.isEmpty());
    }
}
