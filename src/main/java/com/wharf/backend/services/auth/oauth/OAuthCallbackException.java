package com.wharf.backend.services.auth.oauth;

import com.wharf.backend.model.core.OAuthClient;
import lombok.Getter;

/**
 * A failure during the OAuth authorize/callback flow. Unlike the RFC 7807 domain
 * exceptions, this is never rendered as a JSON body: the controller catches it and issues a
 * browser/deep-link redirect carrying a machine-readable {@link OAuthErrorCode}, never
 * client-facing detail.
 *
 * <p>It also carries the {@link OAuthClient} so the controller can pick the redirect base
 * (web {@code /oauth/complete} vs mobile {@code wharf://oauth}). The client is only known
 * once the one-time state has been consumed; before that (e.g. an unknown state) it defaults
 * to {@link OAuthClient#WEB}.
 */
@Getter
public class OAuthCallbackException extends RuntimeException {

    private final OAuthErrorCode errorCode;
    private final OAuthClient client;

    public OAuthCallbackException(OAuthErrorCode errorCode) {
        this(errorCode, OAuthClient.WEB);
    }

    public OAuthCallbackException(OAuthErrorCode errorCode, OAuthClient client) {
        super(errorCode.getCode());
        this.errorCode = errorCode;
        this.client = client;
    }

    public OAuthCallbackException(OAuthErrorCode errorCode, Throwable cause) {
        this(errorCode, OAuthClient.WEB, cause);
    }

    public OAuthCallbackException(OAuthErrorCode errorCode, OAuthClient client, Throwable cause) {
        super(errorCode.getCode(), cause);
        this.errorCode = errorCode;
        this.client = client;
    }
}
