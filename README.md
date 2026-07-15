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
| `POST /device-codes` | — | `{code, expiresAt}` — 8-char code (no `0/O/1/I`), TTL 10 min; issuing invalidates the caller's previous unused codes |
| `GET /vault` | — | `{vault, version, updatedAt}` |
| `PUT /vault` | `{vault, expectedVersion}` | `{version, updatedAt}` · `409` on version conflict (optimistic concurrency) |

All errors are RFC 7807 `application/problem+json`.

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
entity/         UserEntity, VaultEntity, DeviceCodeEntity
model/          action (request DTOs), core (response DTOs + TokenMode), exception (BaseException + domain)
repository/     Spring Data JPA repositories
security/       SecurityConfig, JwtFilter, JwtService, RefreshCookieFactory, ratelimit/
services/       auth/ (incl. oauth/ — provider clients, state store, linking), vault/, devicecode/, user/
resources/db/migration/  V1__init.sql … V3__oauth.sql (Flyway)
```

## Testing

```bash
mvn verify
```

Unit tests (Mockito + AssertJ) cover the auth, vault and device-code services; a MockMvc
`@SpringBootTest` integration test drives the full flow against a Flyway-migrated H2
schema: register → login → device code issue/exchange → vault get/put (with version
conflict) → recovery verify/reset → old tokens rejected.
