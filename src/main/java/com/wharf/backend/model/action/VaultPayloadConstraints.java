package com.wharf.backend.model.action;

/**
 * Shared bound for the base64-encoded ciphertext vault fields. This is a coarse,
 * defence-in-depth cap rejecting an oversized request body at validation time (before it
 * is base64-decoded); the authoritative, configurable byte-size ceiling is
 * {@code vault.max-size-bytes}, enforced in {@code VaultService}.
 *
 * <p>Sized for a ~1&nbsp;MiB ciphertext blob: base64 inflates {@code n} bytes to
 * {@code 4 * ceil(n / 3)} characters, so 1&nbsp;MiB encodes to ~1.33&nbsp;M characters;
 * the value below leaves a little slack for padding.</p>
 */
public final class VaultPayloadConstraints {

    public static final int MAX_BASE64_LENGTH = 1_400_000;

    private VaultPayloadConstraints() {
    }
}
