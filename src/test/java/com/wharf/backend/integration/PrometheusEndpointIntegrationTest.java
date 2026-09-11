package com.wharf.backend.integration;

import com.wharf.backend.configuration.Profiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The in-cluster Grafana Alloy agent scrapes {@code /actuator/prometheus} without credentials,
 * so it must answer 200 through the real filter chain. The body is pinned too: request latency
 * ships as client-side percentiles, never as a bucket histogram, because every bucket is a
 * series against the cluster's shared Grafana Cloud budget.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(Profiles.TEST)
class PrometheusEndpointIntegrationTest {

    private static final String PROMETHEUS_PATH = "/actuator/prometheus";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void scrapeIsPermittedWithoutAuthAndShipsPercentilesNotBuckets() throws Exception {
        // Record at least one HTTP request so the timer exists before the scrape.
        mockMvc.perform(get("/api/v1/auth/oauth/providers")).andExpect(status().isOk());

        String body = mockMvc.perform(get(PROMETHEUS_PATH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("http_server_requests_seconds")
                .contains("quantile=\"0.95\"")
                .doesNotContain("_bucket");
    }
}
