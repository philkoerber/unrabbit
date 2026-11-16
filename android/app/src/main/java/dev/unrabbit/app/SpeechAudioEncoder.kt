package dev.unrabbit.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Platform AAC-LC encoder: 16 kHz mono, 32 kbit/s, ADTS framing, memory only. */
internal object SpeechAudioEncoder {
    fun encode(samples: FloatArray, cancelled: AtomicBoolean): ByteArray {
        require(samples.isNotEmpty() && samples.size <= 30 * 16_000 && samples.all { it.isFinite() })
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val output = ByteArrayOutputStream()
        try {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 16_000, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var cursor = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (true) {
                if (cancelled.get()) throw CancellationException()
                check(SystemClock.elapsedRealtime() < deadline) { "Audio encoding timed out" }
                if (!inputDone) {
                    // Never wait for input while the codec may be waiting for us to drain output.
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                        val count = minOf(buffer.remaining() / 2, samples.size - cursor)
                        val timestamp = cursor.toLong() * 1_000_000 / 16_000
                        repeat(count) { buffer.putShort((samples[cursor++].coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
                        inputDone = cursor == samples.size
                        codec.queueInputBuffer(index, 0, count * 2, timestamp,
                            if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 1_000)
                if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val frame = ByteArray(info.size)
                            requireNotNull(codec.getOutputBuffer(index)).apply { position(info.offset); limit(info.offset + info.size) }.get(frame)
                            check(output.size() + frame.size + 7 <= 256 * 1024)
                            output.write(adts(frame.size)); output.write(frame); frame.fill(0)
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return output.toByteArray().also { check(it.isNotEmpty()) }
        } finally { runCatching { codec.stop() }; codec.release() }
    }

    private fun adts(payload: Int): ByteArray {
        val length = payload + 7
        require(length < 8192)
        return byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x60, (0x40 or (length shr 11)).toByte(),
            (length shr 3).toByte(), (((length and 7) shl 5) or 0x1f).toByte(), 0xfc.toByte())
    }
}
