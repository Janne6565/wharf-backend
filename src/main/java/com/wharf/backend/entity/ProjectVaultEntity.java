package com.wharf.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One opaque ciphertext vault per project. {@code version} is an app-managed monotonic
 * counter (deliberately not a JPA {@code @Version}) used for optimistic concurrency on PUT
 * and to guard key-wrapping against a concurrent rotation — the client supplies the version
 * it last saw and the write is rejected if it has moved on.
 */
@Entity
@Table(name = "project_vaults")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectVaultEntity {

    @Id
    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "blob", nullable = false)
    private byte[] blob;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
