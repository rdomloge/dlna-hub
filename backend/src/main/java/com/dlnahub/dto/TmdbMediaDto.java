package com.dlnahub.dto;

import java.util.List;

public class TmdbMediaDto {

    private String tmdbId;
    private String type;
    private String title;
    private String overview;
    private String tagline;
    private String posterPath;
    private String backdropPath;
    private String releaseYear;
    private List<String> genres;
    private String runtime;
    private String posterUrl;
    private String backdropUrl;
    private List<CastMemberDto> cast;
    private List<CrewMemberDto> crew;

    public String getTmdbId() {
        return tmdbId;
    }

    public void setTmdbId(String tmdbId) {
        this.tmdbId = tmdbId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOverview() {
        return overview;
    }

    public void setOverview(String overview) {
        this.overview = overview;
    }

    public String getTagline() {
        return tagline;
    }

    public void setTagline(String tagline) {
        this.tagline = tagline;
    }

    public String getPosterPath() {
        return posterPath;
    }

    public void setPosterPath(String posterPath) {
        this.posterPath = posterPath;
    }

    public String getBackdropPath() {
        return backdropPath;
    }

    public void setBackdropPath(String backdropPath) {
        this.backdropPath = backdropPath;
    }

    public String getReleaseYear() {
        return releaseYear;
    }

    public void setReleaseYear(String releaseYear) {
        this.releaseYear = releaseYear;
    }

    public List<String> getGenres() {
        return genres;
    }

    public void setGenres(List<String> genres) {
        this.genres = genres;
    }

    public String getRuntime() {
        return runtime;
    }

    public void setRuntime(String runtime) {
        this.runtime = runtime;
    }

    public String getPosterUrl() {
        return posterUrl;
    }

    public void setPosterUrl(String posterUrl) {
        this.posterUrl = posterUrl;
    }

    public String getBackdropUrl() {
        return backdropUrl;
    }

    public void setBackdropUrl(String backdropUrl) {
        this.backdropUrl = backdropUrl;
    }

    public List<CastMemberDto> getCast() {
        return cast;
    }

    public void setCast(List<CastMemberDto> cast) {
        this.cast = cast;
    }

    public List<CrewMemberDto> getCrew() {
        return crew;
    }

    public void setCrew(List<CrewMemberDto> crew) {
        this.crew = crew;
    }
}
