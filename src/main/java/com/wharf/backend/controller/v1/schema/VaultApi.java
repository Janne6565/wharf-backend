package com.wharf.backend.controller.v1.schema;

import com.wharf.backend.configuration.OpenApiConfig;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.UpdateVaultRequest;
import com.wharf.backend.model.core.VaultResponse;
import com.wharf.backend.model.core.VaultUpdateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/v1/vault")
@Tag(name = "Vault", description = "Encrypted vault blob storage (server stores ciphertext only)")
public interface VaultApi {

    @GetMapping
    @Operation(operationId = "getVault",
            summary = "Fetch the stored vault blob and its version",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Vault returned")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "404", description = "No vault for this account")
    ResponseEntity<VaultResponse> getVault(@AuthenticationPrincipal UserEntity user);

    @PutMapping
    @Operation(operationId = "updateVault",
            summary = "Replace the vault blob with optimistic concurrency",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Vault updated")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "409", description = "Version conflict")
    @ApiResponse(responseCode = "413", description = "Vault blob too large")
    ResponseEntity<VaultUpdateResponse> updateVault(@AuthenticationPrincipal UserEntity user,
                                                    @Valid @RequestBody UpdateVaultRequest request);
}
