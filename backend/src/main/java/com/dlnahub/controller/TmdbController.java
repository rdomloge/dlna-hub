package com.dlnahub.controller;

import com.dlnahub.config.TmdbConfig;
import com.dlnahub.dto.TmdbMediaDto;
import com.dlnahub.service.TmdbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tmdb")
public class TmdbController {

    private static final Logger log = LoggerFactory.getLogger(TmdbController.class);

    private final TmdbService tmdbService;
    private final TmdbConfig tmdbConfig;

    @Autowired
    public TmdbController(TmdbService tmdbService, TmdbConfig tmdbConfig) {
        this.tmdbService = tmdbService;
        this.tmdbConfig = tmdbConfig;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String title,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Boolean tv) {
        log.info("TMDB search request for: {}, year={}, tv={}", title, year, tv);

        if (!tmdbConfig.isEnabled()) {
            return ResponseEntity.ok(Map.of("available", false));
        }

        List<TmdbMediaDto> results = tmdbService.searchByTitle(title, year, tv);
        if (results.isEmpty()) {
            return ResponseEntity.ok(Map.of("available", true, "found", false));
        }

        return ResponseEntity.ok(Map.of("available", true, "results", results));
    }
}
