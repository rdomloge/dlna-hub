package com.dlnahub.service;

import com.dlnahub.config.TmdbConfig;
import com.dlnahub.dto.CastMemberDto;
import com.dlnahub.dto.CrewMemberDto;
import com.dlnahub.dto.TmdbMediaDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TmdbService {

    private static final Logger log = LoggerFactory.getLogger(TmdbService.class);
    private static final String BASE_URL = "https://api.themoviedb.org/3";
    private static final int MAX_CANDIDATES = 3;
    private static final int SEARCH_RESULT_LIMIT = 5;

    /**
     * Result cache keyed on the search arguments. A single searchByTitle can cost up to six
     * sequential TMDB round-trips, and the frontend re-queries whenever the parsed title
     * changes. Bounded and time-limited: TMDB metadata is stable, but this is a process-local
     * convenience cache, not a datastore.
     */
    private static final long TMDB_CACHE_TTL_MS = 6 * 60 * 60 * 1000L;   // 6 hours
    private static final int TMDB_CACHE_MAX_ENTRIES = 256;

    private record CachedSearch(List<TmdbMediaDto> results, long cachedAt) {
    }

    private final Map<String, CachedSearch> searchCache = Collections.synchronizedMap(
            new LinkedHashMap<String, CachedSearch>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedSearch> eldest) {
                    return size() > TMDB_CACHE_MAX_ENTRIES;
                }
            });

    private static String searchCacheKey(String title, Integer yearHint, Boolean tvHint) {
        return title.trim().toLowerCase() + "|" + yearHint + "|" + tvHint;
    }

    private final TmdbConfig tmdbConfig;
    private final RestTemplate restTemplate;

    @Autowired
    public TmdbService(TmdbConfig tmdbConfig) {
        this(tmdbConfig, createRestTemplate());
    }

    TmdbService(TmdbConfig tmdbConfig, RestTemplate restTemplate) {
        this.tmdbConfig = tmdbConfig;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    public List<TmdbMediaDto> searchByTitle(String title, Integer yearHint, Boolean tvHint) {
        if (!tmdbConfig.isEnabled() || title == null || title.isBlank()) {
            return List.of();
        }

        String key = searchCacheKey(title, yearHint, tvHint);
        CachedSearch cached = searchCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.cachedAt() < TMDB_CACHE_TTL_MS) {
            log.debug("TMDB cache hit for '{}'", title);
            return cached.results();
        }

        List<TmdbMediaDto> results = searchByTitleUncached(title, yearHint, tvHint);
        // Cache misses too: a title TMDB does not know will not start being known, and an
        // empty result is exactly the case the frontend retries most often.
        searchCache.put(key, new CachedSearch(results, System.currentTimeMillis()));
        return results;
    }

    private List<TmdbMediaDto> searchByTitleUncached(String title, Integer yearHint, Boolean tvHint) {
        log.debug("Searching TMDB for title='{}', yearHint={}, tvHint={}", title, yearHint, tvHint);

        List<SearchCandidate> candidates = new ArrayList<>();
        List<SearchCandidate> movieResults = List.of();
        List<SearchCandidate> tvResults = List.of();

        boolean searchMovie = tvHint == null || !tvHint;
        boolean searchTv = tvHint == null || tvHint;

        if (searchMovie) {
            try {
                movieResults = searchMovieCandidates(title, yearHint);
            } catch (Exception e) {
                log.warn("Movie search failed: {}", e.getMessage());
            }
        }

        if (searchTv) {
            try {
                tvResults = searchTvCandidates(title, yearHint);
            } catch (Exception e) {
                log.warn("TV search failed: {}", e.getMessage());
            }
        }

        if (tvHint == null) {
            addInterleaved(candidates, movieResults, tvResults);
        } else if (tvHint) {
            candidates.addAll(tvResults);
        } else {
            candidates.addAll(movieResults);
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        candidates.sort(candidateComparator(yearHint, tvHint));
        int limit = Math.min(MAX_CANDIDATES, candidates.size());
        List<SearchCandidate> top = candidates.subList(0, limit);

        // Up to three independent detail fetches; run them concurrently rather than adding
        // three round-trips of latency to the playback page. Order is preserved so the
        // scoring done above still decides which candidate the UI shows first.
        return top.parallelStream()
                .map(c -> "movie".equals(c.type) ? getMovieDetails(c.id) : getTvDetails(c.id))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());
    }

    private static <T> void addInterleaved(List<T> target, List<T> first, List<T> second) {
        int index = 0;
        while (index < first.size() || index < second.size()) {
            if (index < first.size()) {
                target.add(first.get(index));
            }
            if (index < second.size()) {
                target.add(second.get(index));
            }
            index += 1;
        }
    }

    private Comparator<SearchCandidate> candidateComparator(Integer yearHint, Boolean tvHint) {
        return (a, b) -> {
            int aScore = scoreCandidate(a, yearHint, tvHint);
            int bScore = scoreCandidate(b, yearHint, tvHint);
            return Integer.compare(bScore, aScore);
        };
    }

    private int scoreCandidate(SearchCandidate c, Integer yearHint, Boolean tvHint) {
        int score = 100;

        if (yearHint != null && c.year != null) {
            if (yearHint.equals(c.year)) {
                score += 500;
            } else {
                score -= 50;
            }
        }

        if (tvHint != null) {
            if (tvHint && "tv".equals(c.type)) {
                score += 200;
            } else if (!tvHint && "movie".equals(c.type)) {
                score += 200;
            }
        }

        return score;
    }

    @SuppressWarnings("unchecked")
    private List<SearchCandidate> searchMovieCandidates(String title, Integer yearHint) {
        String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8);
        String url = BASE_URL + "/search/movie?query=" + encodedTitle + "&language=en-US";
        if (yearHint != null) {
            url += "&year=" + yearHint;
        }
        log.debug("Searching TMDB movies for: {}", title);

        Map<String, Object> response = callTmdb(url);
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        List<SearchCandidate> candidates = new ArrayList<>();
        int limit = Math.min(SEARCH_RESULT_LIMIT, results.size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> r = results.get(i);
            SearchCandidate c = new SearchCandidate();
            c.id = (int) r.get("id");
            c.type = "movie";
            c.title = (String) r.get("title");
            String releaseDate = (String) r.get("release_date");
            if (releaseDate != null && releaseDate.length() >= 4) {
                try {
                    c.year = Integer.parseInt(releaseDate.substring(0, 4));
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
            candidates.add(c);
        }
        return candidates;
    }

    @SuppressWarnings("unchecked")
    private List<SearchCandidate> searchTvCandidates(String title, Integer yearHint) {
        String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8);
        String url = BASE_URL + "/search/tv?query=" + encodedTitle + "&language=en-US";
        if (yearHint != null) {
            url += "&first_air_date_year=" + yearHint;
        }
        log.debug("Searching TMDB TV shows for: {}", title);

        Map<String, Object> response = callTmdb(url);
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        List<SearchCandidate> candidates = new ArrayList<>();
        int limit = Math.min(SEARCH_RESULT_LIMIT, results.size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> r = results.get(i);
            SearchCandidate c = new SearchCandidate();
            c.id = (int) r.get("id");
            c.type = "tv";
            c.title = (String) r.get("name");
            String firstAirDate = (String) r.get("first_air_date");
            if (firstAirDate != null && firstAirDate.length() >= 4) {
                try {
                    c.year = Integer.parseInt(firstAirDate.substring(0, 4));
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
            candidates.add(c);
        }
        return candidates;
    }

    @SuppressWarnings("unchecked")
    private TmdbMediaDto getMovieDetails(int tmdbId) {
        try {
            String url = BASE_URL + "/movie/" + tmdbId + "?language=en-US&append_to_response=credits";
            log.debug("Fetching movie details for TMDB id: {}", tmdbId);

            Map<String, Object> response = callTmdb(url);

            TmdbMediaDto dto = new TmdbMediaDto();
            dto.setTmdbId(String.valueOf(tmdbId));
            dto.setType("movie");
            dto.setTitle((String) response.get("title"));
            dto.setOverview(asString(response.get("overview")));
            dto.setTagline(asString(response.get("tagline")));

            String posterPath = (String) response.get("poster_path");
            String backdropPath = (String) response.get("backdrop_path");
            dto.setPosterPath(posterPath);
            dto.setBackdropPath(backdropPath);
            dto.setPosterUrl(buildImageUrl(posterPath));
            dto.setBackdropUrl(buildImageUrl(backdropPath));

            String date = (String) response.get("release_date");
            if (date != null && date.length() >= 4) {
                dto.setReleaseYear(date.substring(0, 4));
            }

            Number runtimeNum = (Number) response.get("runtime");
            if (runtimeNum != null && runtimeNum.intValue() > 0) {
                dto.setRuntime(runtimeNum.intValue() + " min");
            }

            List<Map<String, Object>> genres = (List<Map<String, Object>>) response.get("genres");
            if (genres != null) {
                List<String> genreNames = new ArrayList<>();
                for (Map<String, Object> g : genres) {
                    genreNames.add((String) g.get("name"));
                }
                dto.setGenres(genreNames);
            }

            Map<String, Object> credits = (Map<String, Object>) response.get("credits");
            if (credits != null) {
                List<CastMemberDto> cast = extractCast((List<Map<String, Object>>) credits.get("cast"));
                List<CrewMemberDto> crew = extractKeyCrew((List<Map<String, Object>>) credits.get("crew"));
                dto.setCast(cast);
                dto.setCrew(crew);
            }

            return dto;
        } catch (Exception e) {
            log.error("Failed to get movie details for {}: {}", tmdbId, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private TmdbMediaDto getTvDetails(int tmdbId) {
        try {
            String url = BASE_URL + "/tv/" + tmdbId + "?language=en-US&append_to_response=seasons";
            log.debug("Fetching TV show details for TMDB id: {}", tmdbId);

            Map<String, Object> response = callTmdb(url);

            TmdbMediaDto dto = new TmdbMediaDto();
            dto.setTmdbId(String.valueOf(tmdbId));
            dto.setType("tv");
            dto.setTitle((String) response.get("name"));
            dto.setOverview(asString(response.get("overview")));
            dto.setTagline(asString(response.get("tagline")));

            String posterPath = (String) response.get("poster_path");
            String backdropPath = (String) response.get("backdrop_path");
            dto.setPosterPath(posterPath);
            dto.setBackdropPath(backdropPath);
            dto.setPosterUrl(buildImageUrl(posterPath));
            dto.setBackdropUrl(buildImageUrl(backdropPath));

            String date = (String) response.get("first_air_date");
            if (date != null && date.length() >= 4) {
                dto.setReleaseYear(date.substring(0, 4));
            }

            List<Map<String, Object>> genres = (List<Map<String, Object>>) response.get("genres");
            if (genres != null) {
                List<String> genreNames = new ArrayList<>();
                for (Map<String, Object> g : genres) {
                    genreNames.add((String) g.get("name"));
                }
                dto.setGenres(genreNames);
            }

            List<Map<String, Object>> seasons = (List<Map<String, Object>>) response.get("seasons");
            if (seasons != null) {
                int seasonCount = ((Number) response.get("number_of_seasons")).intValue();
                dto.setRuntime(seasonCount + " season(s)");

                int firstSeason = 1;
                for (Map<String, Object> s : seasons) {
                    int sn = ((Number) s.get("season_number")).intValue();
                    if (sn > 0) {
                        firstSeason = sn;
                        break;
                    }
                }

                try {
                    String creditsUrl = BASE_URL + "/tv/" + tmdbId + "/season/" + firstSeason + "/credits?language=en-US";
                    Map<String, Object> credits = callTmdb(creditsUrl);
                    List<CastMemberDto> cast = extractCast((List<Map<String, Object>>) credits.get("cast"));
                    List<CrewMemberDto> crew = extractKeyCrew((List<Map<String, Object>>) credits.get("crew"));
                    dto.setCast(cast);
                    dto.setCrew(crew);
                } catch (Exception e) {
                    log.warn("Failed to fetch TV season credits: {}", e.getMessage());
                }
            }

            return dto;
        } catch (Exception e) {
            log.error("Failed to get TV details for {}: {}", tmdbId, e.getMessage());
            return null;
        }
    }

    private List<CastMemberDto> extractCast(List<Map<String, Object>> castList) {
        if (castList == null) return new ArrayList<>();
        List<CastMemberDto> result = new ArrayList<>();
        int limit = Math.min(12, castList.size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> member = castList.get(i);
            CastMemberDto dto = new CastMemberDto();
            dto.setName((String) member.get("name"));
            dto.setCharacter(asString(member.get("character")));
            String profilePath = (String) member.get("profile_path");
            dto.setProfilePath(profilePath);
            dto.setProfileUrl(buildImageUrl(profilePath));
            dto.setOrder((Integer) member.get("order"));
            result.add(dto);
        }
        return result;
    }

    private List<CrewMemberDto> extractKeyCrew(List<Map<String, Object>> crewList) {
        if (crewList == null) return new ArrayList<>();
        List<CrewMemberDto> result = new ArrayList<>();
        List<String> keyJobs = List.of("Director", "Writer", "Screenplay", "Story", "Producer");

        for (Map<String, Object> member : crewList) {
            String job = (String) member.get("job");
            if (job != null && keyJobs.contains(job)) {
                CrewMemberDto dto = new CrewMemberDto();
                dto.setName((String) member.get("name"));
                dto.setJob(job);
                dto.setDepartment(asString(member.get("department")));
                String profilePath = (String) member.get("profile_path");
                dto.setProfilePath(profilePath);
                dto.setProfileUrl(buildImageUrl(profilePath));
                result.add(dto);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTmdb(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + tmdbConfig.getApiReadAccessToken());
        headers.set("Accept", "application/json");

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, new ParameterizedTypeReference<Map<String, Object>>() {});

        return response.getBody();
    }

    private String buildImageUrl(String path) {
        if (path == null || path.isEmpty()) return null;
        return tmdbConfig.getImageBaseUrl() + path;
    }

    private String asString(Object value) {
        if (value == null) return null;
        return value.toString();
    }

    private static class SearchCandidate {
        int id;
        String type;
        String title;
        Integer year;
    }
}
