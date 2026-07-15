package com.wharf.backend.entity;

import com.wharf.backend.model.core.OAuthProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An external OAuth identity linked to a local account. The pair
 * {@code (provider, subject)} is unique — a given provider account maps to at most one
 * local user — while one user may link several providers. {@code emailAtLink} records the
 * verified email seen when the link was created (audit only; the account's own email is
 * authoritative).
 */
@Entity
@Table(name = "oauth_identities",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_oauth_identities_provider_subject",
                columnNames = {"provider", "subject"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OAuthIdentityEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 16)
    private OAuthProvider provider;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @Column(name = "email_at_link", nullable = false)
    private String emailAtLink;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
