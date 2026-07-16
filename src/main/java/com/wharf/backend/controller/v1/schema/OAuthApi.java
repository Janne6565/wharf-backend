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
 *
 * <p>Both the browser SPA and the mobile app use the same flow; the {@code client} query
 * parameter on {@code /authorize} decides how the callback hands the session back — a web
 * client gets an httpOnly refresh cookie and a redirect to {@code /oauth/complete}, a mobile
 * client (which cannot use cookies) gets a one-time device code over a {@code wharf://oauth}
 * deep link, which it then exchanges at {@code /device-codes/exchange} for a DIRECT session.
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
            description = "302-redirects to the provider's consent page with a one-time state that records the "
                    + "initiating client. Pass client=mobile to hand the session back over the wharf://oauth deep "
                    + "link (default web). A disabled/unknown provider redirects to the matching error target: "
                    + "/oauth/complete?error=provider_disabled for web, wharf://oauth?error=provider_disabled for mobile.")
    @ApiResponse(responseCode = "302", description = "Redirect to the provider consent page (or to the error target on error)")
    ResponseEntity<Void> authorize(
            @Parameter(description = "Provider slug: google or github", required = true)
            @PathVariable String provider,
            @Parameter(description = "Initiating client: web (default) or mobile. mobile hands the session back "
                    + "over the wharf://oauth deep link instead of a refresh cookie.")
            @RequestParam(defaultValue = "web") String client,
            HttpServletResponse response);

    @GetMapping("/{provider}/callback")
    @Operation(operationId = "oauthCallback",
            summary = "Handle the OAuth provider callback",
            description = "Validates and consumes the state (which carries the initiating client), exchanges the code "
                    + "server-to-server, requires a verified email and links/creates the local account. For a web "
                    + "flow it sets the httpOnly refresh cookie and 302-redirects to /oauth/complete; for a mobile "
                    + "flow it issues a one-time device code and 302-redirects to wharf://oauth?code=<code> (no "
                    + "cookie). On failure it redirects to the client's error target with ?error=<code> "
                    + "(provider_disabled | email_not_verified | provider_error | server_error); invalid_state, whose "
                    + "client is unknowable, always uses the web target /oauth/complete?error=invalid_state.")
    @ApiResponse(responseCode = "302",
            description = "Web: /oauth/complete (+ refresh cookie) or /oauth/complete?error=<code>. "
                    + "Mobile: wharf://oauth?code=<code> or wharf://oauth?error=<code>.")
    ResponseEntity<Void> callback(
            @Parameter(description = "Provider slug: google or github", required = true)
            @PathVariable String provider,
            @Parameter(description = "Authorization code from the provider", required = true)
            @RequestParam String code,
            @Parameter(description = "One-time state issued at /authorize", required = true)
            @RequestParam String state,
            HttpServletResponse response);
}
