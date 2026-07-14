package com.wharf.backend.configuration;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Hard ceiling on the opaque vault blob size. The server never parses the blob;
 * it only refuses anything larger than this.
 */
@Validated
@ConfigurationProperties(prefix = "vault")
public record VaultProperties(

        @Min(1)
        long maxSizeBytes
) {
}
