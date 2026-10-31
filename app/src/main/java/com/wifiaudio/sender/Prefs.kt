package com.wifiaudio.sender

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wifi_audio", Context.MODE_PRIVATE)

    var code: String
        get() = sp.getString("code", "") ?: ""
        set(v) = sp.edit().putString("code", v).apply()
    var manualIp: String
        get() = sp.getString("manual_ip", "") ?: ""
        set(v) = sp.edit().putString("manual_ip", v).apply()
    var lastReceiverIp: String
        get() = sp.getString("last_ip", "") ?: ""
        set(v) = sp.edit().putString("last_ip", v).apply()
    var autoReconnect: Boolean
        get() = sp.getBoolean("auto_reconnect", true)
        set(v) = sp.edit().putBoolean("auto_reconnect", v).apply()
    var muteLocal: Boolean
        get() = sp.getBoolean("mute_local", true)
        set(v) = sp.edit().putBoolean("mute_local", v).apply()
    /** Music volume to restore after streaming; -1 = nothing to restore. */
    var savedVolume: Int
        get() = sp.getInt("saved_volume", -1)
        set(v) = sp.edit().putInt("saved_volume", v).apply()
    /** Streaming mode: "sender" (capture & send) or "receiver" (play incoming). */
    var mode: String
        get() = sp.getString("mode", "sender") ?: "sender"
        set(v) = sp.edit().putString("mode", v).apply()
}