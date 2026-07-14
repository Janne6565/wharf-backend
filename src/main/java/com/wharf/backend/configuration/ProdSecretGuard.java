package com.wharf.backend.configuration;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Fails startup if the prod profile is active while still using the insecure JWT secret
 * committed as the local-dev fallback. The secret must be injected via {@code JWT_SECRET_KEY}
 * in every real environment; booting with the default would sign forgeable tokens.
 */
@Component
public class ProdSecretGuard {

    /** Must match the {@code jwt.secret-key} fallback in {@code application.properties}. */
    static final String DEV_DEFAULT_SECRET = "dev-only-insecure-secret-change-me-0123456789abcdef";

    private final Environment environment;
    private final JwtProperties jwtProperties;

    public ProdSecretGuard(Environment environment, JwtProperties jwtProperties) {
        this.environment = environment;
        this.jwtProperties = jwtProperties;
    }

    @PostConstruct
    void verifyProdSecret() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains(Profiles.PROD);
        if (prod && DEV_DEFAULT_SECRET.equals(jwtProperties.secretKey())) {
            throw new IllegalStateException(
                    "Refusing to start on the prod profile with the insecure committed dev JWT secret; "
                            + "set JWT_SECRET_KEY to a strong value.");
        }
    }
}
