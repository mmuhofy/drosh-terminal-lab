# android/input — escape encoder + gestures

Turns touch, pointer and keyboard input into the bytes the terminal understands.

**Components (in `app/src/main/java/dev/drosh/lab/input/`):**

- `Encoder.kt` — xterm key sequences: Ctrl/Alt/Shift, arrows, Home/End,
  PgUp/PgDn, F1–F12, Esc, Tab/Shift-Tab, DEL/BS (read out of alacritty's
  `config/bindings.rs` and `input/keyboard.rs`)
- `Mouse.kt` — xterm mouse reports: SGR (`?1006`) and the legacy form, motion
  32–35, wheel 64–67, modifiers 4/8/16, UTF-8 coordinates (`?1005`)
  (alacritty's `input/mod.rs`)
- `ExtraKeysView.kt` — the extra-keys row (Ctrl is one-shot)
- `TerminalInputView.kt` — the invisible text editor that owns the IME
- `AndroidKeys.kt` — Android key codes → `Encoder`
- `Selection.kt` / `SelectionText.kt` — word/line selection and copying

Touch has no desktop equivalent, so the mouse mapping is Termux's: with
`?1000/1002/1003` active a tap is a left click and a finger drag is a wheel
report; a real pointer reports buttons and per-cell motion. With no mouse
tracking, a scroll in the alternate screen goes through `?1007`
(`ESC O A/B`, Shift bypasses it) instead of moving the scrollback. Clipboard
paste is in `MainActivity` (bracketed when mode 2004 is on); OSC 52 is handled
by the session (`rust/core/src/bridge/events.rs`) plus `MainActivity`.

**Tests:** `app/src/test/java/dev/drosh/lab/input/` — `EncoderTest`,
`MouseTest`; CI runs them with `gradle testDebugUnitTest`.

Note: the soft-keyboard mapping is written here for the first time —
DroshKeyboard integration happens in the Drosh integration phase.
