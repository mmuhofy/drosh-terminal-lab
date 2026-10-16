package dev.drosh.lab.bench

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends one frozen bench line to the loopback collector.
 *
 * The device cannot push to a build box (its `127.0.0.1` is the device), so the
 * collector runs **on the device** and the person running the device test bridges
 * it out (`bench/README.md`). This class is the device half: it posts the line
 * that freezing produced, and nothing else.
 *
 * Deliberately narrow, because "a terminal that uploads" is a pattern this
 * project does not want to inherit:
 *
 * - it only ever runs on an explicit freeze tap, never on a timer and never on
 *   output;
 * - it only ever talks to `127.0.0.1` — the endpoints are constants below, not a
 *   setting (D12: no configuration system);
 * - it runs off the main thread, swallows every failure into a log line, and
 *   cannot take the terminal down with it.
 *
 * Recording a measurement and losing the upload is fine (the same line is in
 * logcat and in `files/bench.log`); losing the terminal is not.
 */
internal object BenchUploader {

    private const val TAG = "DroshLabBench"

    /** Where the collector listens. Loopback only, by construction. */
    private const val HOST = "http://127.0.0.1:8080"

    /** Tried in order: a named endpoint first, then whatever the root does. */
    private val ENDPOINTS = arrayOf("/bench", "/")

    private const val TIMEOUT_MILLIS = 2_000

    /** Post `line` (fire and forget; the caller is the UI thread). */
    fun send(line: String) {
        Thread({ sendBlocking(line) }, "bench-upload").apply {
            isDaemon = true
            start()
        }
    }

    private fun sendBlocking(line: String) {
        for (endpoint in ENDPOINTS) {
            try {
                val connection = URL(HOST + endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                connection.outputStream.use { it.write(line.toByteArray(Charsets.UTF_8)) }

                val status = connection.responseCode
                connection.disconnect()

                if (status in 200..299) {
                    Log.i(TAG, "posted to $HOST$endpoint ($status)")
                    return
                }
                Log.w(TAG, "$HOST$endpoint answered $status")
            } catch (error: IOException) {
                // Nothing listening, or the bridge is down: the line is still in
                // logcat and in files/bench.log.
                Log.w(TAG, "$HOST$endpoint unreachable: ${error.message}")
            } catch (error: Throwable) {
                Log.w(TAG, "$HOST$endpoint failed: ${error.message}")
            }
        }
    }
}
