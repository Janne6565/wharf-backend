package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "UpdatePublicKeyRequest", description = "Publish, rotate or upgrade the account's identity public key")
public record UpdatePublicKeyRequest(

        @Schema(description = "Base64-encoded identity public key: 32 bytes (v1, bare X25519) "
                + "or 1217 bytes (v2, hybrid post-quantum)")
        @NotBlank
        String publicKey,

        @Schema(description = "When true, replace an existing key and reset every wrapped key the account holds "
                + "(each affected project membership re-enters the awaiting-key state)")
        boolean rotate,

        @Schema(description = "When true, replace an existing key but KEEP every wrapped key. Only valid for a "
                + "v1 -> v2 hybrid upgrade, where the new key embeds the old X25519 key and the existing "
                + "sealed boxes therefore still open. The server cannot verify that — a client that sets this "
                + "with an unrelated key locks only itself out, recoverable with rotate. Ignored when rotate "
                + "is also set. Absent is false — older clients omit the field entirely.")
        Boolean upgrade
) {
}
