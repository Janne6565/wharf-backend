package com.wharf.backend.services.project;

import com.wharf.backend.model.exception.InvalidPublicKeyException;
import com.wharf.backend.model.exception.InvalidWrappedKeyException;

import java.util.Base64;

/**
 * Length-checked base64 decoding of the two crypto values the server stores for projects:
 * a member's identity public key and a project DEK wrapped to it. The server only ever
 * validates the <em>length</em> — it never inspects or uses the key material, keeping the
 * model zero-knowledge.
 *
 * <p>Each value has two accepted lengths, one per client crypto version. v1 is classical
 * (a bare X25519 key, a bare sealed box); v2 is the hybrid post-quantum form, where the
 * client prefixes a version byte and adds an ML-KEM-768 layer. Both are accepted for as
 * long as un-upgraded clients exist, and the server cannot tell them apart beyond the
 * length — which is the point.
 */
public final class ProjectCrypto {

    /** v1 public key: a bare X25519 key. */
    public static final int PUBLIC_KEY_LENGTH = 32;

    /** v2 public key: version byte + X25519 key (32) + ML-KEM-768 encapsulation key (1184). */
    public static final int PUBLIC_KEY_LENGTH_V2 = 1 + 32 + 1184;

    /** v1 wrapped DEK: an X25519 sealed box (32-byte ephemeral pk + 32-byte DEK + 16-byte tag). */
    public static final int WRAPPED_DEK_LENGTH = 80;

    /**
     * v2 wrapped DEK: version byte + ML-KEM-768 ciphertext (1088) + XChaCha20 nonce (24)
     * + the v1 sealed box sealed again under the encapsulated key (80 + 16 tag).
     */
    public static final int WRAPPED_DEK_LENGTH_V2 = 1 + 1088 + 24 + 80 + 16;

    private ProjectCrypto() {
    }

    public static byte[] decodePublicKey(String base64) {
        return decodeOneOf(base64, PUBLIC_KEY_LENGTH, PUBLIC_KEY_LENGTH_V2,
                new InvalidPublicKeyException(PUBLIC_KEY_LENGTH, PUBLIC_KEY_LENGTH_V2));
    }

    public static byte[] decodeWrappedDek(String base64) {
        return decodeOneOf(base64, WRAPPED_DEK_LENGTH, WRAPPED_DEK_LENGTH_V2,
                new InvalidWrappedKeyException(WRAPPED_DEK_LENGTH, WRAPPED_DEK_LENGTH_V2));
    }

    private static byte[] decodeOneOf(String base64, int lengthV1, int lengthV2, RuntimeException onInvalid) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            throw onInvalid;
        }
        if (decoded.length != lengthV1 && decoded.length != lengthV2) {
            throw onInvalid;
        }
        return decoded;
    }

    public static String encode(byte[] bytes) {
        return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
    }
}
