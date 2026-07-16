package com.wharf.backend.model.core;

/**
 * A member's role within a project, ordered by privilege (ascending): a MEMBER can read
 * and write the vault, an ADMIN additionally manages members/invites/keys, and the single
 * OWNER additionally transfers ownership and deletes the project. Declared least- to
 * most-privileged so {@link #atLeast(ProjectRole)} is a plain ordinal comparison.
 */
public enum ProjectRole {

    MEMBER,
    ADMIN,
    OWNER;

    /** True when this role is at least as privileged as {@code other}. */
    public boolean atLeast(ProjectRole other) {
        return ordinal() >= other.ordinal();
    }
}
