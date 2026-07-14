package com.wharf.backend.controller.v1.implementation;

import com.wharf.backend.controller.v1.schema.UserApi;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.UserDto;
import com.wharf.backend.services.UserMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController implements UserApi {

    @Override
    public ResponseEntity<UserDto> getCurrentUser(UserEntity user) {
        return ResponseEntity.ok(UserMapper.toDto(user));
    }
}
