package dev.drosh.lab.bench

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The bench endpoint the app *hosts*, so a reviewer can pull measurements.
 *
 * A device cannot push to a build box, and an app that dials out — to GitHub, to
 * a paste service, anywhere — is a pattern this project does not want to inherit.
 * So the app owns the server and the reviewer comes to it; the app has to be
 * running (and reachable) for that to work, which is the trade the device tester
 * accepted.
 *
 * It binds **every interface** (`0.0.0.0`), so whichever address the person
 * running the test can bridge — the device's Wi-Fi address or its loopback —
 * answers. That means the endpoint is reachable from the network the device is
 * on, which for a lab build is accepted and for a shipped one would not be:
 * what it serves is bench numbers and device facts, never terminal content, and
 * the API is three read-only paths (below). The addresses in use are logged at
 * startup.
 *
 * Deliberately minimal:
 *
 * - a fixed port (D12: no configuration system), logged at startup;
 * - plain-text responses with `Connection: close`, no keep-alive, no uploads, no
 *   filesystem paths taken from the request;
 * - every failure is a log line; nothing here can take the terminal down.
 *
 * The API:
 *
 * ```text
 * GET /            a short index naming the other paths
 * GET /bench.log   the bench artefact: one header line plus one line per capture
 * GET /info        device, build, font and grid metrics as one line
 * ```
 */
internal class BenchServer(private val logFile: File, private val infoLine: () -> String) {

    private var socket: ServerSocket? = null
    private var thread: Thread? = null

    /** Start listening; a second call is a no-op. */
    fun start() {
        if (socket != null) {
            return
        }

        socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(ANY_INTERFACE, PORT), BACKLOG)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "cannot listen on port $PORT: ${error.message}")
            socket = null
            return
        }

        Log.i(TAG, "bench endpoint listening on port $PORT: $URLS")
        thread = Thread({ acceptLoop() }, "bench-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        thread = null
    }

    private fun acceptLoop() {
        val server = socket ?: return
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (error: Throwable) {
                // Closing the socket is how this loop ends.
                return
            }
            try {
                handle(client)
            } catch (error: Throwable) {
                Log.w(TAG, "request failed: ${error.message}")
            } finally {
                try {
                    client.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = TIMEOUT_MILLIS
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))

        val requestLine = reader.readLine() ?: return
        // Drain the headers: they are not part of the API, and reading them keeps
        // the client from seeing a reset before its request is fully sent.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                break
            }
        }

        val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        val remote = client.inetAddress?.hostAddress ?: "?"
        Log.i(TAG, "GET $path from $remote")

        val body = when {
            path == "/" || path.isEmpty() -> index()
            path == "/info" -> infoLine()
            path == "/bench.log" || path == "/bench" -> benchLog()
            else -> "unknown path: $path\n\n" + index()
        }
        respond(client, body)
    }

    private fun index(): String = buildString {
        append("drosh-terminal-lab bench endpoint\n\n")
        append("GET /bench.log   one header line plus one line per frozen capture\n")
        append("GET /info        device, build, font and grid metrics\n")
        append("\nFrozen counters only: tap the status line in the app to capture, tap again to reset.\n")
    }

    private fun benchLog(): String =
        if (!logFile.exists()) {
            "(no captures yet — tap the status line in the app)\n"
        } else {
            try {
                logFile.readText()
            } catch (error: Throwable) {
                "(cannot read ${logFile.name}: ${error.message})\n"
            }
        }

    private fun respond(client: Socket, body: String) {
        val payload = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${payload.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"

        client.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(payload)
            flush()
        }
    }

    private companion object {
        const val TAG = "DroshLabBench"
        const val PORT = 8080
        const val BACKLOG = 8
        const val TIMEOUT_MILLIS = 3_000

        val ANY_INTERFACE: InetAddress = InetAddress.getByName("0.0.0.0")

        /** The addresses a reviewer can try, for the startup log line. */
        val URLS: String
            get() = buildString {
                append("http://127.0.0.1:$PORT/")
                try {
                    val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val addresses = interfaces.nextElement().inetAddresses
                        while (addresses.hasMoreElements()) {
                            val address = addresses.nextElement()
                            if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                                append(" http://").append(address.hostAddress).append(':').append(PORT).append('/')
                            }
                        }
                    }
                } catch (error: Throwable) {
                    // Enumerating interfaces is a nicety; the loopback URL is enough.
                    append(" (interface list unavailable: ${error.message})")
                }
            }
    }
}
