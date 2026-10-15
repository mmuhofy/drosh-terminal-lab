# android/renderer — GL ES 3.0 terminal renderer (Kotlin)

Terminalin görüntüsünü üreten tek yer. Rust'tan gelen **snapshot'ı** (flat cell
buffer + satır hasar listesi) ve **glyph bitmaplerini** çizer.

**Bileşenler (Faz B):**
- `atlas.kt` — bitmap atlas + 1x/1.5x/2x/3x boyut kovaları (Alacritty glyph
  cache/atlas deseni)
- `shader.kt` — GLES3 instancing (vertex + quad shader)
- `quads.kt` — instanced quad batch (glyph + bg/cursor rect)
- `loop.kt` — Choreographer + damage-limited redraw + frame skip politikası
- Cursor: blink + shape, sürekli tam redraw olmadan

**Kurallar:** render döngüsünde tahsis yok (zero-allocation). Ayrıntı:
[`../../PLAN.md`](../../PLAN.md) §6.
