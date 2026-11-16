package dev.unrabbit.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

internal interface SpeechCapture {
    fun finish(): Future<FloatArray>
    fun cancel()
}

internal class RecordingTooLong : Exception()

/** Foreground push-to-talk only. At most 30 seconds, kept in memory and never written to disk. */
@SuppressLint("MissingPermission") // Checked before constructing AudioRecord; revocation is handled as a capture error.
internal class MicrophoneCapture(context: Context, onEnd: () -> Unit) : SpeechCapture {
    private val running = AtomicBoolean(true)
    private val discard = AtomicBoolean(false)
    private val recorder: AudioRecord
    private val result: FutureTask<FloatArray>
    init {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 6400)).build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        } catch (failure: Exception) { recorder.release(); throw failure }
        result = FutureTask {
            val samples = FloatArray(30 * 16_000)
            val buffer = ShortArray(1600)
            var size = 0
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                while (running.get() && size < samples.size) {
                    val count = recorder.read(buffer, 0, minOf(buffer.size, samples.size - size), AudioRecord.READ_BLOCKING)
                    if (count < 0) { check(!running.get()) { "Microphone read failed" }; break }
                    if (count == 0) { check(!running.get()) { "Microphone returned no data" }; break }
                    repeat(count) { samples[size++] = buffer[it] / 32768f }
                }
                if (size == samples.size && running.get()) throw RecordingTooLong()
                if (discard.get()) FloatArray(0) else samples.copyOf(size)
            } finally {
                running.set(false)
                runCatching { recorder.stop() }
                recorder.release()
                samples.fill(0f); buffer.fill(0)
                onEnd()
            }
        }
        Thread(result, "unrabbit-microphone").start()
    }
    override fun finish(): Future<FloatArray> {
        running.set(false)
        runCatching { recorder.stop() }
        return result
    }
    override fun cancel() { discard.set(true); finish() }
}
