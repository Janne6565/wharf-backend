package com.wharf.backend.security;

import com.wharf.backend.configuration.AuthCookieProperties;
import com.wharf.backend.configuration.JwtProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the httpOnly refresh cookie (and its clearing counterpart). Attributes such as
 * {@code Secure} come from {@link AuthCookieProperties} so they can be toggled per env.
 */
@Component
public class RefreshCookieFactory {

    private final AuthCookieProperties cookieProperties;
    private final JwtProperties jwtProperties;

    public RefreshCookieFactory(AuthCookieProperties cookieProperties, JwtProperties jwtProperties) {
        this.cookieProperties = cookieProperties;
        this.jwtProperties = jwtProperties;
    }

    public ResponseCookie create(String refreshToken) {
        return baseBuilder(refreshToken)
                .maxAge(jwtProperties.refreshExpiration())
                .build();
    }

    public ResponseCookie clearing() {
        return baseBuilder("")
                .maxAge(0)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder baseBuilder(String value) {
        return ResponseCookie.from(cookieProperties.name(), value)
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .sameSite(cookieProperties.sameSite())
                .path(cookieProperties.path());
    }

    public String cookieName() {
        return cookieProperties.name();
    }
}
