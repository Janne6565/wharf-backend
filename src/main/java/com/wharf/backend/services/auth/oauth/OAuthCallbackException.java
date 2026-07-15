package com.wharf.backend.services.auth.oauth;

import lombok.Getter;

/**
 * A failure during the OAuth authorize/callback flow. Unlike the RFC 7807 domain
 * exceptions, this is never rendered as a JSON body: the controller catches it and issues
 * a browser redirect to {@code /oauth/complete?error=<code>}. It therefore carries only a
 * machine-readable {@link OAuthErrorCode}, never client-facing detail.
 */
@Getter
public class OAuthCallbackException extends RuntimeException {

    private final OAuthErrorCode errorCode;

    public OAuthCallbackException(OAuthErrorCode errorCode) {
        super(errorCode.getCode());
        this.errorCode = errorCode;
    }

    public OAuthCallbackException(OAuthErrorCode errorCode, Throwable cause) {
        super(errorCode.getCode(), cause);
        this.errorCode = errorCode;
    }
}
