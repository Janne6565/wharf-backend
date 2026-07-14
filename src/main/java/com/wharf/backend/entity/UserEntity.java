package com.wharf.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An account. Holds only bcrypt hashes of the client-derived auth and recovery keys,
 * never the keys themselves. {@code tokenVersion} is embedded in every issued JWT and
 * bumped on a recovery reset to revoke all outstanding sessions at once.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "auth_key_hash", nullable = false)
    private String authKeyHash;

    @Column(name = "recovery_key_hash", nullable = false)
    private String recoveryKeyHash;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA optimistic-lock version — guards against lost updates on concurrent account writes. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
