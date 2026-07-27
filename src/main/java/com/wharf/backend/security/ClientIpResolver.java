package com.wharf.backend.security;

import com.wharf.backend.configuration.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Determines the caller's IP address, honouring {@code X-Forwarded-For} only where it can be
 * believed.
 *
 * <p>By default the socket peer address is the only trustworthy source: a client can put any
 * value in {@code X-Forwarded-For}. Only when a trusted proxy (Traefik) sits in front and
 * overwrites the header do we read it — its first entry is then the real client IP. Gated by
 * {@code rate-limit.trust-forwarded-header}, which production sets and local dev does not.
 *
 * <p>Extracted from the rate-limit filter so the security-notification mails resolve the IP
 * they report by exactly the same rule. A notice that names an address the reader is expected
 * to recognise must not be spoofable by the very request it is reporting on.
 */
@Component
public class ClientIpResolver {

    private final RateLimitProperties properties;

    public ClientIpResolver(RateLimitProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        if (properties.trustForwardedHeader()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
