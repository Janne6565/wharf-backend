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

    /**
     * Whether the account has proven control of {@link #email} with a one-time code.
     * No token is ever issued for an unverified account — an unproven address would be
     * enough to claim a project invite addressed to it.
     */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

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

    /**
     * Per-notification opt-outs for <em>collaboration</em> mail, all defaulting to on.
     *
     * <p>The security notices have deliberately no field here. There must be no value in the
     * schema capable of switching them off: with no password reset, no support-side recovery
     * and no server-side activity log, email is the only channel through which an account
     * holder can learn that someone else took control of their account.
     *
     * <p>Read and written through {@link com.wharf.backend.model.core.NotificationPreference},
     * which owns the mapping from the stable API/column key to these accessors — so the seven
     * flags are enumerated in exactly one place.
     */
    @Builder.Default
    @Column(name = "notify_project_invite", nullable = false)
    private boolean notifyProjectInvite = true;

    @Builder.Default
    @Column(name = "notify_invite_accepted", nullable = false)
    private boolean notifyInviteAccepted = true;

    @Builder.Default
    @Column(name = "notify_invite_declined", nullable = false)
    private boolean notifyInviteDeclined = true;

    @Builder.Default
    @Column(name = "notify_project_access_granted", nullable = false)
    private boolean notifyProjectAccessGranted = true;

    @Builder.Default
    @Column(name = "notify_project_access_removed", nullable = false)
    private boolean notifyProjectAccessRemoved = true;

    @Builder.Default
    @Column(name = "notify_role_changed", nullable = false)
    private boolean notifyRoleChanged = true;

    @Builder.Default
    @Column(name = "notify_project_deleted", nullable = false)
    private boolean notifyProjectDeleted = true;

    /** JPA optimistic-lock version — guards against lost updates on concurrent account writes. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
