package dev.unrabbit.app

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real pinned SSH, VM synthesis and Android speaker decoding, without adding chat jobs. */
internal object ReplyDeviceCheck {
    fun run(test: Instrumentation, report: Bundle) {
        val context = test.targetContext
        check(android.os.Process.myUid() != 0)
        test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        test.waitForIdleSync()
        val api = requireNotNull(TransportFactory.createSpeechApi(context))
        try {
            api.resume()
            val health = api.request("GET", "/v1/health", null)
            check(health.first == 200 && health.second.getJSONObject("tts").getBoolean("ready"))
        } finally { api.close() }
        val output = VmReplySpeech(context)
        fun play(name: String, text: String, language: SpeechLanguage) {
            val finished = CountDownLatch(1)
            val terminal = AtomicReference<ReplySpeechState>()
            val start = SystemClock.elapsedRealtime()
            test.runOnMainSync {
                output.speak(text, language) { state ->
                    if (state == ReplySpeechState.PLAYING && !report.containsKey(name + "_first_audio_ms"))
                        report.putLong(name + "_first_audio_ms", SystemClock.elapsedRealtime() - start)
                    if (state == ReplySpeechState.FINISHED || state == ReplySpeechState.FAILED) {
                        terminal.set(state); finished.countDown()
                    }
                }
            }
            check(finished.await(45, TimeUnit.SECONDS))
            check(terminal.get() == ReplySpeechState.FINISHED)
            check(report.containsKey(name + "_first_audio_ms"))
            report.putLong(name + "_total_ms", SystemClock.elapsedRealtime() - start)
            report.putString(name, "passed")
        }
        try {
            play("english", "Hello. Your voice reply is ready. You can interrupt me with the side button.", SpeechLanguage.ENGLISH)
            play("german", "Hallo. Deine Sprachausgabe ist bereit. Mit der Seitentaste kannst du mich unterbrechen.", SpeechLanguage.GERMAN)
            val started = CountDownLatch(1); val callbacks = AtomicInteger()
            test.runOnMainSync {
                output.speak("This reply tests interruption while the next part is being prepared. ".repeat(8), SpeechLanguage.ENGLISH) {
                    callbacks.incrementAndGet()
                    if (it == ReplySpeechState.PLAYING) started.countDown()
                }
            }
            check(started.await(15, TimeUnit.SECONDS))
            val cancelStart = SystemClock.elapsedRealtime()
            test.runOnMainSync { output.cancel() }
            report.putLong("cancel_ms", SystemClock.elapsedRealtime() - cancelStart)
            val before = callbacks.get()
            SystemClock.sleep(2500) // Let a bounded, already-started VM chunk return.
            test.waitForIdleSync()
            check(callbacks.get() == before)
            report.putString("late_audio_after_cancel", "none")
            play("after_cancel", "Ready for your next question.", SpeechLanguage.ENGLISH)
            report.putString("reply_audio", "passed")
            report.putInt("uid", android.os.Process.myUid())
        } finally { test.runOnMainSync { output.close() } }
    }
}
