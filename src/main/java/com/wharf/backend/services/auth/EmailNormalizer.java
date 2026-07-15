package com.wharf.backend.services.auth;

/**
 * Canonical email normalisation, shared by every path that looks accounts up by email
 * (registration, login, recovery, OAuth auto-link): trim surrounding whitespace and
 * lowercase. Keeping this in one place ensures OAuth links to exactly the account a
 * password login would find.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase();
    }
}
