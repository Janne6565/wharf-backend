package com.wharf.backend.controller.v1.schema;

import com.wharf.backend.configuration.OpenApiConfig;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.DeviceCodeExchangeRequest;
import com.wharf.backend.model.core.DeviceCodeResponse;
import com.wharf.backend.model.core.SessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/v1/device-codes")
@Tag(name = "Device Codes", description = "Pair the terminal client with an account")
public interface DeviceCodeApi {

    @PostMapping
    @Operation(operationId = "issueDeviceCode",
            summary = "Issue a pairing code for the calling account",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Code issued")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    ResponseEntity<DeviceCodeResponse> issue(@AuthenticationPrincipal UserEntity user);

    @PostMapping("/exchange")
    @Operation(operationId = "exchangeDeviceCode",
            summary = "Exchange a pairing code for a session (used by the TUI)")
    @ApiResponse(responseCode = "200", description = "Session issued")
    @ApiResponse(responseCode = "404", description = "Unknown code")
    @ApiResponse(responseCode = "410", description = "Code expired or already used")
    ResponseEntity<SessionResponse> exchange(@Valid @RequestBody DeviceCodeExchangeRequest request);
}
