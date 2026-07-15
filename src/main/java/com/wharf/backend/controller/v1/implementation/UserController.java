package com.wharf.backend.controller.v1.implementation;

import com.wharf.backend.controller.v1.schema.UserApi;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.UserProfileResponse;
import com.wharf.backend.services.user.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController implements UserApi {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Override
    public ResponseEntity<UserProfileResponse> getCurrentUser(UserEntity user) {
        return ResponseEntity.ok(userService.getProfile(user));
    }
}
