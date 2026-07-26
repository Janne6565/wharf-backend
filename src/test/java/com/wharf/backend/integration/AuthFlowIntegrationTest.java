package com.wharf.backend.integration;

import com.wharf.backend.configuration.Profiles;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end happy path plus the key failure branches, driven through the real filter
 * chain and Flyway-migrated H2 schema:
 * register -> verify email -> login -> device code issue/exchange -> vault get/put
 * (+ conflict) -> recovery verify/reset -> old tokens rejected.
 *
 * <p>The email-verification step itself (codes, attempts, resend) has its own end-to-end
 * test; here the address is marked verified directly so these flows stay about what they
 * are testing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(Profiles.TEST)
class AuthFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private static final String EMAIL = "deniz@acme.io";
    private static final String AUTH_KEY = "auth-key-v1-base64==";
    private static final String RECOVERY_KEY = "recovery-key-v1-base64==";
    private static final String REFRESH_COOKIE_NAME = "wharf_refresh";

    private String base64(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Shortcut past the code challenge — the flow under test starts at a usable account. */
    private void markVerified(String email) {
        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        userRepository.saveAndFlush(user);
    }

    @Test
    void fullAccountLifecycle() throws Exception {
        // 1. Register
        String registerBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", EMAIL)
                .put("authKey", AUTH_KEY)
                .put("recoveryAuthKey", RECOVERY_KEY)
                .put("vault", base64("vault-v1")));
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(json(registered).path("email").asText()).isEqualTo(EMAIL);
        assertThat(json(registered).path("verificationRequired").asBoolean()).isTrue();
        // Registration hands out nothing usable: no session cookie, no tokens.
        assertThat(registered.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(json(registered).path("tokens").isMissingNode()).isTrue();

        // Duplicate registration is rejected
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isConflict());

        // 2. Login (DIRECT so we get the tokens in the body)
        String loginBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", EMAIL).put("authKey", AUTH_KEY).put("tokenMode", "DIRECT"));

        // Until the address is verified, even the right key gets no session.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isForbidden());
        markVerified(EMAIL);
        MvcResult loggedIn = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk())
                .andReturn();
        String oldAccessToken = json(loggedIn).path("accessToken").asText();
        assertThat(oldAccessToken).isNotBlank();

        // Wrong key -> 401
        String badLogin = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", EMAIL).put("authKey", "wrong").put("tokenMode", "DIRECT"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(badLogin))
                .andExpect(status().isUnauthorized());

        // 3. Authenticated profile
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + oldAccessToken))
                .andExpect(status().isOk())
                .andExpect(status().isOk());

        // Unauthenticated -> 401
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());

        // 4. Issue a device code
        MvcResult codeIssued = mockMvc.perform(post("/api/v1/device-codes")
                        .header("Authorization", "Bearer " + oldAccessToken))
                .andExpect(status().isOk())
                .andReturn();
        String deviceCode = json(codeIssued).path("code").asText();
        assertThat(deviceCode).matches("[A-HJ-NP-Z2-9]{8}");

        // 5. Exchange the code (TUI path, always DIRECT)
        String exchangeBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("code", deviceCode).put("deviceName", "deniz-laptop"));
        MvcResult exchanged = mockMvc.perform(post("/api/v1/device-codes/exchange")
                        .contentType(MediaType.APPLICATION_JSON).content(exchangeBody))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(exchanged).path("accessToken").asText()).isNotBlank();

        // Re-using the one-time code -> 410
        mockMvc.perform(post("/api/v1/device-codes/exchange")
                        .contentType(MediaType.APPLICATION_JSON).content(exchangeBody))
                .andExpect(status().isGone());

        // 6. Vault get (initial version 1)
        MvcResult vaultV1 = mockMvc.perform(get("/api/v1/vault")
                        .header("Authorization", "Bearer " + oldAccessToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(vaultV1).path("version").asLong()).isEqualTo(1L);

        // 7. Vault put with the expected version -> bumped to 2
        String putBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("vault", base64("vault-v2")).put("expectedVersion", 1));
        MvcResult vaultV2 = mockMvc.perform(put("/api/v1/vault")
                        .header("Authorization", "Bearer " + oldAccessToken)
                        .contentType(MediaType.APPLICATION_JSON).content(putBody))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(vaultV2).path("version").asLong()).isEqualTo(2L);

        // 8. Stale expectedVersion -> 409
        mockMvc.perform(put("/api/v1/vault")
                        .header("Authorization", "Bearer " + oldAccessToken)
                        .contentType(MediaType.APPLICATION_JSON).content(putBody))
                .andExpect(status().isConflict());

        // 9. Recovery verify returns the vault; a wrong code is 401
        String verifyBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", EMAIL).put("recoveryAuthKey", RECOVERY_KEY));
        mockMvc.perform(post("/api/v1/auth/recover/verify")
                        .contentType(MediaType.APPLICATION_JSON).content(verifyBody))
                .andExpect(status().isOk());

        String badVerify = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", EMAIL).put("recoveryAuthKey", "nope"));
        mockMvc.perform(post("/api/v1/auth/recover/verify")
                        .contentType(MediaType.APPLICATION_JSON).content(badVerify))
                .andExpect(status().isUnauthorized());

        // 10. Recovery reset -> new tokens, sessions revoked
        String resetBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", EMAIL)
                .put("recoveryAuthKey", RECOVERY_KEY)
                .put("newAuthKey", "new-auth-key")
                .put("newRecoveryAuthKey", "new-recovery-key")
                .put("vault", base64("vault-v3")));
        MvcResult reset = mockMvc.perform(post("/api/v1/auth/recover/reset")
                        .contentType(MediaType.APPLICATION_JSON).content(resetBody))
                .andExpect(status().isOk())
                .andReturn();
        String newAccessToken = json(reset).path("tokens").path("accessToken").asText();
        assertThat(newAccessToken).isNotBlank();

        // 11. The pre-reset token is now revoked
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + oldAccessToken))
                .andExpect(status().isUnauthorized());

        // 12. The freshly issued token works
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk());

        // 13. Vault version kept climbing across the reset (2 -> 3)
        MvcResult vaultV3 = mockMvc.perform(get("/api/v1/vault")
                        .header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(vaultV3).path("version").asLong()).isEqualTo(3L);
    }

    @Test
    void changePasswordRotatesAuthKeyAndVaultWhileKeepingSession() throws Exception {
        String email = "changepw@acme.io";
        String oldKey = "old-auth-key-base64==";
        String newKey = "new-auth-key-base64==";

        // Register, verify, then log in (DIRECT so we hold the tokens).
        String registerBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email)
                .put("authKey", oldKey)
                .put("recoveryAuthKey", "recovery-key-base64==")
                .put("vault", base64("vault-v1"))
                .put("tokenMode", "DIRECT"));
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isAccepted());
        markVerified(email);

        MvcResult loggedIn = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                                .put("email", email).put("authKey", oldKey).put("tokenMode", "DIRECT"))))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = json(loggedIn).path("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        // Unauthenticated change-password is rejected.
        String changeBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("currentAuthKey", oldKey)
                .put("newAuthKey", newKey)
                .put("vault", base64("vault-v2")));
        mockMvc.perform(post("/api/v1/auth/password")
                        .contentType(MediaType.APPLICATION_JSON).content(changeBody))
                .andExpect(status().isUnauthorized());

        // Wrong current key -> 401, nothing changes.
        String wrongCurrent = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("currentAuthKey", "not-the-current-key")
                .put("newAuthKey", newKey)
                .put("vault", base64("vault-v2")));
        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongCurrent))
                .andExpect(status().isUnauthorized());

        // Correct current key -> 200, vault bumped 1 -> 2.
        MvcResult changed = mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content(changeBody))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(changed).path("version").asLong()).isEqualTo(2L);

        // The old password no longer logs in; the new one does.
        String oldLogin = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email).put("authKey", oldKey).put("tokenMode", "DIRECT"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(oldLogin))
                .andExpect(status().isUnauthorized());

        String newLogin = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email).put("authKey", newKey).put("tokenMode", "DIRECT"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(newLogin))
                .andExpect(status().isOk());

        // A password change does NOT revoke the current session (device pairings stand).
        MvcResult vault = mockMvc.perform(get("/api/v1/vault")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(vault).path("version").asLong()).isEqualTo(2L);
    }
}
