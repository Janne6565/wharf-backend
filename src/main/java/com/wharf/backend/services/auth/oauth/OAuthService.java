package com.wharf.backend.services.auth.oauth;

import com.wharf.backend.configuration.OAuthProperties;
import com.wharf.backend.entity.OAuthStateEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.OAuthClient;
import com.wharf.backend.model.core.OAuthProvider;
import com.wharf.backend.security.JwtService;
import com.wharf.backend.services.devicecode.DeviceCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates OAuth social login: it builds the provider consent URL, and on the callback
 * it validates the one-time state, drives the provider-specific code exchange, enforces
 * that the email is verified, resolves the local account, and mints the credential the
 * initiating client needs — a refresh token for the web SPA, or a one-time device code for
 * the mobile app. OAuth is only the authentication step — the app's self-issued JWT is
 * still the session.
 */
@Service
public class OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);

    private final OAuthProperties properties;
    private final OAuthStateStore stateStore;
    private final OAuthUserService userService;
    private final JwtService jwtService;
    private final DeviceCodeService deviceCodeService;
    private final Map<OAuthProvider, OAuthProviderClient> clients;

    public OAuthService(OAuthProperties properties,
                        OAuthStateStore stateStore,
                        OAuthUserService userService,
                        JwtService jwtService,
                        DeviceCodeService deviceCodeService,
                        List<OAuthProviderClient> providerClients) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.userService = userService;
        this.jwtService = jwtService;
        this.deviceCodeService = deviceCodeService;
        this.clients = providerClients.stream()
                .collect(Collectors.toMap(OAuthProviderClient::provider, Function.identity()));
    }

    /** Slugs of providers that are configured (client id + secret present), for the picker. */
    public List<String> enabledProviders() {
        return java.util.Arrays.stream(OAuthProvider.values())
                .filter(properties::isEnabled)
                .map(OAuthProvider::getSlug)
                .toList();
    }

    /**
     * Build the provider consent URL for a slug, minting a fresh one-time state that records
     * the initiating {@code client} so the callback knows how to hand the session back.
     * @throws OAuthCallbackException PROVIDER_DISABLED if the slug is unknown or unconfigured
     */
    public String buildAuthorizationUrl(String slug, OAuthClient client) {
        OAuthProvider provider = requireEnabled(slug);
        OAuthProperties.Credentials credentials = properties.credentialsFor(provider);
        String state = stateStore.issue(client);
        return provider.getAuthorizationUri()
                + "?client_id=" + encode(credentials.clientId())
                + "&redirect_uri=" + encode(properties.callbackUri(provider))
                + "&response_type=code"
                + "&scope=" + encode(provider.getScope())
                + "&state=" + encode(state);
    }

    /**
     * Complete the callback: validate+consume the state, exchange the code, require a
     * verified email, resolve/create the account, and mint the credential the initiating
     * client needs — a refresh token (web) or a one-time device code (mobile).
     *
     * <p>The initiating client is learned from the consumed state row; before that point the
     * client is unknowable, so an unknown/expired state ({@code invalid_state}) always
     * reports the {@link OAuthClient#WEB} default. Every failure past state consumption is
     * tagged with the real client so the controller can pick the right redirect base.
     * @throws OAuthCallbackException with the appropriate {@link OAuthErrorCode} on failure
     */
    public OAuthLoginResult handleCallback(String slug, String code, String state) {
        OAuthProvider provider = requireEnabled(slug);
        OAuthStateEntity stateEntity = stateStore.consume(state)
                .orElseThrow(() -> new OAuthCallbackException(OAuthErrorCode.INVALID_STATE));
        OAuthClient client = stateEntity.getClient();

        try {
            OAuthUserIdentity identity = clients.get(provider)
                    .authenticate(code, properties.callbackUri(provider), properties.credentialsFor(provider));

            if (!identity.emailVerified()) {
                log.warn("Rejecting {} login: email not verified", provider);
                throw new OAuthCallbackException(OAuthErrorCode.EMAIL_NOT_VERIFIED, client);
            }

            UserEntity user = userService.findOrCreateAndLink(provider, identity);
            log.debug("Completed {} {} login for account {}", client, provider, user.getId());
            return buildResult(client, user);
        } catch (OAuthCallbackException ex) {
            // Re-tag failures thrown without knowledge of the client (e.g. PROVIDER_ERROR
            // from the provider client) with the now-known client.
            throw ex.getClient() == client ? ex : new OAuthCallbackException(ex.getErrorCode(), client, ex);
        } catch (RuntimeException ex) {
            throw new OAuthCallbackException(OAuthErrorCode.SERVER_ERROR, client, ex);
        }
    }

    private OAuthLoginResult buildResult(OAuthClient client, UserEntity user) {
        return switch (client) {
            case WEB -> new OAuthLoginResult.Web(user, jwtService.issueRefreshToken(user));
            // Mobile cannot use cookies: hand back a one-time device code instead of a
            // refresh token (which would be an orphaned live credential), exchanged at
            // /device-codes/exchange for a DIRECT session.
            case MOBILE -> new OAuthLoginResult.Mobile(user, deviceCodeService.issue(user.getId()).code());
        };
    }

    private OAuthProvider requireEnabled(String slug) {
        OAuthProvider provider = OAuthProvider.fromSlug(slug)
                .orElseThrow(() -> new OAuthCallbackException(OAuthErrorCode.PROVIDER_DISABLED));
        if (!properties.isEnabled(provider) || !clients.containsKey(provider)) {
            throw new OAuthCallbackException(OAuthErrorCode.PROVIDER_DISABLED);
        }
        return provider;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
