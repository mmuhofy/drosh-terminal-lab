# Third-party notices

What this repository ships and what each piece is licensed under. Everything
listed here is permissive (MIT / Apache-2.0 / OFL-1.1) and therefore compatible
with the project's own GPL-3.0 (`LICENSE`).

## Distributed in the APK

| Component | Version | License | Where |
|---|---|---|---|
| This project | — | **GPL-3.0** (`LICENSE`) | the app and the Rust core |
| JetBrains Mono NL | 2.304 | **SIL OFL-1.1** | `android/app/src/main/assets/fonts/` — the license text ships inside the APK next to the fonts, as OFL requires |

## Linked into `libdrosh_core.so`

Licenses read from each crate's own metadata (crates.io API, 2026-10-16):

| Crate | Version | License |
|---|---|---|
| `alacritty_terminal` | 0.26.0 (pinned) | Apache-2.0 |
| `vte` | 0.15.0 | Apache-2.0 OR MIT |
| `rustix-openpty` | 0.2.0 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| `jni` | 0.21.1 | MIT/Apache-2.0 |
| `libc` | 0.2.x | MIT OR Apache-2.0 |
| `log` | 0.4.x | MIT OR Apache-2.0 |
| `parking_lot` | 0.12.x | MIT OR Apache-2.0 |
| `polling` | 3.x | Apache-2.0 OR MIT |
| `signal-hook` | 0.4.x | MIT OR Apache-2.0 |
| `rustybuzz` | 0.20.x | MIT |
| `swash` | 0.2.x | Apache-2.0 OR MIT |
| `ttf-parser` | 0.25.x | MIT OR Apache-2.0 |
| `zeno` | 0.3.x | Apache-2.0 OR MIT |
| `unicode-width` | 0.2.x | MIT OR Apache-2.0 |

Apache-2.0 requires its license text and a notice of changes to be preserved
when the code is redistributed; the vendored subtrees below keep theirs. Where a
crate offers a choice, this project takes the Apache-2.0 option for the
Apache-2.0-or-MIT crates so that a single notice covers them.

## Vendored / planned

| Component | Version | License | Note |
|---|---|---|---|
| `chroot-ng` | commit-pinned (Phase C) | Apache-2.0 | vendored subtree; its own `LICENSE` stays inside the subtree |

## Build-time only (not distributed)

Gradle/AGP, the Kotlin compiler, the Android NDK toolchain and the Rust
toolchain are used to produce the artifacts and are not part of them. Their
licenses therefore do not attach to the APK, but they are all permissive or
Apache-2.0 with the usual exceptions.
