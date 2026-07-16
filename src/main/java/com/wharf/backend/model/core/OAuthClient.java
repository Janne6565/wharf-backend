package com.wharf.backend.model.core;

/**
 * Which client initiated an OAuth login, threaded through the one-time state row so the
 * callback can hand the session back the right way. WEB (default, browser SPA) receives the
 * refresh token as an httpOnly cookie and a redirect to the frontend; MOBILE (React Native
 * app, custom {@code wharf://} URL scheme) cannot use cookies, so it instead receives a
 * one-time device code via a deep-link redirect and exchanges it for a DIRECT session.
 */
public enum OAuthClient {

    WEB,
    MOBILE;

    private static final String MOBILE_PARAM = "mobile";

    /**
     * Map the {@code client} query parameter to an enum. Anything other than {@code mobile}
     * (case-insensitive), including {@code null} and {@code web}, resolves to {@link #WEB} —
     * the default, safe client.
     */
    public static OAuthClient fromParam(String value) {
        return MOBILE_PARAM.equalsIgnoreCase(value) ? MOBILE : WEB;
    }
}
