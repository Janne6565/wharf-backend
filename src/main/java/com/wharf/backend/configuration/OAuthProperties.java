package com.wharf.backend.configuration;

import com.wharf.backend.model.core.OAuthProvider;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * OAuth client credentials, per provider, plus the public base URL used to build the
 * provider {@code redirect_uri} ({@code <public-base-url>/api/v1/auth/oauth/{provider}/callback}).
 *
 * <p>No secrets live in git: the client id/secret default to empty and a provider is
 * treated as <em>disabled</em> until both are supplied (via {@code OAUTH_GOOGLE_CLIENT_ID}
 * etc.). Only the credentials are configuration; each provider's protocol endpoints and
 * scopes are fixed constants on {@link OAuthProvider}.
 */
@Validated
@ConfigurationProperties(prefix = "oauth")
public record OAuthProperties(

        @NotBlank
        String publicBaseUrl,

        Credentials google,

        Credentials github
) {

    public record Credentials(String clientId, String clientSecret) {

        public boolean isConfigured() {
            return isPresent(clientId) && isPresent(clientSecret);
        }

        private static boolean isPresent(String value) {
            return value != null && !value.isBlank();
        }
    }

    public Credentials credentialsFor(OAuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> google;
            case GITHUB -> github;
        };
    }

    /** A provider is enabled only when both its client id and secret are configured. */
    public boolean isEnabled(OAuthProvider provider) {
        Credentials credentials = credentialsFor(provider);
        return credentials != null && credentials.isConfigured();
    }

    /** The provider {@code redirect_uri}, which must match between authorize and token exchange. */
    public String callbackUri(OAuthProvider provider) {
        return publicBaseUrl + "/api/v1/auth/oauth/" + provider.getSlug() + "/callback";
    }
}
