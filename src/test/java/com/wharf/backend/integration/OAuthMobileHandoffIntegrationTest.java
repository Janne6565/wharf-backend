package com.wharf.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wharf.backend.configuration.Profiles;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.DeviceCodeResponse;
import com.wharf.backend.model.core.OAuthClient;
import com.wharf.backend.repository.OAuthStateRepository;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.services.devicecode.DeviceCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The mobile OAuth deep-link hand-off, driven through the real filter chain and Flyway H2
 * schema. GitHub is configured (client id/secret) so {@code /authorize} builds a real
 * consent URL and persists a state row without any network call; the provider code exchange
 * itself needs the network, so the callback's happy path is proven the way the mobile branch
 * mints its credential — a one-time device code, exchanged at {@code /device-codes/exchange}
 * for a session belonging to the right user.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(Profiles.TEST)
@TestPropertySource(properties = {
        "oauth.github.client-id=gh-test-id",
        "oauth.github.client-secret=gh-test-secret",
        "oauth.mobile-redirect-uri=wharf://oauth"
})
class OAuthMobileHandoffIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OAuthStateRepository stateRepository;
    @Autowired
    private DeviceCodeService deviceCodeService;

    @Test
    void authorize_mobileClient_persistsMobileStateAndRedirectsToProvider() throws Exception {
        stateRepository.deleteAll();

        mockMvc.perform(get("/api/v1/auth/oauth/github/authorize").param("client", "mobile"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.startsWith("https://github.com/login/oauth/authorize")));

        assertThat(stateRepository.findAll())
                .singleElement()
                .satisfies(row -> assertThat(row.getClient()).isEqualTo(OAuthClient.MOBILE));
    }

    @Test
    void authorize_defaultClient_persistsWebStateAndRedirectsToProvider() throws Exception {
        stateRepository.deleteAll();

        mockMvc.perform(get("/api/v1/auth/oauth/github/authorize"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.startsWith("https://github.com/login/oauth/authorize")));

        assertThat(stateRepository.findAll())
                .singleElement()
                .satisfies(row -> assertThat(row.getClient()).isEqualTo(OAuthClient.WEB));
    }

    @Test
    void mobileIssuedDeviceCode_isExchangeableForTheRightUsersSession() throws Exception {
        // The mobile OAuth branch resolves the user, then mints its credential via exactly
        // this call — DeviceCodeService.issue(userId) — instead of a refresh cookie.
        UserEntity user = userRepository.saveAndFlush(UserEntity.builder()
                .id(UUID.randomUUID())
                .email("mobile-oauth@acme.io")
                .authKeyHash(null)
                .recoveryKeyHash(null)
                .tokenVersion(0)
                .createdAt(Instant.now())
                .build());
        DeviceCodeResponse issued = deviceCodeService.issue(user.getId());

        String exchangeBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("code", issued.code())
                .put("deviceName", "iPhone"));

        mockMvc.perform(post("/api/v1/device-codes/exchange")
                        .contentType(MediaType.APPLICATION_JSON).content(exchangeBody))
                .andExpect(status().isOk())
                // DIRECT session (the exchange never uses cookies): tokens are in the body,
                // for the user the OAuth login resolved.
                .andExpect(cookie().doesNotExist("wharf_refresh"))
                .andExpect(jsonPath("$.user.email").value("mobile-oauth@acme.io"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        // One-time: a second exchange of the same code is gone (410).
        mockMvc.perform(post("/api/v1/device-codes/exchange")
                        .contentType(MediaType.APPLICATION_JSON).content(exchangeBody))
                .andExpect(status().isGone());
    }
}
