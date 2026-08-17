package ink.harsh.plugins.pipe.sabr

import android.util.Log
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves SABR sessions over loopback HTTP so any player can consume them.
 *
 * This is the portable transport: a WebView `<video>` with dash.js or
 * Shaka, or Media3's `DashMediaSource`, all speak HTTP. The Media3 adapter
 * exists to skip this hop, not because this cannot serve it.
 *
 * Routes, all under an unguessable session id:
 * ```
 *   GET /{session}/manifest.mpd
 *   GET /{session}/{formatKey}/init
 *   GET /{session}/{formatKey}/{sequenceNumber}
 * ```
 *
 * Access control is the loopback bind plus the random session id in the path.
 * Nothing here is reachable off-device, and a segment URL leaking is
 * uninteresting — the media expires with the session.
 *
 * Deliberately minimal rather than pulling in an HTTP server dependency: it
 * answers GET and HEAD, supports a single byte range, and closes each
 * connection. Segment responses stream from the session's spool file, so a large
 * segment is never held in memory twice.
 */
class PipeSabrServer(private val manager: PipeSabrManager) {

    private val workers: ExecutorService = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var port = -1

    /** Start on an ephemeral loopback port. Idempotent. */
    @Synchronized
    @Throws(IOException::class)
    fun start(): Int {
        if (running.get()) {
            return port
        }
        // Port 0: let the OS choose, so two apps on one device cannot collide.
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        running.set(true)

        val thread = Thread({ acceptLoop() }, "PipeSabrServer")
        acceptThread = thread
        thread.isDaemon = true
        thread.start()

        Log.i(TAG, "SABR loopback server listening on 127.0.0.1:" + port)
        return port
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        try {
            serverSocket?.close()
        } catch (ignored: IOException) {
            // Closing the socket is what unblocks accept(); failure here is moot.
        }
        workers.shutdownNow()
        acceptThread?.interrupt()
        port = -1
    }

    fun getPort(): Int = port

    fun isRunning(): Boolean = running.get()

    fun manifestUrl(sessionId: String): String =
        "http://127.0.0.1:" + port + "/" + sessionId + "/manifest.mpd"

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = serverSocket!!.accept()
                workers.execute { handleQuietly(socket) }
            } catch (e: IOException) {
                if (running.get()) {
                    Log.w(TAG, "accept failed", e)
                }
                return
            }
        }
    }

    private fun handleQuietly(socket: Socket) {
        try {
            handle(socket)
        } catch (e: Throwable) {
            Log.w(TAG, "request failed", e)
        } finally {
            try {
                socket.close()
            } catch (ignored: IOException) {
                // Client may already be gone; nothing to do.
            }
        }
    }

    @Throws(IOException::class)
    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        val input = socket.getInputStream()
        val rawOut = socket.getOutputStream()
        val out = BufferedOutputStream(rawOut, 32 * 1024)

        val requestLine = readLine(input)
        if (requestLine == null || requestLine.isEmpty()) {
            return
        }
        val parts = requestLine.split(" ").dropLastWhile { it.isEmpty() }
        if (parts.size < 2) {
            respondError(out, 400, "Bad Request")
            return
        }
        val method = parts[0]
        val path = parts[1]

        var rangeHeader: String? = null
        while (true) {
            val header = readLine(input)
            if (header == null || header.isEmpty()) {
                break
            }
            val colon = header.indexOf(':')
            if (colon > 0 && "range".equals(header.substring(0, colon).trim(), ignoreCase = true)) {
                rangeHeader = header.substring(colon + 1).trim()
            }
        }

        if (!"GET".equals(method, ignoreCase = true) && !"HEAD".equals(method, ignoreCase = true)) {
            respondError(out, 405, "Method Not Allowed")
            return
        }
        val bodyWanted = "GET".equals(method, ignoreCase = true)

        // /{session}/{...}
        val segments = path.split("?", limit = 2)[0]
            .split("/").dropLastWhile { it.isEmpty() }
        if (segments.size < 3) {
            respondError(out, 404, "Not Found")
            return
        }
        val sessionId = segments[1]
        val session = manager.get(sessionId)
        if (session == null) {
            respondError(out, 404, "Unknown session")
            return
        }

        if (segments.size == 3 && "manifest.mpd" == segments[2]) {
            val mpd = session.manifest.toByteArray(StandardCharsets.UTF_8)
            respondBytes(out, mpd, "application/dash+xml", rangeHeader, bodyWanted)
            return
        }

        if (segments.size == 4) {
            serveSegment(out, session, segments[2], segments[3], rangeHeader, bodyWanted)
            return
        }

        respondError(out, 404, "Not Found")
    }

    @Throws(IOException::class)
    private fun serveSegment(
        out: BufferedOutputStream,
        session: PipeSabrSession,
        formatKey: String,
        sequence: String,
        rangeHeader: String?,
        bodyWanted: Boolean,
    ) {
        val format = session.spec.getFormat(formatKey)
        if (format == null) {
            respondError(out, 404, "Unknown format $formatKey")
            return
        }

        if ("init" == sequence) {
            val data = session.spec.getInitializationData(format)
            if (data == null) {
                // Should be impossible: the manifest only advertises formats
                // whose init segment has been parsed.
                respondError(out, 503, "Initialisation not ready")
                return
            }
            respondBytes(out, data, "application/octet-stream", rangeHeader, bodyWanted)
            return
        }

        val sequenceNumber: Int
        try {
            sequenceNumber = sequence.toInt()
        } catch (e: NumberFormatException) {
            respondError(out, 400, "Bad sequence $sequence")
            return
        }

        val key = SabrSegmentKey.media(format, sequenceNumber)
        val segment: SabrMediaSegment
        try {
            segment = session.bridge.awaitSegment(key)
        } catch (e: Exception) {
            // 503 rather than 404: the segment is legitimate, the session just
            // could not produce it. Players back off and retry on 503.
            Log.w(TAG, "Could not obtain segment $key", e)
            respondError(out, 503, "Segment unavailable")
            return
        }

        try {
            respondStream(out, segment, rangeHeader, bodyWanted)
        } finally {
            // Hand-off complete; free the spool file.
            session.bridge.discard(key)
        }
    }

    @Throws(IOException::class)
    private fun respondStream(
        out: BufferedOutputStream,
        segment: SabrMediaSegment,
        rangeHeader: String?,
        bodyWanted: Boolean,
    ) {
        val length = segment.length
        val range = parseRange(rangeHeader, length.toLong())
        val start = range[0]
        val end = range[1]
        val count = end - start + 1

        writeHeaders(
            out,
            if (range[2] == 1L) 206 else 200,
            "application/octet-stream",
            count,
            start,
            end,
            length.toLong(),
        )

        if (!bodyWanted) {
            out.flush()
            return
        }

        segment.openStream().use { input ->
            skipFully(input, start)
            val buffer = ByteArray(64 * 1024)
            var remaining = count
            while (remaining > 0) {
                val read = input.read(buffer, 0, Math.min(buffer.size.toLong(), remaining).toInt())
                if (read < 0) {
                    break
                }
                out.write(buffer, 0, read)
                remaining -= read.toLong()
            }
        }
        out.flush()
    }

    @Throws(IOException::class)
    private fun respondBytes(
        out: BufferedOutputStream,
        data: ByteArray,
        contentType: String,
        rangeHeader: String?,
        bodyWanted: Boolean,
    ) {
        val range = parseRange(rangeHeader, data.size.toLong())
        val start = range[0]
        val end = range[1]
        val count = (end - start + 1).toInt()

        writeHeaders(
            out,
            if (range[2] == 1L) 206 else 200,
            contentType,
            count.toLong(),
            start,
            end,
            data.size.toLong(),
        )
        if (bodyWanted) {
            out.write(data, start.toInt(), count)
        }
        out.flush()
    }

    @Throws(IOException::class)
    private fun writeHeaders(
        out: BufferedOutputStream,
        status: Int,
        contentType: String,
        contentLength: Long,
        start: Long,
        end: Long,
        total: Long,
    ) {
        val head = StringBuilder(256)
        head.append("HTTP/1.1 ").append(status).append(' ')
            .append(if (status == 206) "Partial Content" else "OK").append("\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(contentLength).append("\r\n")
            .append("Accept-Ranges: bytes\r\n")
            // The WebView origin is not 127.0.0.1, so without this a web player
            // cannot read the response at all.
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("Access-Control-Allow-Headers: Range\r\n")
            .append("Access-Control-Expose-Headers: Content-Length, Content-Range\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
        if (status == 206) {
            head.append("Content-Range: bytes ").append(start).append('-').append(end)
                .append('/').append(total).append("\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(StandardCharsets.US_ASCII))
    }

    @Throws(IOException::class)
    private fun respondError(out: BufferedOutputStream, status: Int, message: String) {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        val head = String.format(
            Locale.US,
            "HTTP/1.1 %d %s\r\nContent-Type: text/plain\r\nContent-Length: %d\r\n" +
                "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n",
            status,
            message,
            body.size,
        )
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    companion object {

        private const val TAG = "PipeSabrServer"

        /** @return {start, end, isPartial} */
        private fun parseRange(rangeHeader: String?, length: Long): LongArray {
            if (rangeHeader == null || !rangeHeader.startsWith("bytes=") || length <= 0) {
                return longArrayOf(0, Math.max(0L, length - 1), 0)
            }
            val spec = rangeHeader.substring("bytes=".length).trim()
            // Only the first range of a multi-range request is honoured; players
            // requesting several ranges of one segment do not occur in practice.
            val first = spec.split(",").dropLastWhile { it.isEmpty() }[0].trim()
            val dash = first.indexOf('-')
            if (dash < 0) {
                return longArrayOf(0, length - 1, 0)
            }
            try {
                val from = first.substring(0, dash).trim()
                val to = first.substring(dash + 1).trim()
                var start: Long
                var end: Long
                if (from.isEmpty()) {
                    // Suffix range: last N bytes.
                    val suffix = to.toLong()
                    start = Math.max(0L, length - suffix)
                    end = length - 1
                } else {
                    start = from.toLong()
                    end = if (to.isEmpty()) length - 1 else to.toLong()
                }
                start = Math.max(0L, Math.min(start, length - 1))
                end = Math.max(start, Math.min(end, length - 1))
                return longArrayOf(start, end, 1)
            } catch (e: NumberFormatException) {
                return longArrayOf(0, length - 1, 0)
            }
        }

        @Throws(IOException::class)
        private fun skipFully(input: InputStream, count: Long) {
            var remaining = count
            val scratch = ByteArray(8192)
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    continue
                }
                val read = input.read(scratch, 0, Math.min(scratch.size.toLong(), remaining).toInt())
                if (read < 0) {
                    return
                }
                remaining -= read.toLong()
            }
        }

        @Throws(IOException::class)
        private fun readLine(input: InputStream): String? {
            val buffer = ByteArrayOutputStream(128)
            var previous = -1
            var current: Int
            while (input.read().also { current = it } != -1) {
                if (previous == '\r'.code && current == '\n'.code) {
                    val bytes = buffer.toByteArray()
                    // Drop the trailing CR.
                    return String(
                        bytes,
                        0,
                        Math.max(0, bytes.size - 1),
                        StandardCharsets.UTF_8,
                    )
                }
                buffer.write(current)
                previous = current
                if (buffer.size() > 8192) {
                    throw IOException("HTTP header line too long")
                }
            }
            return if (buffer.size() == 0) null else buffer.toString("UTF-8")
        }
    }
}
