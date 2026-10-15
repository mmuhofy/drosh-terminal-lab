# android/input — escape encoder + gestures

Turns touch and keyboard input into bytes the terminal understands.

**Components (Phase B):**

- `encoder.kt` — xterm conventions: Ctrl/Alt/Shift, arrows, Home/End,
  PgUp/PgDn, F1–F12, Esc, Tab/Shift-Tab, DEL/BS (Alacritty's
  `input/keyboard.rs` pattern)
- `keys.kt` — extra-keys row (Ctrl+C/D/Z, Tab, arrows)
- `paste.kt` — bracketed paste (mode 2004)
- `gestures.kt` — tap, double-tap (word), long-press (selection mode), drag
  (extend), pinch zoom (row anchoring), momentum scroll

Note: the soft-keyboard mapping is written here for the first time —
DroshKeyboard integration happens in the Drosh integration phase.
