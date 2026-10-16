package dev.drosh.lab

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import dev.drosh.lab.core.NativeCore
import dev.drosh.lab.core.TerminalSession
import dev.drosh.lab.input.AndroidKeys
import dev.drosh.lab.input.Encoder
import dev.drosh.lab.input.ExtraKeysView
import dev.drosh.lab.input.TerminalInputView
import dev.drosh.lab.render.CellMetrics
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

    /** Cell geometry from the bundled font, filled in by [loadFont]. */
    private lateinit var metrics: CellMetrics

    /** Pixel size the glyphs are rasterised at. */
    private var fontPx = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var blinkMillis = 0L
    private var scrollAccumulator = 0f
    private var exited = false

    private val framePoller = object : Runnable {
        override fun run() {
            if (session.pollWakeup()) {
                surface.requestRender()
                drainEvents()
            }

            renderer.lastGlError?.let { message ->
                renderer.lastGlError = null
                toast("renderer: $message")
            }

            blinkMillis += POLL_MILLIS
            if (blinkMillis >= BLINK_MILLIS) {
                blinkMillis = 0
                renderer.cursorVisible = !renderer.cursorVisible
                surface.requestRender()
                drainEvents()
            }

            handler.postDelayed(this, POLL_MILLIS)
        }
    }

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

        renderer = TerminalRenderer(session, atlas, metrics, fontPx)
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
            keys,
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
        handler.removeCallbacks(framePoller)
        handler.postDelayed(framePoller, POLL_MILLIS)
    }

    override fun onPause() {
        handler.removeCallbacks(framePoller)
        if (::surface.isInitialized) {
            surface.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(framePoller)
        if (::session.isInitialized) {
            session.close()
        }
        super.onDestroy()
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
                    focusInput()
                    return true
                }

                override fun onScroll(
                    first: MotionEvent?,
                    current: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
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

        return View.OnTouchListener { _, event -> detector.onTouchEvent(event) }
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

        const val POLL_MILLIS = 32L
        const val BLINK_MILLIS = 500L

        /** Font size in dp; the cell size and the whole grid follow from it. */
        const val FONT_SIZE_DP = 12f

        /** The faces bundled in the APK (Phase B: one font, everywhere identical). */
        const val REGULAR_FONT = "fonts/JetBrainsMonoNL-Regular.ttf"
        const val BOLD_FONT = "fonts/JetBrainsMonoNL-Bold.ttf"
    }
}
