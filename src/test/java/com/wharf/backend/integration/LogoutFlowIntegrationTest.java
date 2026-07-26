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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Logout end to end through the real filter chain: the local sign-out that only clears the
 * browser's cookie, and the {@code allDevices} variant that bumps the token version and
 * therefore kills every token already issued.
 *
 * <p>The distinction is the whole point of the endpoint, so both halves are pinned here:
 * a default logout must <em>not</em> touch the token version (other devices keep working),
 * and an unauthenticated caller must be able to clear its own cookie but never bump anyone's
 * token version.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(Profiles.TEST)
class LogoutFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private static final String REFRESH_COOKIE_NAME = "wharf_refresh";

    private String base64(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    /** Registers an account, marks its address verified and logs in, returning the access token. */
    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email)
                .put("authKey", "auth-" + email)
                .put("recoveryAuthKey", "recovery-" + email)
                .put("vault", base64("personal-vault")));
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        userRepository.saveAndFlush(user);

        String loginBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email).put("authKey", "auth-" + email).put("tokenMode", "DIRECT"));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).path("accessToken").asText();
    }

    private int tokenVersion(String email) {
        return userRepository.findByEmail(email).orElseThrow().getTokenVersion();
    }

    /** The refresh cookie the response tells the browser to keep — expected to be an expiry. */
    private String refreshSetCookie(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(REFRESH_COOKIE_NAME + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + REFRESH_COOKIE_NAME + " cookie in the response"));
    }

    @Test
    void defaultLogoutClearsTheCookieWithoutRevokingAnything() throws Exception {
        String email = "logout-local@acme.io";
        String accessToken = register(email);
        int versionBefore = tokenVersion(email);

        MvcResult loggedOut = mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isNoContent())
                .andReturn();

        // Max-Age=0 is what actually deletes the httpOnly cookie in the browser.
        assertThat(refreshSetCookie(loggedOut)).contains("Max-Age=0");

        // A local sign-out is exactly that: nothing server-side is revoked, so the token
        // version is untouched and other devices keep working.
        assertThat(tokenVersion(email)).isEqualTo(versionBefore);
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk());
    }

    @Test
    void logoutAllDevicesRevokesEveryIssuedToken() throws Exception {
        String email = "logout-global@acme.io";
        String accessToken = register(email);
        int versionBefore = tokenVersion(email);

        String body = objectMapper.writeValueAsString(
                objectMapper.createObjectNode().put("allDevices", true));
        MvcResult loggedOut = mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(refreshSetCookie(loggedOut)).contains("Max-Age=0");
        assertThat(tokenVersion(email)).isEqualTo(versionBefore + 1);

        // Every token issued under the old version is dead — this browser's included.
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void directModeCallerGetsNoContent() throws Exception {
        String email = "logout-tui@acme.io";
        String accessToken = register(email);

        // The TUI never had a cookie; it just wants the 204 as its signal to drop its own
        // stored tokens. The clearing cookie it receives is harmless to a non-browser client.
        String body = objectMapper.writeValueAsString(
                objectMapper.createObjectNode().put("allDevices", false));
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        assertThat(tokenVersion(email)).isZero();
    }

    @Test
    void logoutWithoutAuthenticationStillClearsTheCookie() throws Exception {
        // The deliberate design decision: a browser whose access token has already expired
        // must still be able to get rid of its httpOnly refresh cookie, which it cannot
        // delete itself. Clearing a cookie is a caller-local, information-free operation.
        MvcResult loggedOut = mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(refreshSetCookie(loggedOut)).contains("Max-Age=0");
    }

    @Test
    void allDevicesWithoutAuthenticationIsRejected() throws Exception {
        String email = "logout-unauth@acme.io";
        register(email);

        String body = objectMapper.writeValueAsString(
                objectMapper.createObjectNode().put("allDevices", true));
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        // Nothing was revoked — the privileged half is unreachable without a token.
        assertThat(tokenVersion(email)).isZero();
    }
}
