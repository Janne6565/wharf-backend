-- Mobile OAuth deep-link hand-off.
--
-- The same authorization-code flow now serves the React Native app, which cannot use
-- cookies: it must receive the session via a wharf:// deep link instead. Which client
-- started the flow is recorded on the one-time state row at /authorize so the callback
-- can branch (web -> refresh cookie + /oauth/complete; mobile -> one-time device code +
-- wharf://oauth deep link). Existing rows default to WEB, the historical behaviour.
ALTER TABLE oauth_states ADD COLUMN client VARCHAR(16) NOT NULL DEFAULT 'WEB';
