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
 *
 * <p>{@code mobileRedirectUri} is the server-controlled deep-link base a mobile-initiated
 * login is handed back through (never caller-supplied — this preserves the no-open-redirect
 * stance). It defaults to the app's custom {@code wharf://oauth} scheme.
 */
@Validated
@ConfigurationProperties(prefix = "oauth")
public record OAuthProperties(

        @NotBlank
        String publicBaseUrl,

        @NotBlank
        String mobileRedirectUri,

        Credentials google,

        Credentials github
) {

    /** Default deep-link base for the mobile hand-off when {@code oauth.mobile-redirect-uri} is unset. */
    private static final String DEFAULT_MOBILE_REDIRECT_URI = "wharf://oauth";

    public OAuthProperties {
        if (mobileRedirectUri == null || mobileRedirectUri.isBlank()) {
            mobileRedirectUri = DEFAULT_MOBILE_REDIRECT_URI;
        }
    }

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
