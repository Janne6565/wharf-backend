-- Per-account notification preferences for collaboration mail.
--
-- Seven booleans, one per optional notification, all defaulting to TRUE so every existing
-- account keeps receiving exactly what it received before this migration ran. Opting out is
-- an explicit act; nothing silently goes quiet.
--
-- Only *collaboration* mail is represented here. The security notices (recovery code used,
-- password changed, password set, device paired, provider linked, account deleted) have no
-- column on purpose: there must be no value anywhere in this schema that can switch them off.
-- Wharf has no password reset, no support-side recovery and no server-side activity log, so
-- email is the only out-of-band channel through which an account holder can learn their
-- account was taken over. A column would be a way to silence that, and this is the cheapest
-- place to make that impossible.
--
-- Columns on `users` rather than a preferences table: it is a fixed set of seven flags read
-- on the path of every outbound notification, so a join buys nothing and the row is already
-- being loaded to resolve the recipient.
ALTER TABLE users ADD COLUMN notify_project_invite         BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN notify_invite_accepted        BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN notify_invite_declined        BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN notify_project_access_granted BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN notify_project_access_removed BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN notify_role_changed           BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN notify_project_deleted        BOOLEAN NOT NULL DEFAULT TRUE;
