package com.wharf.backend.model.core;

import java.util.Optional;

/**
 * A supported OAuth social-login provider. Each constant carries the provider's public,
 * non-secret protocol endpoints and scopes — only the client id/secret are configuration
 * (see {@code OAuthProperties}). The lowercase {@link #getSlug() slug} is the value used
 * in URLs (e.g. {@code /api/v1/auth/oauth/google/authorize}) and stored on identities.
 */
public enum OAuthProvider {

    GOOGLE("google",
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "openid email"),

    GITHUB("github",
            "https://github.com/login/oauth/authorize",
            "https://github.com/login/oauth/access_token",
            "user:email");

    private final String slug;
    private final String authorizationUri;
    private final String tokenUri;
    private final String scope;

    OAuthProvider(String slug, String authorizationUri, String tokenUri, String scope) {
        this.slug = slug;
        this.authorizationUri = authorizationUri;
        this.tokenUri = tokenUri;
        this.scope = scope;
    }

    public String getSlug() {
        return slug;
    }

    public String getAuthorizationUri() {
        return authorizationUri;
    }

    public String getTokenUri() {
        return tokenUri;
    }

    public String getScope() {
        return scope;
    }

    public static Optional<OAuthProvider> fromSlug(String slug) {
        if (slug == null) {
            return Optional.empty();
        }
        for (OAuthProvider provider : values()) {
            if (provider.slug.equals(slug)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }
}
