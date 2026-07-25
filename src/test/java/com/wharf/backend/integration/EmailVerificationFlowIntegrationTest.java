package com.wharf.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wharf.backend.configuration.Profiles;
import com.wharf.backend.entity.EmailVerificationEntity;
import com.wharf.backend.repository.EmailVerificationRepository;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.services.auth.EmailVerificationNotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The email-verification flow end to end, through the real filter chain and Flyway-migrated
 * H2 schema: register (no session) -> code -> verify (session) -> the account works.
 *
 * <p>The notification service is mocked out purely so the test can read the code that was
 * generated — it is the only place the plaintext ever exists, by design.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(Profiles.TEST)
class EmailVerificationFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailVerificationRepository verificationRepository;

    @MockBean
    private EmailVerificationNotificationService notificationService;

    private static final String AUTH_KEY = "auth-key-v1-base64==";

    private String base64(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Register {@code email} and return the 6-digit code that was mailed out. */
    private String registerAndCaptureCode(String email) throws Exception {
        reset(notificationService);
        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email)
                .put("authKey", AUTH_KEY)
                .put("recoveryAuthKey", "recovery-key-v1-base64==")
                .put("vault", base64("vault-v1")));
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        return capturedCode(email);
    }

    private String capturedCode(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendVerificationCode(eq(email), code.capture(), any(Duration.class));
        return code.getValue();
    }

    private MvcResult verifyEmail(String email, String code, org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email).put("code", code).put("tokenMode", "DIRECT"));
        return mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(expected)
                .andReturn();
    }

    @Test
    void register_issuesNoTokensAndCreatesAPendingVerification() throws Exception {
        String email = "pending@acme.io";

        String code = registerAndCaptureCode(email);

        assertThat(code).matches("\\d{6}");
        assertThat(userRepository.findByEmail(email)).get()
                .satisfies(user -> {
                    assertThat(user.isEmailVerified()).isFalse();
                    // The code is only ever stored hashed — never in a readable column.
                    assertThat(verificationRepository.findByUserId(user.getId())).get()
                            .satisfies(row -> assertThat(row.getCodeHash()).isNotEqualTo(code));
                });
    }

    @Test
    void verifyEmail_correctCode_issuesASessionAndClearsTheChallenge() throws Exception {
        String email = "verifyme@acme.io";
        String code = registerAndCaptureCode(email);

        MvcResult verified = verifyEmail(email, code, status().isOk());

        String accessToken = json(verified).path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(json(verified).path("refreshToken").asText()).isNotBlank();
        assertThat(json(verified).path("user").path("email").asText()).isEqualTo(email);
        // The embedded user carries the freshly flipped verification state, not a stale one.
        assertThat(json(verified).path("user").path("emailVerified").asBoolean()).isTrue();

        // The freshly issued session actually works, and the challenge is gone.
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true));
        assertThat(userRepository.findByEmail(email).orElseThrow().isEmailVerified()).isTrue();
        assertThat(verificationRepository.findByUserId(
                userRepository.findByEmail(email).orElseThrow().getId())).isEmpty();

        // And password login, refused before, now succeeds.
        String loginBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email).put("authKey", AUTH_KEY).put("tokenMode", "DIRECT"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk());
    }

    @Test
    void verifyEmail_cookieMode_setsTheHttpOnlyRefreshCookieAndOmitsItFromTheBody() throws Exception {
        String email = "cookie-verify@acme.io";
        String code = registerAndCaptureCode(email);

        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email).put("code", code));
        MvcResult verified = mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(verified.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(header -> header.startsWith("wharf_refresh=") && header.contains("HttpOnly"));
        assertThat(json(verified).path("refreshToken").isNull()).isTrue();
    }

    @Test
    void verifyEmail_wrongCode_incrementsAttemptsAndReturnsTheGenericCode() throws Exception {
        String email = "wrongcode@acme.io";
        String code = registerAndCaptureCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                                .put("email", email).put("code", wrong))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_verification_code"));

        EmailVerificationEntity row = verificationRepository
                .findByUserId(userRepository.findByEmail(email).orElseThrow().getId()).orElseThrow();
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void verifyEmail_sixthAttempt_isRejectedEvenWithTheCorrectCode() throws Exception {
        String email = "bruteforce@acme.io";
        String code = registerAndCaptureCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int attempt = 0; attempt < 5; attempt++) {
            verifyEmail(email, wrong, status().isBadRequest());
        }

        // The attempt cap — not the 20-bit code — is the security control: the fifth miss
        // destroys the challenge, so even the right digits get nothing on the sixth try.
        verifyEmail(email, code, status().isBadRequest());
        assertThat(userRepository.findByEmail(email).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void verifyEmail_expiredCode_isRejected() throws Exception {
        String email = "expired@acme.io";
        String code = registerAndCaptureCode(email);

        EmailVerificationEntity row = verificationRepository
                .findByUserId(userRepository.findByEmail(email).orElseThrow().getId()).orElseThrow();
        row.setExpiresAt(Instant.now().minusSeconds(1));
        verificationRepository.saveAndFlush(row);

        verifyEmail(email, code, status().isBadRequest());
        assertThat(userRepository.findByEmail(email).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void unverifiedAccount_isRefusedByEveryTokenIssuingPath() throws Exception {
        String email = "gated@acme.io";
        registerAndCaptureCode(email);

        // Login: the key is right, the address is not proven.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                                .put("email", email).put("authKey", AUTH_KEY).put("tokenMode", "DIRECT"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("email_not_verified"));

        // Refresh: a token minted for the account before it was gated is equally useless.
        // (There is no way to obtain one through the API, so this covers the code path via a
        // token issued directly by the verify flow of a *different*, verified account below.)
        String otherEmail = "gated-other@acme.io";
        String otherCode = registerAndCaptureCode(otherEmail);
        String refreshToken = json(verifyEmail(otherEmail, otherCode, status().isOk()))
                .path("refreshToken").asText();
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                                .put("refreshToken", refreshToken).put("tokenMode", "DIRECT"))))
                .andExpect(status().isOk());

        // Now un-verify that account and the same refresh token stops working.
        var other = userRepository.findByEmail(otherEmail).orElseThrow();
        other.setEmailVerified(false);
        userRepository.saveAndFlush(other);
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                                .put("refreshToken", refreshToken).put("tokenMode", "DIRECT"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("email_not_verified"));
    }

    @Test
    void deviceCodeExchange_isRefusedWhileUnverifiedAndWorksAfterwards() throws Exception {
        String email = "device-gate@acme.io";
        String code = registerAndCaptureCode(email);
        String accessToken = json(verifyEmail(email, code, status().isOk())).path("accessToken").asText();

        MvcResult issued = mockMvc.perform(post("/api/v1/device-codes")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        String deviceCode = json(issued).path("code").asText();
        String exchangeBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("code", deviceCode).put("deviceName", "tui"));

        // Revoking verification blocks the pairing exchange...
        var user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(false);
        userRepository.saveAndFlush(user);
        mockMvc.perform(post("/api/v1/device-codes/exchange")
                        .contentType(MediaType.APPLICATION_JSON).content(exchangeBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("email_not_verified"));

        // ...and the refused exchange did not burn the one-time code, so it still works once
        // the address is verified again.
        user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        userRepository.saveAndFlush(user);
        mockMvc.perform(post("/api/v1/device-codes/exchange")
                        .contentType(MediaType.APPLICATION_JSON).content(exchangeBody))
                .andExpect(status().isOk());
    }

    @Test
    void resendVerification_unknownEmail_isAcceptedWithoutRevealingAnything() throws Exception {
        reset(notificationService);

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                                .put("email", "nobody@acme.io"))))
                .andExpect(status().isAccepted())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString()).isEmpty());

        org.mockito.Mockito.verifyNoInteractions(notificationService);
    }

    @Test
    void resendVerification_withinCooldown_silentlySendsNothing() throws Exception {
        String email = "resend@acme.io";
        registerAndCaptureCode(email);

        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode().put("email", email));
        reset(notificationService);
        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        // Registration only just sent one, so the 60s cooldown swallows this — silently,
        // so a caller cannot tell a throttled resend from a delivered one.
        org.mockito.Mockito.verifyNoInteractions(notificationService);
    }

    @Test
    void resendVerification_afterCooldown_mailsAFreshUsableCode() throws Exception {
        String email = "resend-ok@acme.io";
        String firstCode = registerAndCaptureCode(email);

        // Age the row past the cooldown instead of sleeping through it.
        EmailVerificationEntity row = verificationRepository
                .findByUserId(userRepository.findByEmail(email).orElseThrow().getId()).orElseThrow();
        row.setLastSentAt(Instant.now().minus(Duration.ofMinutes(5)));
        verificationRepository.saveAndFlush(row);

        reset(notificationService);
        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                objectMapper.createObjectNode().put("email", email))))
                .andExpect(status().isAccepted());

        String secondCode = capturedCode(email);
        // The old code is replaced outright — only one code is ever live per account.
        verifyEmail(email, firstCode, status().isBadRequest());
        verifyEmail(email, secondCode, status().isOk());
    }
}
