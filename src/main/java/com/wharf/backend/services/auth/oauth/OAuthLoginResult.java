package com.wharf.backend.services.auth.oauth;

import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.OAuthClient;

/**
 * The outcome of a completed OAuth callback: the resolved account plus the one credential
 * appropriate to the client that started the flow. The service decides which variant to
 * mint so the controller branches purely on shape, doing no business logic of its own.
 *
 * <ul>
 *   <li>{@link Web} carries a freshly minted refresh token (set as an httpOnly cookie).</li>
 *   <li>{@link Mobile} carries a one-time device code (handed back over a {@code wharf://}
 *       deep link and exchanged at {@code /device-codes/exchange} for a DIRECT session).
 *       No refresh token is minted here — that would be an orphaned live credential.</li>
 * </ul>
 */
public sealed interface OAuthLoginResult permits OAuthLoginResult.Web, OAuthLoginResult.Mobile {

    UserEntity user();

    OAuthClient client();

    /** Browser SPA login: the refresh token is delivered as an httpOnly cookie. */
    record Web(UserEntity user, String refreshToken) implements OAuthLoginResult {
        @Override
        public OAuthClient client() {
            return OAuthClient.WEB;
        }
    }

    /** Mobile app login: a one-time device code is delivered via the {@code wharf://} deep link. */
    record Mobile(UserEntity user, String deviceCode) implements OAuthLoginResult {
        @Override
        public OAuthClient client() {
            return OAuthClient.MOBILE;
        }
    }
}
