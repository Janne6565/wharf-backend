package com.wharf.backend.configuration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Signing key and lifetimes for the self-issued HMAC JWTs.
 * The secret is injected in every real environment via {@code JWT_SECRET_KEY}.
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(

        @NotBlank
        String secretKey,

        @NotNull
        Duration identityExpiration,

        @NotNull
        Duration refreshExpiration,

        @NotBlank
        String issuer
) {
}
