# android/app — thin shell

**Not** where the terminal is drawn; the platform layer that keeps it alive:

- `MainActivity` — the session screen (one session in Phase A), lifecycle,
  frame polling, cursor blink
- `GLSurfaceView` + EGL host — the surface the renderer draws into
- JNI bindings (`libdrosh_core.so`, package `core/`)
- Keyboard input through an invisible text editor (package `input/`)

## Phase A status

Implemented and green in CI (run 38870912218): the app starts
`/system/bin/sh`, streams the snapshot buffer into the GL renderer and accepts
typed commands. What is missing is device acceptance, not code — see
[`../../TODO.md`](../../TODO.md).

Everything lives in this one module for Phase A. Renderer:
[`../renderer`](../renderer) (Phase B, when the code justifies a module);
input: [`../input`](../input).
