# Step 14 — Lock down CORS

**Phase:** 2 — Security and robustness
**Severity:** High (report: H5)
**Files:** `backend/src/main/java/com/dlnahub/config/CorsConfig.java`,
`backend/src/main/resources/application.yml`
**Depends on:** —

## Problem

```java
config.setAllowCredentials(true);
config.addAllowedOriginPattern("*");
```

This is the most permissive CORS configuration possible: any origin, with credentials.
The hub has no authentication and sits on a home LAN, so any web page the user visits can
script it — enumerate the media library, start playback on the TV, and read the thumbnail
proxy — via the victim's own browser. `allowedOriginPattern("*")` reflects the requesting
origin back in `Access-Control-Allow-Origin`, so the browser's usual protection does not
apply.

In production the SPA is served by nginx on the *same* origin and proxies `/api` locally,
so CORS is not needed at all there. It is only needed for the Vite dev server on
`localhost:5173`.

## Change

### 1. Make the allowed origins configurable, defaulting to dev origins only

Replace `CorsConfig.java` with:

```java
package com.dlnahub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
public class CorsConfig {

    /**
     * Origins allowed to call the API cross-origin. In production the SPA is served from the
     * same origin (nginx proxies /api to the backend), so no cross-origin access is needed
     * and this list only covers the Vite dev server. It is deliberately NOT a wildcard:
     * the API has no authentication, so any origin allowed here can drive playback and read
     * the whole media library from a victim's browser.
     */
    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowCredentials(false);
        config.addAllowedHeader("*");
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
```

Three substantive changes: an explicit origin list instead of a wildcard,
`allowCredentials(false)` (nothing sends credentials — there are no cookies or auth
headers), and the filter scoped to `/api/**` instead of `/**`.

### 2. Document the property in `application.yml`

Add a top-level block:

```yaml
cors:
  # Comma-separated origins allowed to call the API cross-origin. Only needed for the Vite
  # dev server; in production nginx serves the SPA from the same origin. Never set this to "*".
  allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173}
```

## Do not

- Do not add `"*"` back as a fallback, and do not use `addAllowedOriginPattern`.
- Do not add authentication in this step — that is a larger design decision, out of scope
  here.

## Verify

```bash
cd backend && mvn test
cd backend && mvn spring-boot:run
```

In another shell:

```bash
# Allowed dev origin: expect the header to come back
curl -s -i -H "Origin: http://localhost:5173" http://localhost:9100/api/servers | grep -i access-control-allow-origin

# Arbitrary origin: expect NO access-control-allow-origin header
curl -s -i -H "Origin: http://evil.example" http://localhost:9100/api/servers | grep -i access-control-allow-origin
```

Then confirm the Vite dev server (`npm run dev`) still reaches the API.
