package com.wifiaudio.sender

import android.os.Handler
import android.os.Looper

enum class StreamState { IDLE, CONNECTING, STREAMING, RECONNECTING }

/** Tiny shared state between the service and the activity (same process). */
object StreamStatus {
    @Volatile var state = StreamState.IDLE; private set
    @Volatile var receiverName = ""; private set
    @Volatile var host = ""; private set
    @Volatile var message = ""; private set   // error / info shown when idle
    @Volatile var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun update(state: StreamState, receiverName: String = this.receiverName,
               host: String = this.host, message: String = "") {
        this.state = state
        this.receiverName = receiverName
        this.host = host
        this.message = message
        main.post { listener?.invoke() }
    }
}