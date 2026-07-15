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

/**
 * One-time CSRF state for the OAuth authorization-code flow. Created at {@code /authorize}
 * and deleted the moment it is consumed at {@code /callback}; a TTL bounds how long an
 * unconsumed state stays valid. It is a data row, not an HTTP session — the API stays
 * stateless.
 */
@Entity
@Table(name = "oauth_states")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OAuthStateEntity {

    @Id
    @Column(name = "state", nullable = false, updatable = false)
    private String state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
