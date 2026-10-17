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
| D7 | Protocols | In: OSC 8 (hyperlink), OSC 133 (semantic markers), OSC 0/2 (title), OSC 4 (palette), OSC 10/11 (fg/bg), OSC 52 (clipboard), bell, bracketed paste (2004), focus (1004), mouse reporting (1000/1002/1003/1006/1015). **Deferred: sixel, kitty graphics, kitty keyboard (`CSI u`)** | Alacritty's engine has no sixel (the Termux engine did — a small, accepted regression); block/agent UI covers the practical ground; separate task if users ask |
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
- 2026-10-16: **`alacritty_terminal::tty` evaluated, deliberately not used.**
  Compilation is *not* the reason: `pub mod tty` is unconditional in 0.26.0
  (`alacritty_terminal/src/lib.rs:15`), so Gate 1 has compiled `tty/unix.rs`
  for `aarch64-linux-android` all along. The reasons are behavioural:
  1. `tty::from_fd` calls `ShellUser::from_env()?` unconditionally
     (`tty/unix.rs:213`) — even when an explicit shell is passed. It needs
     `USER`/`HOME`/`SHELL` from the environment or a `getpwuid_r` entry, and
     returns `Err` when neither exists. An Android app process normally has
     none of those variables set, and its uid has no passwd entry — we would
     have to mutate our own process environment just to start a session.
  2. It injects desktop environment (`ALACRITTY_WINDOW_ID`, `WINDOWID`, `USER`,
     `HOME`) that must not leak into the Phase C guest, and removes
     `XDG_ACTIVATION_TOKEN`/`DESKTOP_STARTUP_ID` — alacritty's assumptions, not
     a guest session's.
  3. The OEM survival path (slave-as-stdio when the login-tty spawn fails) and
     the `TIOCGPTPEER` → `/dev/pts/N` → `ptsname_r` fallback chain are ours;
     adding them to upstream code means a fork-and-patch commitment, which D1
     reserves for chroot-ng.
  4. Its `/usr/bin/login` branch is `#[cfg(target_os = "macos")]`
     (`tty/unix.rs:161-192`); on Linux/Android the default is
     `Command::new(shell)`. Recorded so this is not re-raised later as an
     Android login-tty risk.
  Cost of the decision: ~451 lines of PTY code and the mirrored private token
  constants (already an open item) instead of upstream's tested implementation
  *and* its free `EventedPty`. Benefit: child argv/env/winsize stay ours, which
  is what Phase C's chroot-ng argv mapping (`-u`, `-w`, `-b`, `-E`) and the OEM
  fallbacks need.
  **Revisit trigger:** PTY bugs we cannot fix locally, or once Phase C's spawn
  is stable — then re-evaluate `Options { shell: Shell::new(program, args), env,
  working_directory }` with that evidence, not before.
  Taken from `tty/unix.rs` immediately: `IUTF8` on the master fd (it sets it in
  `tty/unix.rs:206-211`; bionic does not), so a backspace erases a whole
  multibyte character in the guest. Implemented with a host test that reads the
  termios back through `stty -g`.
- 2026-10-16: **First device run of the Phase A APK: two defects, both found by
  the acceptance test (which is what it is for).**
  1. *Wire format*: `encode_color` masked a named colour down to 8 bits. The
     frozen-value test only asserted `NamedColor::Red` (= 1), which survives that
     mask, so CI stayed green while `Background` (257) and `Foreground` (256)
     silently became `Red` (1) and `Black` (0). On the device every default cell
     was alacritty-red and text nearly black; the screenshot's pixels
     (`#ac4342` / `#181818`) confirmed the code reading. The test now pins 0, 7,
     15 and 256..268 — a frozen-value test must pin the *whole* range, not one
     representative value.
  2. *Renderer*: the atlas V coordinate assumed `GLUtils.texImage2D` flips the
     bitmap's rows (it uploads them in bitmap order, so the bitmap's top row is
     v = 0), which mirrored every glyph vertically. Recorded as a verified fact
     in `AsciiAtlas.kt`: **with bitmap rows uploaded in order, v grows downwards
     through the image.**
  Both are fixed and covered (`cargo test` on the Rust side; the Atlas change is
  device-verified by the retest). Device, Android version and kernel are
  recorded in TODO.md once the retest passes.
- 2026-10-16: **Phase A acceptance passed on a physical device** (Android 15,
  kernel `6.6.30-android15-8-g6b55ce738535-4k`, `OpenGL ES 3.2` driver string —
  all in TODO.md): `echo hello` renders from the snapshot, with alacritty's
  default colours verified by reading pixels out of the screenshot (`#181818`
  background, `#d8d8d8` text). The whole path — PTY → `alacritty_terminal` →
  snapshot bridge → GL ES → screen — is therefore proven on hardware, not only
  in CI, and §8 item 5 (GL ES 3.0 on target devices) is verified with it.
  Phase A is closed; what follows is quality (Phase B) and the guest runtime
  (Phase C).
- 2026-10-16: Phase B started, first decision: **the font travels inside the
  APK** (user decision). JetBrains Mono NL 2.304, Regular + Bold + `OFL.txt` in
  `android/app/src/main/assets/fonts/`, with the release URL and every SHA-256 in
  that directory's README. Rationale: cell metrics, atlas buckets and the future
  selection geometry all derive from the font, so a cell has to be the same size
  on every device — and `/system/fonts` differs per OEM, both in what a
  monospace face is called and in its metrics, with devices that lack one
  entirely. The **`NL` variant** (no ligature lookups) is chosen because D6 has
  no ligature support: a cell must show the character the guest wrote; the shaper
  also disables `liga`/`clig`/`calt`/`rlig`, so a later font swap cannot start
  ligating silently. Italic is synthesised by skew rather than shipped, and the
  release's variable font is deliberately unused (D12: no configuration).
  Second decision, from implementing Gate 2: the renderer's input stays **alpha
  masks only** — swash is asked for `Source::Outline`, and the tests assert one
  byte per pixel (`coverage.len() == width * height`), so the Kotlin atlas is a
  single channel the shader tints. Colour glyphs (emoji) need an RGBA atlas pass
  and stay a recorded decision (§8 item 3). Cell width comes from a *shaped*
  advance, not from an average, so the metric the grid is laid out with is the
  same number the glyphs are positioned with.
- 2026-10-16: Phase B input. The encoder's sequences are pinned to sources, not
  to memory: `Encoder.kt` derives from `alacritty/src/config/bindings.rs`
  (APP_CURSOR cursors `ESC O A`, Shift+Tab `ESC [ Z`, Alt as an ESC prefix or as
  the modifier parameter `CSI 1;3D`) and from
  `alacritty/src/input/keyboard.rs` (`~`-terminated keys, F1–F4 as SS3, the named
  control characters), and `EncoderTest.kt` pins every one of them so a
  regression fails in CI rather than on a device. Two deliberate choices: Enter
  sends **CR** (0x0d) — ICRNL turns it into a newline for the shell while a
  raw-mode program receives what it expects — and Ctrl+letter uses the **C0
  mapping** (`code & 0x1f`), because on Android no OS keyboard layer does it for
  us; the device test exercises it through Ctrl+C. The sticky Ctrl is one-shot,
  and the extra-keys row is Ctrl/Esc/Tab plus the arrows until a device test
  says more is worth the screen space.
- 2026-10-16: **User decision: no unit-test step in CI.** The encoder's JVM
  tests stay in the tree (`android/app/src/test/`, run by hand with
  `gradle testDebugUnitTest`), but CI does not execute them; the Android job
  goes back to `assembleDebug` plus the APK content check. The cost is on the
  record: while the step was in place it failed on its first run with a real
  defect (Shift+Tab sent `ESC [ SPACE Z` — a stray space in a string template),
  so encoder changes now lean on the device test instead. Recorded here so the
  gap stays deliberate and is not silently re-added.
  **Changed 2026-10-17 (user decision): the step is back.** The mouse encoder
  (`Mouse.kt`) is a byte-exact contract with alacritty and could not be verified
  any other way — local Gradle builds are forbidden (D13) — so the Android job
  runs `gradle testDebugUnitTest` before `assembleDebug`. The cost is a few
  seconds in a job that already runs Gradle; the 2026-10-16 decision is kept
  above so the reversal is on the record rather than silently undone.
- 2026-10-16: Phase B renderer, second decision: **damage-limited redraw by row
  slots.** Every grid row owns a fixed slot in the vertex buffer, unused slots
  are filled with degenerate quads (zero area, so they cost nothing), and a
  frame rebuilds only what the engine reported — the damage list's `(row, left,
  right)` triples, plus the row the cursor left or moved to, because the engine
  does not count cursor movement as damage. The whole row is rebuilt rather than
  the reported column range: it keeps the slot arithmetic honest and a row is a
  few hundred quads, so the extra work is not worth a second addressing scheme.
  The frame pump moved from a 32 ms `Handler` poll to a Choreographer
  `FrameLoop`: one wakeup-flag check per vsync (a cheap JNI call) with the cursor
  blink on the same clock. `glBufferSubData` per damaged row replaces the whole
  buffer upload, and the frame no longer rebuilds cells that did not change.
- 2026-10-16: Phase B selection, phase 1. The model is a **linear (row-major)
  range** anchored by a long press and extended by dragging: a phone drags along
  lines, so alacritty's rectangular mode is deferred rather than half-built. Word
  boundaries and colours are taken from alacritty instead of invented —
  `SEMANTIC_ESCAPE_CHARS` (`,│\`|:"' ()[]{}<>\t`, defined in
  `alacritty_terminal/src/term/mod.rs`) and the inverted cell
  (`InvertedCellColors::default` = the primary foreground as the highlight, the
  primary background as the text). Copying reads the cell buffer Kotlin already
  owns (D5), so it needs no new JNI call and no copy of the grid; wide-character
  spacers are skipped and trailing blanks trimmed per line. Paste is the row's
  other button: `ESC [ 200 ~ … 201 ~` when the program enabled mode 2004, raw
  otherwise, capped at 64 KB so a stray clipboard cannot flood the PTY.
- 2026-10-16: **First device numbers, and what they changed.** The Phase B build
  reported `p50 114.0 ms / p99 114.0 ms, 115 janky / 129` under `yes` — the
  acceptance target is `p99 < 8 ms`, so it is missed by an order of magnitude and
  the frame is *not attributable* from that one number: the frame includes the
  snapshot call, which takes the grid lock and therefore also waits while the
  parser is busy, and the row-slot design padded every row to `columns * 5`
  quads and wrote that padding on every rebuild. Two changes follow from it, both
  the honest kind — measure, then fix: the status line now splits the frame into
  **pump / build / draw** so the next number says where the time goes, and the
  padding is gone (a row records how many quads it produced; `draw` submits that
  prefix, one `glDrawArray` per row). The acceptance criterion also needs a
  *workload* rather than a stress test: `yes` saturates the PTY and keeps the
  parser holding the lock, so both a burst (`cat` of a few MB) and the runaway
  case belong in the record — the numbers, and the attribution, are in TODO.md.
- 2026-10-16: **Measurement process, and the transport question.** The rolling
  240-frame window is gone: counters that only grow (work buckets
  `<2/<4/<8/<16/<33/≥33 ms`, phase averages, jank count, worst frame), and
  tapping the status line *freezes* them — amber, one `DroshLabBench` logcat
  line, the same line appended to `files/bench.log` — so a number cannot change
  while it is being written down. The workload is written down too
  (`bench/scenarios.sh`: `steady`, `scroll-cat`, `scroll-ls`, `runaway`), and the
  device run that produced 114 ms is corrected in TODO.md to
  `yes | head -30000` (the earlier note said only `yes`).
  On the transport: a device cannot push to a build box (its `127.0.0.1` is the
  device), but the tester bridges a collector *on the device* out to this
  environment. So the app posts a frozen line to `http://127.0.0.1:8080/bench` —
  off the main thread, loopback constants only, and **only** on an explicit
  freeze tap: never on a timer, never on output. This is the first permission the
  prototype declares (`INTERNET`, which Android requires even for loopback), and
  it is justified in the manifest and in `bench/README.md`.
  Verified from the reviewing side: every path and a POST to that address answer
  `200 WORKING`. **Open:** the collector does not serve back what it stores — a
  marker POSTed from here does not appear at `/`, `/data`, `/last`, `/bench`,
  `/results`, `/log` or `/lines` — so `adb logcat -s DroshLabBench` and
  `bench/push-results.sh` stay the working channels until a read-back endpoint
  exists. Rejected: an in-app upload to GitHub (a token inside a debug APK) or to
  a public paste service (a terminal phoning home).
- 2026-10-16: **The endpoint moved *into* the app** (user decision). Rather than
  posting to a collector, the lab build hosts one: `bench/BenchServer.kt` listens
  on a fixed port (8080, logged with the addresses to try) and answers three
  read-only GETs — `/`, `/info` and `/bench.log` (one header line per app start,
  then one line per frozen capture; `bench/fetch-results.sh` pulls them). The
  consequence the user accepted explicitly: **the app has to be running** for the
  numbers to exist. The server binds **every interface**, so whichever address the
  tester's bridge can reach answers (their Wi-Fi address or loopback); that is a
  lab-build trade recorded here — a shipped build binds loopback and leaves the
  bridging to `adb forward`. The app never dials out, and the endpoint serves
  bench numbers and device facts, never terminal content, which is also why
  `INTERNET` is the only permission the prototype declares.
  **changed: 2026-10-16 18:22 — removed again** (`4f4113d`, user decision): the
  endpoint, `BenchServer.kt`, `INTERNET`, the scenario/label/result scripts and
  the pull path are gone. It worked (a device line was pulled end to end without
  a paste), but it cost the tester more than the numbers were worth, and the
  numbers were still not comparable until a workload label existed. What stays:
  monotone counters and one frozen line on a tap (`DroshLabBench` in logcat plus
  `files/bench.log`). The lesson — a harness must be cheaper than the measurement
  it produces — lives in `bench/README.md`.
- 2026-10-16: **Renderer vertex path re-read against alacritty and Ghostty, and
  redesigned for the phone** (user request). Sources read, not remembered:
  - alacritty `src/renderer/text/gles2.rs` + `res/gles2/text.v.glsl`: one vertex
    buffer drawn **twice** with a `renderingPass` uniform (pass 0 places cell
    backgrounds from `cellCoords`, pass 1 places glyphs from `glyphCoords`+`uv`);
    colours are packed as normalised `u8x4`; quads are **indexed** (4 vertices, 6
    indices) and batches are bounded by the 16-bit `glDrawElements` limit
    (`MAX_INDEX_COUNT`, gles2.rs:221-224).
  - alacritty `src/renderer/rects.rs:240-320`: a rect vertex is `vec2 position` +
    `u8x4` colour — 12 bytes, four per quad.
  - Ghostty `src/renderer/shaders/glsl/cell_text.v.glsl`: the vertex shader builds
    the quad itself — `gl_VertexID` picks the corner of a 4-vertex triangle strip —
    and the record is **per cell** (`glyph_pos`, `glyph_size`, `bearings`,
    `grid_pos`, `color`, `atlas`, `glyph_bools`), with no per-vertex position at
    all. Its `bearings.y` is measured from the bottom of the cell.
  - Ghostty `src/renderer/generic.zig`: two pipelines (`cell_bg`, `cell_text`);
    backgrounds from a **dense** per-cell array, foregrounds from **per-row lists**
    with reserved overdraw capacity (`fg_row_capacity = columns * 3`) drawn
    **instanced** (`.instance_count = fg_count`, generic.zig:1979); buffers synced
    per frame; the renderer is its own thread behind a display link and is
    triple-buffered so a frame never waits on the previous one (OpenGL.zig:32-33).
  Ours today, by comparison: up to 5 quads per cell × 6 vertices × 8 floats — up
  to ~1 KB per cell — uploaded as one `glBufferSubData` per dirty row, and both
  device captures put the cost exactly there (`pump` 1.5 ms, `draw` 0.7 ms,
  `build` 13–63 ms depending on how many rows were rebuilt).
  **Adaptation for GLES 3.0 on a phone** — Ghostty's `std430` storage buffer is
  desktop-only, so the background colour travels as an attribute instead:
  1. one **record per cell** (atlas rect, bearings, packed fg/bg, flags) of ~20
     bytes, uploaded per changed row;
  2. the quad is built in the vertex shader from `gl_VertexID` and drawn
     **instanced** (`glVertexAttribDivisor` + 4 template vertices) — GLES 3.0 has
     it and the test device reports 3.2;
  3. **two passes** over the same buffer (backgrounds, then glyphs) with
     alacritty's normalised `u8x4` colour packing;
  4. our damage-limited row slots stay (Ghostty's per-row lists with reserved
     capacity are the same idea), including the whole-buffer upload when most rows
     are dirty — that is the mobile answer to buffer orphaning, since a sub-range
     upload cannot be orphaned;
  5. a grayscale atlas now and a colour page when the emoji decision lands —
     Ghostty carries an `atlas` attribute for exactly that.
  The claim is that CPU→GPU data per damaged cell drops by roughly an order of
  magnitude; it is not claimed to be enough until a device capture says so.
- 2026-10-17: **Performance work is parked, and the phase moves on** (user
  decision). The Phase B budget went into `p99 < 8 ms` measured on
  `assembleDebug` builds — no R8, `debuggable`, no workload label — so the
  numbers were pessimistic *and* not comparable to each other: runs 2/3/4 differ
  by 5x on `build` mostly because they rebuilt different amounts of the grid, not
  because the renderer changed. The instanced rewrite (`09467eb`, ~28 B/cell) is
  merged and CI-green but **not device-verified**; the next device run is a
  *correctness* check (does the screen still draw right), not a benchmark.
  Consequence for the acceptance criterion: a frame target is only an acceptance
  number when it comes from a release build with a labelled workload and one
  freeze per scenario — the debug-build captures above are recorded as
  attribution, never as pass/fail. The parked items stay in TODO.md; Phase B's
  functional gaps (pinch zoom, OSC 4/10/11 answers, CJK/emoji fallback) are not
  closed by this decision and are carried forward.
- 2026-10-17: **Protocol completeness, ported from alacritty, not invented.**
  Every rule below was read out of `alacritty` v0.17.0 — the tag whose
  `alacritty_terminal` is exactly our pinned 0.26.0 — and the Kotlin/Rust tests
  pin the bytes:
  - **Mouse reporting** (`?1000/1002/1003`, SGR `?1006`, UTF-8 `?1015`).
    `input/Mouse.kt` is a port of `alacritty/src/input/mod.rs`:
    `sgr_mouse_report`, `normal_mouse_report`, motion codes 32–35, wheel 64–67,
    modifiers added to the button (shift 4, alt 8, ctrl 16). Touch has no
    desktop equivalent, so the mapping is Termux's (`TerminalView.onUp` and
    `doScroll`): a tap is a left click and a finger drag is a wheel report;
    a real pointer reports buttons and per-cell motion as they are. Mode
    `?1007` (alternate scroll) is exported too: with no mouse tracking, a wheel
    on the alternate screen sends `ESC O A/B`, which is alacritty's
    `scroll_terminal`.
  - **Focus reporting** (`?1004`): `ESC [ I` / `ESC [ O` from
    `onWindowFocusChanged`, alacritty's `on_focus_change`.
  - **OSC 4/10/11/12 queries.** The engine's formatter is the only code that
    knows the prefix and the terminator, so `EventProxy` probes it with black
    and splits the result (`parse_color_template`); a colour the engine stores
    as an override is answered in Rust with alacritty's exact reply string, a
    default-palette query goes to Kotlin as
    `color_query:index:prefix:bel|st`, which owns the palette (D5). *Set* now
    reaches the renderer as well: `nativeColors` exports the engine's
    269-entry table, and `state[STATE_COLORS]` carries a fingerprint so the
    table is pulled only when a colour actually changes.
  - **OSC 52** store and load are wired end to end; the text crosses as base64
    because event records are newline-separated, and the load reply is
    formatted in Kotlin because that is where the clipboard lives.
    **Policy:** the session sets `Osc52::CopyPaste`. alacritty's default is
    `OnlyCopy` because a remote could read the clipboard; the prototype has no
    remote (D12), so paste is safe *today* and the default is revisited when
    SSH lands (§8).
  **Not implemented, on purpose:** application keypad (`APP_KEYPAD`) —
  alacritty itself only uses that mode to select bindings, and its default
  bindings contain no keypad sequences, so emitting SS3 from a numpad would be
  invention rather than parity; and the kitty keyboard protocol (`CSI u`),
  which is a body of work of its own (TODO.md).

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

**Acceptance:** frame p99 < 8 ms on a **release** build with a labelled workload
(a debug-APK number is attribution, not acceptance — 2026-10-17); no jank under
full-screen output; crisp pinch zoom; select/copy/paste working. If targets are
missed, §9 Plan-B (renderer port) is evaluated **with the measured data**.

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
   Phase B Gate 2: **the compile half is verified** (`Gate 2 — Rust font stack`
   job cross-checks the crate for `aarch64-linux-android`, and the font tests
   produce real alpha masks from the bundled font). The colour half is a
   recorded decision rather than an implementation: swash can rasterise
   CBDT/COLR (`Source::ColorBitmap`), the renderer needs an RGBA atlas pass
   first — see TODO.md, Phase B Gate 2.
4. chroot-ng probe result + file-backed mode — Phase C Gate 3: **passed on
   device 2026-10-17** (Android 15, kernel `6.6.30-android15-8-g6b55ce738535-4k`,
   aarch64, 4096-byte pages). `--probe` exit 0, verdict "primary in-process tier:
   LIKELY VIABLE": seccomp `filter RET_ERRNO WORKS` (mode 2), **execmem OK**
   (anon RW→RX→execute), `/` and `.` both `noexec=no`, `uid=euid=gid=egid=10341`,
   `at_secure 0`. Because execmem is allowed the file-backed loading mode is not
   needed here; it stays recorded as the OEM fallback. Full output in TODO.md,
   Phase C Gate 3.
5. GL ES 3.0 available on target devices (fallback: GLES2 path) — **verified
   2026-10-16 on an Android 15 device**: `OpenGL ES 3.2
   v1.r49p1-03bet0.3a1759ee1cb57182986f58db4ad336c2`, logged by the Phase A
   shell's `GL_VERSION` line. No GLES2 fallback path is needed for now.
6. **OSC 52 paste policy** — the session sets `Osc52::CopyPaste` (alacritty's
   default is `OnlyCopy`). Safe only while the prototype has no remote
   transport (D12). Revisit when SSH lands: the decision is whether to return
   to `OnlyCopy`, add a per-session setting, or ask the user at first paste.

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
