package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Re-authentication for self-service account deletion. A valid access token alone is not
 * enough to destroy an account — a stolen or forgotten-open session must not be able to do
 * it — so an account that has a master password must prove knowledge of it here.
 *
 * <p>{@code authKey} is therefore required whenever the account has a password, and ignored
 * (may be omitted entirely, along with the whole body) for an OAuth-only account, which has
 * no second factor to offer.
 */
@Schema(name = "DeleteAccountRequest",
        description = "Re-authentication for irreversible account deletion. Required for accounts that have a "
                + "master password; may be omitted by OAuth-only accounts, which have no password to prove.")
public record DeleteAccountRequest(

        @Schema(description = "The current base64 authentication key. Required if the account has a password; "
                + "null/absent is only accepted for an OAuth-only account.")
        String authKey
) {

    /** True when the caller actually supplied something to verify against. */
    public boolean hasAuthKey() {
        return authKey != null && !authKey.isBlank();
    }
}
