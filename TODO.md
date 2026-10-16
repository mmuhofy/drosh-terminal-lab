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
- [x] `rust/` cargo workspace (profiles: LTO, strip, panic=abort) +
      `rust/core` cdylib with pinned deps (alacritty_terminal =0.26.0, jni
      =0.21, libc, log, parking_lot)
- [x] `android/` Gradle skeleton (AGP 8.9.1 / Gradle 8.13 / JDK 17 /
      compileSdk 36 / minSdk 26 / targetSdk 28 / arm64-v8a)
- [x] GitHub Actions `.github/workflows/ci.yml` — rust-host (fmt, clippy,
      test), Gate 1 (aarch64-linux-android cross-check), android-build
      (assembleDebug + APK artifact)
- [x] `.opencode/` (opencode.json + gitignored install dir)
- [x] GitHub repo `mmuhofy/drosh-terminal-lab` (public) + remote + first push
- [ ] Branch strategy in use: `main` (protected) + phase branches
      (`feat/phase-a-*`, `chore/phase0-*`) — merge only with CI green

## Phase A — Get the engine onto Android (~1 week)

**Goal:** prove alacritty_terminal compiles for Android and parses a real PTY
session. (Details: PLAN.md §7-Phase A)

### Gate 1 (in CI, before phase tasks)
- [x] CI job: `cargo check --target aarch64-linux-android` — does the Phase A
      dependency set cross-compile: alacritty_terminal =0.26.0 (pulls
      rustix-openpty, polling, vte 0.15) + jni? (rustybuzz/swash join at
      Gate 2 — Phase B)
- [x] **Result: PASSED (2026-10-15)** — run
      <https://github.com/mmuhofy/drosh-terminal-lab/actions/runs/38807056087>;
      cross-check + clippy for aarch64-linux-android green. All three CI jobs
      green (host fmt/clippy/test, Gate 1, assembleDebug).
- [x] CI run watched to completion (`gh run watch`), status reported

### Rust core
- [x] `core/Cargo.toml`: cdylib + rlib (integration tests) + pinned
      dependencies (alacritty_terminal =0.26.0, jni =0.21, libc, log,
      polling 3.8, signal-hook 0.4.3 — the last two matching the engine's own
      versions so the tree keeps one copy)
- [x] `core/src/lib.rs`: cdylib entry, JNI_OnLoad, panic hook, nativeVersion
- [x] `core/src/ffi/mod.rs`: error code enum + helpers + frozen-value test
- [x] `core/src/session/manager.rs`: handle table (ids from 1, max 8)
- [x] `core/src/session/pty_factory.rs`: LocalPty — posix_openpt +
      TIOCGPTPEER + login-tty child (setsid/TIOCSCTTY/dup2), slave-stdio OEM
      fallback, SIGCHLD self-pipe, EventedPty/OnResize for the engine loop,
      `IUTF8` on the master (backspace erases a whole multibyte character)
- [x] `core/src/session/mod.rs`: Session lifecycle (spawn/write/resize/scroll/
      shutdown), lock order term → damage_scratch → damage
- [x] `core/src/ffi/session.rs`: JNI functions
- [x] `core/src/bridge/cell.rs`: 16-byte cell record (codepoint, tagged fg/bg,
      frozen flag bits) + unit tests
- [x] `core/src/bridge/snapshot.rs`: Term → flat cell buffer + damage list
      (viewport rows, scroll-back ⇒ full redraw) + state array
- [x] `core/src/ffi/snapshot.rs`: direct ByteBuffer + damage/state IntArrays
- [x] `core/src/bridge/events.rs`: EventProxy/EventQueue (PTY replies handled
      inline, the rest queued)
- [x] `core/src/ffi/events.rs`: wakeup flag + event record drain
- [x] `core/src/util/*`: logging (no extra dependency)

### Kotlin thin shell
- [x] `android/app/`: MainActivity + SurfaceView (EGL host) + JNI bindings
      (mirror `ErrorCode`, the cell record and the event records from
      `bridge/`)
- [x] Snapshot read + minimal line drawing (plain text drawing is fine; the
      goal is proving the data path end to end)
- [x] **CI green (2026-10-16)**, run
      <https://github.com/mmuhofy/drosh-terminal-lab/actions/runs/38870912218>:
      `:app:compileDebugKotlin` (Kotlin 2.4.0), `assembleDebug`, and with it the
      whole Android job — SDK setup, NDK install, the arm64-v8a core build, and
      the APK content assertion.
- [x] Merged to `main` (squash `96d88b0`); `main` CI green on the merge, run
      <https://github.com/mmuhofy/drosh-terminal-lab/actions/runs/38872213752>
      — host fmt/clippy/test, Gate 1 and the Android job all passed
- [x] The Rust core reaches the APK: CI links `libdrosh_core.so` (662,736
      bytes, release/LTO/strip/panic=abort) with the pinned NDK
      `28.2.13676358`, copies it into `src/main/jniLibs/arm64-v8a/` and fails
      the run unless `unzip -l` shows `lib/arm64-v8a/libdrosh_core.so` in it —
      an APK without the .so would otherwise only fail on the device. The
      `debug-apk` artifact is 1,257,538 bytes.
- What the shell does today (all of it Phase A scope, no quality work):
  - `render/`: GL ES 3.0, one interleaved vertex buffer and one draw call per
    frame; glyph quads from an ASCII atlas baked once from the monospace
    typeface; solid quads for non-default backgrounds, underlines, strikeout
    and all cursor shapes; fake-bold by a one-pixel second pass.
  - `core/`: the handle plus the buffers Kotlin owns (D5) — `nativeSnapshot`
    is called on every frame, so the buffer is always current even though the
    renderer ignores the damage list.
  - `input/`: an invisible text editor (`BaseInputConnection`) that sends
    committed text and DEL; Enter/backspace work from the soft keyboard.
  - MainActivity: wakeup-flag polling every 32 ms, cursor blink every 500 ms,
    drag scrolls the scrollback, tap opens the keyboard, and GL/session
    failures surface as toasts (so a device test can be diagnosed without
    adb).

### Tests
- [x] `tests/session_tests.rs`: host `/bin/sh` echo + `stty size` (PTY winsize)
      + child exit code + manager handles + `IUTF8` set on the line discipline
- [x] `tests/snapshot_tests.rs`: grid → buffer correctness, damage boundaries,
      resize ⇒ full redraw, undersized buffers rejected
- [x] **CI green (2026-10-16)**, run
      <https://github.com/mmuhofy/drosh-terminal-lab/actions/runs/38815576100>:
      `cargo fmt --check`, `clippy -D warnings` (host `--all-targets` and
      `aarch64-linux-android`), `cargo check --target aarch64-linux-android`
      and `cargo test` — **27 tests, 0 failed** (16 unit, 6 snapshot, 5 session
      with a real PTY). The Android cross-check compiling *and* passing clippy
      is the important half: the engine, our PTY code and the JNI layer all
      build for `aarch64-linux-android`.
- [x] Re-verified after the `IUTF8` change (2026-10-16), run
      <https://github.com/mmuhofy/drosh-terminal-lab/actions/runs/38873799259>:
      **28 tests, 0 failed** (16 unit, 6 snapshot, 6 session with a real PTY).
      The cross-target clippy pass is what proves `libc::IUTF8` and the bionic
      `termios` layout are right for Android, not just for the host.
- [ ] Still unverified: anything that needs a device. The JNI binding shape is
      written (`object NativeCore` + `external fun`, the pattern Termi runs on
      device; the Rust side ignores the receiver), and CI proves the `.so` is
      inside the APK — a device run has to confirm that `System.loadLibrary`
      finds it and that the grid actually paints.

### Phase A open items (recorded, not silently dropped)
- [ ] Evented event keys: `alacritty_terminal`'s `PTY_READ_WRITE_TOKEN` (0) and
      `PTY_CHILD_EVENT_TOKEN` (1) are private; `pty_factory.rs` mirrors them.
      Re-verify on every engine upgrade.
- [ ] `color_query` records are queued but nobody answers them yet: Kotlin owns
      the palette, so the answer belongs in the Kotlin shell (vim/`OSC 11`
      background detection waits for a reply until it times out).
- [ ] OSC 52 clipboard events are ignored on purpose (Phase B: `input/paste.kt`
      + clipboard bridge).
- [ ] Zerowidth (combining) characters are not in the cell record yet; Phase B
      shapes text in Rust and adds a glyph reference.
- [ ] The Kotlin mirror has no tests of its own: the frozen values (error
      codes, cell offsets, colour tags, flag bits, state indices) are guarded
      by the Rust-side frozen-value test plus review. A JVM unit test asserting
      the same table would catch drift on the Kotlin side.
- [ ] The atlas is ASCII-only (the D4 stand-in): every other codepoint renders
      as `?`, and a wide character occupies two cells without being shaped.
      Phase B (Gate 2) replaces the source of the bitmap, not the interface.
- [ ] The renderer redraws the whole grid every frame; the damage list is
      filled by the bridge but unused until Phase B's damage-limited loop.
- [ ] `color_query` (OSC 4/10/11) answers still have no owner: the palette
      lives in Kotlin, so the reply belongs there (Phase B, with the encoder).
- [ ] `PS1="$ "` is passed through the environment so the prompt is visible
      without relying on mksh's rc files; Phase C's guest shell takes over.
- [ ] Cursor blink is a fixed 500 ms toggle and the poll interval is a fixed
      32 ms — no config (D12), no Choreographer (Phase B).
- [ ] Device acceptance below needs a physical device and the CI-built APK.

### Acceptance
- [ ] On a physical device (CI-built APK artifact): `echo hello` / `ls`
      renders from the snapshot — device + kernel recorded here.
      The APK is attached to the `phase-a-device-test` pre-release
      (<https://github.com/mmuhofy/drosh-terminal-lab/releases/tag/phase-a-device-test>);
      the release body names the commit, the CI run and the APK's SHA-256, so
      the hash lives with the binary instead of drifting from it.
      `adb install app-debug.apk`, or copy it to the device and open it (it is
      debug-signed, so "install unknown apps" must be allowed for the file
      manager). The artifact on the run page needs an authenticated session;
      the release asset does not. If something looks wrong,
      `adb logcat -s DroshLab` matters: the app logs the core version, the
      grid size, `GL_VERSION` and every failure, and shows a toast for each
      failure.
- [x] `cargo test` green in CI (28 tests, run 38873799259)
- [x] `assembleDebug` green in CI and the APK carries `libdrosh_core.so`
      (run 38870912218)
- [x] Gate 1 passed in CI

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
