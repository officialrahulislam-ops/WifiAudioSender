package com.wifiaudio.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat

/** Temporarily silences the phone's music stream and always restores the original level. */
object VolumeGuard {
    fun mute(context: Context, prefs: Prefs) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (prefs.savedVolume < 0) prefs.savedVolume = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        } catch (_: Exception) { }
    }

    fun restore(context: Context, prefs: Prefs) {
        val v = prefs.savedVolume
        if (v < 0) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0)
        } catch (_: Exception) { }
        prefs.savedVolume = -1
    }
}

/**
 * Foreground service that runs the Windows -> Android receiver.
 *
 * Unlike the sender, this service does NOT need a MediaProjection: it only plays audio.
 * It binds to a TCP port, authenticates the pairing code, decodes Opus and plays it.
 */
class ReceiverService : Service() {
    companion object {
        const val ACTION_START = "com.wifiaudio.receiver.START"
        const val ACTION_STOP = "com.wifiaudio.receiver.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_PORT = "port"
        private const val CHANNEL_ID = "stream"
        private const val NOTIF_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private val idle = java.util.concurrent.atomic.AtomicBoolean(true)
    private lateinit var prefs: Prefs
    private var decoder: OpusDecoder? = null
    private var player: AudioTrackPlayer? = null
    private var connection: ReceiverConnection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val ch = NotificationChannel(CHANNEL_ID, "Audio streaming", NotificationManager.IMPORTANCE_LOW)
        getNotificationManager().createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startReceiving(intent)
            ACTION_STOP -> if (idle.get()) stopSelf() else stopReceiving("")
            else -> if (idle.get()) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startReceiving(intent: Intent) {
        if (!idle.compareAndSet(true, false)) return
        val code = intent.getStringExtra(EXTRA_CODE) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_TCP_PORT)

        startForeground(NOTIF_ID, notification("Listening for the PC…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        StreamStatus.update(StreamState.CONNECTING, "PC", "")

        acquireLocks()

        val dec = OpusDecoder()
        val err = dec.start()
        if (err != null) { stopReceiving(err); return }
        decoder = dec

        val pl = AudioTrackPlayer { level, msg -> if (!idle.get()) onStatus(level, msg) }
        val perr = pl.start()
        if (perr != null) { stopReceiving(perr); return }
        player = pl

        val conn = ReceiverConnection(
            port = port,
            code = code,
            autoReconnect = prefs.autoReconnect,
            onPacket = { pkt -> decoder?.decode(pkt)?.let { player?.push(it) } },
            onStatus = { level, msg -> if (!idle.get()) onStatus(level, msg) },
            onConnected = { name ->
                StreamStatus.update(StreamState.STREAMING, name, "")
            },
            onFinished = { msg -> main.post { stopReceiving(msg) } }
        )
        connection = conn
        conn.start()
    }

    private fun onStatus(level: String, msg: String) {
        if (idle.get()) return
        getNotificationManager().notify(NOTIF_ID, notification(msg))
    }

    private fun stopReceiving(message: String) {
        if (!idle.compareAndSet(false, true)) return
        val conn = connection; val pl = player; val dec = decoder
        connection = null; player = null; decoder = null
        Thread({
            conn?.stopAndWait(1000)
            dec?.stop()
            pl?.stop()
            main.post {
                releaseLocks()
                StreamStatus.update(StreamState.IDLE, "", "", message)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "receiver-cleanup").start()
    }

    override fun onDestroy() {
        if (!idle.get()) {
            idle.set(true)
            connection?.stopAndWait(300)
            decoder?.stop()
            player?.stop()
            releaseLocks()
            StreamStatus.update(StreamState.IDLE, "", "", "")
        }
        super.onDestroy()
    }

    private fun getNotificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wifi-audio:receiver").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) { }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "wifi-audio").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) { }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        wakeLock = null; wifiLock = null
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_audio)
            .setContentTitle("Wi-Fi Audio")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
