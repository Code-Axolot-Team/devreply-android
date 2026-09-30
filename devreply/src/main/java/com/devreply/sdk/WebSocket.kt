package com.devreply.sdk

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A small RFC 6455 WebSocket client for the live channel (spec 05, "Live updates over WebSocket"): no
 * dependencies, `Socket`/`SSLSocket` and framing. Text messages in and out, pings answered with pongs,
 * fragmented incoming text reassembled, a clean close. Blocking: use it from a background thread.
 */
internal object WsFrame {
    const val CONTINUATION = 0x0
    const val TEXT = 0x1
    const val BINARY = 0x2
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA

    /** One frame from the client: always masked with [mask] (4 bytes), as RFC 6455 requires. */
    fun encode(opcode: Int, payload: ByteArray, mask: ByteArray, fin: Boolean = true): ByteArray {
        require(mask.size == 4)
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write((if (fin) 0x80 else 0) or (opcode and 0x0F))
        val n = payload.size
        when {
            n <= 125 -> out.write(0x80 or n)
            n <= 0xFFFF -> {
                out.write(0x80 or 126)
                out.write(n ushr 8 and 0xFF)
                out.write(n and 0xFF)
            }
            else -> {
                out.write(0x80 or 127)
                val len = n.toLong()
                for (shift in 56 downTo 0 step 8) out.write((len ushr shift and 0xFF).toInt())
            }
        }
        out.write(mask)
        for (i in payload.indices) out.write(payload[i].toInt() xor mask[i and 3].toInt())
        return out.toByteArray()
    }

    /** A close frame's payload: the status code, then an optional reason. */
    fun closePayload(code: Int, reason: String = ""): ByteArray =
        byteArrayOf((code ushr 8 and 0xFF).toByte(), (code and 0xFF).toByte()) + reason.toByteArray(Charsets.UTF_8)

    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    /**
     * Reads one frame. Server frames are never masked (a masked one is a protocol error); control frames
     * carry at most 125 bytes and are never fragmented. [maxPayload] guards memory.
     */
    fun read(input: InputStream, maxPayload: Int): Frame {
        val b0 = readByte(input)
        val b1 = readByte(input)
        if (b0 and 0x70 != 0) throw ProtocolException("reserved bits set")
        val fin = b0 and 0x80 != 0
        val opcode = b0 and 0x0F
        if (b1 and 0x80 != 0) throw ProtocolException("masked frame from the server")
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = (readByte(input).toLong() shl 8) or readByte(input).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or readByte(input).toLong() }
            if (len < 0) throw ProtocolException("bad length")
        }
        if (opcode >= 0x8) {
            if (!fin || len > 125) throw ProtocolException("bad control frame")
        }
        if (len > maxPayload) throw ProtocolException("frame too big")
        val payload = ByteArray(len.toInt())
        var read = 0
        while (read < payload.size) {
            val r = input.read(payload, read, payload.size - read)
            if (r < 0) throw EOFException()
            read += r
        }
        return Frame(fin, opcode, payload)
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }
}

/** The server broke the WebSocket protocol (or refused the upgrade). */
internal class ProtocolException(message: String, val status: Int? = null) : IOException(message)

/** The opening handshake (RFC 6455 §4): a random key, and the server's accept value checked against it. */
internal object WsHandshake {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun newKey(random: SecureRandom = SecureRandom()): String =
        Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))

    /** What the server must answer in `Sec-WebSocket-Accept` for [key]. */
    fun accept(key: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))

    fun request(uri: URI, key: String, userAgent: String): String {
        val defaultPort = if (uri.scheme == "wss") 443 else 80
        val host = if (uri.port == -1 || uri.port == defaultPort) uri.host else "${uri.host}:${uri.port}"
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        return "GET $path HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "User-Agent: $userAgent\r\n" +
            "\r\n"
    }

    /**
     * Checks the server's response head (status line and headers, without the blank line): `101`, the
     * upgrade headers, and `Sec-WebSocket-Accept` matching [key]. Throws [ProtocolException] otherwise
     * (with the HTTP status, e.g. 401 for a used ticket, 503 when full).
     */
    fun verify(head: String, key: String) {
        val lines = head.split("\r\n")
        val status = lines.first().split(" ").getOrNull(1)?.toIntOrNull()
        if (status != 101) throw ProtocolException("upgrade refused: ${lines.first()}", status)
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        if (!headers["upgrade"].equals("websocket", ignoreCase = true)) throw ProtocolException("no upgrade header")
        if (headers["connection"]?.split(",")?.none { it.trim().equals("upgrade", ignoreCase = true) } != false) {
            throw ProtocolException("no connection: upgrade")
        }
        if (headers["sec-websocket-accept"] != accept(key)) throw ProtocolException("wrong Sec-WebSocket-Accept")
    }

    /** Reads the response head up to the blank line (8 KB at most), byte by byte so no frame bytes are eaten. */
    fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var tail = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("closed during the handshake")
            out.write(b)
            tail = (tail shl 8) or b // the last four bytes
            if (tail == 0x0D0A0D0A) break
            if (out.size() > 8192) throw ProtocolException("handshake response too long")
        }
        return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
    }
}

/**
 * One WebSocket connection. [receive] blocks for the next text message (answering pings on the way) and
 * returns null once the server closed; [readTimeoutMs] without any frame throws (a dead connection).
 * [send] and [close] can be called from any thread.
 */
internal class WebSocketClient private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    private val random: SecureRandom,
    private val maxMessage: Int,
) {
    private val writeLock = Any()
    @Volatile private var closeSent = false
    private val closed = CountDownLatch(1)

    /** The next text message, or null when the connection closed normally. */
    fun receive(): String? {
        // A fragmented message being put together, and whether it's binary (not part of the protocol: skipped).
        var buffer: ByteArrayOutputStream? = null
        var binary = false
        try {
            while (true) {
                val frame = WsFrame.read(input, maxMessage)
                when (frame.opcode) {
                    WsFrame.PING -> write(WsFrame.PONG, frame.payload)
                    WsFrame.PONG -> Unit
                    WsFrame.CLOSE -> {
                        // Echo the close (unless we started it), then the TCP connection goes.
                        if (!closeSent) runCatching { sendClose(frame.payload.take(2).toByteArray()) }
                        finish()
                        return null
                    }
                    WsFrame.TEXT, WsFrame.BINARY -> {
                        if (buffer != null) throw ProtocolException("new message inside a fragmented one")
                        if (frame.fin) {
                            if (frame.opcode == WsFrame.TEXT) return frame.payload.toString(Charsets.UTF_8)
                        } else {
                            buffer = ByteArrayOutputStream().apply { write(frame.payload) }
                            binary = frame.opcode == WsFrame.BINARY
                        }
                    }
                    WsFrame.CONTINUATION -> {
                        val b = buffer ?: throw ProtocolException("continuation without a start")
                        b.write(frame.payload)
                        if (b.size() > maxMessage) throw ProtocolException("message too big")
                        if (frame.fin) {
                            buffer = null
                            if (!binary) return b.toByteArray().toString(Charsets.UTF_8)
                        }
                    }
                    else -> throw ProtocolException("unknown opcode ${frame.opcode}")
                }
            }
        } catch (e: IOException) {
            if (e is ProtocolException) runCatching { sendClose(WsFrame.closePayload(1002)) }
            finish()
            // We closed it (close() from another thread): a normal end.
            if (closeSent && e !is ProtocolException) return null
            throw e
        }
    }

    fun send(text: String) = write(WsFrame.TEXT, text.toByteArray(Charsets.UTF_8))

    /**
     * A normal close ([code] 1000): sends the close frame, waits up to [waitMs] for the server's (read by
     * whoever is in [receive]), then closes the TCP connection.
     */
    fun close(code: Int = 1000, waitMs: Long = 1_000) {
        runCatching { sendClose(WsFrame.closePayload(code)) }
        runCatching { closed.await(waitMs, TimeUnit.MILLISECONDS) }
        finish()
    }

    /** Closes the connection without a close frame (after an error). */
    fun abort() = finish()

    private fun sendClose(payload: ByteArray) {
        if (closeSent) return
        closeSent = true
        write(WsFrame.CLOSE, payload)
    }

    private fun write(opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4).also(random::nextBytes)
        val bytes = WsFrame.encode(opcode, payload, mask)
        synchronized(writeLock) {
            if (opcode != WsFrame.CLOSE && closeSent) throw IOException("closing")
            output.write(bytes)
            output.flush()
        }
    }

    private fun finish() {
        closed.countDown()
        runCatching { socket.close() }
    }

    companion object {
        const val MAX_MESSAGE = 4 * 1024 * 1024

        /**
         * Connects to a `ws://` or `wss://` [url] and completes the handshake. TLS checks the certificate
         * and the host name, like HTTPS. [readTimeoutMs]: a read waiting this long throws (dead connection).
         */
        fun connect(
            url: String,
            connectTimeoutMs: Int = 15_000,
            readTimeoutMs: Int = 75_000,
            userAgent: String = "devreply-android/$DEVREPLY_SDK_VERSION",
            maxMessage: Int = MAX_MESSAGE,
        ): WebSocketClient {
            val uri = URI(url)
            val secure = when (uri.scheme?.lowercase()) {
                "wss" -> true
                "ws" -> false
                else -> throw IOException("not a WebSocket URL")
            }
            val host = uri.host ?: throw IOException("no host")
            val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
            val raw = Socket()
            try {
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
                // The handshake shouldn't hang for 75 s.
                raw.soTimeout = connectTimeoutMs
                val socket: Socket = if (secure) {
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
                    ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    ssl.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                        throw javax.net.ssl.SSLPeerUnverifiedException("certificate isn't for $host")
                    }
                    ssl
                } else {
                    raw
                }
                val random = SecureRandom()
                val key = WsHandshake.newKey(random)
                val output = socket.getOutputStream()
                output.write(WsHandshake.request(uri, key, userAgent).toByteArray(Charsets.ISO_8859_1))
                output.flush()
                val input = BufferedInputStream(socket.getInputStream())
                WsHandshake.verify(WsHandshake.readHead(input), key)
                socket.soTimeout = readTimeoutMs
                return WebSocketClient(socket, input, output, random, maxMessage)
            } catch (e: Exception) {
                runCatching { raw.close() }
                throw e as? IOException ?: IOException(e)
            }
        }

        /** Unit tests: a connection over any streams (no handshake). */
        internal fun over(socket: Socket, input: InputStream, output: OutputStream, maxMessage: Int = MAX_MESSAGE) =
            WebSocketClient(socket, input, output, SecureRandom(), maxMessage)
    }
}
