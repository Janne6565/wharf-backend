package com.wharf.backend.repository;

import com.wharf.backend.entity.OAuthIdentityEntity;
import com.wharf.backend.model.core.OAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OAuthIdentityRepository extends JpaRepository<OAuthIdentityEntity, UUID> {

    Optional<OAuthIdentityEntity> findByProviderAndSubject(OAuthProvider provider, String subject);

    boolean existsByUserIdAndProvider(UUID userId, OAuthProvider provider);
}
