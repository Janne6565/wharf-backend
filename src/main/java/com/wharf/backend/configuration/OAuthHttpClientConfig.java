package com.wharf.backend.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * The synchronous HTTP client used for server-to-server OAuth calls (token exchange and
 * user-info fetch). {@link RestClient} ships with spring-web, so no reactive stack is
 * pulled in. Scheduling is enabled here so abandoned OAuth states can be evicted.
 */
@Configuration
@EnableScheduling
public class OAuthHttpClientConfig {

    @Bean("oauthRestClient")
    public RestClient oauthRestClient(RestClient.Builder builder) {
        return builder.build();
    }
}
