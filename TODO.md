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
- [x] **On a physical device (CI-built APK): `echo hello` renders from the
      snapshot — PASSED (2026-10-16).**
      - Device: an Android 15 phone. The tester deliberately left the *model*
        out (AGENTS.md §10.2 asks for it); Android version, kernel and driver
        string are recorded below, so a regression can still be tied to a
        platform.
      - Kernel: `Linux localhost 6.6.30-android15-8-g6b55ce738535-4k #1 SMP
        PREEMPT Thu Jan 1 00:00:00 UTC 1970 aarch64 Toybox`
      - GL: `OpenGL ES 3.2 v1.r49p1-03bet0.3a1759ee1cb57182986f58db4ad336c2`
        (from `adb logcat -s DroshLab`) — so D3's GL ES 3.0 requirement is
        satisfied on real hardware, with headroom
      - APK: commit `71ceaf5`, 1,265,475 bytes, SHA-256
        `b540296cd703bb0773d260643848bb8fe09b5ad566554c2e15637877b1ac69de`
      - Seen: `:/data/user/0/dev.drosh.lab/files $ echo hello` → `hello` →
        next prompt; pixel read of the screenshot gives `#181818` background and
        `#d8d8d8` text, i.e. the palette and the atlas orientation are right
      - **First run (2026-10-16) failed, and that is why it exists**: the grid
        did receive the engine's content, but every default cell was painted
        alacritty-red and every glyph was mirrored vertically. Both defects are
        fixed in `71ceaf5` (see `CHANGELOG.md` `Fixed`).
      - Not tested on this run: rotation, the IME shrinking the grid, the OEM
        slave-as-stdio fallback (it was not needed).
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
- [x] swash compiles for Android and produces glyph bitmaps (alpha) — the
      `Gate 2 — Rust font stack (swash glyphs)` job runs the font tests against
      the *bundled* font and cross-checks the crate for
      `aarch64-linux-android`. The tests assert the mask is single-channel
      (`coverage.len() == width * height`), that a real glyph has ink, that
      `' '` has none, and that the bold face is a different raster.
- [x] **Color-emoji path decision (2026-10-16): swash colour bitmaps into a
      second atlas pass — deferred, not dropped.** swash 0.2 rasterises
      CBDT/COLR through `Source::ColorBitmap` / `Scaler::scale_color_bitmap`
      (read from its source); what is missing is the renderer half: an RGBA
      texture, a colour quad path and a per-glyph format flag in the atlas
      record. Until then the bundled font is the only source of glyphs and
      `fallback::resolve` reports `Substituted`/`Missing`, so the Kotlin side can
      show that a codepoint had no glyph instead of silently drawing nonsense.
      Rejected alternative: drawing emoji with Android text APIs over the GL
      surface — it would put text drawing outside the renderer, which breaks the
      damage list, the cursor cell and (later) selection geometry.

### Rust font stack
- [x] `font/shape.rs`: rustybuzz (`Face::from_slice`, `UnicodeBuffer`,
      `shape`) → glyph id/cluster/advance/offset; `liga`/`clig`/`calt`/`rlig`
      are switched off explicitly, so the shipped NL font cannot ligate and a
      future font swap cannot start either
- [x] `font/raster.rs`: swash (`ScaleContext` → `Scaler` → `Render` with
      `Source::Outline`) → alpha mask + bearings, and the cell metrics (cell
      width from a *shaped* advance, so it is the same number the glyphs are
      positioned with)
- [x] `font/mod.rs`: `FontStack` (owns the face bytes, holds the scale context,
      `Weight::Bold` falls back to regular if the bold file is unusable)
- [x] `font/fallback.rs`: first cut — codepoint → glyph with the replacement
      policy and an explicit `Substituted`/`Missing` answer; the full chain
      (ASCII → Latin → CJK → emoji) is still open
- [x] Font files bundled in the APK: JetBrains Mono NL 2.304, Regular + Bold +
      `OFL.txt` in `android/app/src/main/assets/fonts/` with a README recording
      the release URL and every SHA-256 (decision taken by the user, 2026-10-16:
      bundled font, JetBrains Mono; the `NL` variant because the prototype has
      no ligature support)
- [ ] `font/atlas_api.rs`: Kotlin atlas record format (UV, metrics)
- [ ] `ffi/font.rs`: JNI rasterize/metrics requests (this is what lets the
      Kotlin renderer stop baking its ASCII atlas from Android's typeface)

### Kotlin renderer
- [x] Glyphs from the Rust stack: `GlyphAtlas` asks `nativeRasterize` once per
      (codepoint, weight, pixel size), packs the coverage masks into one
      single-channel (`GL_R8`) texture and caches the result; the renderer holds
      a per-cell cache, so a steady-state frame does no map lookups and no
      allocation
- [x] Cell geometry from the font: `CellMetrics` ← `nativeFontMetrics`, so the
      grid, the baselines, the underline position and the rule thickness all
      come from the bundled face (the Android-baked `AsciiAtlas` is gone)
- [x] Bold uses the real bold face (the fake-bold second pass is gone)
- [x] `renderer/loop.kt` → `FrameLoop`: Choreographer instead of a 32 ms
      `Handler` poll, so the engine's wakeup flag is checked once per vsync and
      output lands on the next refresh. It also owns the cursor blink, so the
      two timers cannot drift apart.
- [x] Damage-limited redraw: each grid row owns a fixed slot in the vertex
      buffer; a frame rebuilds only the rows the engine reported (plus the row
      the cursor sits on or just left) and uploads just those with
      `glBufferSubData`. Unused slots hold degenerate quads, which rasterise
      nothing. The engine gives a column range per damaged line, but the whole
      row is rebuilt — that keeps the slot arithmetic honest, and a row is a few
      hundred quads.
- [ ] `renderer/atlas.kt`: size buckets (1x/1.5x/2x/3x) — the atlas is keyed by
      pixel size already, so buckets are about picking the size per zoom level
- [ ] `renderer/shader.kt` / `quads.kt`: GLES3 instancing (the buffer is already
      one draw call; instancing would cut the CPU-side vertex building, which is
      not the bottleneck yet)
- [ ] Cursor: shape (block/bar) without full redraws — block/beam/underline work;
      the blink redraws one row, which is the cheap part already
- [ ] Strikeout position: approximated at 35% of the ascent because the font's
      own strikeout metrics are not on the wire yet (`nativeFontMetrics` would
      need two more floats)
- [ ] Italic/oblique: synthesised by skew once the glyph batch is instanced (no
      italic face is bundled)

### Kotlin input
- [x] `input/encoder.kt`: xterm conventions — Ctrl/Alt/Shift, arrows,
      Home/End, PgUp/PgDn, F1–F12, Esc, Tab/Shift-Tab, DEL/BS. The sequences
      were read out of alacritty's sources (`config/bindings.rs` for the
      APP_CURSOR, Shift+Tab and Alt forms; `input/keyboard.rs` for the
      `~`-terminated keys and the named control characters) and from the xterm
      modifier convention.
- [x] `input/keys.kt` → `ExtraKeysView`: Ctrl (sticky), Esc, Tab and the four
      arrows. Enter is CR (0x0d) now, not LF: the tty's ICRNL turns it into a
      newline for the shell and a raw-mode program gets what it expects.
- [ ] `android/app/src/test/java/.../EncoderTest.kt` pins every sequence, but
      **CI does not run it** (user decision, 2026-10-16: no unit-test step in
      CI). Run it by hand with `gradle testDebugUnitTest` when the encoder
      changes — while it *was* wired into CI it caught a real bug immediately
      (Shift+Tab sent `ESC [ SPACE Z` because of a stray space in a string
      template). Recorded here so the gap is deliberate, not forgotten.
- [ ] Ctrl+C/D/Z: reachable as Ctrl plus `c`/`d`/`z` from the soft keyboard and
      from a hardware keyboard (the sticky Ctrl turns the first committed
      character into its C0 control code). A dedicated row button is not there
      yet — a device test decides whether it is worth the space.
- [x] `input/paste.kt` → the row's **Paste** button: the clipboard goes in
      wrapped in `ESC [ 200 ~ … ESC [ 201 ~` when the program asked for mode
      2004, raw otherwise, capped at 64 KB. Plain paste never runs a multi-line
      block line by line.
- [x] Selection + **Copy**: long-press picks the word under the finger, dragging
      extends the range, a tap clears it. Word boundaries are alacritty's own set
      (`alacritty_terminal/src/term/mod.rs`, `SEMANTIC_ESCAPE_CHARS`), and the
      highlight is the inverted cell, which is alacritty's default
      (`InvertedCellColors`). Copy reads straight out of the cell buffer (D5),
      skips wide-character spacers and trims trailing blanks per line.
- [ ] `input/gestures.kt`, still open: pinch zoom (row anchoring), momentum
      scroll, line selection (triple tap), and the *rectangular* selection mode
      alacritty has — the prototype's is a linear range, which is what a phone
      drag produces.

### Benchmarks
- [x] Frame timings in the app (`bench/FrameStats.kt`): per-frame CPU work
      (build + upload + submit) with p50/p99 and a jank counter (a gap of more
      than two refreshes since the previous drawn frame), shown in a status line
      above the extra-keys row; tapping the line resets the window. GPU time
      would need timer queries and is out of scope for the prototype.
- [ ] The acceptance numbers themselves (`frame p99 < 8 ms`, no jank under
      full-screen output) have to be read off a device — the status line shows
      them, and the values go here once a device test reports them.
- [ ] `bench/`: the repo-level benchmark notes (guest runtime timings land there
      in Phase C).

### Acceptance
- [ ] **Device run 1 (2026-10-16, commit `64db7e2`, Android 15, kernel
      `6.6.30-android15-8-g6b55ce738535-4k`, `GL ES 3.2`):**
      - log: `font: cell 12.33x27.126px at 20.55px (density 1.7125, ascent
        20.961)`, `core 0.1.0 session 1: 58x55 cells of 12x27px`,
        `GL version: OpenGL ES 3.2 v1.r49p1-03bet0.3a1759ee1cb57182986f58db4ad336c2`
      - screenshot pixel read: terminal `#181818`, stats strip `#151515`, key
        buttons `#2a2a2a` — palette and layout as designed; glyphs upright and
        legible, prompt and block cursor correct
      - **frame numbers: p50 114.0 ms, p99 114.0 ms, 115 janky / 129**, measured
        while `yes` saturated the PTY. The target (`p99 < 8 ms`) is missed by an
        order of magnitude, and the number is **not yet attributed**: the frame
        includes the snapshot call, which can wait on the grid lock while the
        parser runs, and the row-slot padding made every row cost a full
        `columns * 5` quads. The next build splits the timing (pump / build /
        draw) and stops padding slots it does not draw.
      - still to test: Tab/Ctrl combinations, selection + copy/paste, and the
        same numbers under a *defined* workload (a burst that does not saturate
        the PTY, e.g. `cat` of a few MB) — the acceptance criterion needs a
        workload, not a stress test
- [ ] frame p99 < 8 ms — the status line shows p50/p99 while a burst runs
      (`yes | head -20000`); the first numbers go here
- [ ] no jank under full-screen output (the same line counts janky frames)
- [ ] crisp pinch zoom; select + copy + paste working ← zoom is not built yet
      (see the input section); copy/paste are
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
