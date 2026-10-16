package dev.drosh.lab.render

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import dev.drosh.lab.core.NativeCore
import dev.drosh.lab.core.TerminalSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The Phase B renderer: one textured quad per visible glyph plus one per
 * non-default background, all in a single interleaved vertex buffer and a single
 * draw call, with the glyph coverage coming from the Rust font stack (D4) and
 * the cell geometry from its metrics.
 *
 * Per-cell glyph results are cached in parallel arrays, so a steady-state frame
 * does no map lookups and no allocation; the atlas is only touched when a cell's
 * content changed. Damage-limited redraw and instancing are the next steps in
 * the Phase B plan — this renderer still draws the whole grid.
 *
 * GL ES 3.0 as decided in D3; `glVersion` is logged so a device that only offers
 * GL ES 2 can be identified (open item 5 in PLAN.md §8).
 */
internal class TerminalRenderer(
    private val session: TerminalSession,
    private val atlas: GlyphAtlas,
    private val metrics: CellMetrics,
    /** Pixel size the glyphs are rasterised at. */
    private val fontPx: Float,
) : GLSurfaceView.Renderer {

    /** Set by the activity; called when the surface needs a different grid. */
    var onGridChanged: ((columns: Int, rows: Int) -> Unit)? = null

    /** Last GL failure, read and cleared by the activity's frame poller. */
    @Volatile
    var lastGlError: String? = null

    @Volatile
    var cursorVisible = true

    /** Logged once; useful when a device cannot do GL ES 3. */
    var glVersion: String = "unknown"
        private set

    private var program = 0
    private var projectionUniform = 0
    private var atlasUniform = 0
    private var texture = 0
    private var vertexBuffer = 0
    private var vertices: FloatBuffer? = null
    private var width = 0
    private var height = 0
    private var columns = 0
    private var rows = 0

    // Per-cell glyph cache (see `onDrawFrame`).
    private var cellCodepoints = IntArray(0)
    private var cellBold = BooleanArray(0)
    private var cellEntries = arrayOfNulls<GlyphEntry>(0)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"
        Log.i(TAG, "GL version: $glVersion")

        program = buildProgram()
        if (program == 0) {
            return
        }
        projectionUniform = GLES20.glGetUniformLocation(program, "uProjection")
        atlasUniform = GLES20.glGetUniformLocation(program, "uAtlas")
        texture = buildTexture()

        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vertexBuffer = buffers[0]

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glClearColor(
            channel(Palette.DEFAULT_BACKGROUND, 16),
            channel(Palette.DEFAULT_BACKGROUND, 8),
            channel(Palette.DEFAULT_BACKGROUND, 0),
            1f,
        )
    }

    override fun onSurfaceChanged(gl: GL10?, surfaceWidth: Int, surfaceHeight: Int) {
        width = surfaceWidth
        height = surfaceHeight
        GLES20.glViewport(0, 0, width, height)

        val newColumns = (width / metrics.width).toInt().coerceAtLeast(MIN_COLUMNS)
        val newRows = (height / metrics.height).toInt().coerceAtLeast(MIN_ROWS)
        columns = newColumns
        rows = newRows
        ensureCapacity(newColumns, newRows)
        resetCellCache(newColumns, newRows)
        onGridChanged?.invoke(newColumns, newRows)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val buffer = session.cells ?: return
        val floats = vertices ?: return
        // Wait for the activity's resize to land before drawing a stale grid.
        if (program == 0 || session.columns != columns || session.rows != rows) {
            return
        }
        if (session.pump() == NativeCore.INVALID_HANDLE) {
            return
        }
        if (atlas.dirty) {
            uploadAtlas()
        }

        val state = session.stateArray
        val cellWidth = metrics.width
        val cellHeight = metrics.height
        val baselineOffset = metrics.ascent
        val cursorRow = state[NativeCore.STATE_CURSOR_ROW]
        val cursorColumn = state[NativeCore.STATE_CURSOR_COL]
        val cursorShape = state[NativeCore.STATE_CURSOR_SHAPE]
        val atlasSize = atlas.size.toFloat()

        floats.clear()

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val cell = row * columns + column
                val at = cell * NativeCore.CELL_BYTES
                val codepoint = buffer.getInt(at + NativeCore.CELL_OFFSET_CODEPOINT)
                val fgEncoded = buffer.getInt(at + NativeCore.CELL_OFFSET_FG)
                val bgEncoded = buffer.getInt(at + NativeCore.CELL_OFFSET_BG)
                val flags = buffer.getShort(at + NativeCore.CELL_OFFSET_FLAGS).toInt() and 0xffff

                if (flags and NativeCore.FLAG_HIDDEN != 0) continue
                if (flags and NativeCore.FLAG_WIDE_CHAR_SPACER != 0) continue
                if (flags and NativeCore.FLAG_LEADING_WIDE_CHAR_SPACER != 0) continue

                var foreground = Palette.resolve(fgEncoded, Palette.DEFAULT_FOREGROUND)
                var background = Palette.resolve(bgEncoded, Palette.DEFAULT_BACKGROUND)
                val bold = flags and NativeCore.FLAG_BOLD != 0

                if (flags and NativeCore.FLAG_INVERSE != 0) {
                    val swap = foreground
                    foreground = background
                    background = swap
                }

                val cursorHere = cursorVisible &&
                    cursorShape != NativeCore.CURSOR_HIDDEN &&
                    row == cursorRow &&
                    column == cursorColumn
                if (cursorHere && cursorShape == NativeCore.CURSOR_BLOCK) {
                    // A block cursor is the cell drawn in the cursor colour.
                    background = Palette.CURSOR
                    foreground = Palette.DEFAULT_BACKGROUND
                }

                val x = column * cellWidth
                val y = row * cellHeight

                if (background != Palette.DEFAULT_BACKGROUND) {
                    addSolid(x, y, cellWidth, cellHeight, background)
                }

                val blank = codepoint == ' '.code
                if (!blank) {
                    // One lookup per cell *change*; the frame loop reads the cache.
                    var entry = cellEntries[cell]
                    if (entry == null || cellCodepoints[cell] != codepoint || cellBold[cell] != bold) {
                        entry = atlas.glyph(codepoint, bold, fontPx)
                        cellEntries[cell] = entry
                        cellCodepoints[cell] = codepoint
                        cellBold[cell] = bold
                    }

                    if (entry != null && !entry.isBlank) {
                        // `left` is relative to the pen position, `top` is the
                        // distance from the baseline up to the bitmap's top edge.
                        val left = x + entry.left
                        val glyphTop = y + baselineOffset - entry.top
                        addGlyph(
                            left,
                            glyphTop,
                            entry.width,
                            entry.height,
                            entry.x / atlasSize,
                            entry.y / atlasSize,
                            (entry.x + entry.width) / atlasSize,
                            (entry.y + entry.height) / atlasSize,
                            foreground,
                        )
                    }
                }

                if (flags and NativeCore.FLAG_ANY_UNDERLINE != 0) {
                    addSolid(x, y + baselineOffset + metrics.underline, cellWidth, metrics.rule, foreground)
                }
                if (flags and NativeCore.FLAG_STRIKEOUT != 0) {
                    // The font's own strikeout position is not on the wire yet
                    // (TODO.md); a third of the ascent above the baseline is the
                    // usual place and reads correctly at any size.
                    val strike = y + baselineOffset - metrics.ascent * 0.35f
                    addSolid(x, strike, cellWidth, metrics.rule, foreground)
                }

                if (cursorHere) {
                    when (cursorShape) {
                        NativeCore.CURSOR_BEAM ->
                            addSolid(x, y, cellWidth * 0.12f, cellHeight, Palette.CURSOR)
                        NativeCore.CURSOR_UNDERLINE ->
                            addSolid(x, y + cellHeight * 0.86f, cellWidth, cellHeight * 0.14f, Palette.CURSOR)
                        NativeCore.CURSOR_HOLLOW ->
                            addSolid(x, y + cellHeight * 0.9f, cellWidth, cellHeight * 0.1f, Palette.CURSOR)
                    }
                }
            }
        }

        draw(floats)
    }

    private fun draw(floats: FloatBuffer) {
        floats.flip()
        val count = floats.remaining() / FLOATS_PER_VERTEX
        if (count == 0) {
            return
        }

        GLES20.glUseProgram(program)
        GLES20.glUniform4f(projectionUniform, -1f, 1f, 2f / width, -2f / height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(atlasUniform, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            floats.remaining() * FLOAT_BYTES,
            floats,
            GLES20.GL_DYNAMIC_DRAW,
        )

        val stride = FLOATS_PER_VERTEX * FLOAT_BYTES
        GLES20.glEnableVertexAttribArray(ATTRIB_POSITION)
        GLES20.glVertexAttribPointer(ATTRIB_POSITION, 2, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(ATTRIB_TEX_COORD)
        GLES20.glVertexAttribPointer(ATTRIB_TEX_COORD, 2, GLES20.GL_FLOAT, false, stride, 2 * FLOAT_BYTES)
        GLES20.glEnableVertexAttribArray(ATTRIB_COLOR)
        GLES20.glVertexAttribPointer(ATTRIB_COLOR, 4, GLES20.GL_FLOAT, false, stride, 4 * FLOAT_BYTES)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)

        GLES20.glDisableVertexAttribArray(ATTRIB_POSITION)
        GLES20.glDisableVertexAttribArray(ATTRIB_TEX_COORD)
        GLES20.glDisableVertexAttribArray(ATTRIB_COLOR)
    }

    private fun addGlyph(
        left: Float,
        top: Float,
        bitmapWidth: Int,
        bitmapHeight: Int,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        color: Int,
    ) {
        addQuad(
            left,
            top,
            left + bitmapWidth,
            top + bitmapHeight,
            u0,
            v0,
            u1,
            v1,
            color,
        )
    }

    /** A solid rectangle: background, underline, strikeout or cursor. */
    private fun addSolid(x: Float, y: Float, rectWidth: Float, rectHeight: Float, color: Int) {
        addQuad(
            x,
            y,
            x + rectWidth,
            y + rectHeight,
            atlas.solidU,
            atlas.solidV,
            atlas.solidU,
            atlas.solidV,
            color,
        )
    }

    private fun addQuad(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        uLeft: Float,
        vTop: Float,
        uRight: Float,
        vBottom: Float,
        color: Int,
    ) {
        val floats = vertices ?: return
        val red = channel(color, 16)
        val green = channel(color, 8)
        val blue = channel(color, 0)

        vertex(floats, left, top, uLeft, vTop, red, green, blue)
        vertex(floats, right, top, uRight, vTop, red, green, blue)
        vertex(floats, left, bottom, uLeft, vBottom, red, green, blue)
        vertex(floats, right, top, uRight, vTop, red, green, blue)
        vertex(floats, right, bottom, uRight, vBottom, red, green, blue)
        vertex(floats, left, bottom, uLeft, vBottom, red, green, blue)
    }

    private fun vertex(
        floats: FloatBuffer,
        x: Float,
        y: Float,
        u: Float,
        v: Float,
        red: Float,
        green: Float,
        blue: Float,
    ) {
        floats.put(x)
        floats.put(y)
        floats.put(u)
        floats.put(v)
        floats.put(red)
        floats.put(green)
        floats.put(blue)
        floats.put(1f)
    }

    private fun ensureCapacity(columns: Int, rows: Int) {
        // Worst case: a background quad and a glyph quad for every cell, plus the
        // cursor. Allocated on resize, never in the frame loop.
        val quads = columns * rows * 2 + 4
        val floats = quads * QUAD_VERTICES * FLOATS_PER_VERTEX
        if (vertices == null || floats > vertices!!.capacity()) {
            vertices = ByteBuffer
                .allocateDirect(floats * FLOAT_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
    }

    private fun resetCellCache(columns: Int, rows: Int) {
        val cells = columns * rows
        if (cellEntries.size == cells) {
            // Same grid: only the stored codepoints have to be forgotten.
            java.util.Arrays.fill(cellCodepoints, -1)
            return
        }
        cellCodepoints = IntArray(cells) { -1 }
        cellBold = BooleanArray(cells)
        cellEntries = arrayOfNulls(cells)
    }

    /** The atlas is a single-channel texture; the shader tints its coverage. */
    private fun buildTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val handle = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handle)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // One byte per row is not a multiple of four: without this, rows would be
        // read with padding and every glyph would shear.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        uploadAtlas()
        return handle
    }

    private fun uploadAtlas() {
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES30.GL_R8,
            atlas.size,
            atlas.size,
            0,
            GLES30.GL_RED,
            GLES20.GL_UNSIGNED_BYTE,
            atlas.directPixels(),
        )
    }

    private fun buildProgram(): Int {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertexShader == 0 || fragmentShader == 0) {
            return 0
        }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            report("link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            report("shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun report(message: String) {
        Log.e(TAG, message)
        lastGlError = message
    }

    private fun channel(color: Int, shift: Int): Float = (color shr shift and 0xff) / 255f

    private companion object {
        const val TAG = "DroshLab"
        const val MIN_COLUMNS = 2
        const val MIN_ROWS = 1
        const val FLOAT_BYTES = 4
        const val QUAD_VERTICES = 6
        const val FLOATS_PER_VERTEX = 8
        const val ATTRIB_POSITION = 0
        const val ATTRIB_TEX_COORD = 1
        const val ATTRIB_COLOR = 2

        val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            layout(location = 2) in vec4 aColor;
            uniform vec4 uProjection;
            out vec2 vTexCoord;
            out vec4 vColor;
            void main() {
                vTexCoord = aTexCoord;
                vColor = aColor;
                gl_Position = vec4(uProjection.xy + uProjection.zw * aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            // Single channel (GL_R8): the glyph coverage the Rust stack rasterised.
            uniform sampler2D uAtlas;
            in vec2 vTexCoord;
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                float coverage = texture(uAtlas, vTexCoord).r;
                fragColor = vec4(vColor.rgb, vColor.a * coverage);
            }
        """.trimIndent()
    }
}
