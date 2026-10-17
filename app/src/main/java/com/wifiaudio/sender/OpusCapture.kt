package com.wifiaudio.sender

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Process

/**
 * System playback capture (NOT the microphone) -> 20 ms PCM frames -> Android's Opus encoder.
 * Every finished Opus packet is handed to [onPacket].
 */
class OpusCapture(
    private val projection: MediaProjection,
    private val onPacket: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 48000
        const val FRAME_SAMPLES = 960                    // 20 ms
        const val FRAME_BYTES = FRAME_SAMPLES * 2 * 2    // stereo, 16-bit
        const val BITRATE = 128_000
    }

    @Volatile private var running = false
    @Volatile private var stopRequested = false
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    /** Returns null on success or a user-readable error. */
    fun start(): String? {
        val rec: AudioRecord
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return "Could not start Android audio capture."
            }
        } catch (e: SecurityException) {
            return "Audio permission was denied. Android requires it to capture system audio."
        } catch (e: Exception) {
            return "Could not start Android audio capture."
        }

        val enc: MediaCodec
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 2)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
        } catch (e: Exception) {
            rec.release()
            return "This phone has no Opus encoder."
        }

        record = rec
        codec = enc
        running = true
        try {
            rec.startRecording()
        } catch (e: Exception) {
            running = false
            release()
            return "Could not start Android audio capture."
        }
        thread = Thread({ loop(rec, enc) }, "opus-capture").also { it.start() }
        return null
    }

    private fun loop(rec: AudioRecord, enc: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val pcm = ByteArray(FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L
        try {
            while (running) {
                var got = 0
                while (running && got < FRAME_BYTES) {
                    val n = rec.read(pcm, got, FRAME_BYTES - got)
                    if (n < 0) throw IllegalStateException("read error $n")
                    got += n
                }
                if (!running) break
                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = enc.getInputBuffer(inIdx)!!
                    buf.clear()
                    buf.put(pcm, 0, FRAME_BYTES)
                    enc.queueInputBuffer(inIdx, 0, FRAME_BYTES, ptsUs, 0)
                    ptsUs += 20_000
                }
                drain(enc, info, 2_000)
            }
        } catch (e: Exception) {
            if (!stopRequested) onError("Android stopped the audio capture.")
        }
    }

    private fun drain(enc: MediaCodec, info: MediaCodec.BufferInfo, firstTimeoutUs: Long) {
        var timeout = firstTimeoutUs
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, timeout)
            timeout = 0
            when {
                idx >= 0 -> {
                    val out = enc.getOutputBuffer(idx)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (out != null && info.size > 0 && !isConfig) {
                        val data = ByteArray(info.size)
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        out.get(data)
                        onPacket(data)
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                // INFO_OUTPUT_FORMAT_CHANGED / BUFFERS_CHANGED: just loop again
            }
        }
    }

    fun stop() {
        stopRequested = true
        running = false
        try { thread?.join(1000) } catch (_: InterruptedException) { }
        release()
    }

    private fun release() {
        try { record?.stop() } catch (_: Exception) { }
        try { record?.release() } catch (_: Exception) { }
        try { codec?.stop() } catch (_: Exception) { }
        try { codec?.release() } catch (_: Exception) { }
        record = null
        codec = null
    }
}
