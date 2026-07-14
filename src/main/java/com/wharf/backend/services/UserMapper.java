package com.wharf.backend.services;

import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.UserDto;

/** Maps the user entity to its public DTO. Kept out of the entity to preserve SRP. */
public final class UserMapper {

    private UserMapper() {
    }

    public static UserDto toDto(UserEntity user) {
        return new UserDto(user.getId(), user.getEmail(), user.getCreatedAt());
    }
}
