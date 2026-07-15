-- OAuth social login (Google / GitHub).
--
-- OAuth is only the authentication step: it links an external identity to a local
-- account, then the app issues its own JWT pair. The master password stays the
-- client-side vault-encryption factor, so an OAuth-only account starts with no
-- password auth key, no recovery key and no vault — hence both credential-hash
-- columns become nullable here.

ALTER TABLE users ALTER COLUMN auth_key_hash DROP NOT NULL;
ALTER TABLE users ALTER COLUMN recovery_key_hash DROP NOT NULL;

-- External identities linked to a local account. A user may link several providers;
-- a given provider account (provider + subject) maps to at most one local user.
CREATE TABLE oauth_identities (
    id            UUID         NOT NULL,
    user_id       UUID         NOT NULL,
    provider      VARCHAR(16)  NOT NULL,
    subject       VARCHAR(255) NOT NULL,
    email_at_link VARCHAR(320) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_oauth_identities PRIMARY KEY (id),
    CONSTRAINT fk_oauth_identities_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_oauth_identities_provider_subject UNIQUE (provider, subject)
);

CREATE INDEX idx_oauth_identities_user_id ON oauth_identities (user_id);

-- One-time, server-side CSRF state for the authorization-code flow. A row is created
-- at /authorize and deleted the moment it is consumed at /callback (TTL-bounded).
CREATE TABLE oauth_states (
    state      VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_oauth_states PRIMARY KEY (state)
);

CREATE INDEX idx_oauth_states_created_at ON oauth_states (created_at);
