# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/mmuhofy/drosh-terminal-lab/compare/v0.0.0...HEAD
