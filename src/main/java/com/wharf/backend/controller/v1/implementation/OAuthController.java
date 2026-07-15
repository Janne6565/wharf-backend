package com.wharf.backend.controller.v1.implementation;

import com.wharf.backend.controller.v1.schema.OAuthApi;
import com.wharf.backend.model.core.OAuthProvidersResponse;
import com.wharf.backend.security.RefreshCookieFactory;
import com.wharf.backend.services.auth.oauth.OAuthCallbackException;
import com.wharf.backend.services.auth.oauth.OAuthErrorCode;
import com.wharf.backend.services.auth.oauth.OAuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * OAuth endpoints. The browser is redirected throughout, so failures never render a JSON
 * body: they 302 to a fixed relative frontend path with a machine-readable error code.
 * Redirect targets are always fixed, server-controlled relative paths (never anything
 * caller-supplied) so there is no open-redirect surface; the app is same-origin.
 */
@RestController
public class OAuthController implements OAuthApi {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    /** Fixed relative path the SPA handles after a completed (or failed) OAuth login. */
    private static final String COMPLETE_PATH = "/oauth/complete";

    private final OAuthService oAuthService;
    private final RefreshCookieFactory refreshCookieFactory;

    public OAuthController(OAuthService oAuthService, RefreshCookieFactory refreshCookieFactory) {
        this.oAuthService = oAuthService;
        this.refreshCookieFactory = refreshCookieFactory;
    }

    @Override
    public ResponseEntity<OAuthProvidersResponse> listProviders() {
        return ResponseEntity.ok(new OAuthProvidersResponse(oAuthService.enabledProviders()));
    }

    @Override
    public ResponseEntity<Void> authorize(String provider, HttpServletResponse response) {
        try {
            String url = oAuthService.buildAuthorizationUrl(provider);
            return redirect(url);
        } catch (OAuthCallbackException ex) {
            return redirectToComplete(ex.getErrorCode());
        }
    }

    @Override
    public ResponseEntity<Void> callback(String provider, String code, String state,
                                         HttpServletResponse response) {
        try {
            OAuthService.OAuthLoginResult result = oAuthService.handleCallback(provider, code, state);
            // COOKIE mode: the refresh token is set httpOnly; the SPA silently refreshes on
            // /oauth/complete to obtain its (in-memory) access token.
            response.addHeader(HttpHeaders.SET_COOKIE,
                    refreshCookieFactory.create(result.refreshToken()).toString());
            return redirect(COMPLETE_PATH);
        } catch (OAuthCallbackException ex) {
            log.warn("OAuth callback for provider {} failed: {}", provider, ex.getErrorCode().getCode());
            return redirectToComplete(ex.getErrorCode());
        } catch (RuntimeException ex) {
            log.error("Unexpected OAuth callback failure for provider {}", provider, ex);
            return redirectToComplete(OAuthErrorCode.SERVER_ERROR);
        }
    }

    private ResponseEntity<Void> redirectToComplete(OAuthErrorCode error) {
        return redirect(COMPLETE_PATH + "?error=" + error.getCode());
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }
}
