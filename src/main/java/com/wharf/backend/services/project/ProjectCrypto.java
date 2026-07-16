package com.wharf.backend.services.project;

import com.wharf.backend.model.exception.InvalidPublicKeyException;
import com.wharf.backend.model.exception.InvalidWrappedKeyException;

import java.util.Base64;

/**
 * Length-checked base64 decoding of the two fixed-size crypto values the server stores for
 * projects: a member's X25519 public key and a project DEK sealed to it. The server only
 * ever validates the <em>length</em> — it never inspects or uses the key material, keeping
 * the model zero-knowledge.
 */
public final class ProjectCrypto {

    /** X25519 public key length. */
    public static final int PUBLIC_KEY_LENGTH = 32;

    /** Length of a DEK wrapped as an X25519 sealed box (32-byte ephemeral pk + 16-byte tag + 32-byte DEK). */
    public static final int WRAPPED_DEK_LENGTH = 80;

    private ProjectCrypto() {
    }

    public static byte[] decodePublicKey(String base64) {
        return decodeExact(base64, PUBLIC_KEY_LENGTH, new InvalidPublicKeyException(PUBLIC_KEY_LENGTH));
    }

    public static byte[] decodeWrappedDek(String base64) {
        return decodeExact(base64, WRAPPED_DEK_LENGTH, new InvalidWrappedKeyException(WRAPPED_DEK_LENGTH));
    }

    private static byte[] decodeExact(String base64, int expectedLength, RuntimeException onInvalid) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            throw onInvalid;
        }
        if (decoded.length != expectedLength) {
            throw onInvalid;
        }
        return decoded;
    }

    public static String encode(byte[] bytes) {
        return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
    }
}
