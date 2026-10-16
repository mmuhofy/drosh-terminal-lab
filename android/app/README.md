# android/app — thin shell

**Not** where the terminal is drawn; the platform layer that keeps it alive:

- `MainActivity` — the session screen (one session in Phase A), lifecycle,
  frame polling, cursor blink
- `GLSurfaceView` + EGL host — the surface the renderer draws into
- JNI bindings (`libdrosh_core.so`, package `core/`)
- Keyboard input through an invisible text editor (package `input/`)

## Status

Phase A is closed (the device acceptance passed, see [`../../TODO.md`](../../TODO.md));
Phase B is in progress. The app now loads its font from `assets/fonts/` into
the Rust stack at startup and takes the cell metrics from it, so the grid comes
from the same face the glyphs are rasterised from.

Everything lives in this one module for now. Renderer:
[`../renderer`](../renderer); input: [`../input`](../input).
