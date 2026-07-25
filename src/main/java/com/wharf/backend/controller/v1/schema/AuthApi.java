package com.wharf.backend.controller.v1.schema;

import com.wharf.backend.configuration.OpenApiConfig;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.AccountSetupRequest;
import com.wharf.backend.model.action.ChangePasswordRequest;
import com.wharf.backend.model.action.LoginRequest;
import com.wharf.backend.model.action.LogoutRequest;
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
import com.wharf.backend.model.core.VaultUpdateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Account registration, login, refresh and recovery")
public interface AuthApi {

    @PostMapping("/register")
    @Operation(operationId = "register",
            summary = "Create a new zero-knowledge account and send a verification code",
            description = "Creates the account and emails a 6-digit code. No tokens are issued and no cookie is "
                    + "set: the account is unusable until the code is submitted to /auth/verify-email, which is "
                    + "what returns the session.")
    @ApiResponse(responseCode = "202", description = "Account created; verification code sent")
    @ApiResponse(responseCode = "409", description = "Email already registered")
    ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegisterRequest request);

    @PostMapping("/verify-email")
    @Operation(operationId = "verifyEmail",
            summary = "Complete registration with the emailed 6-digit code",
            description = "Marks the address verified and issues a full session, exactly like login: in COOKIE "
                    + "mode the refresh token is set as an httpOnly cookie, in DIRECT mode it is returned in the "
                    + "body. The code expires after 15 minutes and dies after 5 failed attempts (ask for a "
                    + "resend). Every failure returns the same generic 400 so the endpoint is not an oracle.")
    @ApiResponse(responseCode = "200", description = "Email verified; session issued")
    @ApiResponse(responseCode = "400", description = "Code is wrong, expired, missing or out of attempts (code: invalid_verification_code)")
    ResponseEntity<SessionResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request,
                                                HttpServletResponse response);

    @PostMapping("/resend-verification")
    @Operation(operationId = "resendVerification",
            summary = "Re-send the email verification code",
            description = "Always 202 with no body, whether or not the address belongs to an unverified account, "
                    + "so it cannot be used to probe which emails are registered. Subject to a 60-second "
                    + "per-account cooldown, which is likewise silent.")
    @ApiResponse(responseCode = "202", description = "Request accepted")
    ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request);

    @PostMapping("/login")
    @Operation(operationId = "login",
            summary = "Authenticate with the derived auth key",
            description = "In COOKIE mode the refresh token is set as an httpOnly cookie; in DIRECT mode it is returned in the body.")
    @ApiResponse(responseCode = "200", description = "Authenticated")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    @ApiResponse(responseCode = "403", description = "Email address is not verified (code: email_not_verified)")
    ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request,
                                          HttpServletResponse response);

    @PostMapping("/refresh")
    @Operation(operationId = "refresh",
            summary = "Exchange a refresh token for a new access token",
            description = "Reads the refresh token from the httpOnly cookie when present, otherwise from the body.")
    @ApiResponse(responseCode = "200", description = "New access token issued")
    @ApiResponse(responseCode = "401", description = "Missing, invalid or revoked refresh token")
    @ApiResponse(responseCode = "403", description = "Email address is not verified (code: email_not_verified)")
    ResponseEntity<AccessTokenResponse> refresh(@RequestBody(required = false) RefreshRequest request,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse response);

    @PostMapping("/logout")
    @Operation(operationId = "logout",
            summary = "End the session in this browser, or revoke every session",
            description = "Always answers 204 and always returns the clearing refresh cookie (Max-Age=0), so a "
                    + "browser can drop its httpOnly cookie even when its access token has already expired — "
                    + "which is precisely when people sign out. Without allDevices this is a *local* sign-out: "
                    + "the refresh token itself stays cryptographically valid until it expires, because the "
                    + "design is stateless and there is no server-side token store. With allDevices=true the "
                    + "account's token version is bumped and every issued token dies immediately, including the "
                    + "paired TUI and mobile clients; that variant requires a valid identity token (401 without "
                    + "one), so nobody can revoke someone else's sessions.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "204", description = "Signed out; refresh cookie cleared")
    @ApiResponse(responseCode = "401", description = "allDevices was requested without a valid identity token")
    ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest request,
                                @AuthenticationPrincipal UserEntity user,
                                HttpServletResponse response);

    @PostMapping("/recover/verify")
    @Operation(operationId = "recoverVerify",
            summary = "Verify a recovery code and return the vault for re-encryption")
    @ApiResponse(responseCode = "200", description = "Recovery code valid; vault returned")
    @ApiResponse(responseCode = "401", description = "Recovery code does not match")
    ResponseEntity<RecoveryVerifyResponse> recoverVerify(@Valid @RequestBody RecoveryVerifyRequest request);

    @PostMapping("/recover/reset")
    @Operation(operationId = "recoverReset",
            summary = "Re-encrypt the vault under a new password and rotate the recovery code",
            description = "Atomically replaces the credential hashes and vault, invalidates the old recovery code and revokes all existing sessions. "
                    + "In COOKIE mode the refresh token is set as an httpOnly cookie; in DIRECT mode it is returned in the body.")
    @ApiResponse(responseCode = "200", description = "Reset complete; new tokens issued")
    @ApiResponse(responseCode = "401", description = "Recovery code does not match")
    ResponseEntity<AuthResponse> recoverReset(@Valid @RequestBody RecoveryResetRequest request,
                                              HttpServletResponse response);

    @PostMapping("/setup")
    @Operation(operationId = "setupAccount",
            summary = "One-time onboarding for an account created via OAuth",
            description = "Atomically sets the recovery key, the initial encrypted vault and (optionally) a password "
                    + "auth key — all-or-nothing, so an account can never end up with a recovery key but no vault. "
                    + "Strictly first-time: rejected with 409 if a recovery key or vault already exists, or if authKey "
                    + "is supplied while a password is already set. Rotation stays exclusive to recover/reset.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "204", description = "Account set up")
    @ApiResponse(responseCode = "400", description = "Invalid vault payload")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "409", description = "Recovery key or vault already exists, or password already set")
    ResponseEntity<Void> setupAccount(@Valid @RequestBody AccountSetupRequest request,
                                      @AuthenticationPrincipal UserEntity user);

    @PostMapping("/password")
    @Operation(operationId = "changePassword",
            summary = "Rotate the master-password auth key and re-encrypt the vault",
            description = "Authenticated master-password change: verifies the current auth key, replaces the stored "
                    + "auth-key hash with one derived from the new password and stores the vault blob re-encrypted "
                    + "under it (bumping the vault version). The recovery code is left untouched, and existing "
                    + "sessions stay valid (device pairings are independent of the password). Requires a password to "
                    + "already be set — OAuth-first accounts set one via /auth/setup.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Password changed; new vault version returned")
    @ApiResponse(responseCode = "400", description = "Invalid vault payload")
    @ApiResponse(responseCode = "401", description = "Not authenticated, or the current auth key does not match")
    @ApiResponse(responseCode = "409", description = "No password is set for this account")
    ResponseEntity<VaultUpdateResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                       @AuthenticationPrincipal UserEntity user);
}
