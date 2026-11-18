package dev.unrabbit.app

import android.Manifest
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowToneGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingCueTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val audio get() = context.getSystemService(AudioManager::class.java)

    @Test fun readyTonePlaysOnceAndCannotPlayAfterClosing() {
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        val cue = ReadyTone(context)
        assertTrue(cue.play() > 0)
        assertEquals(0, cue.play())
        assertEquals(1, ShadowToneGenerator.getPlayedTones().size)
        cue.close(); cue.close()
        assertEquals(0, cue.play())
        assertEquals(1, ShadowToneGenerator.getPlayedTones().size)
    }

    @Test fun silentVibrateAndMutedMediaNeverPlayOrChangeVolume() {
        for (mode in listOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE)) {
            audio.ringerMode = mode
            ReadyTone(context).use { assertEquals(0, it.play()) }
        }
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        ReadyTone(context).use { assertEquals(0, it.play()) }
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        shadowOf(audio).setIsStreamMute(AudioManager.STREAM_MUSIC, true)
        ReadyTone(context).use { assertEquals(0, it.play()) }
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(ShadowToneGenerator.getPlayedTones().isEmpty())
    }

    @Test fun permissionFailureNeverPlaysAReadyCue() {
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertThrows(IllegalStateException::class.java) { MicrophoneCapture(context, {}) }
        assertTrue(ShadowToneGenerator.getPlayedTones().isEmpty())
    }

    @Test fun microphoneDropsCueLeadInAndReleaseClosesCueWithoutDroppingLaterSpeech() {
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val reading = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        var chunks = 0
        var played = false
        var closed = false
        val cue = object : RecordingCue {
            override fun play(): Int { played = true; return 150 }
            override fun close() { closed = true }
        }
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(buffer: ShortArray, offset: Int, size: Int, blocking: Boolean): Int {
                if (chunks++ < 2) {
                    // Two 100 ms chunks: the first 150 ms are the cue, followed by speech.
                    buffer.fill(1000, offset, offset + size)
                    return size
                }
                reading.countDown()
                check(finishRead.await(3, TimeUnit.SECONDS))
                return 0
            }
        })
        val capture = MicrophoneCapture(context, {}, cue)
        try {
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            assertTrue(played)
            val result = capture.finish()
            assertTrue(closed)
            finishRead.countDown()
            val samples = result.get(3, TimeUnit.SECONDS)
            assertEquals(800, samples.size)
            assertTrue(samples.all { it == 1000 / 32768f })
        } finally { capture.cancel(); finishRead.countDown() }
    }

    @Test fun leadInHandlesShortBuffersAndSilentModeKeepsAllSamples() {
        val gate = RecordingLeadIn(2400)
        assertEquals(400, gate.skip(400))
        assertEquals(1600, gate.skip(1600))
        assertEquals(400, gate.skip(1600))
        assertEquals(0, gate.skip(1600))
        assertEquals(0, RecordingLeadIn(0).skip(1600))
    }
}
