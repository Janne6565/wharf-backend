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

    /**
     * bcrypt hash of the client-derived password auth key, or {@code null} for an
     * OAuth-only account that has not set a master password yet.
     */
    @Column(name = "auth_key_hash")
    private String authKeyHash;

    /**
     * bcrypt hash of the client-derived recovery auth key, or {@code null} until the
     * account initialises recovery (registration sets it; OAuth accounts set it later).
     */
    @Column(name = "recovery_key_hash")
    private String recoveryKeyHash;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * The account's published X25519 public key (exactly 32 bytes), or {@code null} until
     * the user publishes one. Project DEKs are sealed against this key; the server only ever
     * stores and hands it out, never derives from it.
     */
    @Column(name = "public_key")
    private byte[] publicKey;

    @Column(name = "public_key_updated_at")
    private Instant publicKeyUpdatedAt;

    /** JPA optimistic-lock version — guards against lost updates on concurrent account writes. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
