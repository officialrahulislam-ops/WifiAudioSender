package com.wifiaudio.sender

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue

/**
 * Plays decoded PCM through the phone's speaker using AudioTrack.
 *
 * A small jitter buffer absorbs network jitter: decoded frames are queued and the
 * playback thread pre-buffers ~60 ms before starting, then trims back to ~60 ms when
 * the queue grows past ~250 ms (the sender's capture/encode path is 20 ms frames, so
 * 60 ms = 3 frames is a safe prebuffer).
 */
class AudioTrackPlayer(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2,
    private val onStatus: ((String, String) -> Unit)? = null   // (level, msg)
) {
    companion object {
        private const val TARGET_MS = 60      // prebuffer target
        private const val MAX_MS = 250        // overflow threshold
        private const val FRAME_MS = 20
        private const val FRAME_SAMPLES = 48000 / 50        // 960 samples @ 48k
        private const val FRAME_BYTES = FRAME_SAMPLES * 2 * 2  // stereo, 16-bit
    }

    private var track: AudioTrack? = null
    @Volatile private var running = false
    private var prebuf = true
    var underruns = 0; private set
    var dropped = 0; private set

    private val queue = ArrayBlockingQueue<ByteArray>(25)   // ~500 ms max

    /** Returns null on success or a user-readable error. */
    fun start(): String? {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf, FRAME_BYTES * 8)
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.setPlaybackRate(sampleRate)
            t.play()
            track = t
            running = true
            Thread({ loop() }, "at-playback").also { it.isDaemon = true; it.start() }
            onStatus?.invoke("ok", "Playing audio")
            return null
        } catch (e: Exception) {
            onStatus?.invoke("err", "Audio output unavailable: ${e.message}")
            return "Could not open audio output."
        }
    }

    /** Push decoded PCM (16-bit, interleaved stereo). */
    fun push(pcm: ByteArray) {
        if (!running) return
        if (!queue.offer(pcm)) {
            queue.poll()      // drop oldest to make room
            queue.offer(pcm)
            dropped++
        }
    }

    fun setVolume(v: Float) {
        try { track?.setVolume(v.coerceIn(0f, 1f)) } catch (_: Exception) { }
    }

    fun setMuted(muted: Boolean) {
        try { track?.setVolume(if (muted) 0f else 1f) } catch (_: Exception) { }
    }

    fun stop() {
        running = false
        try { track?.stop() } catch (_: Exception) { }
        try { track?.release() } catch (_: Exception) { }
        track = null
        queue.clear()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            val frame = queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            if (prebuf) {
                if (queue.size >= TARGET_MS / FRAME_MS) {
                    prebuf = false
                } else {
                    continue
                }
            }
            try {
                val t = track
                if (t != null) {
                    val written = t.write(frame, 0, frame.size)
                    if (written < 0) {
                        underruns++
                        prebuf = true
                    }
                }
            } catch (e: Exception) {
                underruns++
                prebuf = true
            }
        }
    }
}