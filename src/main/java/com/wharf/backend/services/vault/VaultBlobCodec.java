package com.wharf.backend.services.vault;

import com.wharf.backend.configuration.VaultProperties;
import com.wharf.backend.model.exception.InvalidVaultPayloadException;
import com.wharf.backend.model.exception.VaultTooLargeException;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Base64-(de)codes and size-validates an opaque ciphertext vault blob. Shared by the
 * personal {@link VaultService} and the project vault so both enforce the exact same
 * pre-decode size ceiling and emptiness/format rules — the server never parses the blob
 * itself, it only bounds it.
 */
@Component
public class VaultBlobCodec {

    /** Slack over the exact base64 length to tolerate padding characters. */
    private static final int BASE64_PADDING_SLACK = 4;

    private final VaultProperties vaultProperties;

    public VaultBlobCodec(VaultProperties vaultProperties) {
        this.vaultProperties = vaultProperties;
    }

    /**
     * Decodes a base64 blob after rejecting anything that could exceed the byte ceiling,
     * so an oversized payload never gets expanded into a {@code byte[]} in memory.
     */
    public byte[] decodeAndValidate(String base64Blob) {
        // Reject an oversized payload from its encoded length *before* decoding. base64
        // encodes n bytes as 4 * ceil(n / 3) characters, so anything longer than that (plus
        // padding slack) cannot fit within the byte ceiling.
        long maxBase64Length = (long) Math.ceil(vaultProperties.maxSizeBytes() / 3.0) * 4 + BASE64_PADDING_SLACK;
        if (base64Blob.length() > maxBase64Length) {
            throw new VaultTooLargeException(vaultProperties.maxSizeBytes());
        }

        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(base64Blob);
        } catch (IllegalArgumentException ex) {
            throw new InvalidVaultPayloadException("Vault blob is not valid base64");
        }
        if (blob.length == 0) {
            throw new InvalidVaultPayloadException("Vault blob must not be empty");
        }
        if (blob.length > vaultProperties.maxSizeBytes()) {
            throw new VaultTooLargeException(vaultProperties.maxSizeBytes());
        }
        return blob;
    }

    public String encode(byte[] blob) {
        return Base64.getEncoder().encodeToString(blob);
    }
}
