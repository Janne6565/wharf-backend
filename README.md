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

### Authenticated (`Authorization: Bearer <identity token>`)

| Method & path | Body | Result |
|---------------|------|--------|
| `GET /users/me` | — | `{id, email, createdAt}` |
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
services/       auth/, vault/, devicecode/
resources/db/migration/  V1__init.sql (Flyway)
```

## Testing

```bash
mvn verify
```

Unit tests (Mockito + AssertJ) cover the auth, vault and device-code services; a MockMvc
`@SpringBootTest` integration test drives the full flow against a Flyway-migrated H2
schema: register → login → device code issue/exchange → vault get/put (with version
conflict) → recovery verify/reset → old tokens rejected.
