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
import android.widget.FrameLayout
import android.widget.Toast
import dev.drosh.lab.core.NativeCore
import dev.drosh.lab.core.TerminalSession
import dev.drosh.lab.input.TerminalInputView
import dev.drosh.lab.render.AsciiAtlas
import dev.drosh.lab.render.TerminalRenderer

/**
 * Phase A thin shell: one shell session, a GL surface fed by the snapshot
 * bridge, and just enough input to type a command.
 *
 * What it deliberately does *not* have: tabs, config, extra keys, selection
 * (PLAN.md D12 and the Phase B task list).
 */
class MainActivity : Activity() {

    private lateinit var atlas: AsciiAtlas
    private lateinit var session: TerminalSession
    private lateinit var renderer: TerminalRenderer
    private lateinit var surface: GLSurfaceView
    private lateinit var input: TerminalInputView

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

        atlas = AsciiAtlas(this)
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

        renderer = TerminalRenderer(session, atlas)
        renderer.onGridChanged = { columns, rows ->
            // The renderer reports from the GL thread; the session is only ever
            // touched from the UI thread.
            runOnUiThread {
                session.resize(columns, rows, atlas.cellWidth, atlas.cellHeight)
                surface.requestRender()
            }
        }

        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        input = TerminalInputView(this)
        input.onText = { text -> session.writeUtf8(text) }
        input.onBackspace = { session.write(byteArrayOf(DEL.toByte())) }
        input.onKey = { keyCode, _ -> handleKey(keyCode) }

        val root = FrameLayout(this)
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            input,
            // One pixel, behind the surface: it only exists so the soft keyboard
            // has a focused text editor to talk to. Touches stay on the surface.
            FrameLayout.LayoutParams(1, 1),
        )
        setContentView(root)

        surface.setOnTouchListener(gestureListener())

        startSession()
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
        handler.removeCallbacks(framePoller)
        handler.postDelayed(framePoller, POLL_MILLIS)
    }

    override fun onPause() {
        handler.removeCallbacks(framePoller)
        surface.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(framePoller)
        session.close()
        super.onDestroy()
    }

    private fun startSession() {
        val metrics = resources.displayMetrics
        val columns = (metrics.widthPixels / atlas.cellWidth).coerceAtLeast(2)
        val rows = (metrics.heightPixels / atlas.cellHeight).coerceAtLeast(1)

        val result = try {
            session.start(columns, rows, atlas.cellWidth, atlas.cellHeight)
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
                "${atlas.cellWidth}x${atlas.cellHeight}px",
        )
    }

    private fun handleKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            session.writeUtf8("\n")
            true
        }
        KeyEvent.KEYCODE_DEL -> {
            session.write(byteArrayOf(DEL.toByte()))
            true
        }
        else -> false
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
                    val rowsMoved = (scrollAccumulator / atlas.cellHeight).toInt()
                    if (rowsMoved != 0) {
                        scrollAccumulator -= rowsMoved * atlas.cellHeight
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

        /** DEL: what xterm sends for backspace, and what a shell expects. */
        const val DEL = 0x7f
        const val POLL_MILLIS = 32L
        const val BLINK_MILLIS = 500L
    }
}
