package com.wharf.backend.configuration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Per-IP token-bucket limits for the public auth endpoints. Recovery endpoints get
 * their own, tighter bucket because a valid recovery code is the only reset path, and the
 * email-verification endpoints get one too: a 6-digit code is only ~20 bits, so throttling
 * guesses (and outbound resend mail) matters as much as the per-code attempt cap.
 */
@Validated
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(

        boolean enabled,

        /**
         * Whether to trust the {@code X-Forwarded-For} header for the client IP. Keep
         * {@code false} unless the app runs behind a trusted reverse proxy (Traefik) that
         * overwrites the header — otherwise a client can spoof it to dodge the limit.
         */
        boolean trustForwardedHeader,

        @Min(1)
        int authCapacity,

        @NotNull
        Duration authRefillPeriod,

        @Min(1)
        int recoveryCapacity,

        @NotNull
        Duration recoveryRefillPeriod,

        @Min(1)
        int verifyCapacity,

        @NotNull
        Duration verifyRefillPeriod,

        @Min(1)
        int inviteCapacity,

        @NotNull
        Duration inviteRefillPeriod
) {
}
