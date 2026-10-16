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
 * non-default background, in a single interleaved vertex buffer and a single
 * draw call, with the glyph coverage coming from the Rust font stack (D4) and
 * the cell geometry from its metrics.
 *
 * **Damage-limited:** every grid row owns a fixed slot in the buffer. A frame
 * rebuilds only the rows the engine reported as damaged (plus the row the cursor
 * sits on or just left) and uploads just those slots with `glBufferSubData`;
 * unused slots hold degenerate quads, which cost nothing to rasterise. The
 * engine gives a column range per damaged line, but the whole row is rebuilt —
 * that keeps the slot arithmetic honest, and a row is a few hundred quads.
 *
 * Within a row, glyph lookups go through a per-cell cache, so a steady-state
 * frame neither allocates nor looks anything up.
 *
 * Still to come in Phase B: instancing, the size buckets and the font's own
 * strikeout metrics. GL ES 3.0 as decided in D3.
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
    private val atlasSize = atlas.size.toFloat()
    private var width = 0
    private var height = 0
    private var columns = 0
    private var rows = 0

    // Slot layout: each row owns `floatsPerRow` floats.
    private var rowSlotQuads = 0
    private var floatsPerRow = 0
    /** The next frame rebuilds every row (first frame, resize, scroll). */
    private var fullRebuild = true

    // Per-cell glyph cache (see `buildRow`).
    private var cellCodepoints = IntArray(0)
    private var cellBold = BooleanArray(0)
    private var cellEntries = arrayOfNulls<GlyphEntry>(0)

    // Rows to rebuild this frame.
    private var dirtyRows = BooleanArray(0)
    private var lastCursorRow = -1
    private var lastCursorVisible = false

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
        resetGrid(newColumns, newRows)
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

        // Everything that can bail out has bailed out: from here the damage is
        // consumed, so the frame has to be drawn.
        val damage = session.pump()
        if (damage == NativeCore.INVALID_HANDLE) {
            return
        }

        if (atlas.dirty) {
            uploadAtlas()
        }

        val state = session.stateArray
        val cursorRow = state[NativeCore.STATE_CURSOR_ROW]
        val cursorShape = state[NativeCore.STATE_CURSOR_SHAPE]
        val cursorMoved = cursorRow != lastCursorRow || cursorVisible != lastCursorVisible

        if (fullRebuild || damage == NativeCore.DAMAGE_FULL) {
            for (row in 0 until rows) {
                buildRow(row, buffer, state)
            }
            uploadAllRows(floats)
            fullRebuild = false
        } else {
            for (line in 0 until damage) {
                markDirty(session.damageRow(line))
            }

            // The cursor is not part of the engine's damage: it moves by itself.
            if (cursorMoved) {
                markDirty(cursorRow)
                markDirty(lastCursorRow)
            }

            for (row in 0 until rows) {
                if (dirtyRows[row]) {
                    buildRow(row, buffer, state)
                    uploadRow(floats, row)
                    dirtyRows[row] = false
                }
            }
        }

        lastCursorRow = cursorRow
        lastCursorVisible = cursorVisible
        draw(rows * rowSlotQuads * QUAD_VERTICES)

        if (cursorShape == NativeCore.CURSOR_HIDDEN) {
            lastCursorRow = -1
        }
    }

    /** Build every quad of one row into that row's slot. */
    private fun buildRow(row: Int, buffer: java.nio.ByteBuffer, state: IntArray) {
        val floats = vertices ?: return
        val base = row * floatsPerRow
        floats.limit(base + floatsPerRow)
        floats.position(base)

        val cursorRow = state[NativeCore.STATE_CURSOR_ROW]
        val cursorColumn = state[NativeCore.STATE_CURSOR_COL]
        val cursorShape = state[NativeCore.STATE_CURSOR_SHAPE]

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

            val x = column * metrics.width
            val y = row * metrics.height

            if (background != Palette.DEFAULT_BACKGROUND) {
                addSolid(x, y, metrics.width, metrics.height, background)
            }

            if (codepoint != ' '.code) {
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
                    val glyphLeft = x + entry.left
                    val glyphTop = y + metrics.ascent - entry.top
                    addGlyph(
                        glyphLeft,
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
                addSolid(
                    x,
                    y + metrics.ascent + metrics.underline,
                    metrics.width,
                    metrics.rule,
                    foreground,
                )
            }
            if (flags and NativeCore.FLAG_STRIKEOUT != 0) {
                // The font's own strikeout position is not on the wire yet
                // (TODO.md); a third of the ascent above the baseline is the
                // usual place and reads correctly at any size.
                addSolid(
                    x,
                    y + metrics.ascent - metrics.ascent * 0.35f,
                    metrics.width,
                    metrics.rule,
                    foreground,
                )
            }

            if (cursorHere) {
                when (cursorShape) {
                    NativeCore.CURSOR_BEAM ->
                        addSolid(x, y, metrics.width * 0.12f, metrics.height, Palette.CURSOR)
                    NativeCore.CURSOR_UNDERLINE ->
                        addSolid(
                            x,
                            y + metrics.height * 0.86f,
                            metrics.width,
                            metrics.height * 0.14f,
                            Palette.CURSOR,
                        )
                    NativeCore.CURSOR_HOLLOW ->
                        addSolid(
                            x,
                            y + metrics.height * 0.9f,
                            metrics.width,
                            metrics.height * 0.1f,
                            Palette.CURSOR,
                        )
                }
            }
        }

        // Pad the rest of the slot with degenerate quads: zero area, so they
        // rasterise nothing and the row keeps its fixed stride.
        var used = (floats.position() - base) / FLOATS_PER_QUAD
        if (used > rowSlotQuads) {
            Log.e(TAG, "row $row used $used quads, slot holds $rowSlotQuads")
            used = rowSlotQuads
        }
        while (used < rowSlotQuads) {
            addDegenerateQuad(floats)
            used += 1
        }

        floats.limit(floats.capacity())
    }

    private fun markDirty(row: Int) {
        if (row in dirtyRows.indices) {
            dirtyRows[row] = true
        }
    }

    private fun uploadAllRows(floats: FloatBuffer) {
        floats.position(0)
        floats.limit(rows * floatsPerRow)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            rows * floatsPerRow * FLOAT_BYTES,
            floats,
            GLES20.GL_DYNAMIC_DRAW,
        )
        floats.limit(floats.capacity())
    }

    private fun uploadRow(floats: FloatBuffer, row: Int) {
        floats.position(row * floatsPerRow)
        floats.limit(row * floatsPerRow + floatsPerRow)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
        GLES20.glBufferSubData(
            GLES20.GL_ARRAY_BUFFER,
            row * floatsPerRow * FLOAT_BYTES,
            floatsPerRow * FLOAT_BYTES,
            floats,
        )
        floats.limit(floats.capacity())
    }

    private fun draw(vertexCount: Int) {
        if (vertexCount == 0) {
            return
        }

        GLES20.glUseProgram(program)
        GLES20.glUniform4f(projectionUniform, -1f, 1f, 2f / width, -2f / height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(atlasUniform, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)

        val stride = FLOATS_PER_VERTEX * FLOAT_BYTES
        GLES20.glEnableVertexAttribArray(ATTRIB_POSITION)
        GLES20.glVertexAttribPointer(ATTRIB_POSITION, 2, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(ATTRIB_TEX_COORD)
        GLES20.glVertexAttribPointer(ATTRIB_TEX_COORD, 2, GLES20.GL_FLOAT, false, stride, 2 * FLOAT_BYTES)
        GLES20.glEnableVertexAttribArray(ATTRIB_COLOR)
        GLES20.glVertexAttribPointer(ATTRIB_COLOR, 4, GLES20.GL_FLOAT, false, stride, 4 * FLOAT_BYTES)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

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
        addQuad(left, top, left + bitmapWidth, top + bitmapHeight, u0, v0, u1, v1, color)
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

    /** A quad whose six vertices coincide: nothing to rasterise. */
    private fun addDegenerateQuad(floats: FloatBuffer) {
        repeat(QUAD_VERTICES) {
            vertex(floats, 0f, 0f, atlas.solidU, atlas.solidV, 0f, 0f, 0f)
        }
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

    /** Called on resize: new slot layout, new cell cache, everything rebuilt. */
    private fun resetGrid(newColumns: Int, newRows: Int) {
        rowSlotQuads = newColumns * QUADS_PER_CELL + SLACK_QUADS_PER_ROW
        floatsPerRow = rowSlotQuads * QUAD_VERTICES * FLOATS_PER_VERTEX

        val floats = newRows * floatsPerRow
        if (vertices == null || floats > vertices!!.capacity()) {
            vertices = ByteBuffer
                .allocateDirect(floats * FLOAT_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        } else {
            vertices!!.clear()
        }

        // No need to wipe the buffer: `fullRebuild` writes every slot — with real
        // quads or with degenerate padding — before anything is drawn.

        val cells = newColumns * newRows
        cellCodepoints = IntArray(cells) { -1 }
        cellBold = BooleanArray(cells)
        cellEntries = arrayOfNulls(cells)

        dirtyRows = BooleanArray(newRows)
        fullRebuild = true
        lastCursorRow = -1
        lastCursorVisible = false
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
        const val FLOATS_PER_QUAD = QUAD_VERTICES * FLOATS_PER_VERTEX
        const val ATTRIB_POSITION = 0
        const val ATTRIB_TEX_COORD = 1
        const val ATTRIB_COLOR = 2

        /**
         * Quads one cell can need: background, glyph, underline, strikeout and a
         * beam/underline/hollow cursor. A block cursor replaces the background.
         */
        const val QUADS_PER_CELL = 5
        const val SLACK_QUADS_PER_ROW = 4

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
