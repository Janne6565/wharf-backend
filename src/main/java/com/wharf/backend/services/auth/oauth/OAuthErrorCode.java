package com.wharf.backend.services.auth.oauth;

/**
 * Machine-readable OAuth failure reasons. The callback redirects to
 * {@code /oauth/complete?error=<code>} using {@link #getCode()} — no sensitive detail ever
 * reaches the URL. The frontend maps these to user-facing messages.
 */
public enum OAuthErrorCode {

    /** Unknown provider slug, or the provider has no client id/secret configured. */
    PROVIDER_DISABLED("provider_disabled"),

    /** State was missing, unknown, already consumed or past its TTL. */
    INVALID_STATE("invalid_state"),

    /** The provider account's email is not verified — we refuse to link it. */
    EMAIL_NOT_VERIFIED("email_not_verified"),

    /** Token exchange or user-info fetch against the provider failed. */
    PROVIDER_ERROR("provider_error"),

    /** Any other unexpected failure while completing the callback. */
    SERVER_ERROR("server_error");

    private final String code;

    OAuthErrorCode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
