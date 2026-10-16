package dev.drosh.lab.bench

/**
 * Frame timings, for the Phase B acceptance numbers ("frame p99 < 8 ms; no jank
 * under full-screen output").
 *
 * Only frames that actually drew are recorded: GLSurfaceView runs in
 * `RENDERMODE_WHEN_DIRTY`, so the idle time between frames says nothing about
 * rendering. A frame counts as **janky** when the gap since the previous drawn
 * frame exceeded two refreshes — the definition the acceptance criterion
 * implies (the work itself is the p50/p99 pair).
 *
 * Kept deliberately small and allocation-free on the hot path: a ring buffer of
 * floats, sorted only when a summary is asked for.
 */
internal class FrameStats(private val capacity: Int = 240) {

    /** Milliseconds of work per drawn frame. */
    private val work = FloatArray(capacity)

    /** Milliseconds between drawn frames. */
    private val interval = FloatArray(capacity)

    private var count = 0
    private var next = 0
    private var lastFrameNanos = 0L
    private var longs = 0

    /** Frames drawn since the last [reset]. */
    var frames = 0
        private set

    fun record(startNanos: Long, endNanos: Long) {
        val workMillis = (endNanos - startNanos) / NANOS_PER_MILLI
        work[next] = workMillis

        if (lastFrameNanos != 0L) {
            val intervalMillis = (startNanos - lastFrameNanos) / NANOS_PER_MILLI
            interval[next] = intervalMillis
            if (intervalMillis > JANK_MILLIS) {
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

    /** Median frame work, milliseconds. */
    fun p50(): Float = percentile(WORK_P50)

    /** 99th percentile frame work, milliseconds. */
    fun p99(): Float = percentile(WORK_P99)

    fun janky(): Int = longs

    /** One line for the status row. */
    fun summary(): String {
        if (count == 0) {
            return "frame - ms  (no frame drawn yet)"
        }
        return "frame p50 %.1f ms  p99 %.1f ms  %d janky / %d".format(p50(), p99(), longs, frames)
    }

    fun frameInterval(): Float = percentile(INTERVAL_P50)

    private fun percentile(kind: Int): Float {
        if (count == 0) {
            return 0f
        }

        val copy = FloatArray(count)
        System.arraycopy(if (kind == INTERVAL_P50) interval else work, 0, copy, 0, count)
        copy.sort()

        val fraction = if (kind == INTERVAL_P50) 0.5f else 0.99f
        val index = ((count - 1) * fraction).toInt().coerceIn(0, count - 1)
        return copy[index]
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000f

        /**
         * Two refreshes at 60 Hz: a frame that took longer than this while the
         * engine was sending output is what a user would call a stall.
         */
        const val JANK_MILLIS = 33.4f

        const val WORK_P50 = 0
        const val WORK_P99 = 1
        const val INTERVAL_P50 = 2
    }
}
