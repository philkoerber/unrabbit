package dev.unrabbit.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

internal interface RecordingCue : AutoCloseable {
    /** Called only once the microphone is running. Returns the audio lead-in to discard, in ms. */
    fun play(): Int
}

/** Brief, quiet media cue. Silent/vibrate mode and muted media remain silent. */
internal class ReadyTone(context: Context) : RecordingCue {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var tone: ToneGenerator? = null
    private var closed = false

    @Synchronized override fun play(): Int {
        if (closed || tone != null) return 0
        return try {
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
                audio.isStreamMute(AudioManager.STREAM_MUSIC) || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return 0
            val player = ToneGenerator(AudioManager.STREAM_MUSIC, 25).also { tone = it }
            if (player.startTone(ToneGenerator.TONE_PROP_BEEP, 70)) 150
            else { close(); 0 }
        } catch (_: Exception) { close(); 0 } // Optional feedback must never prevent recording.
    }

    @Synchronized override fun close() {
        closed = true
        tone?.let { runCatching { it.stopTone() }; runCatching { it.release() } }
        tone = null
    }
}

/** Remove the cue lead-in across arbitrary AudioRecord chunk boundaries. */
internal class RecordingLeadIn(private var remaining: Int) {
    fun skip(count: Int): Int = minOf(count, remaining).also { remaining -= it }
}
