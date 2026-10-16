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

**Phase B (current):** the code that exists is `TerminalRenderer` +
`GlyphAtlas` + `CellMetrics` + `Palette` in
`android/app/src/main/java/dev/drosh/lab/render/`. The glyphs are no longer
baked from Android's typeface: Rust rasterises one coverage mask per codepoint
(`ffi/font.rs` → `GlyphAtlas`), the cell geometry comes from the same font
(`CellMetrics` ← `nativeFontMetrics`), and the atlas is a single-channel
(`GL_R8`) texture the shader tints. Still missing from the components above:
instancing, the damage-limited loop, the size buckets and the per-cell colour
batching — the renderer still draws the whole grid every frame.
