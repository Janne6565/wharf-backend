package com.wharf.backend.configuration;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Attributes for the httpOnly refresh-token cookie. {@code secure} is toggled per
 * environment (false locally over plain HTTP, true everywhere else).
 */
@Validated
@ConfigurationProperties(prefix = "auth.cookie")
public record AuthCookieProperties(

        @NotBlank
        String name,

        boolean secure,

        @NotBlank
        String sameSite,

        @NotBlank
        String path
) {
}
