# Drosh Terminal Lab

A sandbox repository for proving the **next terminal core** of the Drosh
Android terminal **outside of Drosh itself**, in a separate minimal prototype
application.

The goal: prove that the Rust core (`alacritty_terminal` 0.26.0) + `chroot-ng`
guest runtime + Kotlin GL ES renderer stack actually works and is fast on
Android, with measurements. Once proven, the core moves into Drosh as a module.

## Status

🚧 **Phase A — the Rust core and the Kotlin thin shell are implemented; CI is
the verification.** PTY + `alacritty_terminal` + the snapshot bridge live in
`rust/core` (with host tests), and `android/app` renders the snapshot on GL ES
3.0 with keyboard input. What is left in Phase A is **device acceptance**: the
CI-built APK has to be installed on a real device and show `echo hello` / `ls`.

Device test recipe: open the latest green run of
[CI](https://github.com/mmuhofy/drosh-terminal-lab/actions/workflows/ci.yml),
download the `debug-apk` artifact, unzip it, install `app-debug.apk` on the
device (debug-signed — allow "install unknown apps" for the file manager), tap
the terminal, and type a command. If something misbehaves, `adb logcat -s
DroshLab` shows the core version, the grid size, `GL_VERSION` and every
failure; the same failures also appear as toasts.

The documents:

| Document | Contents |
|---|---|
| [`PLAN.md`](PLAN.md) | Decisions (D1–D13), architecture, file inventory, phases, risks |
| [`TODO.md`](TODO.md) | Phase task list, gates, acceptance criteria |
| [`AGENTS.md`](AGENTS.md) | Rules for agents and developers working in this repo |
| [`CHANGELOG.md`](CHANGELOG.md) | Change history (Keep a Changelog) |

[![CI](https://github.com/mmuhofy/drosh-terminal-lab/actions/workflows/ci.yml/badge.svg)](https://github.com/mmuhofy/drosh-terminal-lab/actions/workflows/ci.yml)

## Repository layout

```
drosh-terminal-lab/
├── rust/
│   ├── chroot-ng/        vendored subtree (Apache-2.0, commit-pinned)
│   └── core/             cdylib "libdrosh_core.so" — the Rust core
├── android/
│   ├── app/              thin shell: Activity, SurfaceView/EGL, sessions
│   ├── renderer/         GL ES 3.0 terminal renderer (Kotlin)
│   ├── input/            escape encoder + gestures (Kotlin)
│   └── guest/            Ubuntu rootfs bootstrap + chroot-ng launch
└── bench/                frame/parse + guest runtime benchmarks
```

## What this is NOT

- Not Drosh itself — block engine, agent, SSH host management, theme store,
  PIN lock, i18n are **out of scope**.
- No configuration system: it runs on fixed, sensible defaults (the real Drosh
  has a TOML config system, which stays there).
- SSH is out of scope entirely (no SSHJ integration, no FdPair trait).

## Build policy (read first)

**No local Rust or Gradle builds.** Everything compiles in CI:

- **GitHub Actions** — every push; gates (compile checks, clippy, fmt, tests,
  APK assembly) run here
- **GitHub Codespaces** (via SSH) — when interactive work is needed
- **Google Colab** — alternative remote environment

After every push, the CI result is awaited with the GitHub CLI:

```sh
gh run watch            # or: gh run view <run-id> --log-failed
```

Device testing uses the **CI-built APK artifact**: download it from the
Actions run, install on the device, test, and record the result (device +
kernel info) in `TODO.md`. See [`AGENTS.md`](AGENTS.md) §10 for the full
policy.

## License

Shipped under the same license as Drosh (GPL-3.0). Dependencies:
`alacritty_terminal` and `chroot-ng` are Apache-2.0.
