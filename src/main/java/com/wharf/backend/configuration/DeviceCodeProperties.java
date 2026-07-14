package com.wharf.backend.configuration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Lifetime of a device-pairing code.
 */
@Validated
@ConfigurationProperties(prefix = "device-code")
public record DeviceCodeProperties(

        @NotNull
        Duration ttl
) {
}
