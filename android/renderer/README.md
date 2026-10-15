# android/renderer — GL ES 3.0 terminal renderer (Kotlin)

The single place the terminal image is produced. Draws the **snapshot** (flat
cell buffer + per-line damage list) and **glyph bitmaps** coming from Rust.

**Components (Phase B):**

- `atlas.kt` — bitmap atlas + 1x/1.5x/2x/3x size buckets (Alacritty's glyph
  cache/atlas design)
- `shader.kt` — GLES3 instancing (vertex + quad shader)
- `quads.kt` — instanced quad batch (glyphs + background/cursor rects)
- `loop.kt` — Choreographer + damage-limited redraw + frame-skip policy
- Cursor: blink + shape (block/bar) without continuous full redraws

**Rules:** zero allocation in the render loop. Details:
[`../../PLAN.md`](../../PLAN.md) §6.
