package com.wharf.backend.controller.v1.implementation;

import com.wharf.backend.configuration.OAuthProperties;
import com.wharf.backend.controller.v1.schema.OAuthApi;
import com.wharf.backend.model.core.OAuthClient;
import com.wharf.backend.model.core.OAuthProvidersResponse;
import com.wharf.backend.security.RefreshCookieFactory;
import com.wharf.backend.services.auth.oauth.OAuthCallbackException;
import com.wharf.backend.services.auth.oauth.OAuthErrorCode;
import com.wharf.backend.services.auth.oauth.OAuthLoginResult;
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
 * OAuth endpoints. The client is redirected throughout, so failures never render a JSON
 * body: they 302 to a machine-readable error target. A web client is redirected to a fixed
 * relative frontend path ({@code /oauth/complete}); a mobile client is redirected to the
 * server-controlled {@code wharf://oauth} deep link. Redirect targets are always fixed and
 * server-controlled (never caller-supplied), so there is no open-redirect surface.
 */
@RestController
public class OAuthController implements OAuthApi {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    /** Fixed relative path the SPA handles after a completed (or failed) OAuth login. */
    private static final String COMPLETE_PATH = "/oauth/complete";

    private final OAuthService oAuthService;
    private final RefreshCookieFactory refreshCookieFactory;
    private final OAuthProperties properties;

    public OAuthController(OAuthService oAuthService,
                           RefreshCookieFactory refreshCookieFactory,
                           OAuthProperties properties) {
        this.oAuthService = oAuthService;
        this.refreshCookieFactory = refreshCookieFactory;
        this.properties = properties;
    }

    @Override
    public ResponseEntity<OAuthProvidersResponse> listProviders() {
        return ResponseEntity.ok(new OAuthProvidersResponse(oAuthService.enabledProviders()));
    }

    @Override
    public ResponseEntity<Void> authorize(String provider, String client, HttpServletResponse response) {
        // The client is a caller-supplied query param here, so it is known even when the
        // provider is disabled — a mobile authorize error still goes to the deep link.
        OAuthClient clientType = OAuthClient.fromParam(client);
        try {
            String url = oAuthService.buildAuthorizationUrl(provider, clientType);
            return redirect(url);
        } catch (OAuthCallbackException ex) {
            return redirectError(ex.getErrorCode(), clientType);
        }
    }

    @Override
    public ResponseEntity<Void> callback(String provider, String code, String state,
                                         HttpServletResponse response) {
        try {
            OAuthLoginResult result = oAuthService.handleCallback(provider, code, state);
            return switch (result) {
                // COOKIE mode: the refresh token is set httpOnly; the SPA silently refreshes
                // on /oauth/complete to obtain its (in-memory) access token.
                case OAuthLoginResult.Web web -> {
                    response.addHeader(HttpHeaders.SET_COOKIE,
                            refreshCookieFactory.create(web.refreshToken()).toString());
                    yield redirect(COMPLETE_PATH);
                }
                // Deep-link mode: no cookie — the app exchanges the one-time device code at
                // /device-codes/exchange for a DIRECT session.
                case OAuthLoginResult.Mobile mobile ->
                        redirect(properties.mobileRedirectUri() + "?code=" + mobile.deviceCode());
            };
        } catch (OAuthCallbackException ex) {
            log.warn("OAuth callback for provider {} failed: {}", provider, ex.getErrorCode().getCode());
            return redirectError(ex.getErrorCode(), ex.getClient());
        } catch (RuntimeException ex) {
            log.error("Unexpected OAuth callback failure for provider {}", provider, ex);
            return redirectError(OAuthErrorCode.SERVER_ERROR, OAuthClient.WEB);
        }
    }

    /** Pick the error redirect target for the initiating client. */
    private ResponseEntity<Void> redirectError(OAuthErrorCode error, OAuthClient client) {
        String base = client == OAuthClient.MOBILE ? properties.mobileRedirectUri() : COMPLETE_PATH;
        return redirect(base + "?error=" + error.getCode());
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }
}
