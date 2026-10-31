package com.wifiaudio.sender

import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * TCP server that accepts one connection from the Windows sender, authenticates the
 * pairing code, and forwards Opus packets to a decoder + AudioTrack player.
 * Reconnects after a drop if autoReconnect is enabled.
 */
class ReceiverConnection(
    private val port: Int,
    private val code: String,
    private val autoReconnect: Boolean,
    private val onPacket: (ByteArray) -> Unit,
    private val onStatus: (level: String, message: String) -> Unit,
    private val onConnected: (name: String) -> Unit,
    private val onFinished: (message: String) -> Unit
) {
    interface Listener {
        fun onConnecting()
        fun onConnected(name: String)
        fun onReconnecting()
        fun onFinished(message: String)
    }

    companion object {
        private const val NO_RECEIVER = "Could not start the audio server."
    }

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "receiver-conn").also { it.isDaemon = true; it.start() }
    }

    fun stopAndWait(ms: Long) {
        running = false
        try { server?.close() } catch (_: IOException) { }
        try { socket?.close() } catch (_: IOException) { }
        try { thread?.join(ms) } catch (_: InterruptedException) { }
        thread = null
        server = null
        socket = null
    }

    private fun run() {
        var everConnected = false
        while (running) {
            if (everConnected) onReconnecting() else onConnecting()
            var failure: String? = null
            try {
                val srv = ServerSocket(0)   // 0 = ephemeral port; we advertise via discovery
                srv.setReuseAddress(true)
                srv.bind(InetSocketAddress(port))
                srv.soTimeout = 5000
                server = srv

                val s = srv.accept()
                if (!running) { s.close(); return }
                socket = s
                s.tcpNoDelay = true
                s.soTimeout = 5000
                val din = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()

                val type = din.readUnsignedByte()
                val payload = readPayload(din)
                if (type != Protocol.T_HELLO) {
                    writeFrame(out, Protocol.T_REJECT, """{"reason":"bad"}""".toByteArray())
                    throw IOException("bad handshake")
                }
                val hello = try { JSONObject(String(payload)) } catch (_: Exception) { JSONObject() }
                if (hello.optInt("v", 0) != Protocol.VERSION) {
                    writeFrame(out, Protocol.T_REJECT, """{"reason":"version"}""".toByteArray())
                    throw IOException("version mismatch")
                }
                if (hello.optInt("code", -1).toString() != code) {
                    writeFrame(out, Protocol.T_REJECT, """{"reason":"code"}""".toByteArray())
                    throw IOException("code mismatch")
                }
                val name = hello.opt("name", "")?.toString() ?: ""

                writeFrame(out, Protocol.T_OK,
                    """{"name":"${android.os.Build.MODEL}","v":${Protocol.VERSION}}""".toByteArray())
                everConnected = true
                onConnected(name)

                // Reader thread: watch for BYE / keepalive
                val reader = Thread({ readLoop(din, s) }, "receiver-reader").also {
                    it.isDaemon = true; it.start()
                }

                // Forward packets
                while (running && !s.isClosed) {
                    val t = din.readUnsignedByte()
                    val p = readPayload(din)
                    when (t) {
                        Protocol.T_AUDIO -> onPacket(p)
                        Protocol.T_BYE -> { onFinished("Disconnected by the PC."); return }
                        Protocol.T_PING -> Unit
                    }
                }
            } catch (e: IOException) {
                failure = if (everConnected) "Connection lost." else NO_RECEIVER
            } catch (e: InterruptedException) {
                return
            } finally {
                try { socket?.close() } catch (_: IOException) { }
                socket = null
                try { server?.close() } catch (_: IOException) { }
                server = null
            }

            if (!running) return
            if (!everConnected) { onFinished(failure ?: NO_RECEIVER); return }
            if (!autoReconnect) { onFinished("Connection lost."); return }
            onReconnecting()
            var waited = 0
            while (running && waited < 2000) { Thread.sleep(100); waited += 100 }
        }
        onFinished("Stopped")
    }

    private fun readLoop(din: DataInputStream, s: Socket) {
        try {
            while (running && !s.isClosed) {
                val t = din.readUnsignedByte()
                readPayload(din)
                if (t == Protocol.T_BYE) break
            }
        } catch (_: Exception) {
            // timeout or closed
        } finally {
            try { s.close() } catch (_: IOException) { }
        }
    }

    private fun readPayload(din: DataInputStream): ByteArray {
        val len = din.readInt()
        if (len < 0 || len > Protocol.MAX_PAYLOAD) throw IOException("bad frame")
        return ByteArray(len).also { din.readFully(it) }
    }

    private fun writeFrame(out: OutputStream, type: Int, payload: ByteArray) {
        val buf = java.nio.ByteBuffer.allocate(5 + payload.size)
            .put(type.toByte()).putInt(payload.size).put(payload)
        out.write(buf.array())
        out.flush()
    }
}