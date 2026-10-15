# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-10-16

### Added

- Repository scaffolding for the Drosh terminal core prototype: workspace
  layout (`rust/core`, `rust/chroot-ng`, `android/{app,renderer,input,guest}`,
  `bench`).
- `PLAN.md` — architecture and decisions D1–D13, phase plan (A–D) with gates
  and acceptance criteria, risk/Plan-B matrix.
- `TODO.md` — phase task list with CI gates and acceptance criteria.
- `AGENTS.md` — working rules including the no-local-builds CI policy
  (GitHub Actions / Codespaces / Colab), English conventional commits, and
  CHANGELOG discipline.
- `README.md` — project overview, scope boundaries (no config, no SSH), and
  build policy summary.
- Per-directory README files describing each module's future contents.
- Rust workspace with pinned dependencies (`alacritty_terminal =0.26.0`,
  `jni =0.21`) and release profile (LTO, strip, panic=abort).
- Android Gradle skeleton: AGP 8.9.1 / Gradle 8.13 / JDK 17 / compileSdk 36 /
  minSdk 26 / targetSdk 28 / arm64-v8a.
- GitHub Actions CI: host fmt/clippy/test, Gate 1 Android cross-check, and
  `assembleDebug` with the debug APK published as an artifact for device
  testing.
- `.opencode/` configuration for agents working in this repo.

### Verified

- **Gate 1 passed (2026-10-15):** `alacritty_terminal 0.26.0` + `jni 0.21`
  cross-compile and pass clippy for `aarch64-linux-android`
  (CI run 38807651540 on `main`).

[Unreleased]: https://github.com/mmuhofy/drosh-terminal-lab/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/mmuhofy/drosh-terminal-lab/releases/tag/v0.1.0
