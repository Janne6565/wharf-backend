-- Initial Wharf schema.
-- The server is zero-knowledge: it stores only derived credential *hashes*
-- (bcrypt) and opaque ciphertext vault blobs. No passwords, no plaintext vaults.

CREATE TABLE users (
    id                UUID         NOT NULL,
    email             VARCHAR(320) NOT NULL,
    auth_key_hash     VARCHAR(100) NOT NULL,
    recovery_key_hash VARCHAR(100) NOT NULL,
    token_version     INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE vaults (
    user_id    UUID   NOT NULL,
    blob       BYTEA  NOT NULL,
    version    BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_vaults PRIMARY KEY (user_id),
    CONSTRAINT fk_vaults_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE device_codes (
    id         UUID         NOT NULL,
    user_id    UUID         NOT NULL,
    code_hash  VARCHAR(100) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at    TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_device_codes PRIMARY KEY (id),
    CONSTRAINT fk_device_codes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_device_codes_code_hash ON device_codes (code_hash);
CREATE INDEX idx_device_codes_user_id ON device_codes (user_id);
