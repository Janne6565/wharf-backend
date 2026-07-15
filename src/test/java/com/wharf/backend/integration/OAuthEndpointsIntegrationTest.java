package com.wharf.backend.integration;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OAuth surface (with no providers configured) plus the profile flags and the atomic
 * account-setup endpoint for OAuth accounts, driven through the real filter chain and
 * Flyway-migrated H2 schema.
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
    void registeredUser_profileFlagsAllTrue_andSetupRejected() throws Exception {
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

        // A registered account already has a recovery key + vault -> first-time-only setup is rejected.
        String setupBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("recoveryAuthKey", "another-recovery")
                .put("vault", base64("another-vault")));
        mockMvc.perform(post("/api/v1/auth/setup")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(setupBody))
                .andExpect(status().isConflict());
    }

    @Test
    void oauthOnlyUser_setupIsAtomicFirstTimeOnly_andEnablesPasswordLogin() throws Exception {
        // Simulate an account created via OAuth: no password, no recovery, no vault.
        UserEntity oauthUser = oauthOnlyUser("oauth-only@acme.io");
        String token = jwtService.issueIdentityToken(oauthUser);

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.hasRecovery").value(false))
                .andExpect(jsonPath("$.hasVault").value(false));

        // Password login against an OAuth-only account behaves exactly like a wrong password.
        String loginBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", "oauth-only@acme.io").put("authKey", "master-auth-key").put("tokenMode", "DIRECT"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isUnauthorized());

        // Unauthenticated setup is rejected by the filter chain.
        String setupBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("recoveryAuthKey", "fresh-recovery")
                .put("vault", base64("initial-vault"))
                .put("authKey", "master-auth-key"));
        mockMvc.perform(post("/api/v1/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON).content(setupBody))
                .andExpect(status().isUnauthorized());

        // Atomicity: an invalid vault blob fails the whole setup, leaving recovery unset.
        String badVaultBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("recoveryAuthKey", "fresh-recovery")
                .put("vault", "!!!not-base64!!!"));
        mockMvc.perform(post("/api/v1/auth/setup")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(badVaultBody))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.hasRecovery").value(false))
                .andExpect(jsonPath("$.hasVault").value(false));

        // Successful setup (with optional authKey) commits recovery + vault + password together.
        mockMvc.perform(post("/api/v1/auth/setup")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(setupBody))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.hasRecovery").value(true))
                .andExpect(jsonPath("$.hasVault").value(true));

        // The optional authKey enabled password login.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk());

        // Setup is strictly first-time: a second attempt is a conflict.
        mockMvc.perform(post("/api/v1/auth/setup")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(setupBody))
                .andExpect(status().isConflict());
    }

    @Test
    void oauthOnlyUser_setupWithoutAuthKey_leavesPasswordLoginDisabled() throws Exception {
        UserEntity oauthUser = oauthOnlyUser("oauth-no-password@acme.io");
        String token = jwtService.issueIdentityToken(oauthUser);

        String setupBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("recoveryAuthKey", "fresh-recovery")
                .put("vault", base64("initial-vault")));
        mockMvc.perform(post("/api/v1/auth/setup")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(setupBody))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.hasRecovery").value(true))
                .andExpect(jsonPath("$.hasVault").value(true));

        String loginBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", "oauth-no-password@acme.io").put("authKey", "anything").put("tokenMode", "DIRECT"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isUnauthorized());
    }

    private UserEntity oauthOnlyUser(String email) {
        return userRepository.saveAndFlush(UserEntity.builder()
                .id(UUID.randomUUID())
                .email(email)
                .authKeyHash(null)
                .recoveryKeyHash(null)
                .tokenVersion(0)
                .createdAt(Instant.now())
                .build());
    }
}
