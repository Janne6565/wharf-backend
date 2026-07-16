package com.wharf.backend.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for outbound mail delivery via the external Mail Manager service.
 *
 * <p>No secret lives in git: {@code apiKey} defaults to empty and mail delivery is treated
 * as <em>disabled</em> until a key is supplied (via {@code MAIL_SERVICE_API_KEY}). When
 * disabled, a no-op client is wired in — mirroring the OAuth "credentials optional" model.
 */
@Validated
@ConfigurationProperties(prefix = "wharf.mail")
public record MailProperties(

        @DefaultValue("https://mail-service.jannekeipert.de")
        String baseUrl,

        @DefaultValue("")
        String apiKey
) {

    /** Mail delivery is enabled only when an API key is supplied. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
