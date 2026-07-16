package com.wharf.backend.controller.v1.schema;

import com.wharf.backend.configuration.OpenApiConfig;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.UpdatePublicKeyRequest;
import com.wharf.backend.model.core.MyInviteResponse;
import com.wharf.backend.model.core.ProjectSummaryResponse;
import com.wharf.backend.model.core.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.UUID;

@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Account profile, public key and incoming project invites")
public interface UserApi {

    @GetMapping("/me")
    @Operation(operationId = "getCurrentUser",
            summary = "Get the authenticated account's profile",
            description = "Includes hasPassword / hasRecovery / hasVault flags and the account's public key.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Profile returned")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    ResponseEntity<UserProfileResponse> getCurrentUser(@AuthenticationPrincipal UserEntity user);

    @PutMapping("/me/public-key")
    @Operation(operationId = "updatePublicKey",
            summary = "Publish or rotate the account's X25519 public key",
            description = "Rotating (rotate=true) replaces the key and resets every wrapped key the account "
                    + "holds — each affected project membership re-enters the awaiting-key state.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "204", description = "Public key stored")
    @ApiResponse(responseCode = "400", description = "Key is not 32 bytes")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "409", description = "A key already exists and rotate was not set")
    ResponseEntity<Void> updatePublicKey(@AuthenticationPrincipal UserEntity user,
                                         @Valid @RequestBody UpdatePublicKeyRequest request);

    @GetMapping("/me/invites")
    @Operation(operationId = "getMyInvites",
            summary = "List unexpired project invites addressed to the caller",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Invites returned")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    ResponseEntity<List<MyInviteResponse>> getMyInvites(@AuthenticationPrincipal UserEntity user);

    @PostMapping("/me/invites/{id}/accept")
    @Operation(operationId = "acceptInvite",
            summary = "Accept a project invite, joining as an awaiting-key member",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Joined; returns the project summary")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "404", description = "Invite not found or not addressed to the caller")
    @ApiResponse(responseCode = "410", description = "Invite expired")
    ResponseEntity<ProjectSummaryResponse> acceptInvite(@AuthenticationPrincipal UserEntity user,
                                                        @PathVariable UUID id);

    @PostMapping("/me/invites/{id}/decline")
    @Operation(operationId = "declineInvite",
            summary = "Decline a project invite",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "204", description = "Invite declined")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "404", description = "Invite not found or not addressed to the caller")
    ResponseEntity<Void> declineInvite(@AuthenticationPrincipal UserEntity user, @PathVariable UUID id);
}
