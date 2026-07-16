package com.wharf.backend.configuration;

import com.wharf.backend.client.MailPort;
import com.wharf.backend.client.MailServiceClient;
import com.wharf.backend.client.NoopMailClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Chooses the {@link MailPort} implementation at startup: a real {@link MailServiceClient}
 * when a mail API key is configured, otherwise a {@link NoopMailClient}. This mirrors the
 * OAuth "credentials optional" model — the feature simply degrades to a no-op when the
 * secret is absent, so local dev and tests need no external mail service.
 */
@Configuration
public class MailClientConfig {

    private static final Logger log = LoggerFactory.getLogger(MailClientConfig.class);

    /** Short timeouts: a notification must not tie up a thread waiting on a slow mail service. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public MailPort mailPort(MailProperties properties, RestClient.Builder builder) {
        if (!properties.isConfigured()) {
            log.info("Mail delivery disabled (no API key configured); using no-op mail client");
            return new NoopMailClient();
        }
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        RestClient restClient = builder
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
        log.info("Mail delivery enabled via {}", properties.baseUrl());
        return new MailServiceClient(restClient, properties.baseUrl(), properties.apiKey());
    }
}
