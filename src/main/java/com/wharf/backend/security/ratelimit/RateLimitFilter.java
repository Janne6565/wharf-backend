package com.wharf.backend.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wharf.backend.configuration.RateLimitProperties;
import com.wharf.backend.security.ProblemDetailWriter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Per-client-IP token-bucket rate limiting for the public auth surface. Recovery
 * endpoints get a tighter bucket than the rest because a recovery code is the only
 * password-reset path and thus the highest-value brute-force target. Invite creation is
 * limited too, since each request can trigger an outbound notification email.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String AUTH_PREFIX = "/api/v1/auth";
    private static final String RECOVERY_PREFIX = "/api/v1/auth/recover";
    private static final String DEVICE_EXCHANGE_PATH = "/api/v1/device-codes/exchange";
    /** Invite creation: {@code POST /api/v1/projects/{id}/invites} (not the nested delete). */
    private static final Pattern INVITE_PATH = Pattern.compile("/api/v1/projects/[^/]+/invites");
    private static final String POST_METHOD = "POST";

    /** Cap on distinct client keys tracked at once, so the store can't grow unbounded. */
    private static final long MAX_TRACKED_CLIENTS = 100_000;
    /** Evict an idle client's bucket well after its window has refilled. */
    private static final Duration BUCKET_IDLE_EXPIRY = Duration.ofHours(1);

    private final RateLimitProperties properties;
    private final ProblemDetailWriter problemDetailWriter;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_CLIENTS)
            .expireAfterAccess(BUCKET_IDLE_EXPIRY)
            .build();

    public RateLimitFilter(RateLimitProperties properties, ProblemDetailWriter problemDetailWriter) {
        this.properties = properties;
        this.problemDetailWriter = problemDetailWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || categoryOf(request) == null;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        Category category = categoryOf(request);
        String key = category.name() + ":" + clientIp(request);
        Bucket bucket = buckets.get(key, k -> newBucket(category));

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            problemDetailWriter.write(response, HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests — please slow down and try again shortly");
        }
    }

    private Category categoryOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith(RECOVERY_PREFIX)) {
            return Category.RECOVERY;
        }
        if (path.startsWith(AUTH_PREFIX) || path.equals(DEVICE_EXCHANGE_PATH)) {
            return Category.AUTH;
        }
        if (POST_METHOD.equalsIgnoreCase(request.getMethod()) && INVITE_PATH.matcher(path).matches()) {
            return Category.INVITE;
        }
        return null;
    }

    private Bucket newBucket(Category category) {
        int capacity = switch (category) {
            case RECOVERY -> properties.recoveryCapacity();
            case INVITE -> properties.inviteCapacity();
            case AUTH -> properties.authCapacity();
        };
        Duration period = switch (category) {
            case RECOVERY -> properties.recoveryRefillPeriod();
            case INVITE -> properties.inviteRefillPeriod();
            case AUTH -> properties.authRefillPeriod();
        };
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, period));
        return Bucket.builder().addLimit(limit).build();
    }

    private String clientIp(HttpServletRequest request) {
        // By default the socket peer address is the only trustworthy source: a client can
        // put any value in X-Forwarded-For. Only when a trusted proxy (Traefik) sits in
        // front and overwrites the header do we read it — its first entry is then the real
        // client IP. Gated by rate-limit.trust-forwarded-header (see application-prod).
        if (properties.trustForwardedHeader()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private enum Category {
        AUTH,
        RECOVERY,
        INVITE
    }
}
