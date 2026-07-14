package com.wharf.backend.controller.v1.implementation;

import com.wharf.backend.controller.v1.schema.VaultApi;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.UpdateVaultRequest;
import com.wharf.backend.model.core.VaultResponse;
import com.wharf.backend.model.core.VaultUpdateResponse;
import com.wharf.backend.services.vault.VaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VaultController implements VaultApi {

    private final VaultService vaultService;

    public VaultController(VaultService vaultService) {
        this.vaultService = vaultService;
    }

    @Override
    public ResponseEntity<VaultResponse> getVault(UserEntity user) {
        return ResponseEntity.ok(vaultService.getVault(user.getId()));
    }

    @Override
    public ResponseEntity<VaultUpdateResponse> updateVault(UserEntity user, UpdateVaultRequest request) {
        return ResponseEntity.ok(vaultService.updateVault(user.getId(), request));
    }
}
