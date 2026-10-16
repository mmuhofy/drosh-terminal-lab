package dev.drosh.lab.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One terminal session: the core's handle plus the buffers Kotlin owns.
 *
 * Decision D5 — Kotlin allocates, Rust writes, the renderer reads: [cells] and
 * [stateArray] are handed to the native side and never copied.
 *
 * Threading: the render thread calls [pump]; the UI thread calls everything
 * else. Reallocating in [allocate] replaces the buffers (the renderer re-reads
 * [cells] every frame), and `handle`/sizes are volatile so both threads see the
 * same session description.
 */
internal class TerminalSession(
    private val program: String,
    private val args: List<String> = emptyList(),
    private val env: Map<String, String> = emptyMap(),
    private val cwd: String? = null,
) {

    @Volatile
    var handle: Long = NO_HANDLE
        private set

    /** True while the core holds a live session for this wrapper. */
    val isAlive: Boolean get() = handle != NO_HANDLE

    @Volatile
    var columns = 0
        private set

    @Volatile
    var rows = 0
        private set

    @Volatile
    var cellWidth = 1
        private set

    @Volatile
    var cellHeight = 1
        private set

    @Volatile
    private var cellBuffer: ByteBuffer? = null

    private var damage = IntArray(0)
    private val state = IntArray(NativeCore.STATE_LEN)

    val cells: ByteBuffer? get() = cellBuffer

    val stateArray: IntArray get() = state

    /** Version of the loaded core, for the log line. */
    val coreVersion: String get() = NativeCore.nativeVersion()

    /** Returns [NativeCore.OK] or a negative error code. */
    fun start(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int): Int {
        close()
        this.cellWidth = cellWidth
        this.cellHeight = cellHeight

        val created = NativeCore.nativeCreateSession(
            columns,
            rows,
            cellWidth,
            cellHeight,
            program,
            args.joinToString("\n"),
            env.entries.joinToString("\n") { (key, value) -> "$key=$value" },
            cwd,
        )
        if (created <= 0L) {
            return created.toInt()
        }

        handle = created
        allocate(columns, rows)
        return NativeCore.OK
    }

    fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        val current = handle
        if (current == NO_HANDLE) {
            return
        }
        if (
            columns == this.columns &&
            rows == this.rows &&
            cellWidth == this.cellWidth &&
            cellHeight == this.cellHeight
        ) {
            return
        }

        this.cellWidth = cellWidth
        this.cellHeight = cellHeight
        allocate(columns, rows)
        NativeCore.nativeResize(current, columns, rows, cellWidth, cellHeight)
    }

    private fun allocate(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        cellBuffer = ByteBuffer
            .allocateDirect(columns * rows * NativeCore.CELL_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        damage = IntArray(NativeCore.damageLength(rows))
    }

    /**
     * Let the core write the damaged cells into [cells]; returns the damage
     * status ([NativeCore.DAMAGE_FULL] or a line count), or a negative code.
     */
    fun pump(): Int {
        val current = handle
        val buffer = cellBuffer
        if (current == NO_HANDLE || buffer == null) {
            return NativeCore.INVALID_HANDLE
        }
        if (NativeCore.nativeSnapshot(current, buffer, damage) != NativeCore.OK) {
            return NativeCore.IO_ERROR
        }
        NativeCore.nativeState(current, state)
        return damage[0]
    }

    fun write(bytes: ByteArray) {
        val current = handle
        if (current != NO_HANDLE && bytes.isNotEmpty()) {
            NativeCore.nativeWrite(current, bytes)
        }
    }

    fun writeUtf8(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun scroll(delta: Int) {
        val current = handle
        if (current != NO_HANDLE && delta != 0) {
            NativeCore.nativeScroll(current, delta)
        }
    }

    fun pollWakeup(): Boolean {
        val current = handle
        return current != NO_HANDLE && NativeCore.nativePollWakeup(current)
    }

    /** Low-frequency engine events (title, bell, exit, …), one record per entry. */
    fun drainEvents(): List<String> {
        val current = handle
        if (current == NO_HANDLE) {
            return emptyList()
        }
        val raw = NativeCore.nativeDrainEvents(current)
        return if (raw.isEmpty()) emptyList() else raw.split('\n')
    }

    fun close() {
        val current = handle
        if (current != NO_HANDLE) {
            NativeCore.nativeCloseSession(current)
            handle = NO_HANDLE
        }
    }

    companion object {
        const val NO_HANDLE = 0L
    }
}
