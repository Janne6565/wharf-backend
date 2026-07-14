-- Optimistic-locking version column for the users table (JPA @Version).
-- Existing rows start at 0; Hibernate bumps it on every update to detect lost updates.

ALTER TABLE users ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
