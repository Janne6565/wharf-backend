package com.wharf.backend.services.auth.oauth;

import com.wharf.backend.configuration.OAuthProperties;
import com.wharf.backend.model.core.OAuthProvider;

/**
 * Provider-specific half of the flow: exchange an authorization code (server-to-server,
 * confidential client) and resolve the verified {@link OAuthUserIdentity}. Implementations
 * throw {@link OAuthCallbackException} with {@link OAuthErrorCode#PROVIDER_ERROR} on any
 * upstream failure.
 */
public interface OAuthProviderClient {

    OAuthProvider provider();

    /**
     * @param code        the authorization code returned to the callback
     * @param redirectUri the exact redirect_uri used at authorize time (must match)
     * @param credentials the confidential client id/secret for this provider
     */
    OAuthUserIdentity authenticate(String code, String redirectUri, OAuthProperties.Credentials credentials);
}
