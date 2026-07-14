package com.wharf.backend.security.ratelimit;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-client-IP token-bucket rate limiting for the public auth surface. Recovery
 * endpoints get a tighter bucket than the rest because a recovery code is the only
 * password-reset path and thus the highest-value brute-force target.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String AUTH_PREFIX = "/api/v1/auth";
    private static final String RECOVERY_PREFIX = "/api/v1/auth/recover";
    private static final String DEVICE_EXCHANGE_PATH = "/api/v1/device-codes/exchange";

    private final RateLimitProperties properties;
    private final ProblemDetailWriter problemDetailWriter;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties, ProblemDetailWriter problemDetailWriter) {
        this.properties = properties;
        this.problemDetailWriter = problemDetailWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || categoryOf(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        Category category = categoryOf(request.getRequestURI());
        String key = category.name() + ":" + clientIp(request);
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket(category));

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            problemDetailWriter.write(response, HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests — please slow down and try again shortly");
        }
    }

    private Category categoryOf(String path) {
        if (path.startsWith(RECOVERY_PREFIX)) {
            return Category.RECOVERY;
        }
        if (path.startsWith(AUTH_PREFIX) || path.equals(DEVICE_EXCHANGE_PATH)) {
            return Category.AUTH;
        }
        return null;
    }

    private Bucket newBucket(Category category) {
        int capacity = category == Category.RECOVERY
                ? properties.recoveryCapacity() : properties.authCapacity();
        var period = category == Category.RECOVERY
                ? properties.recoveryRefillPeriod() : properties.authRefillPeriod();
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, period));
        return Bucket.builder().addLimit(limit).build();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private enum Category {
        AUTH,
        RECOVERY
    }
}
