//! Drosh terminal core — cdylib entry point.
//!
//! Phase A implementation lands here: `ffi/` (JNI boundary), `session/`
//! (Term + EventLoop + PTY), `bridge/` (snapshot + events), `util/`.
//! See TODO.md for the task order and the gates.

#![deny(warnings)]
