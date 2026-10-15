# TODO — Drosh Terminal Lab

> Plan: [`PLAN.md`](PLAN.md) · Order: Phase A → B → C → D
> Rules:
> - Every phase starts with a **Gate**; the gate runs in CI (D13). No gate, no
>   phase tasks.
> - A task abandoned unfinished stays here as "not done + why" — never silently
>   deleted.
> - If a gate fails, a Plan-B item is added here and reported to the user
>   (never proceed on assumptions).
> - No local Rust/Gradle builds: run gates via GitHub Actions, Codespaces
>   (SSH), or Colab. Device acceptance uses the CI-built APK artifact.

---

## Phase 0 — Repo skeleton

- [x] `drosh-terminal-lab` folder + docs (README, PLAN, TODO, AGENTS)
- [x] git init + first commit
- [x] `.gitignore` (Rust + Android + Gradle + IDE)
- [x] Folder READMEs: `rust/core/`, `rust/chroot-ng/`, `android/app/`,
      `android/renderer/`, `android/input/`, `android/guest/`, `bench/`
- [x] `CHANGELOG.md` (Keep a Changelog format)
- [ ] `rust/` cargo workspace (profiles: LTO, strip, panic=abort)
- [ ] `android/` Gradle skeleton (empty app module, minSdk 26, ndk arm64-v8a)
- [ ] GitHub Actions workflow: `cargo check` (host + aarch64-linux-android),
      `cargo clippy -- -D warnings`, `cargo fmt --check`, `cargo test`,
      `./gradlew assembleDebug` — on every push
- [ ] Branch strategy confirmed with user (e.g. `main` + `feat/phase-a-*`)

## Phase A — Get the engine onto Android (~1 week)

**Goal:** prove alacritty_terminal compiles for Android and parses a real PTY
session. (Details: PLAN.md §7-Phase A)

### Gate 1 (in CI, before phase tasks)
- [ ] CI job: `cargo check --target aarch64-linux-android` with
      alacritty_terminal =0.26.0, rustix-openpty, polling, vte 0.15, jni,
      rustybuzz, swash — does it compile?
- [ ] Result recorded here (compiled / which dependency failed)
- [ ] CI run watched to completion (`gh run watch`), status reported

### Rust core
- [ ] `core/Cargo.toml`: cdylib + pinned dependencies
- [ ] `core/src/lib.rs`: cdylib entry, JNI_OnLoad, panic hook
- [ ] `core/src/ffi/mod.rs`: error code enum + Kotlin mirror + helpers
- [ ] `core/src/session/manager.rs`: Session (Term + EventLoop), handle table
- [ ] `core/src/session/pty_factory.rs`: LocalPty — libc forkpty + setsid,
      OEM fallback (slave-stdio when login-tty spawn fails)
- [ ] `core/src/session/mod.rs`: spawn/write/resize/kill/close lifecycle
- [ ] `core/src/ffi/session.rs`: JNI functions
- [ ] `core/src/bridge/cell.rs`: cell encoding (codepoint u32 + style u32)
- [ ] `core/src/bridge/snapshot.rs`: Term → flat cell buffer + damage list
- [ ] `core/src/ffi/snapshot.rs`: DirectByteBuffer + damage indices
- [ ] `core/src/bridge/events.rs`: EventListener (title/bell/cwd/exit minimum)
- [ ] `core/src/ffi/events.rs`: event queue drain
- [ ] `core/src/util/*`: log, sync, error

### Kotlin thin shell
- [ ] `android/app/`: MainActivity + SurfaceView (EGL host) + JNI bindings
- [ ] Snapshot read + minimal line drawing (plain text drawing is fine; the
      goal is proving the data path end to end)

### Tests
- [ ] `tests/session_tests.rs`: host `/bin/sh` + echo/ls end-to-end
- [ ] `tests/snapshot_tests.rs`: grid → buffer correctness, damage boundaries
- [ ] CI: cargo test green on every push

### Acceptance
- [ ] On a physical device (CI-built APK artifact): `echo hello` / `ls`
      renders from the snapshot — device + kernel recorded here
- [ ] `cargo test` green in CI
- [ ] Gate 1 passed in CI

## Phase B — Renderer + input (~2 weeks)

### Gate 2 (in CI)
- [ ] swash compiles for Android and produces glyph bitmaps (alpha)
- [ ] Color-emoji path decision: swash CBDT/COLR → atlas, otherwise Android
      native text overlay — decision recorded here

### Rust font stack
- [ ] `font/shape.rs`: rustybuzz cluster → glyph
- [ ] `font/raster.rs`: swash rasterize (alpha + metrics)
- [ ] `font/atlas_api.rs`: Kotlin atlas record format (UV, metrics)
- [ ] `font/fallback.rs`: fallback chain (ASCII → Latin → CJK → emoji)
- [ ] `font/mod.rs`: FontStack + cache
- [ ] `ffi/font.rs`: JNI shape/rasterize requests

### Kotlin renderer
- [ ] `renderer/atlas.kt`: bitmap atlas + 1x/1.5x/2x/3x size buckets
- [ ] `renderer/shader.kt`: GLES3 instancing (vertex + quad shader)
- [ ] `renderer/quads.kt`: instanced quad batch (glyphs + bg/cursor rects)
- [ ] `renderer/loop.kt`: Choreographer + damage-limited redraw + frame skip
- [ ] Cursor: blink + shape (block/bar) without full redraws

### Kotlin input
- [ ] `input/encoder.kt`: xterm conventions — Ctrl/Alt/Shift, arrows,
      Home/End, PgUp/PgDn, F1–F12, Esc, Tab/Shift-Tab, DEL/BS
- [ ] `input/keys.kt`: extra-keys row (Ctrl+C/D/Z, Tab, arrows)
- [ ] `input/paste.kt`: bracketed paste (mode 2004)
- [ ] `input/gestures.kt`: tap, double-tap (word), long-press (selection),
      drag extend, pinch zoom (row anchoring), momentum scroll

### Benchmarks
- [ ] `bench/`: frame p50/p99 + jank counter overlay

### Acceptance
- [ ] frame p99 < 8 ms
- [ ] no jank under full-screen output
- [ ] crisp pinch zoom; select + copy + paste working
- [ ] If targets are missed: evaluate the Alacritty renderer port Plan-B with
      the measured data

## Phase C — chroot-ng + Ubuntu (~1–2 weeks)

### Gate 3 (device, CI-built APK)
- [ ] On device: `chroot-ng --probe` — seccomp/execmem/noexec viability
- [ ] execmem denied → file-backed loading mode works
- [ ] Device + kernel recorded here

### Vendoring + launch
- [ ] `rust/chroot-ng/` vendoring: commit-pin + patch branch setup
- [ ] CI cross-build (aarch64) + packaging into the APK
- [ ] `pty_factory.rs`: chroot-ng argv mapping (`-u`, `-w`, `-b`, `-E`)
- [ ] `android/guest/`: rootfs download + verify + extract (from Drosh)

### Guest fidelity checklist
- [ ] `apt update && apt install -y build-essential`
- [ ] tmux: open/close, split pane
- [ ] Ctrl+C / Ctrl+Z / Ctrl+D behavior
- [ ] Resize (SIGWINCH) — rotation + renderer resize
- [ ] `su` / setuid tools
- [ ] `ps` (guest process view)
- [ ] shebang scripts (postinst-style)
- [ ] two heavy outputs: apt install + go build

### Benchmark
- [ ] Identical workloads vs Termux (proot reference) on the same device:
      apt install / go build / file-heavy work timings
- [ ] Results recorded in PLAN.md

### Acceptance
- [ ] fidelity checklist complete
- [ ] benchmark gains documented

## Phase D — Packaging + polish (~1 week)

- [ ] Release build: `.so` size ≤ 8 MB (measured), arm64-v8a
- [ ] README updated (screenshots + benchmark results)
- [ ] AGENTS.md review (lessons from Phases A–C)
- [ ] Prototype verdict report → Drosh integration go/no-go

## Next (Drosh integration — separate plan, not this repo yet)

- [ ] Move core into Drosh as `:terminal-core` module
- [ ] Wire block engine to OSC 133 events
- [ ] Wire agent shell tool to the new session factory
- [ ] SSH (SSHJ → remote PTY) — Drosh integration phase
- [ ] Retire Drosh's vendored Termux engine
- [ ] DroshKeyboard integration
