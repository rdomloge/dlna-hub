package com.dlnahub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tmdb")
public class TmdbConfig {

    private String apiReadAccessToken;
    private String imageBaseUrl = "https://image.tmdb.org/t/p/w500";
    private String searchType = "both";

    public String getApiReadAccessToken() {
        return apiReadAccessToken;
    }

    public void setApiReadAccessToken(String apiReadAccessToken) {
        this.apiReadAccessToken = apiReadAccessToken;
    }

    public String getImageBaseUrl() {
        return imageBaseUrl;
    }

    public void setImageBaseUrl(String imageBaseUrl) {
        this.imageBaseUrl = imageBaseUrl;
    }

    public String getSearchType() {
        return searchType;
    }

    public void setSearchType(String searchType) {
        this.searchType = searchType;
    }

    public boolean isEnabled() {
        return apiReadAccessToken != null && !apiReadAccessToken.isBlank();
    }
}
