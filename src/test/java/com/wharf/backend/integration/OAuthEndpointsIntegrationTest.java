package com.wharf.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wharf.backend.configuration.Profiles;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OAuth surface (with no providers configured) plus the profile flags and recovery-init
 * endpoint added for OAuth accounts, driven through the real filter chain and H2 schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(Profiles.TEST)
class OAuthEndpointsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;

    private String base64(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void providers_noneConfigured_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers").isArray())
                .andExpect(jsonPath("$.providers").isEmpty());
    }

    @Test
    void authorize_disabledProvider_redirectsToCompleteWithError() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/google/authorize"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/oauth/complete?error=provider_disabled"));
    }

    @Test
    void callback_disabledProvider_redirectsToCompleteWithError() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/github/callback").param("code", "x").param("state", "y"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/oauth/complete?error=provider_disabled"));
    }

    @Test
    void registeredUser_profileFlagsAllTrue_andRecoveryAlreadySet() throws Exception {
        String email = "flags@acme.io";
        String registerBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email)
                .put("authKey", "auth-key")
                .put("recoveryAuthKey", "recovery-key")
                .put("vault", base64("vault-v1")));
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isCreated());

        String loginBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email).put("authKey", "auth-key").put("tokenMode", "DIRECT"));
        MvcResult loggedIn = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loggedIn.getResponse().getContentAsString())
                .path("accessToken").asText();

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.hasRecovery").value(true))
                .andExpect(jsonPath("$.hasVault").value(true));

        // A registered account already has a recovery key -> first-time-only init is rejected.
        String recoveryBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("recoveryAuthKey", "another-recovery"));
        mockMvc.perform(post("/api/v1/auth/recovery")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(recoveryBody))
                .andExpect(status().isConflict());
    }

    @Test
    void oauthOnlyUser_profileFlagsFalse_loginRejected_andRecoveryInitFirstTimeOnly() throws Exception {
        // Simulate an account created via OAuth: no password, no recovery, no vault.
        UserEntity oauthUser = userRepository.saveAndFlush(UserEntity.builder()
                .id(UUID.randomUUID())
                .email("oauth-only@acme.io")
                .authKeyHash(null)
                .recoveryKeyHash(null)
                .tokenVersion(0)
                .createdAt(Instant.now())
                .build());
        String token = jwtService.issueIdentityToken(oauthUser);

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.hasRecovery").value(false))
                .andExpect(jsonPath("$.hasVault").value(false));

        // Password login against an OAuth-only account behaves exactly like a wrong password.
        String loginBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", "oauth-only@acme.io").put("authKey", "whatever").put("tokenMode", "DIRECT"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isUnauthorized());

        // First recovery init succeeds (204); a second is rejected (409).
        String recoveryBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("recoveryAuthKey", "fresh-recovery"));
        mockMvc.perform(post("/api/v1/auth/recovery")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(recoveryBody))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/recovery")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(recoveryBody))
                .andExpect(status().isConflict());

        // Unauthenticated recovery init is rejected by the filter chain.
        mockMvc.perform(post("/api/v1/auth/recovery")
                        .contentType(MediaType.APPLICATION_JSON).content(recoveryBody))
                .andExpect(status().isUnauthorized());
    }
}
