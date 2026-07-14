package com.wharf.backend.controller.v1.schema;

import com.wharf.backend.model.action.LoginRequest;
import com.wharf.backend.model.action.RecoveryResetRequest;
import com.wharf.backend.model.action.RecoveryVerifyRequest;
import com.wharf.backend.model.action.RefreshRequest;
import com.wharf.backend.model.action.RegisterRequest;
import com.wharf.backend.model.core.AccessTokenResponse;
import com.wharf.backend.model.core.AuthResponse;
import com.wharf.backend.model.core.RecoveryVerifyResponse;
import com.wharf.backend.model.core.SessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Account registration, login, refresh and recovery")
public interface AuthApi {

    @PostMapping("/register")
    @Operation(operationId = "register",
            summary = "Create a new zero-knowledge account",
            description = "In COOKIE mode the refresh token is set as an httpOnly cookie; in DIRECT mode it is returned in the body.")
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "409", description = "Email already registered")
    ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                          HttpServletResponse response);

    @PostMapping("/login")
    @Operation(operationId = "login",
            summary = "Authenticate with the derived auth key",
            description = "In COOKIE mode the refresh token is set as an httpOnly cookie; in DIRECT mode it is returned in the body.")
    @ApiResponse(responseCode = "200", description = "Authenticated")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request,
                                          HttpServletResponse response);

    @PostMapping("/refresh")
    @Operation(operationId = "refresh",
            summary = "Exchange a refresh token for a new access token",
            description = "Reads the refresh token from the httpOnly cookie when present, otherwise from the body.")
    @ApiResponse(responseCode = "200", description = "New access token issued")
    @ApiResponse(responseCode = "401", description = "Missing, invalid or revoked refresh token")
    ResponseEntity<AccessTokenResponse> refresh(@RequestBody(required = false) RefreshRequest request,
                                                HttpServletRequest httpRequest,
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
}
