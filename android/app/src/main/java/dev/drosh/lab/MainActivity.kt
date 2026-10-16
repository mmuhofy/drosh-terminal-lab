package dev.drosh.lab

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.drosh.lab.bench.FrameStats
import dev.drosh.lab.core.NativeCore
import dev.drosh.lab.core.TerminalSession
import dev.drosh.lab.input.AndroidKeys
import dev.drosh.lab.input.Encoder
import dev.drosh.lab.input.ExtraKeysView
import dev.drosh.lab.input.Selection
import dev.drosh.lab.input.SelectionText
import dev.drosh.lab.input.TerminalInputView
import dev.drosh.lab.render.CellMetrics
import dev.drosh.lab.render.FrameLoop
import dev.drosh.lab.render.GlyphAtlas
import dev.drosh.lab.render.TerminalRenderer

/**
 * Phase A thin shell: one shell session, a GL surface fed by the snapshot
 * bridge, and just enough input to type a command.
 *
 * What it deliberately does *not* have: tabs, config, extra keys, selection
 * (PLAN.md D12 and the Phase B task list).
 */
class MainActivity : Activity() {

    private lateinit var session: TerminalSession
    private lateinit var renderer: TerminalRenderer
    private lateinit var surface: GLSurfaceView
    private lateinit var input: TerminalInputView
    private lateinit var keys: ExtraKeysView
    private lateinit var status: TextView

    /** Frame timings; the status row shows them and a tap resets them. */
    private val stats = FrameStats()

    /** Cell geometry from the bundled font, filled in by [loadFont]. */
    private lateinit var metrics: CellMetrics

    /** Pixel size the glyphs are rasterised at. */
    private var fontPx = 0f

    private var scrollAccumulator = 0f
    private var exited = false

    /** The current selection; long-press selects a word, dragging extends it. */
    private val selection = Selection()
    private var draggingSelection = false

    /**
     * The frame pump: the engine's wakeup flag is polled once per vsync instead
     * of every 32 ms, so output reaches the screen on the next refresh rather
     * than up to a poll interval later.
     */
    private val frameLoop = FrameLoop(
        onWakeup = {
            reportGlErrors()
            surface.requestRender()
            drainEvents()
        },
        onBlink = {
            reportGlErrors()
            renderer.cursorVisible = !renderer.cursorVisible
            surface.requestRender()
            drainEvents()
            status.text = stats.summary()
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The font is the first thing that has to work: without its metrics there
        // is no cell, and without a cell there is no grid.
        if (!loadFont()) {
            return
        }

        val atlas = GlyphAtlas()
        session = TerminalSession(
            program = SHELL,
            env = mapOf(
                "HOME" to filesDir.absolutePath,
                "PWD" to filesDir.absolutePath,
                "TMPDIR" to cacheDir.absolutePath,
                // mksh reads PS1 from the environment; without it an interactive
                // shell can look like it printed nothing at all.
                "PS1" to "$ ",
            ),
            cwd = filesDir.absolutePath,
        )

        renderer = TerminalRenderer(session, atlas, metrics, fontPx, selection, stats)
        renderer.onGridChanged = { columns, rows ->
            // The renderer reports from the GL thread; the session is only ever
            // touched from the UI thread.
            runOnUiThread {
                session.resize(columns, rows, metrics.width.toInt(), metrics.height.toInt())
                surface.requestRender()
            }
        }

        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        input = TerminalInputView(this)
        input.onText = { text ->
            // A soft keyboard commits text; the row's armed Ctrl turns the first
            // character into a control code, which is how a phone sends Ctrl+C.
            session.write(Encoder.text(text, Encoder.Mods(ctrl = keys.ctrlArmed)))
            keys.clearCtrl()
        }
        input.onBackspace = { session.write(byteArrayOf(0x7f)) }
        input.onKey = { keyCode, event -> handleHardwareKey(keyCode, event) }

        keys = ExtraKeysView(this)
        keys.onKey = { named, ctrl ->
            Encoder.sequence(named, Encoder.Mods(ctrl = ctrl), appCursor())?.let { session.write(it) }
        }
        keys.onCopy = { copySelection() }
        keys.onPaste = { pasteClipboard() }

        // The row scrolls sideways: Ctrl/Esc/Tab/arrows fit a phone, but the row
        // is meant to grow (Ctrl+C, other keys) without a layout rewrite.
        val keysScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                keys,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        // Phase B's acceptance numbers need to be readable on the device, not
        // only in a profiler. Tapping the line resets the window.
        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(STATUS_COLOR)
            setBackgroundColor(STATUS_BACKGROUND)
            setPadding(STATUS_PADDING, STATUS_PADDING, STATUS_PADDING, STATUS_PADDING)
            maxLines = 2
            text = stats.summary()
            setOnClickListener {
                stats.reset()
                text = stats.summary()
            }
        }

        // The keys row takes its own strip of the window, so the grid is told
        // about a smaller surface instead of drawing under the buttons.
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.addView(
            surface,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        column.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        column.addView(
            keysScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        column.addView(
            input,
            // One pixel: it only exists so the soft keyboard has a focused text
            // editor to talk to, and touches stay on the surface.
            LinearLayout.LayoutParams(1, 1),
        )
        setContentView(column)

        surface.setOnTouchListener(gestureListener())

        startSession()
    }

    override fun onResume() {
        super.onResume()
        // A font failure leaves no surface: `onCreate` returned early and there is
        // nothing to start.
        if (!::surface.isInitialized) {
            return
        }
        surface.onResume()
        frameLoop.poll = { session.pollWakeup() }
        frameLoop.start()
        // Numbers from this session only: a resume is a fresh start.
        stats.reset()
    }

    override fun onPause() {
        frameLoop.stop()
        if (::surface.isInitialized) {
            surface.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        frameLoop.stop()
        if (::session.isInitialized) {
            session.close()
        }
        super.onDestroy()
    }

    /** A GL failure is worth a toast: the screen would otherwise just be wrong. */
    private fun reportGlErrors() {
        renderer.lastGlError?.let { message ->
            renderer.lastGlError = null
            toast("renderer: $message")
        }
    }

    /**
     * Load the bundled faces and ask the Rust stack for the cell metrics.
     *
     * The fonts travel inside the APK (`assets/fonts/`) so a cell is the same
     * size on every device; a failure here is a packaging bug and is reported as
     * one instead of starting a session that cannot be laid out.
     */
    private fun loadFont(): Boolean {
        val density = resources.displayMetrics.density
        fontPx = FONT_SIZE_DP * density

        return try {
            val regular = assets.open(REGULAR_FONT).use { it.readBytes() }
            val bold = assets.open(BOLD_FONT).use { it.readBytes() }

            val init = NativeCore.nativeInitFont(regular, bold)
            if (init != NativeCore.OK) {
                toast("font: nativeInitFont failed ($init)")
                return false
            }

            val loaded = CellMetrics.fromCore(fontPx)
            if (loaded == null) {
                toast("font: the faces produced no cell metrics")
                return false
            }
            metrics = loaded

            Log.i(
                TAG,
                "font: cell ${metrics.width}x${metrics.height}px at ${fontPx}px " +
                    "(density $density, ascent ${metrics.ascent})",
            )
            true
        } catch (error: Throwable) {
            Log.e(TAG, "font assets failed to load", error)
            toast("font: ${error.message}")
            false
        }
    }

    private fun startSession() {
        val display = resources.displayMetrics
        val columns = (display.widthPixels / metrics.width).toInt().coerceAtLeast(2)
        val rows = (display.heightPixels / metrics.height).toInt().coerceAtLeast(1)

        val result = try {
            session.start(columns, rows, metrics.width.toInt(), metrics.height.toInt())
        } catch (error: Throwable) {
            toast("core library failed to load: ${error.message}")
            Log.e(TAG, "native setup failed", error)
            -1
        }

        if (result != NativeCore.OK) {
            toast("nativeCreateSession failed (code $result)")
            Log.e(TAG, "nativeCreateSession returned $result")
            return
        }

        Log.i(
            TAG,
            "core ${session.coreVersion} session ${session.handle}: " +
                "${session.columns}x${session.rows} cells of " +
                "${metrics.width.toInt()}x${metrics.height.toInt()}px",
        )
    }

    /**
     * Send a key that arrived as a key event: a hardware keyboard, or an IME
     * that reports keys instead of committing text.
     *
     * Returning `false` lets the IME handle it, which is what must happen for a
     * plain printable key — the text path already carries those.
     */
    private fun handleHardwareKey(keyCode: Int, event: KeyEvent): Boolean {
        val named = AndroidKeys.named(keyCode)
        if (named != null) {
            val mods = AndroidKeys.mods(event, keys.ctrlArmed)
            Encoder.sequence(named, mods, appCursor())?.let { session.write(it) }
            keys.clearCtrl()
            return true
        }

        // A printable key while Ctrl (or Alt) is held: Ctrl+C has to become 0x03
        // even though the character would normally travel as text.
        val armed = keys.ctrlArmed || event.isAltPressed
        val codepoint = event.unicodeChar
        if (armed && codepoint != 0) {
            val character = String(Character.toChars(codepoint))
            session.write(Encoder.text(character, AndroidKeys.mods(event, keys.ctrlArmed)))
            keys.clearCtrl()
            return true
        }

        return false
    }

    /** DECCKM: full-screen programs ask for the `ESC O` cursor keys. */
    private fun appCursor(): Boolean {
        if (!session.isAlive) {
            return false
        }
        val modes = session.stateArray[NativeCore.STATE_MODE_BITS]
        return modes and NativeCore.MODE_APP_CURSOR != 0
    }

    private fun gestureListener(): View.OnTouchListener {
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    // A tap ends the selection instead of clearing the screen.
                    if (!selection.isEmpty) {
                        selection.clear()
                        surface.requestRender()
                        return true
                    }
                    focusInput()
                    return true
                }

                override fun onLongPress(event: MotionEvent) {
                    // The mobile convention: a long press picks a word, and
                    // dragging from there extends the selection.
                    selectWordAt(event.x, event.y)
                    draggingSelection = true
                }

                override fun onDoubleTap(event: MotionEvent): Boolean {
                    selectWordAt(event.x, event.y)
                    return true
                }

                override fun onScroll(
                    first: MotionEvent?,
                    current: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (draggingSelection) {
                        selection.extend(cellRow(current.y), cellColumn(current.x))
                        surface.requestRender()
                        return true
                    }

                    // Finger down (positive dy) scrolls back into the history.
                    scrollAccumulator += distanceY
                    val rowsMoved = (scrollAccumulator / metrics.height).toInt()
                    if (rowsMoved != 0) {
                        scrollAccumulator -= rowsMoved * metrics.height
                        session.scroll(rowsMoved)
                        surface.requestRender()
                    }
                    return true
                }
            },
        )

        return View.OnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                draggingSelection = false
            }
            detector.onTouchEvent(event)
        }
    }

    /** The grid row under a vertical surface coordinate. */
    private fun cellRow(y: Float): Int {
        val rows = session.rows
        if (rows <= 0) {
            return 0
        }
        return (y / metrics.height).toInt().coerceIn(0, rows - 1)
    }

    /** The grid column under a horizontal surface coordinate. */
    private fun cellColumn(x: Float): Int {
        val columns = session.columns
        if (columns <= 0) {
            return 0
        }
        return (x / metrics.width).toInt().coerceIn(0, columns - 1)
    }

    private fun selectWordAt(x: Float, y: Float) {
        val cells = session.cells ?: return
        val columns = session.columns
        if (columns <= 0 || session.rows <= 0) {
            return
        }

        val row = cellRow(y)
        val column = cellColumn(x)
        val range = Selection.wordRange(row, column, columns) { r, c ->
            val at = (r * columns + c) * NativeCore.CELL_BYTES
            cells.getInt(at + NativeCore.CELL_OFFSET_CODEPOINT).toChar()
        }

        selection.select(range[0], range[1], range[2], range[3])
        surface.requestRender()
    }

    private fun copySelection() {
        val cells = session.cells ?: return
        if (selection.isEmpty) {
            toast("nothing selected — long-press a word first")
            return
        }

        val text = SelectionText.extract(cells, session.columns, selection)
        if (text.isEmpty()) {
            toast("the selection is empty")
            return
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, text))
        toast("copied ${text.length} characters")
    }

    private fun pasteClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            toast("the clipboard is empty")
            return
        }

        val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        if (text.isEmpty()) {
            return
        }

        if (text.length > PASTE_LIMIT) {
            toast("paste truncated to $PASTE_LIMIT characters")
        }
        val capped = text.take(PASTE_LIMIT)

        // Mode 2004 lets the shell tell a paste from typing, which is what keeps
        // a multi-line paste from running line by line.
        val bracketed =
            session.stateArray[NativeCore.STATE_MODE_BITS] and NativeCore.MODE_BRACKETED_PASTE != 0
        if (bracketed) {
            session.writeUtf8("\u001b[200~$capped\u001b[201~")
        } else {
            session.writeUtf8(capped)
        }
    }

    private fun focusInput() {
        input.requestFocus()
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun drainEvents() {
        for (event in session.drainEvents()) {
            when {
                event == "exit" || event.startsWith("child_exit") -> {
                    if (!exited) {
                        exited = true
                        toast("shell exited ($event)")
                    }
                }
                event.startsWith("title:") -> Log.i(TAG, "title ${event.removePrefix("title:")}")
                event == "bell" -> Log.i(TAG, "bell")
                else -> Log.i(TAG, "event $event")
            }
        }
    }

    private fun toast(message: String) {
        Log.w(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "DroshLab"
        const val SHELL = "/system/bin/sh"

        /** Font size in dp; the cell size and the whole grid follow from it. */
        const val FONT_SIZE_DP = 12f

        /** The faces bundled in the APK (Phase B: one font, everywhere identical). */
        const val REGULAR_FONT = "fonts/JetBrainsMonoNL-Regular.ttf"
        const val BOLD_FONT = "fonts/JetBrainsMonoNL-Bold.ttf"

        /** Label on the clipboard entry, so a paste is recognisable. */
        const val CLIPBOARD_LABEL = "drosh-terminal-lab"

        /** Pastes are capped: a phone clipboard is not a file transfer. */
        const val PASTE_LIMIT = 65_536

        /** The frame-stats line: readable, quiet, and its own tap target. */
        const val STATUS_COLOR = 0xff9a9a9a.toInt()
        const val STATUS_BACKGROUND = 0xff151515.toInt()
        const val STATUS_PADDING = 6
    }
}
