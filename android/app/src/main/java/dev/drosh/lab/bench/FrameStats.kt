package dev.drosh.lab.bench

/**
 * Frame timings, for the Phase B acceptance numbers ("frame p99 < 8 ms; no jank
 * under full-screen output").
 *
 * The total is split into the three phases a frame is made of, because the
 * first device run showed a number (`p50 114 ms`) nobody could attribute:
 *
 * - **pump**: the snapshot call. It takes the grid lock, so it also waits while
 *   the parser is busy — a stress test that saturates the PTY shows up here.
 * - **build**: turning damaged rows into vertices.
 * - **draw**: the GL submit for those rows.
 *
 * Only frames that actually drew are recorded: GLSurfaceView runs in
 * `RENDERMODE_WHEN_DIRTY`, so idle time between frames says nothing. A frame
 * counts as **janky** when the gap since the previous drawn frame exceeded two
 * refreshes.
 *
 * Kept small and allocation-free on the hot path: ring buffers of floats,
 * sorted only when a summary is asked for.
 */
internal class FrameStats(private val capacity: Int = 240) {

    private val total = FloatArray(capacity)
    private val pump = FloatArray(capacity)
    private val build = FloatArray(capacity)
    private val draw = FloatArray(capacity)
    private val interval = FloatArray(capacity)

    private var count = 0
    private var next = 0
    private var lastFrameNanos = 0L
    private var longs = 0

    /** Frames drawn since the last reset. */
    var frames = 0
        private set

    /**
     * Record one drawn frame.
     *
     * The timestamps are phase boundaries taken inside the frame:
     * `startNanos` … `pumpEndNanos` … `buildEndNanos` … `drawEndNanos`.
     */
    fun record(startNanos: Long, pumpEndNanos: Long, buildEndNanos: Long, drawEndNanos: Long) {
        total[next] = millis(startNanos, drawEndNanos)
        pump[next] = millis(startNanos, pumpEndNanos)
        build[next] = millis(pumpEndNanos, buildEndNanos)
        draw[next] = millis(buildEndNanos, drawEndNanos)

        if (lastFrameNanos != 0L) {
            val gap = millis(lastFrameNanos, startNanos)
            interval[next] = gap
            if (gap > JANK_MILLIS) {
                longs += 1
            }
        } else {
            interval[next] = 0f
        }

        lastFrameNanos = startNanos
        next = (next + 1) % capacity
        count = minOf(count + 1, capacity)
        frames += 1
    }

    fun reset() {
        count = 0
        next = 0
        longs = 0
        lastFrameNanos = 0L
        frames = 0
    }

    fun p50(values: FloatArray): Float = percentile(values, 0.5f)

    fun p99(): Float = percentile(total, 0.99f)

    fun janky(): Int = longs

    /** One line for the status row: what a frame costs, and where it goes. */
    fun summary(): String {
        if (count == 0) {
            return "no frame drawn yet"
        }

        return "p50 %.0f p99 %.0f ms · pump %.0f build %.0f draw %.0f · %d/%d janky"
            .format(p50(total), p99(), p50(pump), p50(build), p50(draw), longs, frames)
    }

    private fun percentile(values: FloatArray, fraction: Float): Float {
        if (count == 0) {
            return 0f
        }

        val copy = FloatArray(count)
        System.arraycopy(values, 0, copy, 0, count)
        copy.sort()

        val index = ((count - 1) * fraction).toInt().coerceIn(0, count - 1)
        return copy[index]
    }

    private fun millis(from: Long, to: Long): Float = (to - from) / NANOS_PER_MILLI

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000f

        /**
         * Two refreshes at 60 Hz: a gap longer than this while the engine was
         * sending output is what a user would call a stall.
         */
        const val JANK_MILLIS = 33.4f
    }
}
