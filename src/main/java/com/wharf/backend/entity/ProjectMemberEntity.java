package com.wharf.backend.entity;

import com.wharf.backend.model.core.ProjectRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's membership of a project: their {@link ProjectRole} and their copy of the project
 * DEK sealed to their public key. A {@code null wrappedDek} means the member is "awaiting
 * key" — they have joined but no admin has sealed the DEK to their public key yet, so they
 * cannot open the vault.
 */
@Entity
@Table(name = "project_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectMemberEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private ProjectRole role;

    /** 80-byte X25519 sealed box wrapping the project DEK, or {@code null} while awaiting key. */
    @Column(name = "wrapped_dek")
    private byte[] wrappedDek;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the current {@code wrappedDek} was set; {@code null} while awaiting key. */
    @Column(name = "keyed_at")
    private Instant keyedAt;

    /** True once the DEK has been sealed to this member's public key. */
    public boolean isKeyed() {
        return wrappedDek != null;
    }
}
