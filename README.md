# wharf-backend

Sync, device-code pairing and encrypted vault storage for [Wharf](https://github.com/Janne6565/wharf-tui) —
a local-first, keyboard-driven terminal SSH client. An account is **optional** and only
adds the online features (cross-machine sync, and later team projects).

The service is **zero-knowledge**: it only ever stores ciphertext vault blobs and bcrypt
hashes of client-derived credential keys. **It never sees your password or the plaintext
contents of your vault.**

Stack: Java 21, Spring Boot 3.3 (Web MVC, Security, Data JPA), Flyway, PostgreSQL (prod) /
H2 (dev & tests), self-issued HMAC JWT via jjwt, Bucket4j rate limiting, springdoc-openapi.

---

## Zero-knowledge crypto contract

All key derivation and vault encryption happen **client-side**. The server receives only
the derived `authKey` / `recoveryAuthKey` (which it bcrypt-hashes before storing) and the
opaque ciphertext `vault` blob. The contract, matching wharf-tui's vault defaults:

| Value | Derivation |
|-------|------------|
| `masterKey` | `argon2id(password, salt = first 16 bytes of SHA-256(lowercased, trimmed email), t=3, m=64 MiB, p=4, 32-byte output)` |
| `authKey` | `HKDF-SHA256(masterKey, info="wharf/auth/v1", 32 bytes)`, base64 — the login credential sent to the server |
| `recoverySecret` | 25 random bytes, shown once as a 40-char Crockford base32 code (8 groups of 5) |
| `recoveryAuthKey` | `HKDF-SHA256(recoverySecret, info="wharf/recovery-auth/v1", 32 bytes)`, base64 |
| `vault` | Opaque bytes in wharf-tui's **WHARFV** format (magic `WHARFV`, version 1, argon2id, two unlock slots — password + recovery — wrapping one DEK, XChaCha20-Poly1305 body). Sent to the server base64-encoded. |

Server-side hardening:

- `authKey` and `recoveryAuthKey` are **bcrypt-hashed** before storage (defence in depth);
  verification uses bcrypt's constant-time comparison.
- The vault blob is treated as **opaque bytes** — never parsed — and capped at
  `vault.max-size-bytes` (default 1 MiB).
- **Recovery has no backdoor.** The only password-reset path is a valid recovery code.
  Resetting rotates the recovery code (invalidating the old one), re-encrypts the vault,
  and **revokes all existing sessions** by bumping the account's `tokenVersion`.

---

## Projects crypto contract

Wharf **Projects** are shared vaults: a project holds its own document sealed under a
per-project **data-encryption key (DEK)**, and that DEK is delivered to each member by
wrapping it to their public key. As with the personal vault, all sealing and wrapping
happen **client-side** — the server only ever stores and relays ciphertext and public keys.
The Go client (wharf-tui `internal/vault`) and the browser client (wharf-web
`src/crypto`) implement byte-for-byte identical formats, proven by a shared fixture test.

**Project blob — `WHARFP` (v1, little-endian):**

| off | len | field |
|-----|-----|-------|
| 0 | 6 | magic `WHARFP` |
| 6 | 2 | version `uint16 = 1` |
| 8 | 24 | body nonce (XChaCha20-Poly1305) |
| 32 | … | `XChaCha20-Poly1305(nonce, projectDEK, payload JSON, AAD = header bytes[0:32])` |

The 32-byte header (magic + version + body nonce) is the AEAD's additional data, so any
edit to it fails authentication. A fresh nonce is drawn on every seal.

**DEK wrapping — X25519 sealed box:** the project DEK is shared with a member by sealing
it to their X25519 public key with libsodium's `crypto_box_seal` (NaCl anonymous box). The
wrapped DEK is **exactly 80 bytes**: 32-byte ephemeral public key + 32-byte DEK + 16-byte
Poly1305 tag. The sender needs no long-term key (an ephemeral keypair is generated per
wrap); only the recipient's private key can `crypto_box_seal_open` it.

**Identity storage:** each member's X25519 **private key never leaves their encrypted
personal vault** — it is stored inside the WHARFV payload document, which gains an optional
`identity: { x25519Priv, x25519Pub, createdAt }` field (personal-vault document schema
bumps 1 → 2; schema-1 documents remain valid with `identity` absent). Only the matching
**public** key is published to the server for others to wrap DEKs to.

**Trust caveat (v1):** the server distributes members' public keys, so it is trusted not to
substitute them — a malicious or compromised server could perform a **man-in-the-middle**
by handing out an attacker's public key. Out-of-band public-key verification is **out of
scope for v1** and accepted as a known limitation; the payload contents themselves remain
end-to-end encrypted regardless.

---

## API

Base path `/api/v1`. Full machine-readable contract in [`openapi.json`](openapi.json)
(the `wharf-web` frontend generates its client from it via Orval).

### Public (rate-limited, no auth)

| Method & path | Body | Result |
|---------------|------|--------|
| `POST /auth/register` | `{email, authKey, recoveryAuthKey, vault}` | `201 {user, tokens}` · `409` on duplicate email |
| `POST /auth/login` | `{email, authKey, tokenMode?}` | `{user, accessToken, refreshToken?}` — `COOKIE` (default) sets an httpOnly refresh cookie; `DIRECT` returns it in the body |
| `POST /auth/refresh` | refresh cookie, or `{refreshToken, tokenMode?}` | new `{accessToken}` (+ rotated refresh) |
| `POST /auth/recover/verify` | `{email, recoveryAuthKey}` | `{vault}` for browser-side decryption · `401` on mismatch (aggressive rate limit) |
| `POST /auth/recover/reset` | `{email, recoveryAuthKey, newAuthKey, newRecoveryAuthKey, vault}` | `{user, tokens}` — atomically replaces the credential hashes + vault and revokes all sessions |
| `POST /device-codes/exchange` | `{code, deviceName?}` | `{user, accessToken, refreshToken}` (always DIRECT — the TUI). One-time; wrong code → `404`, expired/used → `410` |
| `GET /auth/oauth/providers` | — | `{providers}` — slugs of enabled providers, e.g. `{"providers":["google","github"]}` (empty when none configured) |
| `GET /auth/oauth/{provider}/authorize` | — | `302` to the provider consent page (one-time `state`); disabled provider → `302 /oauth/complete?error=provider_disabled` |
| `GET /auth/oauth/{provider}/callback` | `?code&state` | `302 /oauth/complete` + refresh cookie on success, else `302 /oauth/complete?error=<code>` (see [OAuth](#oauth-social-login)) |

### Authenticated (`Authorization: Bearer <identity token>`)

| Method & path | Body | Result |
|---------------|------|--------|
| `GET /users/me` | — | `{id, email, createdAt, hasPassword, hasRecovery, hasVault}` — the three booleans let the frontend route an OAuth account that has not set a master password / recovery / vault yet |
| `POST /auth/setup` | `{recoveryAuthKey, vault, authKey?}` | `204` — one-time onboarding for an OAuth-created account: **atomically** sets the recovery key, the initial vault and (optionally) a password auth key. `409` if a recovery key or vault already exists, or if `authKey` is supplied while a password is already set (rotation stays exclusive to recover/reset) |
| `POST /auth/password` | `{currentAuthKey, newAuthKey, vault}` | `{version, updatedAt}` — authenticated master-password change: verifies `currentAuthKey`, replaces the auth-key hash with one derived from the new password and stores the re-encrypted `vault` (bumping its version). The recovery code is untouched, and existing sessions stay valid (device pairings are independent of the password). `401` if `currentAuthKey` is wrong; `409` if no password is set yet (use `/auth/setup`) |
| `POST /device-codes` | — | `{code, expiresAt}` — 8-char code (no `0/O/1/I`), TTL 10 min; issuing invalidates the caller's previous unused codes |
| `GET /vault` | — | `{vault, version, updatedAt}` |
| `PUT /vault` | `{vault, expectedVersion}` | `{version, updatedAt}` · `409` on version conflict (optimistic concurrency) |

All errors are RFC 7807 `application/problem+json`.

### Projects (team workspaces)

Zero-knowledge shared workspaces. The server stores only the opaque ciphertext project
vault plus metadata (name/description, membership, roles, invites) and the per-member
sealed DEKs — never the DEK, the project plaintext, or any private key. A caller who is
**not a member of a project always receives `404` (never `403`)** on every project-scoped
route, so project existence is never leaked.

Account public key (used to seal DEKs against):

| Method & path | Body | Result |
|---------------|------|--------|
| `GET /users/me` | — | now also returns `publicKey` (base64 or null) |
| `PUT /users/me/public-key` | `{publicKey, rotate}` | `204` — publish (or, with `rotate=true`, replace) the account's 32-byte X25519 public key. `400` if not 32 bytes; `409` if a key exists and `rotate=false`. Rotating also **nulls every wrapped DEK the account holds**, so each membership re-enters the awaiting-key state |

Projects & membership:

| Method & path | Body | Result |
|---------------|------|--------|
| `POST /projects` | `{name, description?, vault, wrappedDek}` | `201 Project` — creates the project, its vault (v1) and the OWNER member (keyed). `412` if the caller has no published public key |
| `GET /projects` | — | `[{id, name, description, role, memberCount, pendingInviteCount, vaultVersion, awaitingKey}]` |
| `GET /projects/{id}` | — | full detail: metadata + `members[{userId, email, role, keyed, publicKey}]` + `invites[{id, email, createdAt, expiresAt}]` |
| `PATCH /projects/{id}` | `{name?, description?}` | `200` — admin+ |
| `DELETE /projects/{id}` | — | `204` — owner only |
| `GET /projects/{id}/vault` | — | `{vault, version, updatedAt, wrappedDek}` — any member; `wrappedDek` is the **caller's** sealed DEK (null while awaiting key) |
| `PUT /projects/{id}/vault` | `{vault, expectedVersion}` | `{version, updatedAt}` — any **keyed** member (`403` if the caller holds no wrapped key); `409` on version conflict |
| `POST /projects/{id}/rotate` | `{removeUserId?, vault, expectedVersion, wrappedKeys:[{userId, wrappedDek}]}` | `{version, updatedAt}` — admin+; rotates the DEK, optionally removes a member (`400` removing the owner, `403` if an admin removes an admin), re-encrypts the vault and re-wraps the new DEK for the listed members (all others re-enter awaiting-key). `409` on version conflict |
| `PATCH /projects/{id}/members/{userId}` | `{role}` | `204` — owner only; setting a member to `OWNER` transfers ownership and demotes the caller to `ADMIN` (single-owner invariant) |
| `DELETE /projects/{id}/members/me` | — | `204` — leave; `409` if the caller is the owner (transfer first) |

Key distribution:

| Method & path | Body | Result |
|---------------|------|--------|
| `GET /projects/{id}/pending-keys` | — | `[{userId, email, publicKey}]` — admin+; members awaiting a key who have published a public key to seal against |
| `POST /projects/{id}/members/{userId}/key` | `{wrappedDek, vaultVersion}` | `204` — admin+; seals the DEK to a member. `400` if the wrapped key is not 80 bytes; `409` if `vaultVersion` != the current vault version (guards wrapping a stale DEK across a rotation) |

Invites (no email delivery in this milestone — surfaced to the invitee via their own listing):

| Method & path | Body | Result |
|---------------|------|--------|
| `POST /projects/{id}/invites` | `{email}` | `201 ProjectInvite` — admin+; `409` if the email is already a member or already has an unexpired invite (an expired invite is replaced). TTL `wharf.projects.invite-ttl` (default 14 days) |
| `DELETE /projects/{id}/invites/{inviteId}` | — | `204` — admin+ |
| `GET /users/me/invites` | — | `[{id, projectId, projectName, invitedByEmail, createdAt, expiresAt}]` — unexpired invites addressed to the caller |
| `POST /users/me/invites/{id}/accept` | — | `ProjectSummary` — join as an awaiting-key MEMBER and consume the invite. `404` if not addressed to the caller; `410` if expired |
| `POST /users/me/invites/{id}/decline` | — | `204` |

#### Projects crypto contract

- The **project vault** is opaque WHARFP ciphertext, treated exactly like the personal
  vault: never parsed, base64-encoded on the wire, capped at `vault.max-size-bytes`, with a
  monotonic `version` for optimistic concurrency.
- Each project has **one DEK**. It is distributed to members as **80-byte X25519 sealed
  boxes** (`wrappedDek`) wrapped against each member's published 32-byte public key. The
  server stores these sealed boxes and hands them out; it never unwraps or uses them.
- A member with a `null` wrapped DEK is **awaiting key**: they have joined but no admin has
  sealed the DEK to their public key yet, so they cannot open the vault (reads return a null
  `wrappedDek`, writes are `403`).
- **Rotation** is the revocation mechanism: it mints a new DEK, re-encrypts the vault and
  re-wraps for the members who should keep access; a removed or omitted member is left with
  no valid key to future secrets.
- **Server-visible plaintext:** project name/description, member emails and roles, invite
  emails. Everything inside the vault stays encrypted.
- **Threat-model note (accepted for v1):** the server distributes members' public keys, so a
  malicious server could substitute a key it controls and MITM the DEK wrapping. There is no
  key verification (TOFU / fingerprint comparison) between members yet; this is accepted for
  v1 and is the natural next hardening step.

### JWT model

- Two token types, distinguished by a `tokenType` claim: **identity** (access) tokens are
  short-lived (~15 min) and claim-rich (`userId`, `email`); **refresh** tokens are
  long-lived (~30 days) and minimal. Only identity tokens authenticate a request; only
  refresh tokens can be exchanged.
- Both token types embed the account's `tokenVersion`. A recovery reset bumps it,
  instantly invalidating every previously issued token.
- HMAC (HS256), signed with `JWT_SECRET_KEY`. Stateless (`SessionCreationPolicy.STATELESS`);
  CSRF is disabled because no cookie is trusted for authentication (the refresh cookie is
  only exchanged at `/auth/refresh`, which re-validates the signed token).

---

## OAuth social login

OAuth (Google, GitHub) is **only the authentication step**: it links an external identity
to a local account, then the backend issues the **same self-issued JWT pair** as any other
login. It never touches the zero-knowledge model — the master password stays the
client-side vault-encryption factor, so an account created via OAuth starts with **no
password auth key, no recovery key and no vault**. The user sets those *after* first login
(see below).

### Flow (authorization code, backend-confidential client)

1. Frontend calls `GET /api/v1/auth/oauth/providers` and shows a button per enabled provider.
2. Button navigates to `GET /api/v1/auth/oauth/{provider}/authorize` → `302` to the provider
   consent page with a one-time, DB-backed `state` (10-min TTL, consumed exactly once).
3. Provider redirects back to `GET /api/v1/auth/oauth/{provider}/callback?code&state`. The
   backend validates+consumes the `state`, exchanges the `code` server-to-server, and
   obtains a **verified** email + stable subject:
   - **Google** (OIDC): exchanges at `oauth2.googleapis.com/token` and reads `sub` / `email`
     / `email_verified` from the returned `id_token` (scopes `openid email`).
   - **GitHub**: exchanges at `github.com/login/oauth/access_token`, then `GET /user` (subject
     = `id`) and `GET /user/emails` for the **primary + verified** address (scope `user:email`).
4. It then **auto-links by verified email**: if an account with that (normalized) email
   exists, the identity is linked to it; otherwise a new OAuth-only account is created.
   Unverified emails are rejected.
5. On success it sets the `wharf_refresh` httpOnly cookie and `302`-redirects to
   `/oauth/complete`; the frontend then silently calls `/auth/refresh` to get its access
   token. On any failure it redirects to `/oauth/complete?error=<code>` where `<code>` is one
   of `provider_disabled`, `invalid_state`, `email_not_verified`, `provider_error`,
   `server_error` (no sensitive detail ever reaches the URL).

A provider is **enabled only when both its client id and secret are configured**; otherwise
`/authorize` and `/callback` redirect with `error=provider_disabled` and it is omitted from
`/providers`. Everything builds and runs with no credentials set.

### After first OAuth login

The frontend uses the `hasPassword` / `hasRecovery` / `hasVault` flags on `GET /users/me` to
route a fresh OAuth account through onboarding: the client picks a **master password**,
generates a **recovery code**, encrypts the initial vault locally and submits everything in
one atomic call — `POST /auth/setup` `{recoveryAuthKey, vault, authKey?}` (first-time only;
all-or-nothing, so an account can never end up with a recovery key but no vault). Including
`authKey` also enables password login. Afterwards the normal `recover/reset` flow handles
any rotation.

### Configuration

All secrets come from the environment; empty defaults keep providers disabled and out of git.

| Variable | Default | Notes |
|----------|---------|-------|
| `OAUTH_PUBLIC_BASE_URL` | `http://localhost:8080` (dev) / `https://wharf.jannekeipert.de` (prod) | Builds the provider `redirect_uri` `<base>/api/v1/auth/oauth/{provider}/callback` |
| `OAUTH_GOOGLE_CLIENT_ID` / `OAUTH_GOOGLE_CLIENT_SECRET` | _(empty → disabled)_ | Google OAuth client credentials |
| `OAUTH_GITHUB_CLIENT_ID` / `OAUTH_GITHUB_CLIENT_SECRET` | _(empty → disabled)_ | GitHub OAuth app credentials |

**Authorized redirect URIs** to register in each provider's console:

- Google: `https://wharf.jannekeipert.de/api/v1/auth/oauth/google/callback`
  (dev: `http://localhost:8080/api/v1/auth/oauth/google/callback`)
- GitHub: `https://wharf.jannekeipert.de/api/v1/auth/oauth/github/callback`
  (dev: `http://localhost:8080/api/v1/auth/oauth/github/callback`)

---

## Running

### Dev (in-memory H2, no external services)

```bash
mvn spring-boot:run           # profile 'dev' is active by default
```

- API at `http://localhost:8080`, Swagger UI at `/swagger-ui.html`, spec at `/v3/api-docs`.
- H2 console at `/h2-console`.

### Production (PostgreSQL)

```bash
# bring up Postgres however you like, e.g.:
docker run -d --name wharf-pg -e POSTGRES_USER=wharf -e POSTGRES_PASSWORD=wharf \
  -e POSTGRES_DB=wharf -p 5432:5432 postgres:16

# then run the app on the prod profile with a real secret
JWT_SECRET_KEY=$(openssl rand -base64 48) \
  SPRING_PROFILES_ACTIVE=prod \
  DB_URL=jdbc:postgresql://localhost:5432/wharf DB_USERNAME=wharf DB_PASSWORD=wharf \
  java -jar target/wharf-backend-*.jar
```

Or build the container image: `docker build -t wharf-backend .`

### Regenerating `openapi.json`

```bash
mvn -q -DskipTests package
java -jar target/wharf-backend-*.jar --spring.profiles.active=dev --server.port=18080 &
curl -s http://localhost:18080/v3/api-docs | python3 -m json.tool > openapi.json
```

---

## Environment variables

| Variable | Default | Notes |
|----------|---------|-------|
| `JWT_SECRET_KEY` | insecure dev fallback in `application.properties` | **Must** be overridden outside local dev (≥ 32 bytes for HS256); the app refuses to start on the `prod` profile if left at the committed dev default |
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev` (H2) or `prod` (PostgreSQL) |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/wharf` / `wharf` / _(required)_ | prod profile only; `DB_PASSWORD` has no default and must be supplied |
| `CORS_ALLOWED_ORIGINS` | `https://wharf.sh` (prod) / `http://localhost:5173` (dev) | comma-separated (`app.cors.allowed-origins`) |
| `OAUTH_PUBLIC_BASE_URL`, `OAUTH_{GOOGLE,GITHUB}_CLIENT_{ID,SECRET}` | empty (providers disabled) | OAuth social login — see [OAuth social login](#oauth-social-login) |

Other tunables live in `application.properties`: `jwt.identity-expiration`,
`jwt.refresh-expiration`, `auth.cookie.*`, `vault.max-size-bytes`, `device-code.ttl`,
`rate-limit.*`.

---

## Layout

Organised by component type (see `docs/conventions/SPRING_BOOT.md`):

```
configuration/  @ConfigurationProperties + bean config (JWT, CORS, cookie, vault, rate-limit, OpenAPI)
controller/     GlobalExceptionHandler + v1/{schema (*Api interfaces), implementation (*Controller)}
entity/         UserEntity, VaultEntity, DeviceCodeEntity, Project{,Vault,Member,Invite}Entity
model/          action (request DTOs), core (response DTOs + TokenMode + ProjectRole), exception (BaseException + domain)
repository/     Spring Data JPA repositories
security/       SecurityConfig, JwtFilter, JwtService, RefreshCookieFactory, ratelimit/
services/       auth/ (incl. oauth/), vault/ (incl. VaultBlobCodec), project/ (project, vault, invite, access), devicecode/, user/
resources/db/migration/  V1__init.sql … V4__projects.sql (Flyway)
```

## Testing

```bash
mvn verify
```

Unit tests (Mockito + AssertJ) cover the auth, vault and device-code services; a MockMvc
`@SpringBootTest` integration test drives the full flow against a Flyway-migrated H2
schema: register → login → device code issue/exchange → vault get/put (with version
conflict) → recovery verify/reset → old tokens rejected.
