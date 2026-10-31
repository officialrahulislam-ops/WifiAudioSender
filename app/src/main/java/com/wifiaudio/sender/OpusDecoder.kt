package com.wifiaudio.sender

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Android's Opus decoder (MediaCodec). Feeds raw Opus packets produced by the Windows
 * libopus encoder and yields 16-bit stereo PCM frames (960 samples = 20 ms @ 48 kHz).
 */
class OpusDecoder {
    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val BIT_RATE = 128_000
        private const val MAX_OUT = 8192
    }

    private var codec: MediaCodec? = null
    @Volatile private var running = false

    /** Returns null on success or a user-readable error. */
    fun start(): String? {
        try {
            val fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65535)
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            dec.configure(fmt, null, null, 0)
            dec.start()
            codec = dec
            running = true
            return null
        } catch (e: Exception) {
            return "This phone has no Opus decoder."
        }
    }

    /**
     * Decodes one Opus packet. Returns the decoded PCM (16-bit, interleaved stereo) or null
     * if the packet could not be decoded (bad/lost packet). The caller must not rely on the
     * returned length being exactly one frame; for 20 ms stereo it is 3840 bytes.
     */
    fun decode(input: ByteArray): ByteArray? {
        val dec = codec ?: return null
        val out = ByteBuffer.allocate(MAX_OUT)
        try {
            val inIdx = dec.dequeueInputBuffer(5_000)
            if (inIdx < 0) return null
            val buf = dec.getInputBuffer(inIdx)!!
            buf.clear()
            buf.put(input)
            dec.queueInputBuffer(inIdx, 0, input.size, 0, 0)

            val info = MediaCodec.BufferInfo()
            var total = 0
            while (total < MAX_OUT) {
                val outIdx = dec.dequeueOutputBuffer(info, 5_000)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val o = dec.getOutputBuffer(outIdx)!!
                            o.position(info.offset)
                            o.limit(info.offset + info.size)
                            val n = minOf(info.size, MAX_OUT - total)
                            out.put(o, n)   // reads n bytes from o into out
                            total += n
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    else -> break
                }
            }
            if (total == 0) return null
            out.flip()
            return ByteArray(total).also { out.get(it) }
        } catch (e: Exception) {
            return null
        }
    }

    fun stop() {
        running = false
        try { codec?.stop() } catch (_: Exception) { }
        try { codec?.release() } catch (_: Exception) { }
        codec = null
    }
}