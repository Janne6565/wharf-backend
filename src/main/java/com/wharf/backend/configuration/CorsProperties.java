package com.wharf.backend.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configurable CORS allow-list. Defaults to the local Vite dev server.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(

        List<String> allowedOrigins
) {
    public CorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            allowedOrigins = List.of("http://localhost:5173");
        }
    }
}
