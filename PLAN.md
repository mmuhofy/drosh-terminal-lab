# PLAN — Drosh Terminal Lab

> Last updated: 2026-10-15 · Status: decisions settled, ready for implementation
> This document is alive: when a decision changes it is updated here with its
> rationale, the old decision is archived under "Decision history" with a
> "changed:" note — never silently deleted.

---

## 1. Purpose and scope

Prove Drosh's next terminal core (Rust core + chroot-ng + GL renderer) in a
**separate prototype application**, not inside Drosh. Once proven, the core
moves into Drosh as a module; integration is a separate plan and phase (§10).

The prototype is **terminal-only**: an app that renders a terminal, accepts
input, and runs an Ubuntu userspace under chroot-ng. The point is to measure
the core's performance and correctness in an environment free of Drosh's
complexity, and to retire the core's risks early.

## 2. Context: why are we changing?

Drosh today runs the vendored Termux engine (Java) with proot driving an
Ubuntu 24.04 userspace. Three fundamental bottlenecks (consistent with
Drosh's own PERFORMANCE-PLAN.md analysis):

1. **Guest runtime**: proot's per-syscall ptrace cost — meaningful slowdowns
   on syscall-heavy workloads (apt, compilation, package installs).
2. **Parse + frame policy**: the engine parses on the main thread, frames
   every chunk, and draws even when idle; no glyph atlas.
3. **Engine language**: the Java engine is adequate for most work, but the
   desktop-class architecture (IO thread + ring-buffer grid + damage + GPU
   atlas) is proven and available in Rust rather than something to rebuild in
   Java.

Mobile constraints (unchanged): `targetSdk 28` + F-Droid/sideload. Android
10+ blocks `execve()` on app-private files for `targetSdk >= 29` (W^X via
SELinux); the Ubuntu-under-PRoot model is permanently tied to 28. Play Store
is closed; the install floor will keep rising — known, accepted risk.

## 3. Decisions

| # | Area | Decision | Rationale / evidence |
|---|---|---|---|
| D1 | Guest runtime | **chroot-ng** instead of proot; vendored subtree, commit-pinned, patch branch | Ptrace-free (seccomp RET_TRAP + its own userland ELF loader); emulates Android SELinux barriers (pty ioctl whitelist, /proc stat, rtnetlink, SysV IPC); the author's on-device tests run apk/gcc/go+cgo build chains; Apache-2.0 |
| D2 | VT engine | **alacritty_terminal `=0.26.0`** as a dependency | The only serious core published to crates.io as a "library for writing terminal emulators"; 23 files/11.7K lines; ring-buffer grid + reflow + wide chars + alt screen + damage + selection; Apache-2.0, GPL-3.0 compatible. 0.x — version is **hard-pinned**, upgrades are deliberate |
| D3 | GL renderer | **Kotlin, GL ES 3.0** — NOT a port of Alacritty's renderer | The renderer is where mobile has the least leverage from desktop code (surface lifecycle, insets, foldables, Compose z-order all live in Kotlin); the snapshot bridge is zero-copy so a Rust renderer gains nothing measurable; iteration speed + debuggability (AGI/Perfetto). Plan-B trigger in §9 |
| D4 | Font stack | **Rust: rustybuzz (shaping) + swash (rasterize)**; atlas in Kotlin as bitmaps with 1x/1.5x/2x/3x size buckets | Android has no shaping API on the Kotlin side (no HarfBuzz access); Alacritty's glyph_cache/atlas design is the reference |
| D5 | Rust↔Kotlin bridge | **Snapshot** (not a render-command list): DirectByteBuffer (zero-copy) + per-line damage list + event queue | Simple and fast; with the renderer in Kotlin, Rust takes no unnecessary dependency |
| D6 | Input | Kotlin: full escape encoder + gesture vocabulary | xterm conventions (Alacritty `input/keyboard.rs` pattern); compatible with DroshKeyboard groundwork |
| D7 | Protocols | In: OSC 8 (hyperlink), OSC 133 (semantic markers), OSC 0/2 (title), OSC 4 (palette), OSC 10/11 (fg/bg), bell, bracketed paste (2004), focus (1004). **Deferred: sixel, kitty graphics** | Alacritty's engine has no sixel (the Termux engine did — a small, accepted regression); block/agent UI covers the practical ground; separate task if users ask |
| D8 | PTY implementation | **libc** path (NOT portable-pty) | portable-pty's `termios` dependency does not compile for Android — verified fact (also discovered independently by Termi) |
| D9 | Distribution (Drosh) | **targetSdk 28 + F-Droid/sideload** | Android 10+ exec ban on app data for targetSdk ≥ 29 (W^X, SELinux); the Ubuntu model is bound to 28; Termux's 7-year proven path. Play is closed; rising install floor is a known long-term risk |
| D10 | Termi (MannanSaood/termi) | Reference only; NOT a foundation | Its emulation is a toy (no alt screen, no wide chars, ~10 CSI actions), its UI is not fed from the native grid; taken from it: JNI discipline (opaque handles, panic hook, mirrored error codes) + PTY/OEM fallback lessons; upstream contributions considered where generic |
| D11 | Warp / kitty / WezTerm / Ghostty | Reading material, not code | Warp is AGPL (legal risk); kitty is C and not a library; wezterm-term is unpublished with explicitly no API stability; libghostty is Zig with a not-yet-stable API |
| D12 | Prototype scope | **No config system, no SSH** | The prototype is terminal-only with fixed defaults (the real Drosh has a TOML config system that stays there). SSH (SSHJ/FdPair) is fully deferred to the Drosh integration phase |
| D13 | Build & CI policy | **No local Rust/Gradle builds.** Builds run in GitHub Actions, Codespaces (SSH), or Colab. CI result awaited after every push (`gh run watch`). Commits in English, conventional style. CHANGELOG.md maintained. Device tests use CI-built APK artifacts | The dev box is not a build farm; remote CI keeps the gates reproducible; waiting on CI prevents "works on my machine" claims; see AGENTS.md §10 |

### Decision history

- 2026-10-15: Repo created with D1–D12; docs initially in Turkish.
- 2026-10-15: D12 updated — config and SSH removed from prototype scope
  (user decision: prototype is terminal-only; Drosh has TOML config).
- 2026-10-15: D13 added — no-local-builds CI policy, English commits,
  CHANGELOG discipline (user decision); all docs translated to English.
- 2026-10-15: GitHub repo `mmuhofy/drosh-terminal-lab` (public) created; CI
  workflow added (host fmt/clippy/test, Gate 1 cross-check, assembleDebug +
  APK artifact). Toolchain pinned: AGP 8.9.1 (compileSdk 36 requires
  AGP ≥ 8.9.1 — verified), Gradle 8.13, JDK 17, minSdk 26, targetSdk 28
  (D9), arm64-v8a. No wrapper jar is committed; CI uses the runner's Gradle
  or installs the pinned version.
- 2026-10-16: Phase A Rust core written. Two corrections to earlier
  assumptions, both verified against the sources rather than memory:
  1. §8 item 2 is wrong as written — alacritty's `tty::new` calls
     `ShellUser::from_env()` (and thus `getpwuid_r`) *before* it looks at an
     explicit shell, so that path is not "irrelevant when a shell is passed".
     It does not matter for us: `session/pty_factory.rs` does not use
     `alacritty_terminal::tty` at all (D8), it spawns the child itself.
  2. The engine's event-loop keys (`PTY_READ_WRITE_TOKEN` = 0,
     `PTY_CHILD_EVENT_TOKEN` = 1) are `pub(crate)`, so an external
     `EventedPty` implementation has to mirror the values. Recorded in
     TODO.md as re-check-on-upgrade.
  Also decided: the cell record is 16 bytes (codepoint u32, tagged fg/bg u32,
  frozen flag bits u16, reserved u16) — §5 said "codepoint + packed style",
  which cannot carry colors; the wire format is documented in
  `bridge/cell.rs` and frozen for the prototype.
  Rustfmt: `rust/rustfmt.toml` uses stable options only
  (`use_small_heuristics = "Max"`, as alacritty does) because CI runs stable
  rustfmt, which ignores nightly-only options.
- 2026-10-16: Phase A Kotlin thin shell written and green in CI (run
  <https://github.com/mmuhofy/drosh-terminal-lab/actions/runs/38870912218>).
  Four things worth recording:
  1. **Library name**: the crate is `drosh-core`, so Cargo produces
     `libdrosh_core.so` (a hyphen becomes an underscore) and
     `System.loadLibrary("drosh_core")` is what finds it. §4 and the README
     said `libdrosh-core.so`; corrected.
  2. **cargo-ndk is not used.** The runner image exports `ANDROID_NDK`,
     `ANDROID_NDK_ROOT` and `ANDROID_NDK_LATEST_HOME` pointing at its own NDK,
     and cargo-ndk 4.1.2 aborts on the disagreement with a pinned
     `ANDROID_NDK_HOME`. CI instead points cargo at the pinned NDK's clang/ar
     through `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER` and
     `CC_aarch64_linux_android` — one less tool, and the NDK version stays the
     one we install (r28c, `28.2.13676358`).
  3. **Phase A draws with an Android-baked ASCII atlas** (coverage, tinted by
     the shader) instead of the D4 font stack. D4 is unchanged: the atlas
     interface (uv per glyph index + cell metrics) is exactly what the Rust
     rasterizer will fill in Phase B behind Gate 2. Non-ASCII renders as `?`
     until then.
  4. **Input** is an invisible text editor whose `BaseInputConnection`
     intercepts committed text and deletions (the Termux recipe; sentinel
     variants break with predictive keyboards). D6 is unchanged — Phase B adds
     the escape encoder and the extra-keys row on top.
  Also verified while writing it: alacritty's palette constants quoted in the
  Kotlin palette (16 ANSI colors, primary fg/bg, `DIM_FACTOR = 0.66`, the
  16..231 cube and 232..255 gray ramp) were read from
  `alacritty/src/config/color.rs` and `alacritty/src/display/color.rs`.

## 4. Target architecture

```
┌──────────────────────────────────────────────────────────┐
│ Kotlin (Android)                                          │
│  app/      Activity, SurfaceView+EGL, session lifecycle   │
│  input/    escape encoder + touch gestures                │
│  renderer/ GL ES 3.0: atlas, instanced quads, damage loop │
│  ui/       Compose chrome: tab bar (does not draw grid!)  │
│  guest/    rootfs bootstrap + chroot-ng launch            │
└───────────────┬──────────────────────────────────────────┘
                │ JNI (zero-copy snapshot + event queue)
┌───────────────┴──────────────────────────────────────────┐
│ Rust core (cdylib "libdrosh_core.so")                     │
│  ffi/        session, snapshot, font, events              │
│  session/    Term + EventLoop + PTY (chroot-ng argv)      │
│  bridge/     Term → flat cell buffer + damage; EventListener│
│  font/       rustybuzz + swash → glyph bitmaps            │
└───────────────┬──────────────────────────────────────────┘
                │ PTY
┌───────────────┴──────────────────────────────────────────┐
│ chroot-ng (vendored, Apache-2.0) → Ubuntu 24.04 userspace │
└──────────────────────────────────────────────────────────┘
```

Data flow: chroot-ng → PTY → alacritty_terminal (parse + grid + damage) →
snapshot buffer → Kotlin GL renderer. Events (OSC/title/bell/cwd/exit) are
drained into Kotlin through a separate queue.

## 5. Rust side — file inventory (~19 files, ~4,700 lines)

| File | Lines | Responsibility |
|---|---|---|
| `core/src/lib.rs` | ~150 | cdylib entry, JNI_OnLoad, panic hook |
| `core/src/ffi/mod.rs` | ~300 | error code enum + Kotlin mirror + string/array helpers |
| `core/src/ffi/session.rs` | ~250 | create/spawn/write/resize/kill/close |
| `core/src/ffi/snapshot.rs` | ~200 | DirectByteBuffer + per-line damage list |
| `core/src/ffi/font.rs` | ~200 | shape+rasterize requests |
| `core/src/ffi/events.rs` | ~150 | event queue drain |
| `core/src/session/mod.rs` | ~300 | Session: Term + EventLoop + PTY + channels |
| `core/src/session/pty_factory.rs` | ~330 | LocalPty — posix_openpt + TIOCGPTPEER + login-tty child (setsid/TIOCSCTTY/dup2), OEM fallback |
| `core/src/session/manager.rs` | ~130 | handle table, session lifecycle |
| `core/src/bridge/snapshot.rs` | ~330 | Term → flat cell buffer + damage computation |
| `core/src/bridge/cell.rs` | ~210 | 16-byte cell record: codepoint u32, tagged fg/bg u32, frozen flag bits u16, reserved u16 |
| `core/src/bridge/events.rs` | ~300 | EventListener implementation → event queue |
| `core/src/font/mod.rs` | ~200 | FontStack: shape + rasterize + cache |
| `core/src/font/shape.rs` | ~200 | rustybuzz: cluster → glyph |
| `core/src/font/raster.rs` | ~250 | swash: alpha/color bitmaps |
| `core/src/font/atlas_api.rs` | ~150 | glyph records for the Kotlin atlas (UV, metrics) |
| `core/src/font/fallback.rs` | ~150 | font fallback chain + emoji path |
| `core/src/util/*` | ~200 | log, sync, error helpers |
| `tests/` (3 files) | ~700 | golden VT + snapshot + PTY end-to-end |

Note: chroot-ng is C (41K lines), not Rust — it lives as a vendored subtree
and is not part of this inventory.

## 6. Kotlin side

| Module | Files (est.) | Responsibility |
|---|---|---|
| `android/app/` | 4–5 | Activity, SurfaceView/EGL host, session management, JNI bindings |
| `android/renderer/` | 5–6 | atlas (size buckets), shader, instanced quads, rects, render loop (Choreographer + damage) |
| `android/input/` | 3–4 | escape encoder, extra-keys row, gestures (select/scroll/zoom), clipboard |
| `android/guest/` | 2–3 | rootfs download/verify/extract, chroot-ng launch |

Phase A ships all of this inside the single `android/app` module (packages
`dev.drosh.lab.core`, `.render`, `.input`); the module split happens in Phase B,
when the renderer and input directories have enough code to be worth building
separately.

## 7. Phases

All gates and builds run in CI (D13). Local runs are prohibited. Device
acceptance uses the CI-built APK artifact.

### Phase A — Get the engine onto Android (~1 week)

**Goal:** prove alacritty_terminal compiles for Android and parses a real PTY
session.

- Repo skeleton: cargo workspace + android/ + bench/; git init (done)
- Android build: arm64-v8a, release (LTO, strip, panic=abort); CI links the
  core with the pinned NDK toolchain (2026-10-16: cargo-ndk is *not* used, see
  the decision history) and asserts the APK carries `libdrosh_core.so`
- GitHub Actions: cargo check (host + aarch64-linux-android), clippy `-D
  warnings`, fmt check, cargo test, gradle assembleDebug
- **Gate 1 (CI):** `cargo check --target aarch64-linux-android` — does
  alacritty_terminal 0.26.0 compile with rustix-openpty, polling, vte 0.15,
  jni, rustybuzz, swash? Result recorded in TODO.md
- `ffi/` + `session/` + `bridge/` implementation (Plan-B: if rustix-openpty
  fails, the tty layer moves to raw libc)
- PTY: spawn `/system/bin/sh` with raw libc (posix_openpt + TIOCGPTPEER +
  login-tty child; Termi's OEM fallback: slave-stdio path when the login-tty
  spawn fails)
- Kotlin thin shell (implemented 2026-10-16): `GLSurfaceView` + GL ES 3.0
  renderer fed straight by the snapshot buffer, keyboard input through an input
  connection, cursor blink and drag-to-scroll — the goal is proving the data
  path, not quality. The Phase A glyphs come from an ASCII atlas baked with
  Android's monospace typeface; Phase B replaces its source with the Rust font
  stack (D4) behind the same interface.
- Host tests: cargo test (golden VT, snapshot, PTY end-to-end)

**Acceptance:** on a physical device (CI-built APK artifact) `echo hello` /
`ls` output renders from the snapshot; `cargo test` green in CI; Gate 1
passed in CI.

### Phase B — Renderer + input (~2 weeks)

**Goal:** terminal-quality rendering and complete mobile input.

- `font/` implementation: rustybuzz + swash, alpha bitmaps + metrics
- **Gate 2 (CI):** swash compiles for Android and produces glyph bitmaps;
  color-emoji path decided (swash CBDT/COLR → atlas; otherwise Android native
  text overlay) and recorded in TODO.md
- Kotlin renderer: atlas (1x–3x buckets), shader, instanced quads, bg/cursor
  rects
- Render loop: Choreographer + damage-limited redraw + frame-skip policy
- Input: escape encoder (Ctrl/Alt/Shift, arrows, Home/End, PgUp/PgDn, F1–F12,
  Esc, Tab/Shift-Tab, DEL/BS), bracketed paste, extra-keys row
- Gestures: tap, double-tap (word), long-press (selection mode), drag extend,
  pinch zoom (row anchoring), momentum scroll
- `bench/`: frame p50/p99 + jank counter overlay

**Acceptance:** frame p99 < 8 ms; no jank under full-screen output; crisp
pinch zoom; select/copy/paste working. If targets are missed, §9 Plan-B
(renderer port) is evaluated **with the measured data**.

### Phase C — chroot-ng + Ubuntu (~1–2 weeks)

**Goal:** replace proot with chroot-ng; prove guest fidelity + performance.

- `rust/chroot-ng/` vendoring (commit-pin) + CI cross-build + patch branch
- **Gate 3 (device, CI-built APK):** `chroot-ng --probe` — seccomp/execmem/
  noexec viability; file-backed loading mode must work when execmem is denied
- `android/guest/`: rootfs download + verify + extract (adapted from Drosh)
- `pty_factory.rs`: chroot-ng argv mapping (`-u`, `-w`, `-b`, `-E`)
- Guest fidelity checklist: `apt update && apt install -y
  build-essential`, tmux, Ctrl+C/Z, resize (SIGWINCH), `su`, `ps`, shebang
  scripts, setuid tools, two heavy-output runs (apt install, go build)
- Benchmark: identical workloads vs Termux (proot reference) on the same
  device — apt install / go build / file-heavy work

**Acceptance:** fidelity checklist complete; benchmark gains documented in
PLAN.md.

### Phase D — Packaging + polish (~1 week)

- Release build: `.so` size ≤ 8 MB (measured), arm64-v8a
- README updated (screenshots + benchmark results)
- AGENTS.md review (lessons from Phases A–C folded into rules)
- Prototype verdict report → Drosh integration go/no-go

## 8. Open verification items

These are **not yet verified** claims; test them in order before trusting
them (AGENTS.md rule 2):

1. alacritty_terminal 0.26.0 cross-compiles for `aarch64-linux-android`
   (rustix-openpty + polling + vte 0.15) — Phase A Gate 1 (passed 2026-10-15)
2. Alacritty's `tty/unix.rs` default-shell/getpwuid path — **corrected
   2026-10-16**: `from_env()` runs even when an explicit shell is passed, so
   that path is *not* irrelevant for callers of `tty::new`. The prototype
   sidesteps it by not using `alacritty_terminal::tty` (D8): `pty_factory.rs`
   spawns the child itself.
3. swash compiles for Android and produces color-emoji (CBDT/COLR) bitmaps —
   Phase B Gate 2; otherwise the emoji overlay path
4. chroot-ng probe result + file-backed mode — Phase C Gate 3
5. GL ES 3.0 available on target devices (fallback: GLES2 path). The Phase A
   shell requests a 3.0 context (D3) and logs `GL_VERSION`; an unsupported
   device would show a black screen plus a toast instead of glyphs. Unverified
   until a device run.

## 9. Risks and Plan-B triggers

| Risk | Likelihood | Plan B / trigger |
|---|---|---|
| alacritty_terminal fails to compile for Android | Low | Move the tty layer to raw libc (Termi's path); the engine itself is pure Rust |
| Kotlin renderer misses quality/perf targets (p99/jank/zoom) | Medium | **At the end of Phase B, with measured data**, evaluate porting Alacritty's renderer to Rust — no port without measurements |
| chroot-ng inconsistent on OEM kernels | Medium | proot fallback flag; device-matrix bug report + repro to chroot-ng upstream |
| swash quality/perf insufficient | Medium | freetype-rs + NDK build (Alacritty's desktop path) |
| Guest fidelity gaps | Medium | pty_factory adjustments + upstream patches; checklist grows in TODO.md |
| targetSdk 28 strategy stalls long-term (install floor) | Low-Med | At Drosh level: Bionic userland + termux-exec migration (separate strategy session) |

## 10. Drosh integration (next phase — outside this plan)

After the prototype is proven, under a separate plan:

1. Move the core into Drosh as a `:terminal-core` module
2. Wire the block engine to OSC 133 events (the semantic layer already exists
   in Kotlin)
3. Wire the agent shell tool to the new session factory
4. Retire/remove Drosh's vendored Termux engine
5. Integrate with DroshKeyboard (the mature form of the prototype's encoder)
6. Keep the targetSdk/distribution strategy unchanged

## 11. Ways of working

All development and agent behavior in this repo is governed by
[`AGENTS.md`](AGENTS.md). Summary: nothing is invented, claims are verified
before they are written, gates are not passed without CI, docs stay in sync,
no flattery, necessary information is requested from the user, and **no local
Rust/Gradle builds — everything goes through CI, Codespaces, or Colab**.
