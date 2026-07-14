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
 * One opaque ciphertext vault per user. {@code version} is a monotonic counter used
 * for optimistic concurrency on PUT (client supplies the expected version).
 */
@Entity
@Table(name = "vaults")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VaultEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "blob", nullable = false)
    private byte[] blob;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
