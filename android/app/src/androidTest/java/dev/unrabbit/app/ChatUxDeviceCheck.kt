package dev.unrabbit.app

import android.app.Instrumentation
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.text.style.StyleSpan
import java.util.concurrent.TimeUnit

/** Real renderer, microphone and ready cue; no recordings are saved or sent to a VM. */
internal object ChatUxDeviceCheck {
    fun run(test: Instrumentation, report: Bundle) {
        val context = test.targetContext
        check(android.os.Process.myUid() != 0)
        test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        test.waitForIdleSync()
        val rendered = ChatMarkdown.render("See [the **artwork**](https://example.com/art_(IV)).")
        check(rendered.toString() == "See the artwork.")
        check(rendered.getSpans(0, rendered.length, StyleSpan::class.java).size == 1)
        report.putString("markdown", "passed")

        val audio = context.getSystemService(AudioManager::class.java)
        val shouldSound = audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            !audio.isStreamMute(AudioManager.STREAM_MUSIC) && audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
        var leadIn = -1
        val tone = ReadyTone(context)
        val cue = object : RecordingCue {
            override fun play(): Int = tone.play().also { leadIn = it }
            override fun close() = tone.close()
        }
        var capture: MicrophoneCapture? = null
        try {
            test.runOnMainSync { capture = MicrophoneCapture(context, {}, cue) }
            check(leadIn == if (shouldSound) 150 else 0)
            report.putString("ready_cue", if (shouldSound) "started" else "muted_by_device_settings")
            SystemClock.sleep(600)
            val samples = capture!!.finish().get(5, TimeUnit.SECONDS)
            try {
                check(samples.isNotEmpty())
                report.putInt("captured_samples", samples.size)
                report.putInt("cue_lead_in_ms", leadIn)
            } finally { samples.fill(0f) }
            check(tone.play() == 0) // A completed capture cannot leave a cue armed for later.
            report.putString("capture_and_cleanup", "passed")
        } finally { capture?.cancel(); tone.close() }
    }
}
