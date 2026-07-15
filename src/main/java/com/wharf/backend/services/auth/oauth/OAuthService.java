package com.wharf.backend.services.auth.oauth;

import com.wharf.backend.configuration.OAuthProperties;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.OAuthProvider;
import com.wharf.backend.security.JwtService;
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
 * that the email is verified, resolves the local account, and mints the app's own refresh
 * token. OAuth is only the authentication step — the app's self-issued JWT is still the
 * session.
 */
@Service
public class OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);

    private final OAuthProperties properties;
    private final OAuthStateStore stateStore;
    private final OAuthUserService userService;
    private final JwtService jwtService;
    private final Map<OAuthProvider, OAuthProviderClient> clients;

    public OAuthService(OAuthProperties properties,
                        OAuthStateStore stateStore,
                        OAuthUserService userService,
                        JwtService jwtService,
                        List<OAuthProviderClient> providerClients) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.userService = userService;
        this.jwtService = jwtService;
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
     * Build the provider consent URL for a slug, minting a fresh one-time state.
     * @throws OAuthCallbackException PROVIDER_DISABLED if the slug is unknown or unconfigured
     */
    public String buildAuthorizationUrl(String slug) {
        OAuthProvider provider = requireEnabled(slug);
        OAuthProperties.Credentials credentials = properties.credentialsFor(provider);
        String state = stateStore.issue();
        return provider.getAuthorizationUri()
                + "?client_id=" + encode(credentials.clientId())
                + "&redirect_uri=" + encode(properties.callbackUri(provider))
                + "&response_type=code"
                + "&scope=" + encode(provider.getScope())
                + "&state=" + encode(state);
    }

    /**
     * Complete the callback: validate+consume the state, exchange the code, require a
     * verified email, resolve/create the account, and issue a refresh token.
     * @throws OAuthCallbackException with the appropriate {@link OAuthErrorCode} on failure
     */
    public OAuthLoginResult handleCallback(String slug, String code, String state) {
        OAuthProvider provider = requireEnabled(slug);
        stateStore.consume(state)
                .orElseThrow(() -> new OAuthCallbackException(OAuthErrorCode.INVALID_STATE));

        OAuthUserIdentity identity = clients.get(provider)
                .authenticate(code, properties.callbackUri(provider), properties.credentialsFor(provider));

        if (!identity.emailVerified()) {
            log.warn("Rejecting {} login: email not verified", provider);
            throw new OAuthCallbackException(OAuthErrorCode.EMAIL_NOT_VERIFIED);
        }

        UserEntity user = userService.findOrCreateAndLink(provider, identity);
        String refreshToken = jwtService.issueRefreshToken(user);
        log.debug("Completed {} login for account {}", provider, user.getId());
        return new OAuthLoginResult(user, refreshToken);
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

    /** The account resolved by an OAuth login plus its freshly issued refresh token. */
    public record OAuthLoginResult(UserEntity user, String refreshToken) {
    }
}
