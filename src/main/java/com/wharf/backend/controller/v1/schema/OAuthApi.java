package com.wharf.backend.controller.v1.schema;

import com.wharf.backend.model.core.OAuthProvidersResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * OAuth social login (Google, GitHub). OAuth is only the authentication step: it links an
 * external identity to a local account, then the app issues its normal self-issued JWT
 * pair. All three endpoints are public and rate-limited.
 */
@RequestMapping("/api/v1/auth/oauth")
@Tag(name = "OAuth", description = "Google / GitHub social login (links an external identity, then issues the app's own JWTs)")
public interface OAuthApi {

    @GetMapping("/providers")
    @Operation(operationId = "listOAuthProviders",
            summary = "List enabled OAuth providers",
            description = "Returns the slugs of providers configured with a client id + secret; empty when none are configured.")
    @ApiResponse(responseCode = "200", description = "Enabled provider slugs")
    ResponseEntity<OAuthProvidersResponse> listProviders();

    @GetMapping("/{provider}/authorize")
    @Operation(operationId = "oauthAuthorize",
            summary = "Begin the OAuth authorization-code flow",
            description = "302-redirects to the provider's consent page with a one-time state. "
                    + "A disabled/unknown provider redirects to /oauth/complete?error=provider_disabled.")
    @ApiResponse(responseCode = "302", description = "Redirect to the provider consent page (or to /oauth/complete on error)")
    ResponseEntity<Void> authorize(
            @Parameter(description = "Provider slug: google or github", required = true)
            @PathVariable String provider,
            HttpServletResponse response);

    @GetMapping("/{provider}/callback")
    @Operation(operationId = "oauthCallback",
            summary = "Handle the OAuth provider callback",
            description = "Validates and consumes the state, exchanges the code server-to-server, requires a verified "
                    + "email, links/creates the local account, sets the httpOnly refresh cookie and 302-redirects to "
                    + "/oauth/complete. On any failure it redirects to /oauth/complete?error=<code> "
                    + "(provider_disabled | invalid_state | email_not_verified | provider_error | server_error).")
    @ApiResponse(responseCode = "302",
            description = "Redirect to /oauth/complete on success (with refresh cookie) or /oauth/complete?error=<code> on failure")
    ResponseEntity<Void> callback(
            @Parameter(description = "Provider slug: google or github", required = true)
            @PathVariable String provider,
            @Parameter(description = "Authorization code from the provider", required = true)
            @RequestParam String code,
            @Parameter(description = "One-time state issued at /authorize", required = true)
            @RequestParam String state,
            HttpServletResponse response);
}
