package dev.drosh.lab.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil

/**
 * Phase A glyph source: ASCII 32..126 baked once from Android's monospace
 * typeface into a coverage atlas. The shader tints the coverage with the cell's
 * foreground color, so one texture serves every color.
 *
 * Decision D4 puts the real font stack in Rust (`rustybuzz` + `swash`, Phase B,
 * Gate 2). This atlas exists so Phase A can prove the *data path*; the interface
 * it presents to the renderer (a uv rect per glyph index plus the cell metrics)
 * is exactly what the Rust rasterizer will fill in Phase B. Anything outside
 * ASCII renders as `?` until then.
 */
internal class AsciiAtlas(context: Context, fontSizeDp: Float = 12f) {

    companion object {
        const val COLUMNS = 16
        const val ROWS = 6
        const val FIRST_CODEPOINT = 32
        const val LAST_CODEPOINT = 126

        /** Glyph count (95) plus one solid block used for rects and the cursor. */
        const val SOLID_INDEX = 95
        private const val CELL_COUNT = SOLID_INDEX + 1

        /** `'?'` — the placeholder for every codepoint outside ASCII. */
        private const val UNKNOWN_CODEPOINT = 63
        private const val UV_STRIDE = 4
    }

    val cellWidth: Int
    val cellHeight: Int
    val bitmap: Bitmap

    /** `index * 4 + {0,1,2,3}` = uLeft, vTop, uRight, vBottom (v grows downwards). */
    private val uvRects = FloatArray(CELL_COUNT * UV_STRIDE)

    /** Center of the solid block, for background rects, underlines and cursors. */
    val solidU: Float
    val solidV: Float

    init {
        val density = context.resources.displayMetrics.density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = fontSizeDp * density
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
        }

        val metrics = paint.fontMetrics
        // A terminal cell is the advance width and the ascent..descent height;
        // every glyph lives inside that box, so the grid never reflows.
        cellWidth = ceil(paint.measureText("M")).toInt().coerceAtLeast(1)
        cellHeight = ceil(metrics.descent - metrics.ascent).toInt().coerceAtLeast(1)

        bitmap = Bitmap.createBitmap(
            COLUMNS * cellWidth,
            ROWS * cellHeight,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)

        for (codepoint in FIRST_CODEPOINT..LAST_CODEPOINT) {
            val index = codepoint - FIRST_CODEPOINT
            val left = (index % COLUMNS) * cellWidth
            val top = (index / COLUMNS) * cellHeight
            canvas.drawText(
                codepoint.toChar().toString(),
                left.toFloat(),
                top + cellHeight - metrics.descent,
                paint,
            )
        }

        // The last cell is a filled block: rects reuse the glyph pipeline.
        val blockPaint = Paint().apply { color = Color.WHITE }
        val blockLeft = (SOLID_INDEX % COLUMNS) * cellWidth
        val blockTop = (SOLID_INDEX / COLUMNS) * cellHeight
        canvas.drawRect(
            blockLeft.toFloat(),
            blockTop.toFloat(),
            (blockLeft + cellWidth).toFloat(),
            (blockTop + cellHeight).toFloat(),
            blockPaint,
        )

        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        for (index in 0 until CELL_COUNT) {
            val column = index % COLUMNS
            val row = index / COLUMNS
            val at = index * UV_STRIDE
            uvRects[at] = column * cellWidth / width
            uvRects[at + 2] = (column + 1) * cellWidth / width
            // `GLUtils.texImage2D` hands the bitmap's rows to `glTexImage2D` in
            // bitmap order (top row first), and OpenGL maps the *first* row of
            // that data to v = 0 — so v here grows downwards through the image
            // and the smaller v is the glyph's top edge. Getting this backwards
            // mirrored every glyph vertically on the device (2026-10-16).
            uvRects[at + 1] = row * cellHeight / height
            uvRects[at + 3] = (row + 1) * cellHeight / height
        }

        solidU = (blockLeft + cellWidth / 2f) / width
        solidV = (blockTop + cellHeight / 2f) / height
    }

    fun indexOf(codepoint: Int): Int = when (codepoint) {
        in FIRST_CODEPOINT..LAST_CODEPOINT -> codepoint - FIRST_CODEPOINT
        else -> UNKNOWN_CODEPOINT - FIRST_CODEPOINT
    }

    fun uLeft(index: Int): Float = uvRects[index * UV_STRIDE]

    fun vTop(index: Int): Float = uvRects[index * UV_STRIDE + 1]

    fun uRight(index: Int): Float = uvRects[index * UV_STRIDE + 2]

    fun vBottom(index: Int): Float = uvRects[index * UV_STRIDE + 3]
}
