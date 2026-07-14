package com.wharf.backend.controller.v1.implementation;

import com.wharf.backend.controller.v1.schema.DeviceCodeApi;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.DeviceCodeExchangeRequest;
import com.wharf.backend.model.core.DeviceCodeResponse;
import com.wharf.backend.model.core.SessionResponse;
import com.wharf.backend.services.devicecode.DeviceCodeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeviceCodeController implements DeviceCodeApi {

    private final DeviceCodeService deviceCodeService;

    public DeviceCodeController(DeviceCodeService deviceCodeService) {
        this.deviceCodeService = deviceCodeService;
    }

    @Override
    public ResponseEntity<DeviceCodeResponse> issue(UserEntity user) {
        return ResponseEntity.ok(deviceCodeService.issue(user.getId()));
    }

    @Override
    public ResponseEntity<SessionResponse> exchange(DeviceCodeExchangeRequest request) {
        return ResponseEntity.ok(deviceCodeService.exchange(request));
    }
}
