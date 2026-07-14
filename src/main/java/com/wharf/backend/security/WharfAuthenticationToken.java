package com.wharf.backend.security;

import com.wharf.backend.entity.UserEntity;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.UUID;

/**
 * Authentication established by {@link JwtFilter}. The principal is the loaded
 * {@link UserEntity} so controllers can read it via {@code @AuthenticationPrincipal}.
 */
public class WharfAuthenticationToken extends AbstractAuthenticationToken {

    private final UUID userId;
    private final transient UserEntity user;

    public WharfAuthenticationToken(UserEntity user) {
        super(AuthorityUtils.NO_AUTHORITIES);
        this.userId = user.getId();
        this.user = user;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return user;
    }

    public UUID getUserId() {
        return userId;
    }
}
