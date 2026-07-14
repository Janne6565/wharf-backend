package com.wharf.backend.configuration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Per-IP token-bucket limits for the public auth endpoints. Recovery endpoints get
 * their own, tighter bucket because a valid recovery code is the only reset path.
 */
@Validated
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(

        boolean enabled,

        @Min(1)
        int authCapacity,

        @NotNull
        Duration authRefillPeriod,

        @Min(1)
        int recoveryCapacity,

        @NotNull
        Duration recoveryRefillPeriod
) {
}
