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
        String apiKey,

        /**
         * Origin every link in an email is built from. Absolute URLs are mandatory in mail —
         * there is no document to resolve a relative path against — and they must point at the
         * deployment that actually sent the message, so this is configured rather than
         * hardcoded (local dev sends links to localhost, production to the public host).
         */
        @DefaultValue("https://wharf.jannekeipert.de")
        String appBaseUrl,

        /** The bare domain shown as the last line of every footer, so the sender is identifiable. */
        @DefaultValue("wharf.jannekeipert.de")
        String sendingDomain
) {

    /** Mail delivery is enabled only when an API key is supplied. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Absolute URL for an in-app path, e.g. {@code url("/projects")}. */
    public String url(String path) {
        String base = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
        return path.startsWith("/") ? base + path : base + "/" + path;
    }
}
