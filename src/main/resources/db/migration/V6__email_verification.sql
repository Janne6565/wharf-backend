-- Email verification.
--
-- An address had to be proven only implicitly until now: anyone could register with
-- someone else's email, which is enough to hijack a project invite addressed to it
-- (invites are matched purely on the email string). Registration therefore no longer
-- issues tokens — the account must first prove control of the address with a 6-digit
-- code, and every token-issuing path refuses an unverified account.

-- The column is added with DEFAULT TRUE *on purpose*: it backfills every pre-existing
-- row as verified in one statement, which is exactly what we want (the only production
-- account is the owner's, and no manual data intervention should be needed). The default
-- is then flipped to FALSE so that every account created from here on starts unverified.
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ALTER COLUMN email_verified SET DEFAULT FALSE;

-- One pending verification per account: a resend upserts this row (resetting the code,
-- the attempt counter and the expiry) rather than accumulating codes, so exactly one
-- code is ever live for an account. The row is deleted on success and when the attempt
-- cap is hit — a 6-digit code is only ~20 bits, so bounding guesses is the real control.
CREATE TABLE email_verifications (
    id           UUID         NOT NULL,
    user_id      UUID         NOT NULL,
    code_hash    VARCHAR(100) NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_email_verifications PRIMARY KEY (id),
    CONSTRAINT fk_email_verifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_email_verifications_user UNIQUE (user_id)
);
