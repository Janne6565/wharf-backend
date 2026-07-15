package com.wharf.backend.controller.v1;

import com.wharf.backend.controller.v1.implementation.OAuthController;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.OAuthProvidersResponse;
import com.wharf.backend.security.RefreshCookieFactory;
import com.wharf.backend.services.auth.oauth.OAuthCallbackException;
import com.wharf.backend.services.auth.oauth.OAuthErrorCode;
import com.wharf.backend.services.auth.oauth.OAuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthControllerTest {

    @Mock
    private OAuthService oAuthService;
    @Mock
    private RefreshCookieFactory refreshCookieFactory;

    private OAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new OAuthController(oAuthService, refreshCookieFactory);
    }

    @Test
    void listProviders_returnsEnabledSlugs() {
        when(oAuthService.enabledProviders()).thenReturn(List.of("google", "github"));

        ResponseEntity<OAuthProvidersResponse> resp = controller.listProviders();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().providers()).containsExactly("google", "github");
    }

    @Test
    void authorize_redirectsToProviderConsentUrl() {
        when(oAuthService.buildAuthorizationUrl("github"))
                .thenReturn("https://github.com/login/oauth/authorize?client_id=abc");

        ResponseEntity<Void> resp = controller.authorize("github", new MockHttpServletResponse());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation().toString()).contains("github.com/login/oauth/authorize");
    }

    @Test
    void authorize_disabledProvider_redirectsToCompleteWithError() {
        when(oAuthService.buildAuthorizationUrl("google"))
                .thenThrow(new OAuthCallbackException(OAuthErrorCode.PROVIDER_DISABLED));

        ResponseEntity<Void> resp = controller.authorize("google", new MockHttpServletResponse());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation().toString()).isEqualTo("/oauth/complete?error=provider_disabled");
    }

    @Test
    void callback_success_setsRefreshCookieAndRedirectsToComplete() {
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID()).email("a@a.com").tokenVersion(0).createdAt(Instant.now()).build();
        when(oAuthService.handleCallback("github", "code", "state"))
                .thenReturn(new OAuthService.OAuthLoginResult(user, "refresh-token"));
        when(refreshCookieFactory.create("refresh-token"))
                .thenReturn(ResponseCookie.from("wharf_refresh", "refresh-token").httpOnly(true).build());

        HttpServletResponse servletResponse = new MockHttpServletResponse();
        ResponseEntity<Void> resp = controller.callback("github", "code", "state", servletResponse);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation().toString()).isEqualTo("/oauth/complete");
        assertThat(((MockHttpServletResponse) servletResponse).getHeader("Set-Cookie"))
                .contains("wharf_refresh=refresh-token").contains("HttpOnly");
    }

    @Test
    void callback_oauthFailure_redirectsToCompleteWithErrorCode() {
        when(oAuthService.handleCallback(anyString(), anyString(), anyString()))
                .thenThrow(new OAuthCallbackException(OAuthErrorCode.EMAIL_NOT_VERIFIED));

        ResponseEntity<Void> resp = controller.callback("github", "code", "state", new MockHttpServletResponse());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation().toString()).isEqualTo("/oauth/complete?error=email_not_verified");
    }

    @Test
    void callback_unexpectedException_redirectsWithServerError() {
        when(oAuthService.handleCallback(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        ResponseEntity<Void> resp = controller.callback("github", "code", "state", new MockHttpServletResponse());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation().toString()).isEqualTo("/oauth/complete?error=server_error");
    }
}
