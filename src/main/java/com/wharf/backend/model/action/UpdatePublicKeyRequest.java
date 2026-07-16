package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "UpdatePublicKeyRequest", description = "Publish or rotate the account's X25519 public key")
public record UpdatePublicKeyRequest(

        @Schema(description = "Base64-encoded X25519 public key (exactly 32 bytes)")
        @NotBlank
        String publicKey,

        @Schema(description = "When true, replace an existing key and reset every wrapped key the account holds "
                + "(each affected project membership re-enters the awaiting-key state)")
        boolean rotate
) {
}
