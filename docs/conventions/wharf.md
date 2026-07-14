<!-- AUTO-SYNCED from agents KB: projects/wharf.md @ a624a02.
     Do NOT edit here — edit the source in ~/projects/agents and re-run scripts/sync-conventions.sh. -->

# Wharf

A terminal-based SSH client (think Termius, but a keyboard-driven TUI) with optional
cloud sync and team collaboration. **Local-first**: hosts, keys and sessions work with
no account against a local encrypted vault; an account only adds the *online* features —
cross-machine sync and team projects — under a **zero-knowledge** model where the server
only ever holds ciphertext.

- **Repos:**
  - github.com/Janne6565/wharf-tui — **flagship TUI client** (Go + Bubble Tea). Exists.
  - `wharf-web` — landing + web auth flow (React + Vite). Planned.
  - `wharf-backend` — sync + device-code auth + projects (Java 21 + Spring Boot). Planned.
  - `wharf-mobile` — companion app (React Native + Expo). Planned.
  - `wharf-deployment` — Kustomize + ArgoCD. Planned.
- **Local:** clone the repo(s) above into `~/projects/wharf/<repo-name>/` (multi-repo, one
  subfolder per repo). Always `git pull` before reading. See
  [repo conventions](README.md#local-repos--clone-on-demand-pull-before-reading).
- **Cluster:** `wharf` (planned; not yet deployed).
- **Design source:** Claude Design project `33a77f79-40ef-4774-8324-6ece35835b06`
  (files: Wharf TUI v2, Wharf Web Auth, Wharf Landing, Wharf Mobile). Import via the
  `claude_design` MCP (`DesignSync` tool). Removed by decision: shared/multiplayer SSH
  sessions, session chat, member presence.

## Idea
Manage your SSH fleet — hosts (searchable/filterable by tags, projects, status), keys/
identities (ED25519, RSA, YubiKey resident), and tabbed sessions (detach keeps them
running) — entirely from a fast TUI: `j/k`, `/`, `1–4`, `tab`, `enter`, `?`. Three themes
(abyss/phosphor/amber). Signing in unlocks the **Projects** tab: shared host workspaces,
invite by email, roles (owner/admin/member); private keys are never shared.

## Security model
- Master password → vault key via **argon2id**, client-side only.
- Vault blobs sealed with **XChaCha20-Poly1305** before upload; server stores ciphertext.
- Sign-in is a **browser device-code** pairing (Google/GitHub/email OAuth) — no password
  is ever typed into the terminal.
- Only recovery path is a **40-character recovery code** shown once at onboarding;
  resetting issues a new code and invalidates the old one. No email reset, no backdoor.

## Stack
- **TUI (flagship):** Go + [Bubble Tea](https://github.com/charmbracelet/bubbletea)
  (Elm architecture) + Lip Gloss + `x/ansi`. Single static binary, no root. This is the
  portfolio's **first Go+Bubble Tea TUI** — a deliberate deviation from the house
  React/Spring stack, justified by the "single binary, no root" client requirement.
- **Web / backend / mobile (planned):** house stack — React+Vite (web), Java 21+Spring
  Boot with JWT/jjwt (backend), React Native+Expo (mobile), Kustomize+ArgoCD (deploy).

## Status
- **wharf-tui:** **usable SSH client** (v1 milestone done, 2026-07-14). Real SSH via
  `internal/sshx` (agent/keyfile/password/keyboard-interactive auth, skeema/knownhosts
  TOFU, detachable sessions with ring-buffer replay, full-screen takeover via
  `tea.Exec`, `ctrl+\` detach). Per-host auth mode — **key (default) or password**,
  form shows only the mode's field; password mode skips pubkey offers for
  strict-MaxAuthTries servers — with a **stored per-host password** in the vault
  (host form field, or `ctrl+r` "remember" in the password prompt; stored password
  replays silently, falls back to prompt on rejection; legacy ""/"auto" values read
  as key mode). Encrypted vault persistence (`internal/vault`,
  argon2id + XChaCha20-Poly1305, password + one-time recovery-code slots) with a
  typed store (`internal/store`). Host CRUD forms, `~/.ssh/config` import
  (`internal/sshcfg`), async reachability probes (`internal/probe`), ed25519 keygen +
  `~/.ssh` scan (`internal/keys`). `--demo` preserves the design prototype. Account
  sign-in/projects remain **simulated** pending `wharf-backend`. Roadmap next: port
  forwarding, sync client.

## Notable (stands out vs other projects)
- **Only Go + Bubble Tea TUI** in the portfolio (alongside Cosy's Go/Rust as non-house
  languages).
- **Local-first with an optional account** — unusual for these projects, which are
  normally account-gated.
- **Zero-knowledge** end-to-end encryption with a no-backdoor recovery-code model.

## Notes for agents
- The TUI follows the Elm architecture: `internal/ui/{model,update,view}.go` plus
  flow-specific files (`update_unlock.go`, `update_session.go`, `view_forms.go`, …).
  Color is stored as theme *roles* (resolved at render) so live theme switches recolor
  scrollback.
- `internal/ui/render_test.go` drives the model headlessly and dumps frames
  (`go test ./internal/ui -run TestDumpFrames -v`) — the fastest way to eyeball layout
  without a TTY (demo mode). `internal/ui/flows_test.go` covers the real-mode flows
  with injected fake vault hooks (`Config.OpenVault`/`CreateVault`).
- Engine contract: prompts (TOFU, secrets) arrive as `sshx` messages with buffered(1)
  `Reply` channels via `Manager.SetNotify(p.Send)` — reply exactly once.
  `Attach().Run()` returns nil on both detach and death; distinguish via
  `Manager.Get`/`SessionEndedMsg`. Session state lives in the Manager, never the Model.
- Multi-rune `tea.KeyMsg` (fast typing/paste) is split per-rune at the top of
  `handleKey` — don't add input handlers that assume single-rune messages elsewhere.
- `internal/sshx` tests run an in-process gliderlabs sshd; keep them race-clean
  (`go test -race ./internal/sshx/`).
