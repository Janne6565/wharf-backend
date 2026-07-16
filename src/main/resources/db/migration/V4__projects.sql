-- Team workspaces ("projects") — zero-knowledge, exactly like the personal vault.
-- The server stores only the opaque ciphertext project-vault blob plus metadata
-- (name/description, membership, roles, invites) and the per-member sealed DEKs.
-- It never sees the DEK, the project plaintext, or any private key.
--
-- Each user publishes an X25519 public key (exactly 32 bytes, validated in the app).
-- A project has one DEK, wrapped once per member as an 80-byte X25519 sealed box
-- against that member's public key; a member with a NULL wrapped_dek is "awaiting key"
-- (an admin must seal the DEK to their public key before they can open the vault).

ALTER TABLE users ADD COLUMN public_key            BYTEA;
ALTER TABLE users ADD COLUMN public_key_updated_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE projects (
    id          UUID         NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_projects PRIMARY KEY (id)
);

-- One opaque ciphertext vault per project. version is an app-managed monotonic counter
-- (NOT a JPA @Version) used for optimistic concurrency on PUT, mirroring the personal vault.
CREATE TABLE project_vaults (
    project_id UUID   NOT NULL,
    blob       BYTEA  NOT NULL,
    version    BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_project_vaults PRIMARY KEY (project_id),
    CONSTRAINT fk_project_vaults_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE project_members (
    id          UUID        NOT NULL,
    project_id  UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    role        VARCHAR(10) NOT NULL, -- OWNER | ADMIN | MEMBER
    wrapped_dek BYTEA,                -- 80-byte sealed box; NULL = awaiting key
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    keyed_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_project_members PRIMARY KEY (id),
    CONSTRAINT fk_project_members_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_project_members_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_project_members_project_user UNIQUE (project_id, user_id)
);

CREATE INDEX idx_project_members_user_id ON project_members (user_id);

CREATE TABLE project_invites (
    id         UUID         NOT NULL,
    project_id UUID         NOT NULL,
    email      VARCHAR(320) NOT NULL, -- lowercased at write
    invited_by UUID         NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_project_invites PRIMARY KEY (id),
    CONSTRAINT fk_project_invites_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_project_invites_user FOREIGN KEY (invited_by) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_project_invites_project_email UNIQUE (project_id, email)
);

CREATE INDEX idx_project_invites_email ON project_invites (email);
