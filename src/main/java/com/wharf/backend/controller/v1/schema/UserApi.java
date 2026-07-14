package com.wharf.backend.controller.v1.schema;

import com.wharf.backend.configuration.OpenApiConfig;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.UserDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Account profile")
public interface UserApi {

    @GetMapping("/me")
    @Operation(operationId = "getCurrentUser",
            summary = "Get the authenticated account's profile",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Profile returned")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    ResponseEntity<UserDto> getCurrentUser(@AuthenticationPrincipal UserEntity user);
}
