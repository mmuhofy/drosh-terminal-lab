# android/app — thin shell

**Not** where the terminal is drawn; the platform layer that keeps it alive:

- `MainActivity` — session screen, permissions, lifecycle
- `SurfaceView` + EGL context host (the surface the renderer draws into)
- Session management (multiple tabs/sessions)
- JNI bindings (`libdrosh-core.so`)

Created in Phase A. Renderer: [`../renderer`](../renderer); input:
[`../input`](../input).
