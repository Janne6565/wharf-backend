package com.wharf.backend.services.auth.oauth;

/**
 * The identity a provider client resolves from a completed authorization-code exchange:
 * a stable provider-scoped {@code subject}, the account {@code email}, and whether that
 * email is {@code emailVerified}. Only verified emails may be linked — the service rejects
 * an unverified one rather than trusting an attacker-controlled address.
 */
public record OAuthUserIdentity(String subject, String email, boolean emailVerified) {
}
