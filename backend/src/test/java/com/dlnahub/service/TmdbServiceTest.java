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
    void constructor_springContext_exactProductionClass() {
        // given
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(TmdbConfig.class);
        context.register(TmdbService.class);

        // when
        context.refresh();

        // then
        try {
            assertEquals(TmdbService.class, context.getBean(TmdbService.class).getClass());
        } finally {
            context.close();
        }
    }

    @Test
    void searchByTitle_movieWithYearHint_sendsYearAndRanksExactFirst() {
        // given
        TmdbConfig config = new TmdbConfig();
        config.setApiReadAccessToken("test-token");
        RestTemplate restTemplate = new RestTemplate();
        // Detail fetches run in parallel, so the detail requests may arrive in either order.
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
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

        // when
        List<TmdbMediaDto> results = service.searchByTitle("Dune", 1984, false);

        // then
        assertEquals(List.of("2", "1"), results.stream().map(TmdbMediaDto::getTmdbId).toList());
        server.verify();
    }

    @Test
    void searchByTitle_tvHint_sendsFirstAirDateYear() {
        // given
        TmdbConfig config = new TmdbConfig();
        config.setApiReadAccessToken("test-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TmdbService service = new TmdbService(config, restTemplate);

        server.expect(requestTo("https://api.themoviedb.org/3/search/tv?query=Succession&language=en-US&first_air_date_year=2018"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        // when
        List<TmdbMediaDto> results = service.searchByTitle("Succession", 2018, true);

        // then
        assertEquals(List.of(), results);
        server.verify();
    }

    @Test
    void searchByTitle_noTypeHint_keepsMovieAndTvCandidates() {
        // given
        TmdbConfig config = new TmdbConfig();
        config.setApiReadAccessToken("test-token");
        RestTemplate restTemplate = new RestTemplate();
        // Detail fetches run in parallel, so the detail requests may arrive in either order.
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        TmdbService service = new TmdbService(config, restTemplate);

        server.expect(requestTo("https://api.themoviedb.org/3/search/movie?query=The+Office&language=en-US"))
                .andRespond(withSuccess("{\"results\":[{\"id\":1,\"title\":\"The Office\",\"release_date\":\"2015-01-01\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.themoviedb.org/3/search/tv?query=The+Office&language=en-US"))
                .andRespond(withSuccess("{\"results\":[{\"id\":2,\"name\":\"The Office\",\"first_air_date\":\"2005-03-24\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.themoviedb.org/3/movie/1?language=en-US&append_to_response=credits"))
                .andRespond(withSuccess(movieDetails(1, "2015-01-01"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.themoviedb.org/3/tv/2?language=en-US&append_to_response=seasons"))
                .andRespond(withSuccess("{\"id\":2,\"name\":\"The Office\",\"first_air_date\":\"2005-03-24\",\"genres\":[]}", MediaType.APPLICATION_JSON));

        // when
        List<TmdbMediaDto> results = service.searchByTitle("The Office", null, null);

        // then
        assertEquals(List.of("movie", "tv"), results.stream().map(TmdbMediaDto::getType).toList());
        server.verify();
    }

    @Test
    void searchByTitle_repeatedSameArguments_noNewHttpCalls() {
        // given
        TmdbConfig config = new TmdbConfig();
        config.setApiReadAccessToken("test-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TmdbService service = new TmdbService(config, restTemplate);

        server.expect(requestTo("https://api.themoviedb.org/3/search/movie?query=Dune&language=en-US"))
                .andRespond(withSuccess("""
                        {"results":[{"id":1,"title":"Dune","release_date":"2021-10-22"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.themoviedb.org/3/search/tv?query=Dune&language=en-US"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.themoviedb.org/3/movie/1?language=en-US&append_to_response=credits"))
                .andRespond(withSuccess(movieDetails(1, "2021-10-22"), MediaType.APPLICATION_JSON));

        // when
        List<TmdbMediaDto> first = service.searchByTitle("Dune", null, null);
        server.verify();

        // Second call with the same arguments: no expectations are registered, so any HTTP
        // call issued by it would make verify() fail — the mock is invoked the same total
        // number of times after the second call as after the first.
        List<TmdbMediaDto> second = service.searchByTitle("Dune", null, null);
        server.verify();

        // then
        assertEquals(List.of("1"), first.stream().map(TmdbMediaDto::getTmdbId).toList());
        assertEquals(first, second);
    }

    private static String movieDetails(int id, String releaseDate) {
        return """
                {"id":%d,"title":"Dune","release_date":"%s","genres":[],
                 "credits":{"cast":[],"crew":[]}}
                """.formatted(id, releaseDate);
    }
}
