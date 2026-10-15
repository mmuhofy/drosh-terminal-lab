# Rust core (`libdrosh-core.so`)

The Rust half of the Drosh terminal core — built as a cdylib; the Android app
talks to it over JNI.

**Contents (written in Phase A):** `ffi/` (JNI boundary), `session/` (Term +
EventLoop + PTY), `bridge/` (snapshot + events), `font/` (rustybuzz + swash).

**Dependencies:** `alacritty_terminal =0.26.0` (VT engine), `jni`, `libc`,
`rustybuzz`, `swash`. Details: [`../../PLAN.md`](../../PLAN.md) §5.
