package com.wifiaudio.sender

import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Connects to the PC, sends queued Opus packets, reconnects after Wi-Fi drops. */
class SenderConnection(
    private val host: String,
    private val port: Int,
    private val deviceName: String,
    private val code: String,
    private val autoReconnect: Boolean,
    private val queue: ArrayBlockingQueue<ByteArray>,
    private val listener: Listener
) {
    interface Listener {
        fun onConnecting()
        fun onConnected(receiverName: String)
        fun onReconnecting()
        fun onFinished(message: String)
    }

    companion object {
        const val NO_RECEIVER = "No receiver found.\n\nMake sure:\n" +
            "• Your phone and PC are on the same Wi-Fi network.\n" +
            "• Windows Firewall allows this application.\n" +
            "• The receiver is running."
    }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var remoteBye = false
    private var thread: Thread? = null
    private val writeLock = Any()

    private class Rejected(message: String) : Exception(message)

    fun start() {
        running = true
        thread = Thread({ run() }, "sender-connection").also { it.start() }
    }

    /** Asks the thread to send BYE and stop; waits a little so BYE actually leaves the phone. */
    fun stopAndWait(ms: Long) {
        running = false
        try { thread?.join(ms) } catch (_: InterruptedException) { }
        try { socket?.close() } catch (_: IOException) { }
    }

    private fun run() {
        var everConnected = false
        while (running) {
            if (everConnected) listener.onReconnecting() else listener.onConnecting()
            remoteBye = false
            var failure: String? = null
            val s = Socket()
            socket = s
            try {
                s.tcpNoDelay = true
                s.sendBufferSize = 16 * 1024
                s.connect(InetSocketAddress(host, port), 4000)
                s.soTimeout = 5000
                val din = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()

                val hello = JSONObject().put("v", Protocol.VERSION).put("name", deviceName)
                    .put("code", code).put("codec", "opus").put("rate", 48000)
                    .put("ch", 2).put("frame_ms", 20)
                writeFrame(out, Protocol.T_HELLO, hello.toString().toByteArray())

                val type = din.readUnsignedByte()
                val payload = readPayload(din)
                val reply = try { JSONObject(String(payload)) } catch (_: Exception) { JSONObject() }
                when (type) {
                    Protocol.T_OK -> Unit
                    Protocol.T_REJECT -> throw Rejected(rejectMessage(reply.optString("reason")))
                    else -> throw IOException("bad handshake")
                }
                everConnected = true
                queue.clear()
                listener.onConnected(reply.optString("name", host))

                val reader = Thread({ readLoop(din, s) }, "sender-reader").also { it.isDaemon = true; it.start() }
                var lastPing = 0L
                while (running && !s.isClosed) {
                    val pkt = queue.poll(100, TimeUnit.MILLISECONDS)
                    if (pkt != null) {
                        writeFrame(out, Protocol.T_AUDIO, pkt)
                    } else if (System.currentTimeMillis() - lastPing > 1000) {
                        writeFrame(out, Protocol.T_PING, ByteArray(0))
                        lastPing = System.currentTimeMillis()
                    }
                }
                if (!running) {
                    try { writeFrame(out, Protocol.T_BYE, ByteArray(0)) } catch (_: IOException) { }
                }
                reader.interrupt()
            } catch (e: Rejected) {
                try { s.close() } catch (_: IOException) { }
                listener.onFinished(e.message ?: "The PC refused the connection.")
                return
            } catch (e: IOException) {
                failure = if (everConnected) "Connection lost." else NO_RECEIVER
            } catch (e: InterruptedException) {
                return
            } finally {
                try { s.close() } catch (_: IOException) { }
            }

            if (!running) return
            if (remoteBye) { listener.onFinished("Disconnected by the PC."); return }
            if (!everConnected) { listener.onFinished(failure ?: NO_RECEIVER); return }
            if (!autoReconnect) { listener.onFinished("Connection lost."); return }
            listener.onReconnecting()
            var waited = 0
            while (running && waited < 2000) { Thread.sleep(100); waited += 100 }
        }
    }

    private fun readLoop(din: DataInputStream, s: Socket) {
        try {
            while (running && !s.isClosed) {
                val type = din.readUnsignedByte()
                readPayload(din)
                if (type == Protocol.T_BYE) { remoteBye = true; break }
            }
        } catch (_: Exception) {
            // timeout (no keep-alive for 5 s) or socket closed
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
        val buf = ByteBuffer.allocate(5 + payload.size).put(type.toByte()).putInt(payload.size).put(payload)
        synchronized(writeLock) {
            out.write(buf.array())
            out.flush()
        }
    }

    private fun rejectMessage(reason: String) = when (reason) {
        "code" -> "Wrong pairing code. Check the code shown on your PC."
        "blocked" -> "Too many wrong codes. Wait a minute and try again."
        "version" -> "The PC app and phone app versions don't match."
        "decoder" -> "The PC could not start its Opus decoder."
        "audio" -> "The PC could not open its audio output."
        else -> "The PC refused the connection."
    }
}
