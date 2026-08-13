package com.dlnahub.service;

import com.dlnahub.config.TmdbConfig;
import com.dlnahub.dto.TmdbMediaDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TmdbServiceTest {

    @Test
    void springConstructsTheServiceWithItsProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TmdbConfig.class);
            context.register(TmdbService.class);
            context.refresh();

            assertEquals(TmdbService.class, context.getBean(TmdbService.class).getClass());
        }
    }

    @Test
    void sendsYearAndRanksTheExactYearFirst() {
        TmdbConfig config = new TmdbConfig();
        config.setApiReadAccessToken("test-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TmdbService service = new TmdbService(config, restTemplate);

        server.expect(requestTo("https://api.themoviedb.org/3/search/movie?query=Dune&language=en-US&year=1984"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"results":[
                          {"id":1,"title":"Dune","release_date":"2021-10-22"},
                          {"id":2,"title":"Dune","release_date":"1984-12-14"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.themoviedb.org/3/movie/2?language=en-US&append_to_response=credits"))
                .andRespond(withSuccess(movieDetails(2, "1984-12-14"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.themoviedb.org/3/movie/1?language=en-US&append_to_response=credits"))
                .andRespond(withSuccess(movieDetails(1, "2021-10-22"), MediaType.APPLICATION_JSON));

        List<TmdbMediaDto> results = service.searchByTitle("Dune", 1984, false);

        assertEquals(List.of("2", "1"), results.stream().map(TmdbMediaDto::getTmdbId).toList());
        server.verify();
    }

    @Test
    void sendsFirstAirDateYearForTvSearches() {
        TmdbConfig config = new TmdbConfig();
        config.setApiReadAccessToken("test-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TmdbService service = new TmdbService(config, restTemplate);

        server.expect(requestTo("https://api.themoviedb.org/3/search/tv?query=Succession&language=en-US&first_air_date_year=2018"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertEquals(List.of(), service.searchByTitle("Succession", 2018, true));
        server.verify();
    }

    @Test
    void keepsMovieAndTvCandidatesWhenThereIsNoTypeHint() {
        TmdbConfig config = new TmdbConfig();
        config.setApiReadAccessToken("test-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TmdbService service = new TmdbService(config, restTemplate);

        server.expect(requestTo("https://api.themoviedb.org/3/search/movie?query=The+Office&language=en-US"))
                .andRespond(withSuccess("{\"results\":[{\"id\":1,\"title\":\"The Office\",\"release_date\":\"2015-01-01\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.themoviedb.org/3/search/tv?query=The+Office&language=en-US"))
                .andRespond(withSuccess("{\"results\":[{\"id\":2,\"name\":\"The Office\",\"first_air_date\":\"2005-03-24\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.themoviedb.org/3/movie/1?language=en-US&append_to_response=credits"))
                .andRespond(withSuccess(movieDetails(1, "2015-01-01"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.themoviedb.org/3/tv/2?language=en-US&append_to_response=seasons"))
                .andRespond(withSuccess("{\"id\":2,\"name\":\"The Office\",\"first_air_date\":\"2005-03-24\",\"genres\":[]}", MediaType.APPLICATION_JSON));

        List<TmdbMediaDto> results = service.searchByTitle("The Office", null, null);

        assertEquals(List.of("movie", "tv"), results.stream().map(TmdbMediaDto::getType).toList());
        server.verify();
    }

    private static String movieDetails(int id, String releaseDate) {
        return """
                {"id":%d,"title":"Dune","release_date":"%s","genres":[],
                 "credits":{"cast":[],"crew":[]}}
                """.formatted(id, releaseDate);
    }
}
