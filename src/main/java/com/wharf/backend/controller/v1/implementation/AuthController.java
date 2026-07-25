package com.wharf.backend.controller.v1.implementation;

import com.wharf.backend.controller.v1.schema.AuthApi;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.AccountSetupRequest;
import com.wharf.backend.model.action.ChangePasswordRequest;
import com.wharf.backend.model.action.LoginRequest;
import com.wharf.backend.model.action.RecoveryResetRequest;
import com.wharf.backend.model.action.RecoveryVerifyRequest;
import com.wharf.backend.model.action.RefreshRequest;
import com.wharf.backend.model.action.RegisterRequest;
import com.wharf.backend.model.action.ResendVerificationRequest;
import com.wharf.backend.model.action.VerifyEmailRequest;
import com.wharf.backend.model.core.AccessTokenResponse;
import com.wharf.backend.model.core.AuthResponse;
import com.wharf.backend.model.core.RecoveryVerifyResponse;
import com.wharf.backend.model.core.RegistrationResponse;
import com.wharf.backend.model.core.SessionResponse;
import com.wharf.backend.model.core.TokenMode;
import com.wharf.backend.model.core.TokenPair;
import com.wharf.backend.model.core.VaultUpdateResponse;
import com.wharf.backend.security.RefreshCookieFactory;
import com.wharf.backend.services.auth.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Optional;

@RestController
public class AuthController implements AuthApi {

    private final AuthService authService;
    private final RefreshCookieFactory refreshCookieFactory;

    public AuthController(AuthService authService, RefreshCookieFactory refreshCookieFactory) {
        this.authService = authService;
        this.refreshCookieFactory = refreshCookieFactory;
    }

    @Override
    public ResponseEntity<RegistrationResponse> register(RegisterRequest request) {
        // 202, not 201-with-tokens: the account exists but is inert until the emailed code
        // comes back, so there is deliberately no session and no cookie to hand out here.
        return ResponseEntity.accepted().body(authService.register(request));
    }

    @Override
    public ResponseEntity<SessionResponse> verifyEmail(VerifyEmailRequest request, HttpServletResponse response) {
        return ResponseEntity.ok(toSessionResponse(authService.verifyEmail(request), response));
    }

    @Override
    public ResponseEntity<Void> resendVerification(ResendVerificationRequest request) {
        authService.resendVerification(request);
        return ResponseEntity.accepted().build();
    }

    @Override
    public ResponseEntity<SessionResponse> login(LoginRequest request, HttpServletResponse response) {
        return ResponseEntity.ok(toSessionResponse(authService.login(request), response));
    }

    @Override
    public ResponseEntity<AccessTokenResponse> refresh(RefreshRequest request,
                                                       HttpServletRequest httpRequest,
                                                       HttpServletResponse response) {
        Optional<String> cookieToken = readRefreshCookie(httpRequest);
        String token = cookieToken.orElseGet(() -> request == null ? null : request.refreshToken());
        TokenMode mode = cookieToken.isPresent()
                ? TokenMode.COOKIE
                : (request == null ? TokenMode.COOKIE : request.tokenModeOrDefault());

        AuthService.TokenIssue issue = authService.refresh(token, mode);
        if (issue.mode() == TokenMode.COOKIE) {
            setRefreshCookie(response, issue.refreshToken());
            return ResponseEntity.ok(new AccessTokenResponse(issue.accessToken(), null));
        }
        return ResponseEntity.ok(new AccessTokenResponse(issue.accessToken(), issue.refreshToken()));
    }

    @Override
    public ResponseEntity<RecoveryVerifyResponse> recoverVerify(RecoveryVerifyRequest request) {
        return ResponseEntity.ok(authService.recoverVerify(request));
    }

    @Override
    public ResponseEntity<AuthResponse> recoverReset(RecoveryResetRequest request, HttpServletResponse response) {
        AuthService.TokenIssue issue = authService.recoverReset(request);
        return ResponseEntity.ok(toAuthResponse(issue, response));
    }

    @Override
    public ResponseEntity<Void> setupAccount(AccountSetupRequest request, UserEntity user) {
        authService.setupAccount(user.getId(), request);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<VaultUpdateResponse> changePassword(ChangePasswordRequest request, UserEntity user) {
        return ResponseEntity.ok(authService.changePassword(user.getId(), request));
    }

    /**
     * Builds the login / verify-email body: in COOKIE mode the refresh token is set as an
     * httpOnly cookie and omitted from the body; in DIRECT mode it is returned in the body.
     */
    private SessionResponse toSessionResponse(AuthService.TokenIssue issue, HttpServletResponse response) {
        if (issue.mode() == TokenMode.COOKIE) {
            setRefreshCookie(response, issue.refreshToken());
            return new SessionResponse(issue.user(), issue.accessToken(), null);
        }
        return new SessionResponse(issue.user(), issue.accessToken(), issue.refreshToken());
    }

    /**
     * Builds the recovery-reset body: in COOKIE mode the refresh token is set as
     * an httpOnly cookie and omitted from the pair; in DIRECT mode it is returned in the body.
     */
    private AuthResponse toAuthResponse(AuthService.TokenIssue issue, HttpServletResponse response) {
        if (issue.mode() == TokenMode.COOKIE) {
            setRefreshCookie(response, issue.refreshToken());
            return new AuthResponse(issue.user(), new TokenPair(issue.accessToken(), null));
        }
        return new AuthResponse(issue.user(), new TokenPair(issue.accessToken(), issue.refreshToken()));
    }

    private void setRefreshCookie(HttpServletResponse response, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookieFactory.create(refreshToken).toString());
    }

    private Optional<String> readRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> cookie.getName().equals(refreshCookieFactory.cookieName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }
}
