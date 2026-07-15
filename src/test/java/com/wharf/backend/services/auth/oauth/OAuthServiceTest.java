package com.wharf.backend.services.auth.oauth;

import com.wharf.backend.configuration.OAuthProperties;
import com.wharf.backend.entity.OAuthStateEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.OAuthProvider;
import com.wharf.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthServiceTest {

    @Mock
    private OAuthStateStore stateStore;
    @Mock
    private OAuthUserService userService;
    @Mock
    private JwtService jwtService;
    @Mock
    private OAuthProviderClient githubClient;

    private OAuthService service;

    @BeforeEach
    void setUp() {
        lenient().when(githubClient.provider()).thenReturn(OAuthProvider.GITHUB);
        // GitHub configured, Google left unconfigured (disabled).
        OAuthProperties properties = new OAuthProperties(
                "http://localhost:8080",
                new OAuthProperties.Credentials("", ""),
                new OAuthProperties.Credentials("gh-id", "gh-secret"));
        service = new OAuthService(properties, stateStore, userService, jwtService, List.of(githubClient));
    }

    @Test
    void enabledProviders_onlyReturnsConfiguredOnes() {
        assertThat(service.enabledProviders()).containsExactly("github");
    }

    @Test
    void buildAuthorizationUrl_containsRequiredParams() {
        when(stateStore.issue()).thenReturn("state-xyz");

        String url = service.buildAuthorizationUrl("github");

        assertThat(url)
                .startsWith(OAuthProvider.GITHUB.getAuthorizationUri())
                .contains("client_id=gh-id")
                .contains("response_type=code")
                .contains("state=state-xyz")
                .contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fv1%2Fauth%2Foauth%2Fgithub%2Fcallback");
    }

    @Test
    void buildAuthorizationUrl_disabledProvider_throwsProviderDisabled() {
        assertThatThrownBy(() -> service.buildAuthorizationUrl("google"))
                .isInstanceOf(OAuthCallbackException.class)
                .satisfies(e -> assertThat(((OAuthCallbackException) e).getErrorCode())
                        .isEqualTo(OAuthErrorCode.PROVIDER_DISABLED));
    }

    @Test
    void buildAuthorizationUrl_unknownProvider_throwsProviderDisabled() {
        assertThatThrownBy(() -> service.buildAuthorizationUrl("bitbucket"))
                .isInstanceOf(OAuthCallbackException.class)
                .satisfies(e -> assertThat(((OAuthCallbackException) e).getErrorCode())
                        .isEqualTo(OAuthErrorCode.PROVIDER_DISABLED));
    }

    @Test
    void handleCallback_invalidState_throwsInvalidState() {
        when(stateStore.consume("bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleCallback("github", "code", "bad"))
                .isInstanceOf(OAuthCallbackException.class)
                .satisfies(e -> assertThat(((OAuthCallbackException) e).getErrorCode())
                        .isEqualTo(OAuthErrorCode.INVALID_STATE));
        verify(githubClient, never()).authenticate(any(), any(), any());
    }

    @Test
    void handleCallback_unverifiedEmail_throwsEmailNotVerified() {
        when(stateStore.consume("valid")).thenReturn(Optional.of(state()));
        when(githubClient.authenticate(eq("code"), any(), any()))
                .thenReturn(new OAuthUserIdentity("gh-1", "a@a.com", false));

        assertThatThrownBy(() -> service.handleCallback("github", "code", "valid"))
                .isInstanceOf(OAuthCallbackException.class)
                .satisfies(e -> assertThat(((OAuthCallbackException) e).getErrorCode())
                        .isEqualTo(OAuthErrorCode.EMAIL_NOT_VERIFIED));
        verify(userService, never()).findOrCreateAndLink(any(), any());
    }

    @Test
    void handleCallback_disabledProvider_throwsProviderDisabledWithoutConsumingState() {
        assertThatThrownBy(() -> service.handleCallback("google", "code", "valid"))
                .isInstanceOf(OAuthCallbackException.class)
                .satisfies(e -> assertThat(((OAuthCallbackException) e).getErrorCode())
                        .isEqualTo(OAuthErrorCode.PROVIDER_DISABLED));
        verify(stateStore, never()).consume(any());
    }

    @Test
    void handleCallback_verifiedEmail_resolvesUserAndIssuesRefreshToken() {
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID()).email("a@a.com").tokenVersion(0).createdAt(Instant.now()).build();
        when(stateStore.consume("valid")).thenReturn(Optional.of(state()));
        OAuthUserIdentity identity = new OAuthUserIdentity("gh-1", "a@a.com", true);
        when(githubClient.authenticate(eq("code"), any(), any())).thenReturn(identity);
        when(userService.findOrCreateAndLink(OAuthProvider.GITHUB, identity)).thenReturn(user);
        when(jwtService.issueRefreshToken(user)).thenReturn("refresh-token");

        OAuthService.OAuthLoginResult result = service.handleCallback("github", "code", "valid");

        assertThat(result.user()).isSameAs(user);
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
    }

    private OAuthStateEntity state() {
        return OAuthStateEntity.builder().state("valid").createdAt(Instant.now()).build();
    }
}
