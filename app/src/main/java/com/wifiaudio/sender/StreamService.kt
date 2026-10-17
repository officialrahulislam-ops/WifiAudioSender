package com.wifiaudio.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Temporarily silences the phone's music stream and always restores the original level. */
object VolumeGuard {
    fun mute(context: Context, prefs: Prefs) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (prefs.savedVolume < 0) prefs.savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (_: Exception) { }
    }

    fun restore(context: Context, prefs: Prefs) {
        val v = prefs.savedVolume
        if (v < 0) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        } catch (_: Exception) { }
        prefs.savedVolume = -1
    }
}

class StreamService : Service() {
    companion object {
        const val ACTION_START = "com.wifiaudio.sender.START"
        const val ACTION_STOP = "com.wifiaudio.sender.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        const val EXTRA_CODE = "code"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "stream"
        private const val NOTIF_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private val idle = AtomicBoolean(true)
    private val queue = ArrayBlockingQueue<ByteArray>(12)   // ~240 ms; oldest packets are dropped
    private lateinit var prefs: Prefs
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var capture: OpusCapture? = null
    private var connection: SenderConnection? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverName = ""
    private var host = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val ch = NotificationChannel(CHANNEL_ID, "Audio streaming", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreaming(intent)
            ACTION_STOP -> if (idle.get()) stopSelf() else stopStreaming("")
            else -> if (idle.get()) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(intent: Intent) {
        if (!idle.compareAndSet(true, false)) return
        host = intent.getStringExtra(EXTRA_HOST) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_TCP_PORT)
        receiverName = intent.getStringExtra(EXTRA_NAME) ?: host
        val code = intent.getStringExtra(EXTRA_CODE) ?: ""

        // Must be in the foreground (type mediaProjection) BEFORE getMediaProjection() on Android 14+.
        startForeground(NOTIF_ID, notification("Connecting to $receiverName…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        StreamStatus.update(StreamState.CONNECTING, receiverName, host)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (data == null) { stopStreaming("Audio capture permission is missing."); return }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(resultCode, data)
            val cb = object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopStreaming("Android stopped the audio capture.") } }
            }
            p.registerCallback(cb, main)
            projection = p
            projectionCallback = cb
        } catch (e: Exception) {
            stopStreaming("Could not get permission to capture audio."); return
        }

        acquireLocks()
        if (prefs.muteLocal) VolumeGuard.mute(this, prefs)

        val cap = OpusCapture(projection!!, { pkt ->
            if (!queue.offer(pkt)) { queue.poll(); queue.offer(pkt) }
        }, { msg -> main.post { stopStreaming(msg) } })
        val err = cap.start()
        if (err != null) { stopStreaming(err); return }
        capture = cap

        val conn = SenderConnection(host, port, deviceName(), code, prefs.autoReconnect, queue,
            object : SenderConnection.Listener {
                override fun onConnecting() = setState(StreamState.CONNECTING, "Connecting to $receiverName…")
                override fun onConnected(receiverName: String) {
                    this@StreamService.receiverName = receiverName
                    prefs.lastReceiverIp = host
                    setState(StreamState.STREAMING, "Streaming audio to $receiverName")
                }
                override fun onReconnecting() = setState(StreamState.RECONNECTING, "Connection lost. Trying to reconnect…")
                override fun onFinished(message: String) { main.post { stopStreaming(message) } }
            })
        connection = conn
        conn.start()
    }

    private fun setState(state: StreamState, notifText: String) {
        if (idle.get()) return
        StreamStatus.update(state, receiverName, host)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(notifText))
    }

    private fun stopStreaming(message: String) {
        if (!idle.compareAndSet(false, true)) return
        val conn = connection; val cap = capture; val proj = projection; val cb = projectionCallback
        connection = null; capture = null; projection = null; projectionCallback = null
        Thread({
            conn?.stopAndWait(1000)
            cap?.stop()
            main.post {
                try { cb?.let { proj?.unregisterCallback(it) }; proj?.stop() } catch (_: Exception) { }
                releaseLocks()
                VolumeGuard.restore(this, prefs)
                queue.clear()
                StreamStatus.update(StreamState.IDLE, receiverName, host, message)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "stream-cleanup").start()
    }

    override fun onDestroy() {
        if (!idle.get()) {      // killed by the system: still clean up
            idle.set(true)
            connection?.stopAndWait(300); capture?.stop()
            try { projection?.stop() } catch (_: Exception) { }
            releaseLocks()
            VolumeGuard.restore(this, prefs)
            StreamStatus.update(StreamState.IDLE, receiverName, host, "")
        }
        super.onDestroy()
    }

    private fun acquireLocks() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "wifi-audio").apply {
                setReferenceCounted(false); acquire()
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wifi-audio:stream").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) { }
    }

    private fun releaseLocks() {
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        wifiLock = null; wakeLock = null
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
            .addAction(0, "Disconnect", stop)
            .build()
    }
}

fun Context.deviceName(): String =
    android.provider.Settings.Global.getString(contentResolver, "device_name")
        ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
