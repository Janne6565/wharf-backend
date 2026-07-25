package com.wharf.backend.controller.v1.implementation;

import com.wharf.backend.controller.v1.schema.UserApi;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.DeleteAccountRequest;
import com.wharf.backend.model.action.UpdatePublicKeyRequest;
import com.wharf.backend.model.core.AccountDeletionPreviewResponse;
import com.wharf.backend.model.core.MyInviteResponse;
import com.wharf.backend.model.core.ProjectSummaryResponse;
import com.wharf.backend.model.core.UserProfileResponse;
import com.wharf.backend.services.project.ProjectInviteService;
import com.wharf.backend.services.user.AccountDeletionService;
import com.wharf.backend.services.user.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class UserController implements UserApi {

    private final UserService userService;
    private final AccountDeletionService accountDeletionService;
    private final ProjectInviteService projectInviteService;

    public UserController(UserService userService,
                          AccountDeletionService accountDeletionService,
                          ProjectInviteService projectInviteService) {
        this.userService = userService;
        this.accountDeletionService = accountDeletionService;
        this.projectInviteService = projectInviteService;
    }

    @Override
    public ResponseEntity<UserProfileResponse> getCurrentUser(UserEntity user) {
        return ResponseEntity.ok(userService.getProfile(user));
    }

    @Override
    public ResponseEntity<Void> deleteAccount(UserEntity user, DeleteAccountRequest request) {
        // The account to delete comes from the authenticated principal, never from the body —
        // there is no way to aim this endpoint at anyone else.
        accountDeletionService.deleteAccount(user.getId(), request);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AccountDeletionPreviewResponse> getAccountDeletionPreview(UserEntity user) {
        return ResponseEntity.ok(accountDeletionService.previewDeletion(user.getId()));
    }

    @Override
    public ResponseEntity<Void> updatePublicKey(UserEntity user, UpdatePublicKeyRequest request) {
        userService.updatePublicKey(user.getId(), request);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<MyInviteResponse>> getMyInvites(UserEntity user) {
        return ResponseEntity.ok(projectInviteService.listMyInvites(user));
    }

    @Override
    public ResponseEntity<ProjectSummaryResponse> acceptInvite(UserEntity user, UUID id) {
        return ResponseEntity.ok(projectInviteService.accept(user, id));
    }

    @Override
    public ResponseEntity<Void> declineInvite(UserEntity user, UUID id) {
        projectInviteService.decline(user, id);
        return ResponseEntity.noContent().build();
    }
}
