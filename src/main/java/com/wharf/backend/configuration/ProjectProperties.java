package com.wharf.backend.configuration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Tunables for team projects. Currently just the invite time-to-live: a project invite
 * older than this is treated as expired (filtered from listings, rejected on accept and
 * replaceable by a fresh invite to the same email).
 */
@Validated
@ConfigurationProperties(prefix = "wharf.projects")
public record ProjectProperties(

        @NotNull
        @DefaultValue("P14D")
        Duration inviteTtl
) {
}
