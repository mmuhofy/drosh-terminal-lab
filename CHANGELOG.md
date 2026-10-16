# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Phase B groundwork: the Rust font stack (`rust/core/src/font/`) — `rustybuzz`
  shapes text, `swash` rasterises it.
  - `shape.rs`: glyph ids, clusters, advances and offsets; `liga`/`clig`/`calt`/
    `rlig` are disabled explicitly, so a terminal cell always shows the
    character the guest wrote.
  - `raster.rs`: alpha coverage masks with their bearings, plus the cell metrics
    (cell width from a shaped advance, so the grid and the glyphs agree).
  - `fallback.rs`: codepoint → glyph with the replacement policy and an explicit
    `Substituted`/`Missing` answer instead of a silent hole.
  - `mod.rs`: `FontStack` — owns the face bytes, keeps the rasteriser's context,
    and treats a broken bold face as a warning rather than a failed session.
- The font is bundled in the APK: JetBrains Mono NL 2.304, Regular + Bold and
  `OFL.txt`, in `android/app/src/main/assets/fonts/` (provenance, license and
  SHA-256s in that directory's README). Bundled rather than taken from
  `/system/fonts` so a cell is the same pixel size on every OEM's build.
- CI: a `Gate 2 — Rust font stack (swash glyphs)` job runs the font tests
  against the bundled font and cross-checks the crate for
  `aarch64-linux-android`.
- `LICENSE` (GPL-3.0, taken verbatim from gnu.org) and `THIRD-PARTY.md`: every
  component that is actually distributed — the bundled font and each crate
  linked into `libdrosh_core.so` — with the license read from the crates' own
  metadata. The font's OFL text ships inside the APK next to the font, which is
  what the license requires.
- The renderer draws glyphs from the Rust stack (`ffi/font.rs` →
  `GlyphAtlas`): coverage masks are rasterised on demand, packed into one
  single-channel (`GL_R8`) texture and cached by (codepoint, weight, pixel
  size), with a per-cell cache in the renderer so the frame loop neither
  allocates nor looks anything up. The cell geometry (`CellMetrics`) now comes
  from `nativeFontMetrics`, so the grid, baselines and underline position are
  all the bundled font's, and bold uses the real bold face instead of a second
  pass. The Android-baked `AsciiAtlas` is gone.
- The app hosts the bench endpoint itself (`bench/BenchServer.kt`, fixed port
  8080): read-only GETs for `/` (index), `/info` (device, kernel, core version,
  grid, cell metrics, GL, font) and `/bench.log` (one header line per app start,
  then one line per frozen capture), with `bench/fetch-results.sh` to pull them.
  Nothing is uploaded from the device; the app never dials out. The lab build
  binds every interface so a bridged address answers, and `INTERNET` exists for
  that listening socket alone.
- The input layer (`input/Encoder.kt`, `input/AndroidKeys.kt`,
  `input/ExtraKeysView.kt`): xterm key sequences read out of alacritty's own
  sources — APP_CURSOR (`ESC O A`) versus normal (`ESC [ A`) cursors, the
  `~`-terminated keys, F1–F4 as SS3, Shift+Tab, Alt as an ESC prefix or a
  modifier parameter (`CSI 1;3D`), the C0 mapping for Ctrl+letter — plus the
  extra-keys row (sticky Ctrl, Esc, Tab, arrows) and Enter as CR rather than LF.
- Selection and copy/paste (`input/Selection.kt`, `input/SelectionText.kt`, the
  row's Copy/Paste buttons): long-press selects the word under the finger with
  alacritty's own boundary set, dragging extends it, a tap clears it; the
  highlight is the inverted cell, which is alacritty's default. Copy reads the
  cell buffer directly, skipping wide-character spacers and trimming trailing
  blanks; Paste wraps the clipboard in `ESC [ 200 ~ … 201 ~` when the program
  enabled mode 2004.
- Frame timings (`bench/FrameStats.kt`) with a status line in the app: frame work
  split into pump/build/draw, monotone counters (work and interval buckets,
  averages, jank count, worst frame) and **freeze on tap**, which turns a glance
  into an artefact — one `DroshLabBench` logcat line, the same line appended to
  `files/bench.log`, and posted to the device-side collector
  (`bench/BenchUploader.kt`, loopback only).
- `bench/`: the scenario script (`scenarios.sh`: steady, scroll-cat, scroll-ls,
  runaway), the result collector for a machine with `adb` (`push-results.sh`) and
  a README that records how a measurement is taken and why the device cannot push
  to a build box. The app declares its first permission, `INTERNET`, because
  Android requires it even for a loopback socket.
- JVM unit tests for the encoder (`android/app/src/test/`, JUnit 4.13.2). They
  are **not** wired into CI (user decision, 2026-10-16); run them with
  `gradle testDebugUnitTest` when the encoder changes. While they were briefly
  part of CI they caught a real bug on the first run: Shift+Tab sent
  `ESC [ SPACE Z`, a stray space in a Kotlin string template.

### Changed

- The frame timing in the status line is split into **pump / build / draw**: the
  first device numbers (`p50 114 ms`) could not be attributed, so the phases have
  to be visible separately. A device run then settled it (`pump 1.5 ms`,
  `build 13.3 ms`, `draw 0.7 ms`): the snapshot is cheap and the cost is the
  Kotlin side.
- Jank now means **work over two refreshes**, not the gap between drawn frames:
  the gap counts idle time, and it reported 15 "janky" frames out of 16 while the
  app was doing nothing.
- The atlas rasterises printable ASCII once, from the GL thread, before the first
  frame: a burst used to pay one JNI call per new glyph plus a full atlas
  re-upload per frame, which is where the 120 ms frame came from.
- The frame line carries **rows/frame** and the vertex building is measured apart
  from the **uploading**: `build` scales with how many rows were rebuilt, so
  without that number two device runs cannot be compared (runs 2 and 3 differed
  by 5x for that reason). When at least a third of the rows are dirty the whole
  buffer goes up in one call instead of one `glBufferSubData` per row.
- The renderer no longer pads a row's slot with degenerate quads: a row records
  how many quads it produced and `draw` submits that prefix with one
  `glDrawArrays` per row. The first device build wrote (and paid for) roughly
  four wasted quads per drawn one.
- The layout is a vertical column: the GL surface takes everything above the
  extra-keys row, so the grid is told about a smaller surface instead of drawing
  underneath the buttons. The row itself scrolls sideways, because it is meant to
  grow.
- The renderer is damage-limited: every grid row owns a fixed slot in the vertex
  buffer, a frame rebuilds only the rows the engine reported (plus the cursor's
  row) and uploads them with `glBufferSubData`. The 32 ms `Handler` poll is gone:
  a Choreographer-driven `FrameLoop` checks the engine's wakeup flag once per
  vsync, which also makes the cursor blink cheap (one row).

## [0.2.0] - 2026-10-16

**Phase A — the engine runs on Android.** `alacritty_terminal` 0.26.0 parses a
real PTY session on a physical device and the Kotlin GL ES 3.0 shell paints it
from the snapshot bridge.

### Added

- Phase A Rust core (`rust/core`): the JNI boundary (`ffi/`), the session layer
  (`session/`: PTY + engine + event loop + handle table) and the data path to
  Kotlin (`bridge/`: 16-byte cell records, damage list, event queue).
  - PTY: raw libc — `posix_openpt` + `TIOCGPTPEER`, child via `setsid` +
    `TIOCSCTTY` + `dup2` in `pre_exec`, with a slave-as-stdio fallback for OEM
    kernels (D8).
  - Nothing throws across JNI: every `native*` function returns a handle or a
    frozen `ErrorCode`, and the panic hook reports through `log`.
  - `polling` and `signal-hook` added at the engine's own versions, so the tree
    keeps a single copy of each crate.
- Host tests: grid → buffer correctness, damage boundaries and resize behaviour
  (`tests/snapshot_tests.rs`); a real PTY session covering shell output, PTY
  resize (`stty size`) and child exit codes (`tests/session_tests.rs`).
- `IUTF8` on the PTY line discipline (`pty_factory.rs`): bionic leaves it unset,
  so backspace erased one byte of a multibyte character instead of the whole
  character. Now set on the master (as alacritty does for the desktop), with a
  host test that reads it back through `stty -g` inside the session.
- `rust/rustfmt.toml` — stable options only, matching the density alacritty
  formats with.
- Phase A Kotlin thin shell (`android/app`): the prototype now runs a real
  `/system/bin/sh` session on a device-ready APK.
  - `core/`: the JNI surface plus the frozen mirrors of the Rust wire formats
    (error codes, 16-byte cell records, colour tags, flag bits, state array,
    damage list) and a session wrapper that allocates the buffers Kotlin owns
    (D5) and reallocates them on resize.
  - `render/`: GL ES 3.0 (D3) — a coverage atlas baked once from the monospace
    typeface, one interleaved vertex buffer per frame, one draw call; glyphs,
    backgrounds, underlines, strikeout and all cursor shapes; alacritty's
    palette (16 ANSI colours, primary fg/bg, `DIM_FACTOR`, cube/gray ramp read
    from its source).
  - `input/`: an invisible text editor whose `BaseInputConnection` sends
    committed text and DEL, so the soft keyboard types into the terminal.
  - `MainActivity`: wakeup-flag polling (32 ms), cursor blink (500 ms), drag to
    scroll the scrollback, tap to open the keyboard, and toasts for GL and
    session failures.
  - The Phase A glyph source is an Android-baked ASCII atlas standing in for
    D4's Rust font stack (Phase B, Gate 2); the atlas interface it fills is the
    one the Rust rasterizer will fill.

### Changed

- `AGENTS.md` §9 and `PLAN.md` now describe the PTY path that is actually
  implemented (raw libc, not `forkpty`).
- `PLAN.md` §8 item 2 corrected: alacritty's `tty::new` calls `getpwuid_r` even
  when an explicit shell is passed — irrelevant here because the prototype
  spawns the child itself.
- The cell record is 16 bytes (codepoint, tagged fg/bg, frozen flag bits,
  reserved); the sketch in `PLAN.md` §5 could not carry colours.
- The core library is `libdrosh_core.so`, not `libdrosh-core.so`: Cargo turns
  the hyphen in the crate name into an underscore, so `System.loadLibrary` asks
  for `drosh_core`. `PLAN.md` §4 and the README were corrected.
- CI links the core with the pinned NDK toolchain (`CC_aarch64_linux_android` +
  `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER`, NDK `28.2.13676358`) instead of
  cargo-ndk, which aborts because the runner image pre-sets its own
  `ANDROID_NDK*` variables. The Android job now also asserts that
  `lib/arm64-v8a/libdrosh_core.so` is inside the built APK.
- `.gitignore`: `android/app/src/main/jniLibs/` is ignored as a whole — the
  previous rule would have let a built `.so` be committed.
- The device-test APK is published as a release asset (pre-release
  `phase-a-device-test`) on top of the CI artifact: artifact downloads on the
  run page need an authenticated session, a release asset does not. CI still
  builds the artifact, and the release carries the APK's SHA-256.

### Fixed

- **Named colours were truncated to 8 bits** (`bridge/cell.rs`): `NamedColor`
  runs to 268 (`DimForeground`), so `Background` (257) folded onto `Red` (1) and `Foreground`
  (256) onto `Black` (0). On the device every default cell was painted
  alacritty-red and the text went almost black — found by the Phase A device
  acceptance test and confirmed from the screenshot's pixel values
  (`#ac4342` background, `#181818` glyphs). The frozen-value test now pins every
  named payload (0, 7, 15, 256..268) instead of only `Red`, which is why the old
  test passed while the format was broken.
- **The renderer sampled the glyph atlas upside down** (`AsciiAtlas.kt`): the V
  coordinate was computed as if `GLUtils.texImage2D` flipped the bitmap's rows.
  It does not — the first row of the data is v = 0 — so every glyph was mirrored
  vertically on the device. Also found by the device test.

### Removed

- The bench harness: the app-hosted HTTP endpoint (`bench/BenchServer.kt`), the
  `INTERNET` permission it needed, the scenario/label/result scripts and the pull
  path (user decision, 2026-10-16 — it worked, but it cost the person holding the
  phone more than the numbers were worth; `bench/README.md` records why). What
  stays is the frame counters and the frozen line in `DroshLabBench` logcat plus
  `files/bench.log`.

- A device screenshot that was accidentally committed with a docs change
  (`git add -A` swept it up): it is removed from the tree and
  `Screenshot_*.{jpg,png}` is now ignored. It remains in the history of commit
  `2cc331f` — rewriting a public `main` is not worth it for an attachment, but
  the mistake is recorded here rather than hidden.

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
- **Phase A acceptance passed (2026-10-16):** the CI-built APK (commit
  `71ceaf5`, SHA-256 `b540296c…69de`) renders `echo hello` from the snapshot on
  an Android 15 device — kernel `6.6.30-android15-8-g6b55ce738535-4k`,
  `OpenGL ES 3.2` driver. Background `#181818` and text `#d8d8d8` were read out
  of the screenshot's pixels, so the palette, the atlas orientation and the
  cell metrics are all confirmed against the engine's data.
- CI green on that merge (`main`, run 38876185265): 28 Rust tests, the
  aarch64-linux-android cross-check, and `assembleDebug` plus the APK content
  assertion.

[Unreleased]: https://github.com/mmuhofy/drosh-terminal-lab/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/mmuhofy/drosh-terminal-lab/releases/tag/v0.2.0
[0.1.0]: https://github.com/mmuhofy/drosh-terminal-lab/releases/tag/v0.1.0
