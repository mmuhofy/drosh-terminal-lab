package dev.drosh.lab

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.drosh.lab.bench.FrameStats
import dev.drosh.lab.core.NativeCore
import dev.drosh.lab.core.TerminalSession
import dev.drosh.lab.guest.ChrootProbe
import dev.drosh.lab.guest.Rootfs
import dev.drosh.lab.input.AndroidKeys
import dev.drosh.lab.input.Encoder
import dev.drosh.lab.input.ExtraKeysView
import dev.drosh.lab.input.Mouse
import dev.drosh.lab.input.Selection
import dev.drosh.lab.input.SelectionText
import dev.drosh.lab.input.TerminalInputView
import dev.drosh.lab.render.CellMetrics
import dev.drosh.lab.render.FrameLoop
import dev.drosh.lab.render.GlyphAtlas
import dev.drosh.lab.render.Palette
import dev.drosh.lab.render.TerminalRenderer
import kotlin.math.abs

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

    /** Frame timings; the status row shows them and a tap freezes them. */
    private val stats = FrameStats()

    /** The log's header is written once, when the GL version is known. */
    private var benchHeaderWritten = false

    /** Cell geometry from the bundled font, filled in by [loadFont]. */
    private lateinit var metrics: CellMetrics

    /** Pixel size the glyphs are rasterised at. */
    private var fontPx = 0f

    private var scrollAccumulator = 0f
    private var exited = false

    /** True while the rootfs is downloading or extracting. */
    private var guestBusy = false

    /** The current selection; long-press selects a word, dragging extends it. */
    private val selection = Selection()
    private var draggingSelection = false

    /** Last cell a real pointer reported, so motion is sent per cell, not per pixel. */
    private var lastMouseColumn = -1
    private var lastMouseLine = -1

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
            if (!stats.isFrozen) {
                status.text = stats.summary()
            }
            ensureBenchHeader()
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
        keys.onProbe = { runChrootProbe() }
        keys.onGuest = { launchGuest() }

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

        // Phase B's acceptance numbers need to be readable on the device, and a
        // number that keeps moving cannot be reported: tapping freezes the
        // counters and writes one artefact — a `DroshLabBench` logcat line (and
        // the same line appended to `files/bench.log`) — that can be sent as-is.
        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(STATUS_COLOR)
            setBackgroundColor(STATUS_BACKGROUND)
            setPadding(STATUS_PADDING, STATUS_PADDING, STATUS_PADDING, STATUS_PADDING)
            maxLines = 2
            text = stats.summary()
            setOnClickListener { toggleFreeze(this) }
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
        surface.setOnGenericMotionListener { _, event -> handleScrollWheel(event) }

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

    /**
     * Focus reporting (mode `?1004`): the application is told when the window
     * gains or loses focus, exactly as alacritty does in `on_focus_change`.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!::session.isInitialized || !session.isAlive) {
            return
        }
        if (modes() and NativeCore.MODE_FOCUS_IN_OUT == 0) {
            return
        }
        session.write(if (hasFocus) FOCUS_IN else FOCUS_OUT)
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
     * Device, build, font and grid facts as one line: what a device run has to
     * record, and what `GET /info` answers.
     */
    private fun benchInfoLine(): String = buildString {
        // Locale.ROOT: on a comma-decimal device (Turkish, German…) `%.2f` would
        // emit `12,33`, and the numbers have to be machine-readable.
        fun number(value: Float, pattern: String) =
            java.lang.String.format(java.util.Locale.ROOT, pattern, value)

        append("device=").append(Build.MODEL)
        append(" android=").append(Build.VERSION.RELEASE)
        append(" kernel=").append(System.getProperty("os.version") ?: "?")
        append(" core=").append(if (::session.isInitialized) session.coreVersion else "?")
        append(" grid=").append(session.columns).append('x').append(session.rows)
        append(" cell=").append(number(metrics.width, "%.2f")).append('x')
            .append(number(metrics.height, "%.2f"))
        append(" ascent=").append(number(metrics.ascent, "%.2f"))
        append(" density=").append(number(resources.displayMetrics.density, "%.4f"))
        append(" gl=").append(if (::renderer.isInitialized) renderer.glVersion else "?")
        append(" font=").append(FONT_NAME)
    }

    /**
     * The header names the build a pulled log came from, so it is written once the
     * GL version is known — not in `onCreate`, where the surface does not exist yet
     * and the line said `gl=unknown`.
     *
     * Called from the frame loop's blink and before the first capture, whichever
     * comes first; an app that never draws a frame has nothing to measure anyway.
     */
    private fun ensureBenchHeader() {
        if (benchHeaderWritten || !::renderer.isInitialized || renderer.glVersion == "unknown") {
            return
        }
        benchHeaderWritten = true
        val header = "# %s · %s%n".format(java.util.Date().toString(), benchInfoLine())
        try {
            java.io.File(filesDir, BENCH_FILE).appendText(header)
        } catch (error: Throwable) {
            Log.w(BENCH_TAG, "cannot write the header to $BENCH_FILE: ${error.message}")
        }
    }

    /**
     * Freeze the counters (or resume them).
     *
     * Freezing is what makes a measurement sendable: the numbers stop moving, the
     * line turns amber so the frozen state is visible, and the same line goes to
     * logcat (`adb logcat -d -s DroshLabBench`) and to `files/bench.log`, which
     * the app's own endpoint serves (`GET /bench.log`).
     */
    private fun toggleFreeze(view: TextView) {
        val frozen = stats.toggleFreeze()
        view.setTextColor(if (frozen) STATUS_FROZEN_COLOR else STATUS_COLOR)
        view.text = stats.summary()

        if (!frozen) {
            // Resuming starts a fresh window: mixing two runs would make the
            // averages unreadable.
            stats.reset()
            return
        }

        ensureBenchHeader()

        val line = stats.logLine()
        Log.i(BENCH_TAG, line)
        try {
            java.io.File(filesDir, BENCH_FILE).appendText("$line\n")
        } catch (error: Throwable) {
            Log.w(BENCH_TAG, "cannot append to $BENCH_FILE: ${error.message}")
        }
        toast("frozen: $line")
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

    /** The terminal mode bits of the live session (0 when there is none). */
    private fun modes(): Int =
        if (session.isAlive) session.stateArray[NativeCore.STATE_MODE_BITS] else 0

    /** DECCKM: full-screen programs ask for the `ESC O` cursor keys. */
    private fun appCursor(): Boolean = modes() and NativeCore.MODE_APP_CURSOR != 0

    /** Is the application tracking the mouse (`?1000`/`?1002`/`?1003`)? */
    private fun mouseMode(): Boolean = Mouse.active(modes())

    private fun gestureListener(): View.OnTouchListener {
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    // With mouse tracking on, a tap is a left click — a finger
                    // has no buttons, so this is the mobile mapping Termux uses.
                    if (mouseMode()) {
                        clickAt(event.x, event.y)
                        if (!selection.isEmpty) {
                            selection.clear()
                            surface.requestRender()
                        }
                        return true
                    }

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

                    // While the application tracks the mouse, a drag is a wheel:
                    // xterm's 64/65 buttons. Scrolling the local history instead
                    // would hide the program's own scrolling from it.
                    if (mouseMode()) {
                        wheelBy(distanceY, current.x, current.y)
                        return true
                    }

                    // Finger down (positive dy) scrolls back into the history.
                    scrollAccumulator += distanceY
                    val rowsMoved = (scrollAccumulator / metrics.height).toInt()
                    if (rowsMoved != 0) {
                        scrollAccumulator -= rowsMoved * metrics.height
                        scrollByRows(rowsMoved)
                    }
                    return true
                }
            },
        )

        return View.OnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                draggingSelection = false
            }
            // A real pointer (USB/Bluetooth mouse, trackpad) is reported as it
            // is; touch falls through to the gesture mapping above.
            if (event.isFromSource(InputDevice.SOURCE_MOUSE) && handleMouseEvent(event)) {
                return@OnTouchListener true
            }
            detector.onTouchEvent(event)
        }
    }

    /** A left click at a surface position: press and release, as xterm expects. */
    private fun clickAt(x: Float, y: Float) {
        val bits = modes()
        val column = cellColumn(x)
        val line = cellRow(y)
        Mouse.report(Mouse.BUTTON_LEFT, true, column, line, bits)?.let(session::write)
        Mouse.report(Mouse.BUTTON_LEFT, false, column, line, bits)?.let(session::write)
    }

    /**
     * A finger drag becomes wheel reports while the application tracks the
     * mouse — the mobile mapping Termux uses (alacritty has no touch screen to
     * map, and its wheel path reports 64/65 to the program in mouse mode).
     */
    private fun wheelBy(distanceY: Float, x: Float, y: Float) {
        scrollAccumulator += distanceY
        val rows = (scrollAccumulator / metrics.height).toInt()
        if (rows == 0) {
            return
        }
        scrollAccumulator -= rows * metrics.height

        // Positive is the same direction as `session.scroll`: back into the
        // history, i.e. wheel up.
        val code = if (rows > 0) Mouse.WHEEL_UP else Mouse.WHEEL_DOWN
        val bits = modes()
        val column = cellColumn(x)
        val line = cellRow(y)
        repeat(abs(rows)) {
            Mouse.report(code, true, column, line, bits)?.let(session::write)
        }
    }

    /**
     * Buttons and motion from a real pointer, when the application asked for
     * mouse reports (alacritty's `mouse_report` path).
     */
    private fun handleMouseEvent(event: MotionEvent): Boolean {
        val bits = modes()
        if (!Mouse.active(bits)) {
            return false
        }

        val column = cellColumn(event.x)
        val line = cellRow(event.y)
        // A MotionEvent carries KeyEvent's meta-state bits, not the key-event
        // accessors (`isCtrlPressed` belongs to KeyEvent).
        val meta = event.metaState
        val mods = Encoder.Mods(
            ctrl = meta and KeyEvent.META_CTRL_ON != 0,
            alt = meta and KeyEvent.META_ALT_ON != 0,
            shift = meta and KeyEvent.META_SHIFT_ON != 0,
        )

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                lastMouseColumn = column
                lastMouseLine = line
                Mouse.report(mouseButton(event.actionButton), true, column, line, bits, mods)
                    ?.let(session::write)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                lastMouseColumn = -1
                lastMouseLine = -1
                Mouse.report(mouseButton(event.actionButton), false, column, line, bits, mods)
                    ?.let(session::write)
            }
            MotionEvent.ACTION_MOVE -> {
                // One report per cell, not per pixel (alacritty does the same).
                if (column == lastMouseColumn && line == lastMouseLine) {
                    return true
                }
                lastMouseColumn = column
                lastMouseLine = line
                motionCode(bits, event.buttonState)?.let { code ->
                    Mouse.report(code, true, column, line, bits, mods)?.let(session::write)
                }
            }
            else -> return false
        }
        return true
    }

    /** xterm's button numbers: left 0, middle 1, right 2. */
    private fun mouseButton(button: Int): Int = when (button) {
        MotionEvent.BUTTON_SECONDARY -> Mouse.BUTTON_RIGHT
        MotionEvent.BUTTON_TERTIARY -> Mouse.BUTTON_MIDDLE
        else -> Mouse.BUTTON_LEFT
    }

    /** Which motion code applies, or `null` when this mode reports none. */
    private fun motionCode(bits: Int, buttons: Int): Int? = when {
        buttons and MotionEvent.BUTTON_PRIMARY != 0 -> Mouse.MOTION_LEFT
        buttons and MotionEvent.BUTTON_TERTIARY != 0 -> Mouse.MOTION_MIDDLE
        buttons and MotionEvent.BUTTON_SECONDARY != 0 -> Mouse.MOTION_RIGHT
        // 1003 reports bare motion; 1002 only reports motion while a button is down.
        bits and NativeCore.MODE_MOUSE_MOTION != 0 -> Mouse.MOTION_NONE
        else -> null
    }

    /**
     * A local scrollback step, or the arrow keys `?1007` asks for in the
     * alternate screen (alacritty's `scroll_terminal`). The sequences are SS3
     * regardless of DECCKM, matching alacritty's bytes.
     */
    private fun scrollByRows(rows: Int, allowAlternate: Boolean = true) {
        if (rows == 0) {
            return
        }

        val bits = modes()
        val alternate = allowAlternate &&
            bits and NativeCore.MODE_ALT_SCREEN != 0 &&
            bits and NativeCore.MODE_ALTERNATE_SCROLL != 0
        if (alternate) {
            val sequence = if (rows > 0) "\u001bOA" else "\u001bOB"
            repeat(abs(rows)) { session.writeUtf8(sequence) }
            return
        }

        session.scroll(rows)
        surface.requestRender()
    }

    /**
     * A wheel notch from a real pointer: a wheel report when the application
     * tracks the mouse, a local scrollback step otherwise — the same split
     * alacritty's `scroll_terminal` makes.
     */
    private fun handleScrollWheel(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL ||
            !event.isFromSource(InputDevice.SOURCE_MOUSE)
        ) {
            return false
        }

        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (vertical != 0f) {
            if (mouseMode()) {
                val code = if (vertical > 0f) Mouse.WHEEL_UP else Mouse.WHEEL_DOWN
                Mouse.report(code, true, cellColumn(event.x), cellRow(event.y), modes())
                    ?.let(session::write)
            } else {
                // Shift bypasses `?1007`, as it does in alacritty.
                val shift = event.metaState and KeyEvent.META_SHIFT_ON != 0
                scrollByRows(if (vertical > 0f) 1 else -1, allowAlternate = !shift)
            }
        }

        val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (horizontal != 0f && mouseMode()) {
            val code = if (horizontal > 0f) Mouse.WHEEL_LEFT else Mouse.WHEEL_RIGHT
            Mouse.report(code, true, cellColumn(event.x), cellRow(event.y), modes())
                ?.let(session::write)
        }

        return true
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

    /**
     * Phase C, Gate 3: run `chroot-ng --probe` on the device and show what it
     * says. The probe is a process of its own, so it must not run on the UI
     * thread; the output goes to a dialog *and* to logcat, because the gate
     * artefact has to be readable without a screenshot.
     */
    private fun runChrootProbe() {
        if (ChrootProbe.binary(this) == null) {
            toast("probe: libchrootng.so is not in the APK")
            return
        }
        toast("probe: running…")
        Thread {
            val output = ChrootProbe.run(this, "--probe")
            runOnUiThread { showProbeOutput(output) }
        }.start()
    }

    private fun showProbeOutput(output: String) {
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
            this.text = output
        }
        AlertDialog.Builder(this)
            .setTitle("chroot-ng --probe")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Close", null)
            .show()
    }

    /**
     * Phase C: install the rootfs if it is not there yet, then hand the session
     * to chroot-ng. The install is a 30 MB download and an 80 MB extraction, so
     * it runs on its own thread with a dialog that says which stage it is in —
     * a silent spinner would make a failed install look like a hung app.
     */
    private fun launchGuest() {
        if (guestBusy) {
            return
        }
        if (Rootfs.isInstalled(this)) {
            enterGuest()
            return
        }

        guestBusy = true
        val progress = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(32, 32, 32, 32)
            text = "preparing"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Ubuntu ${Rootfs.VERSION}")
            .setView(progress)
            .setCancelable(false)
            .create()
        dialog.show()

        Thread {
            try {
                Rootfs.ensureInstalled(this) { message ->
                    runOnUiThread { progress.text = message }
                }
                runOnUiThread {
                    guestBusy = false
                    dialog.dismiss()
                    enterGuest()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "guest install failed", error)
                runOnUiThread {
                    guestBusy = false
                    dialog.dismiss()
                    toast("guest install failed: ${error.message}")
                }
            }
        }.start()
    }

    /** Restarts the session as `chroot-ng <rootfs> /bin/bash` — same renderer. */
    private fun enterGuest() {
        val command = Rootfs.command(this)
        if (command == null) {
            toast("chroot-ng is missing from the APK")
            return
        }
        val (program, args) = command

        session.configure(
            program = program,
            args = args,
            env = mapOf(
                "HOME" to "/root",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            ),
            cwd = null,
        )
        selection.clear()
        exited = false
        startSession()
        surface.requestRender()
        Log.i(TAG, "guest: ${args.joinToString(" ")}")
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
                event.startsWith("color_query:") -> answerColorQuery(event)
                event.startsWith("clipboard_store:") -> storeClipboard(event)
                event.startsWith("clipboard_load:") -> loadClipboard(event)
                else -> Log.i(TAG, "event $event")
            }
        }
    }

    /**
     * `OSC 4/10/11/12` query about a colour the engine has no override for, so
     * the answer comes from the default palette. The reply is alacritty's own
     * format (`dynamic_color_sequence`): every component printed twice, e.g.
     * `\x1b]11;rgb:1818/1818/1818\x07`.
     */
    private fun answerColorQuery(record: String) {
        val parts = record.split(':', limit = 4)
        if (parts.size != 4) {
            return
        }
        val index = parts[1].toIntOrNull() ?: return
        // A cursor query is only answered when the application set it; the
        // engine would have answered that case itself.
        val color = Palette.colorFor(index) ?: return

        val terminator = if (parts[3] == "st") "\u001b\\" else "\u0007"
        val red = hex(color shr 16 and 0xff)
        val green = hex(color shr 8 and 0xff)
        val blue = hex(color and 0xff)
        session.writeUtf8("\u001b]${parts[2]};rgb:$red$red/$green$green/$blue$blue$terminator")
    }

    private fun hex(value: Int): String = value.toString(16).padStart(2, '0')

    /** `OSC 52` store: the application put text on the clipboard. */
    private fun storeClipboard(record: String) {
        val parts = record.split(':', limit = 3)
        if (parts.size != 3) {
            return
        }

        val text = try {
            String(Base64.decode(parts[2], Base64.DEFAULT), Charsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "clipboard store: payload is not base64")
            return
        }

        // Android has one clipboard, so `c` and the selection `p` are the same
        // store here; the distinction only exists on X11.
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, text))
    }

    /** `OSC 52` load: the application asked for the clipboard. */
    private fun loadClipboard(record: String) {
        val parts = record.split(':', limit = 3)
        if (parts.size != 3) {
            return
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip == null || clip.itemCount == 0) {
            ""
        } else {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        }

        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val terminator = if (parts[2] == "st") "\u001b\\" else "\u0007"
        session.writeUtf8("\u001b]52;${parts[1]};$encoded$terminator")
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

        /** Human-readable font identity for the bench header. */
        const val FONT_NAME = "JetBrains Mono NL 2.304"

        /** The faces bundled in the APK (Phase B: one font, everywhere identical). */
        const val REGULAR_FONT = "fonts/JetBrainsMonoNL-Regular.ttf"
        const val BOLD_FONT = "fonts/JetBrainsMonoNL-Bold.ttf"

        /** Label on the clipboard entry, so a paste is recognisable. */
        const val CLIPBOARD_LABEL = "drosh-terminal-lab"

        /** Pastes are capped: a phone clipboard is not a file transfer. */
        const val PASTE_LIMIT = 65_536

        /** Focus reporting (`?1004`), as alacritty's `on_focus_change` sends it. */
        val FOCUS_IN = "\u001b[I".toByteArray(Charsets.US_ASCII)
        val FOCUS_OUT = "\u001b[O".toByteArray(Charsets.US_ASCII)

        /** The frame-stats line: readable, quiet, and its own tap target. */
        const val STATUS_COLOR = 0xff9a9a9a.toInt()
        const val STATUS_BACKGROUND = 0xff151515.toInt()
        const val STATUS_PADDING = 6

        /** Amber while frozen: the numbers are an artefact, not a live reading. */
        const val STATUS_FROZEN_COLOR = 0xfff4bf75.toInt()

        /** Bench artefacts: one logcat tag and one append-only file. */
        const val BENCH_TAG = "DroshLabBench"
        const val BENCH_FILE = "bench.log"
    }
}
