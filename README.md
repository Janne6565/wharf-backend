# wharf-backend

Sync, device-code pairing and encrypted vault storage for [Wharf](https://github.com/Janne6565/wharf-tui) —
a local-first, keyboard-driven terminal SSH client. An account is **optional** and only
adds the online features (cross-machine sync and team projects).

The service is **zero-knowledge**: it only ever stores ciphertext vault blobs and bcrypt
hashes of client-derived credential keys. **It never sees your password or the plaintext
contents of your vault.**

Stack: Java 21, Spring Boot 4.0 (Web MVC, Security, Data JPA), Flyway, PostgreSQL (prod) /
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
- **Account deletion is irreversible — by construction, not by policy.** The server holds
  only ciphertext and bcrypt hashes, so there is nothing it *could* restore: deleting an
  account destroys the only copies of its vault blob and of every project vault it owns,
  and no support path, backup or admin override can bring the plaintext back (nobody ever
  had it). Deletion is therefore re-authenticated rather than session-only — see
  [Account deletion](#account-deletion).
- **An email address must be proven before it grants anything.** Registration issues no
  tokens; a bcrypt-hashed, 15-minute, 5-attempt 6-digit code does (see
  [Email verification](#email-verification)). This closes invite hijack: project invites are
  matched on the email string, so an unproven address would otherwise be enough to claim
  one. Note the residual risk — an attacker can still *register* an address they do not own
  and thereby occupy it (blocking the real owner from registering until the row is cleaned
  up), and OAuth deliberately refuses to link into such a row rather than merging into it.

Threat-model note — vault contents: clients may opt-in sync **secrets** inside the
personal vault payload, currently per-host login passwords and (document schema 3,
2026-07-19) **SSH private keys** (keyfile bytes stored verbatim, so a
passphrase-protected key stays protected even inside the vault). The server's
guarantees are unchanged — it stores only the opaque ciphertext — but compromise of a
user's master password or recovery code consequently yields those synced secrets too.
Keys are never written into project vaults; sharing them is not expressible in the
protocol.

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
| `POST /auth/register` | `{email, authKey, recoveryAuthKey, vault}` | `202 {email, verificationRequired}` — creates the account and emails a 6-digit code. **No tokens, no cookie**: the account is inert until `/auth/verify-email`. `409` on duplicate email |
| `POST /auth/verify-email` | `{email, code, tokenMode?}` | `{user, accessToken, refreshToken?}` — same session shape as login (`COOKIE` default sets the httpOnly refresh cookie; `DIRECT` returns it in the body). `400 code=invalid_verification_code` for a wrong, expired, missing or attempt-exhausted code (one generic error for all of them) |
| `POST /auth/resend-verification` | `{email}` | `202`, empty body — **always**, whether or not the address belongs to an unverified account. Subject to a silent 60-second per-account cooldown |
| `POST /auth/login` | `{email, authKey, tokenMode?}` | `{user, accessToken, refreshToken?}` — `COOKIE` (default) sets an httpOnly refresh cookie; `DIRECT` returns it in the body. `403 code=email_not_verified` while the address is unverified |
| `POST /auth/refresh` | refresh cookie, or `{refreshToken, tokenMode?}` | new `{accessToken}` (+ rotated refresh) · `403 code=email_not_verified` |
| `POST /auth/logout` | `{allDevices?}` (body optional) | `204`, empty body, **always with the clearing refresh cookie** (`Max-Age=0`). Default is a *local* sign-out of this browser only. `allDevices: true` revokes every session of the account everywhere and **requires** `Authorization: Bearer <identity token>` (`401` without one) — see [Sessions & logout](#sessions--logout) |
| `POST /auth/recover/verify` | `{email, recoveryAuthKey}` | `{vault}` for browser-side decryption · `401` on mismatch (aggressive rate limit) |
| `POST /auth/recover/reset` | `{email, recoveryAuthKey, newAuthKey, newRecoveryAuthKey, vault}` | `{user, tokens}` — atomically replaces the credential hashes + vault and revokes all sessions |
| `POST /device-codes/exchange` | `{code, deviceName?}` | `{user, accessToken, refreshToken}` (always DIRECT — the TUI). One-time; wrong code → `404`, expired/used → `410`, unverified account → `403 code=email_not_verified` (the code is not consumed) |
| `GET /auth/oauth/providers` | — | `{providers}` — slugs of enabled providers, e.g. `{"providers":["google","github"]}` (empty when none configured) |
| `GET /auth/oauth/{provider}/authorize` | `?client=web\|mobile` | `302` to the provider consent page (one-time `state`); `client=mobile` records a mobile flow (default `web`); disabled provider → `302 …?error=provider_disabled` (web `/oauth/complete`, mobile `wharf://oauth`) |
| `GET /auth/oauth/{provider}/callback` | `?code&state` | Web: `302 /oauth/complete` + refresh cookie. Mobile: `302 wharf://oauth?code=<device-code>` (no cookie; exchange at `/device-codes/exchange`). Failure → `…?error=<code>` on the client's target (see [OAuth](#oauth-social-login)) |

### Authenticated (`Authorization: Bearer <identity token>`)

| Method & path | Body | Result |
|---------------|------|--------|
| `GET /users/me` | — | `{id, email, emailVerified, createdAt, hasPassword, hasRecovery, hasVault}` — `emailVerified` reflects whether the address was proven with a one-time code; the three `has*` booleans let the frontend route an OAuth account that has not set a master password / recovery / vault yet |
| `DELETE /users/me` | `{authKey?}` | `204` — **irreversibly** deletes the account and everything keyed to it, **including every project it owns** (see [Account deletion](#account-deletion)). Re-authenticated: `authKey` is **required** if the account has a password (`401` if it does not match, `400 code=auth_key_required` if omitted); an OAuth-only account may send no body at all |
| `GET /users/me/deletion-preview` | — | `{ownedProjects:[{id, name, otherMemberCount}], otherMemberships}` — read-only; what a `DELETE /users/me` would destroy, so the UI can warn precisely |
| `POST /auth/setup` | `{recoveryAuthKey, vault, authKey?}` | `204` — one-time onboarding for an OAuth-created account: **atomically** sets the recovery key, the initial vault and (optionally) a password auth key. `409` if a recovery key or vault already exists, or if `authKey` is supplied while a password is already set (rotation stays exclusive to recover/reset) |
| `POST /auth/password` | `{currentAuthKey, newAuthKey, vault}` | `{version, updatedAt}` — authenticated master-password change: verifies `currentAuthKey`, replaces the auth-key hash with one derived from the new password and stores the re-encrypted `vault` (bumping its version). The recovery code is untouched, and existing sessions stay valid (device pairings are independent of the password). `401` if `currentAuthKey` is wrong; `409` if no password is set yet (use `/auth/setup`) |
| `POST /device-codes` | — | `{code, expiresAt}` — 8-char code (no `0/O/1/I`), TTL 10 min; issuing invalidates the caller's previous unused codes |
| `GET /vault` | — | `{vault, version, updatedAt}` |
| `PUT /vault` | `{vault, expectedVersion}` | `{version, updatedAt}` · `409` on version conflict (optimistic concurrency) |

All errors are RFC 7807 `application/problem+json`. Errors clients must branch on carry a
stable machine-readable `code` property alongside the human-readable `detail`
(`email_not_verified`, `invalid_verification_code`, `unverified_account_conflict`,
`auth_key_required`) — match on `code`, never on `detail`.

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

Invites (the invitee is notified by email *and* sees the invite in their own listing):

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

### Email verification

An address is only ever taken at face value once it has been proven. Registration used to
hand out a session immediately, which meant anyone could register **someone else's** email —
enough to hijack a project invite addressed to it, since invites are matched purely on the
email string. So:

- `POST /auth/register` creates the account (and its initial vault) but returns **202 with no
  tokens and no cookie**, and emails a **6-digit code**.
- `POST /auth/verify-email` `{email, code}` marks the address verified and issues the session
  — this is what completes onboarding.
- **Every token-issuing path refuses an unverified account** with `403 code=email_not_verified`:
  `/auth/login`, `/auth/refresh` and `/device-codes/exchange` (which covers both the TUI and
  the mobile OAuth hand-off). In `login` the check runs *after* the bcrypt comparison, so it
  does not reintroduce a user-enumeration timing signal.
- The code is generated with `SecureRandom` and stored **bcrypt-hashed** — never plaintext.
  TTL **15 minutes**, **5 failed attempts** (the fifth destroys the code and forces a
  resend), resend cooldown **60 seconds**, and a dedicated tight per-IP rate-limit bucket
  (`rate-limit.verify-*`, default 5/min) on both endpoints.
- Six digits is only ~20 bits, so **the attempt cap is the actual security control** — the
  code length is a usability choice.
- `POST /auth/resend-verification` always answers `202` with an empty body, and does nothing
  at all for an unknown or already-verified address. Likewise every verify failure returns the
  same generic `invalid_verification_code`. Neither endpoint is an oracle for which addresses
  are registered.
- **OAuth accounts skip the challenge**: the provider already proved ownership (an unverified
  provider identity is rejected outright), so an account created via OAuth is verified on the
  spot. Conversely, an OAuth identity is **never auto-linked into an unverified local
  account** — that row could be a squatter who set a password on someone else's address, and
  linking would let them co-inhabit it.
- Existing accounts were backfilled as verified by the `V6` migration; the column default
  flips to `false` for everything created afterwards.

### Notifications

All outbound mail goes through the `MailPort` → mail-service (`POST /api/v1/send`, scoped
API key). No key configured ⇒ a `NoopMailClient` is wired in and the feature degrades to a
debug log, so local dev and tests need no external service. Every send is `@Async` on a
dedicated executor and **best-effort**: a failure is logged and swallowed, never propagated
to the operation that triggered it.

Templates live in `services/mail/`. `MailTemplate` renders one shell — table layout, inline
styles, 600 px, no external CSS/JS/SVG/webfonts — to **both** HTML and plain text in a single
pass, and mail-service sends them as `multipart/alternative`. Severity is carried by colour
and words only (`MailSeverity`), because Gmail strips SVG and Outlook's Word engine cannot
draw it. Everything interpolated is HTML-escaped: project names and device labels are chosen
by other users and land in a third party's inbox.

| # | Mail | Trigger | Severity |
|---|------|---------|----------|
| 1 | Verification code | register / resend | — (no link at all) |
| 2 | Project invite | `POST /projects/{id}/invites` | neutral |
| 3 | Recovery code used | `POST /auth/recover/reset` | **danger** |
| 4 | Password changed | `POST /auth/password` | warning |
| 5 | Password set | `POST /auth/setup` *with* an authKey | warning |
| 6 | Device paired | `POST /device-codes/exchange` | warning |
| 7 | Sign-in provider linked | OAuth auto-link **into an existing account** only | warning |
| 8 | Account deleted | `DELETE /users/me` | **danger**, no CTA |
| 9 | Invite accepted / declined | invitee answers → to the inviter | neutral |
| 10 | Project access granted | first `POST /projects/{id}/members/{userId}/key` | neutral |
| 11 | Removed / role changed | `POST /projects/{id}/rotate` with `removeUserId`, `PATCH …/members/{userId}` | neutral, no CTA on removal |
| 12 | Project deleted | `DELETE /projects/{id}`, and owner account deletion | neutral |

Security notices (3–8) state that they cannot be turned off and carry no unsubscribe;
collaboration mail (2, 9–12) carries one notification-settings link, pointing at
`/account/notifications`. **No mail offers a password reset** — there is none by construction,
and one that implied otherwise would be indistinguishable from phishing.

#### Preferences

Collaboration mail is opt-out per notification; security notices are not. That asymmetry is
enforced structurally rather than by a check: `NotificationPreference` has a constant only for
the seven collaboration keys, `V7` added a column only for those seven, and
`PATCH /users/me/notifications` **rejects** any key it does not know — so naming a security
notice fails exactly like a typo instead of silently answering 200. There is no value anywhere
in the schema that can switch a security notice off.

| Endpoint | Behaviour |
|----------|-----------|
| `GET /users/me/notifications` | all seven keys, always |
| `PATCH /users/me/notifications` | partial — only the keys present change; every key validated before any is applied, so one bad key changes nothing. Returns the full set |

Keys: `projectInvite`, `inviteAccepted`, `inviteDeclined`, `projectAccessGranted`,
`projectAccessRemoved`, `roleChanged`, `projectDeleted` — all default to `true`, and `V7`
backfills every existing account as opted in, so nothing silently goes quiet.

The check runs in the notification services (`InviteNotificationService`,
`ProjectNotificationService`) rather than at their call sites, so a notification added later
cannot bypass it by forgetting to ask. A recipient with **no Wharf account** counts as opted
in: a project invite is the one mail that can reach a stranger, and refusing to send there
would make it impossible to invite anyone new.

Notices 3–8 report `TIME / IP / CLIENT`. These are read off the live request by
`ActorContextResolver` **on the request thread** and passed into the async method as a value —
they are never persisted. That is deliberate: a zero-knowledge server should not accumulate a
log of where its users sign in from. The cost is that there is no "recent activity" screen to
check afterwards, so the mail is the whole record. The IP is resolved by the same
`ClientIpResolver` the rate limiter uses, so `X-Forwarded-For` is trusted only behind the
proxy — a notice naming a spoofed address would be worse than one naming none. Location is
omitted entirely (no GeoIP), and any row whose value is unknown drops out rather than
rendering blank.

### Account deletion

`DELETE /api/v1/users/me` erases the account for good. The account is always taken from the
authenticated principal — never from the body — so the endpoint cannot be aimed at anyone
else.

- **Re-authentication, not just a session.** An account that has a master password must send
  `{authKey}` and it must match (`401` otherwise); omitting it is `400 code=auth_key_required`.
  A stolen access token or an unattended session must not be enough to destroy an account.
  An **OAuth-only** account (no `authKeyHash`) has no password to re-prove and no other
  second factor available, so the valid session alone is accepted there.
- **Owned projects are deleted outright — including projects with other members.** This is a
  deliberate product decision (an owner leaving without transferring ownership would strand
  an unadministrable project), and it is destructive to third parties, so it is logged at
  INFO with the account id, the number of projects destroyed and the number of other members
  who lost access. Projects the caller is only a *member* of survive; only their membership
  row is removed. Call `GET /api/v1/users/me/deletion-preview` first to show the user exactly
  what they are about to take down.
- **One transaction, all-or-nothing.** Members, invites, project vaults, the projects, the
  caller's other memberships, invites addressed to their address, OAuth identities, device
  codes and the personal vault are removed explicitly and in foreign-key order, then the user
  row last (`email_verifications` is left to its `ON DELETE CASCADE`). A failure anywhere —
  including a rejected `authKey` — leaves the account completely intact.
- Deleting **frees the email address for re-registration**; the new account is an ordinary
  fresh, unverified one and inherits nothing (invites addressed to the address are cleared
  with the old account).
- There is **no undo and no recovery window**: see the [zero-knowledge contract](#zero-knowledge-crypto-contract)
  — the server never held anything but ciphertext.

### JWT model

- Two token types, distinguished by a `tokenType` claim: **identity** (access) tokens are
  short-lived (~15 min) and claim-rich (`userId`, `email`); **refresh** tokens are
  long-lived (~30 days) and minimal. Only identity tokens authenticate a request; only
  refresh tokens can be exchanged.
- Both token types embed the account's `tokenVersion`. A recovery reset and
  `POST /auth/logout {allDevices: true}` bump it, instantly invalidating every previously
  issued token.
- **There is no server-side token store.** A token is valid as long as it is unexpired, its
  signature checks out and its `tokenVersion` still matches the account row. Nothing else can
  end it early — bumping `tokenVersion` is the *only* revocation mechanism there is.
- HMAC (HS256), signed with `JWT_SECRET_KEY`. Stateless (`SessionCreationPolicy.STATELESS`);
  CSRF is disabled because no cookie is trusted for authentication (the refresh cookie is
  only exchanged at `/auth/refresh`, which re-validates the signed token).

### Sessions & logout

`POST /api/v1/auth/logout` (body `{allDevices?}`, optional) always answers `204` and always
sends back the clearing refresh cookie. It does **two deliberately different** things:

- **Default (`allDevices` false or absent) — local sign-out.** The response's `Set-Cookie` has
  `Max-Age=0`, so the browser drops its httpOnly refresh cookie and this browser is signed
  out. **The refresh token itself stays cryptographically valid until it expires** — the model
  is stateless and there is nothing to mark it dead server-side. That is the standard
  stateless-JWT trade-off, not an oversight; the mitigations are the short (~15 min) identity
  token lifetime and, when it actually matters, `allDevices`. Other devices are untouched.
- **`allDevices: true` — global revocation.** Bumps `tokenVersion`, which `JwtFilter`
  re-checks on every request, so **every** issued token dies immediately: this browser, other
  browsers, **and the paired TUI and mobile clients**, whose only pairing is their tokens.
  They have to be paired again with a fresh device code. That unpairing is exactly what "sign
  out everywhere" means, and it is why it is not the default.

**Auth:** the route is not blanket-authenticated, on purpose. A logout has to work when the
session is already broken — an expired access token is the most common state a user is in when
they reach for "sign out" — and behind `.authenticated()` the request would `401` and the
browser would never receive the clearing cookie for a cookie it cannot delete itself. The
unauthenticated half's entire effect is telling the caller's own browser to drop its own
cookie: no information, no server state. The privileged half is gated in the controller
instead — `allDevices` needs a valid identity token (`401` otherwise), so the `tokenVersion`
bump can only ever hit the caller's own account.

**Clients should still clear their local state regardless of the response** (access token in
memory, any stored refresh token): the server's answer is a courtesy, not the source of truth
for a client-side session.

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
   exists *and has itself verified that address*, the identity is linked to it; otherwise a
   new OAuth-only account is created (already `emailVerified`, since the provider proved it).
   Unverified provider emails are rejected, and so is linking into an **unverified local
   account** — that row may belong to a squatter (`error=unverified_account_conflict`).
5. On success it sets the `wharf_refresh` httpOnly cookie and `302`-redirects to
   `/oauth/complete`; the frontend then silently calls `/auth/refresh` to get its access
   token. On any failure it redirects to `/oauth/complete?error=<code>` where `<code>` is one
   of `provider_disabled`, `invalid_state`, `email_not_verified` (the *provider's* email is
   unverified), `unverified_account_conflict`, `account_not_verified`, `provider_error`,
   `server_error` (no sensitive detail ever reaches the URL).

### Mobile hand-off (deep link)

The React Native app (custom URL scheme `wharf`) uses the **same** flow but cannot receive a
cookie, so it opts in with `GET /api/v1/auth/oauth/{provider}/authorize?client=mobile`. The
initiating client (`web` default | `mobile`) is recorded on the one-time `state` row, so the
callback knows how to finish. For a mobile flow the backend does **not** set the refresh
cookie: it issues a one-time **device code** for the resolved user (via the same
`/device-codes` machinery) and `302`-redirects to the server-controlled deep link
`wharf://oauth?code=<code>`. The app then exchanges that code at the existing public
`POST /api/v1/device-codes/exchange` for a **DIRECT** session (tokens in the body) — there is
no mobile-specific exchange endpoint, and no orphaned refresh token is ever minted.

Mobile failures redirect to `wharf://oauth?error=<code>` with the same error codes
(`provider_disabled`, `email_not_verified`, `unverified_account_conflict`,
`account_not_verified`, `provider_error`, `server_error`). The one
exception is `invalid_state`: the state row is unknown, so the initiating client is
unknowable and it falls back to the web target `/oauth/complete?error=invalid_state`. The
deep-link base is server config (`oauth.mobile-redirect-uri`, default `wharf://oauth`), never
caller-supplied — the no-open-redirect stance holds for mobile too.

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
| `OAUTH_MOBILE_REDIRECT_URI` | `wharf://oauth` | Server-controlled deep-link base a mobile-initiated login is handed back through (`?code=` / `?error=`) |
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
mvn -q spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev --server.port=18080" &
curl -s http://localhost:18080/v3/api-docs | python3 -m json.tool > openapi.json
```

The spec must be generated through `spring-boot:run`, not from the packaged jar: H2 is a
test-scope dependency (it is deliberately kept out of the production fat jar), and the
`dev` profile needs it. The Boot plugin's `useTestClasspath` makes it available to the
run goal.

The emitted document is pinned to OpenAPI 3.0.1 via `springdoc.api-docs.version` — see the
comment in `application.properties`.

---

## Environment variables

| Variable | Default | Notes |
|----------|---------|-------|
| `JWT_SECRET_KEY` | insecure dev fallback in `application.properties` | **Must** be overridden outside local dev (≥ 32 bytes for HS256); the app refuses to start on the `prod` profile if left at the committed dev default |
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev` (H2) or `prod` (PostgreSQL) |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/wharf` / `wharf` / _(required)_ | prod profile only; `DB_PASSWORD` has no default and must be supplied |
| `CORS_ALLOWED_ORIGINS` | `https://wharf.sh` (prod) / `http://localhost:5173` (dev) | comma-separated (`app.cors.allowed-origins`) |
| `OAUTH_PUBLIC_BASE_URL`, `OAUTH_MOBILE_REDIRECT_URI`, `OAUTH_{GOOGLE,GITHUB}_CLIENT_{ID,SECRET}` | empty (providers disabled); mobile redirect `wharf://oauth` | OAuth social login — see [OAuth social login](#oauth-social-login) |
| `MAIL_SERVICE_API_KEY` | empty (mail disabled, no-op client) | mail-service key, format `mk_<uuid>_<secret>`, scope `SEND` — see [Notifications](#notifications) |
| `WHARF_APP_BASE_URL` | `https://wharf.jannekeipert.de` (prod) / `http://localhost:5173` (dev) | origin every link in an email is built from; mail links must be absolute and point at the deployment that sent them |
| `WHARF_SENDING_DOMAIN` | `wharf.jannekeipert.de` | bare domain shown as the last line of every email footer |

Other tunables live in `application.properties`: `jwt.identity-expiration`,
`jwt.refresh-expiration`, `auth.cookie.*`, `vault.max-size-bytes`, `device-code.ttl`,
`rate-limit.*`, `wharf.mail.*`.

---

## Layout

Organised by component type (see the agents KB `spring-boot` skill):

```
configuration/  @ConfigurationProperties + bean config (JWT, CORS, cookie, vault, rate-limit, OpenAPI)
controller/     GlobalExceptionHandler + v1/{schema (*Api interfaces), implementation (*Controller)}
entity/         UserEntity, VaultEntity, DeviceCodeEntity, Project{,Vault,Member,Invite}Entity
model/          action (request DTOs), core (response DTOs + TokenMode + ProjectRole), exception (BaseException + domain)
repository/     Spring Data JPA repositories
security/       SecurityConfig, JwtFilter, JwtService, RefreshCookieFactory, ratelimit/
services/       auth/ (incl. oauth/), vault/ (incl. VaultBlobCodec), project/ (project, vault, invite, access), devicecode/, user/, mail/ (MailTemplate renderer + ActorContext)
resources/db/migration/  V1__init.sql … V7__notification_preferences.sql (Flyway)
```

## Testing

```bash
mvn verify
```

Unit tests (Mockito + AssertJ) cover the auth, vault and device-code services; a MockMvc
`@SpringBootTest` integration test drives the full flow against a Flyway-migrated H2
schema: register → login → device code issue/exchange → vault get/put (with version
conflict) → recovery verify/reset → old tokens rejected. Further integration tests cover the
email-verification flow, the team-project flow and account deletion (including that a wrong
`authKey` rolls the whole thing back and that the address is freed for re-registration).
