package dev.drosh.lab.render

import dev.drosh.lab.core.NativeCore

/**
 * The prototype's fixed palette (D12: no config system).
 *
 * The 16 ANSI colors, the primary foreground/background and [CURSOR] are
 * alacritty's defaults — `alacritty/src/config/color.rs`
 * (`NormalColors`/`BrightColors`/`Primary`, cursor defaults to the foreground),
 * and `DIM_FACTOR = 0.66` comes from `alacritty/src/display/color.rs`. Keeping
 * them identical means a screenshot from the lab and one from alacritty can be
 * compared directly.
 *
 * Indexed colors 16..255 use alacritty's own algorithm
 * (`alacritty/src/display/color.rs`, `fill_cube` / `fill_gray_ramp`).
 */
internal object Palette {

    const val DEFAULT_FOREGROUND = 0xd8d8d8
    const val DEFAULT_BACKGROUND = 0x181818
    const val CURSOR = DEFAULT_FOREGROUND

    private const val DIM_FACTOR = 0.66f

    private val ANSI = intArrayOf(
        0x181818, 0xac4242, 0x90a959, 0xf4bf75, 0x6a9fb5, 0xaa759f, 0x75b5aa, 0xd8d8d8,
        0x6b6b6b, 0xc55555, 0xaac474, 0xfeca88, 0x82b8c8, 0xc28cb8, 0x93d3c3, 0xf8f8f8,
    )

    /** Resolve a wire color (see `bridge/cell.rs`) to `0xRRGGBB`. */
    fun resolve(encoded: Int, defaultColor: Int): Int {
        val payload = encoded and NativeCore.COLOR_VALUE_MASK
        return when (encoded ushr NativeCore.COLOR_TAG_SHIFT) {
            NativeCore.COLOR_TAG_NAMED -> named(payload, defaultColor)
            NativeCore.COLOR_TAG_INDEXED -> indexed(payload)
            else -> payload
        }
    }

    /** `vte::ansi::NamedColor` values: 0..15 ANSI, 16..255 unused, then 256+. */
    private fun named(index: Int, defaultColor: Int): Int = when {
        index < 16 -> ANSI[index]
        index == 256 -> DEFAULT_FOREGROUND
        index == 257 -> DEFAULT_BACKGROUND
        index == 258 -> CURSOR
        index in 259..266 -> dim(ANSI[index - 259])
        index == 267 -> ANSI[15]
        index == 268 -> dim(DEFAULT_FOREGROUND)
        index == 269 -> dim(DEFAULT_BACKGROUND)
        else -> defaultColor
    }

    private fun indexed(index: Int): Int = when {
        index < 16 -> ANSI[index]
        index < 232 -> {
            val cube = index - 16
            rgb(level(cube / 36), level((cube % 36) / 6), level(cube % 6))
        }
        index < 256 -> {
            val value = (index - 232) * 10 + 8
            rgb(value, value, value)
        }
        else -> DEFAULT_FOREGROUND
    }

    private fun level(value: Int): Int = if (value == 0) 0 else value * 40 + 55

    private fun dim(color: Int): Int = rgb(
        ((color shr 16 and 0xff) * DIM_FACTOR).toInt(),
        ((color shr 8 and 0xff) * DIM_FACTOR).toInt(),
        ((color and 0xff) * DIM_FACTOR).toInt(),
    )

    private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b
}
