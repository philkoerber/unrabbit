package dev.unrabbit.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SpeechInputTest {
    private class Queue : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        var stopped = false
        override fun execute(command: Runnable) { check(!stopped); tasks.addLast(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
    private class Capture : SpeechCapture {
        val samples = FloatArray(1600) { .1f }
        var stopped = false
        var discarded = false
        var failure: Throwable? = null
        override fun finish(): Future<FloatArray> {
            stopped = true
            return failure?.let { cause -> CompletableFuture<FloatArray>().apply { completeExceptionally(cause) } }
                ?: CompletableFuture.completedFuture(samples)
        }
        override fun cancel() { discarded = true; stopped = true }
    }
    private class Recognizer : SpeechRecognizer {
        var result = "Recognized words."
        var failure = false
        val languages = mutableListOf<SpeechLanguage>()
        var cancelled = 0
        var closed = false
        override fun transcribe(samples: FloatArray, language: SpeechLanguage, cancelled: AtomicBoolean): String {
            languages += language
            if (failure) error("Test error")
            return result
        }
        override fun cancel() { cancelled++ }
        override fun close() { closed = true }
    }
    private class Fixture {
        val worker = Queue()
        val main = Queue()
        val engine = Recognizer()
        val recordings = mutableListOf<Capture>()
        var end: (() -> Unit)? = null
        val input = PushToTalkInput({ callback -> end = callback; Capture().also { recordings += it } }, engine, worker) { main.execute(it) }
        val events = mutableListOf<SpeechEvent>()
        fun start(language: SpeechLanguage = SpeechLanguage.ENGLISH) = input.start(language) { events += it }
        fun drain() { worker.drain(); main.drain() }
        fun close() { input.close(); drain() }
    }

    @Test fun releaseRecognizesOnceWithChosenLanguageAndErasesSamples() {
        val f = Fixture(); f.start(SpeechLanguage.GERMAN)
        f.input.finish(); f.input.finish()
        assertEquals(listOf(SpeechEvent.Recording, SpeechEvent.Transcribing), f.events)
        f.drain()
        assertEquals(listOf(SpeechLanguage.GERMAN), f.engine.languages)
        assertEquals(SpeechEvent.Text("Recognized words."), f.events.last())
        assertTrue(f.recordings.single().samples.all { it == 0f })
        f.close()
    }

    @Test fun cancelBeforeRecognitionDiscardsTheTurnAndAllowsAnotherRecording() {
        val f = Fixture(); f.start(); f.input.finish(); f.input.cancel()
        f.start(SpeechLanguage.GERMAN); f.input.finish(); f.drain()
        assertTrue(f.recordings.first().discarded)
        assertEquals(listOf(SpeechLanguage.GERMAN), f.engine.languages)
        assertEquals(1, f.events.filterIsInstance<SpeechEvent.Text>().size)
        f.close()
    }

    @Test fun cancelledResultAlreadyQueuedForUiCannotBeDelivered() {
        val f = Fixture(); f.start(); f.input.finish(); f.worker.drain()
        f.input.cancel(); f.main.drain()
        assertTrue(f.events.none { it is SpeechEvent.Text || it == SpeechEvent.NoSpeech })
        assertEquals(1, f.engine.cancelled)
        f.close()
    }

    @Test fun automaticCaptureEndAndLaterReleaseProduceOnlyOneResult() {
        val f = Fixture(); f.start(); f.end!!.invoke(); f.main.drain()
        f.input.finish(); f.drain()
        assertEquals(1, f.events.filterIsInstance<SpeechEvent.Text>().size)
        assertTrue(f.recordings.single().stopped)
        f.close()
    }

    @Test fun silenceAndFailureNeverBecomeTextAndNextTurnCanSucceed() {
        val f = Fixture(); f.engine.result = "  "
        f.start(); f.input.finish(); f.drain()
        assertEquals(SpeechEvent.NoSpeech, f.events.last())
        f.engine.failure = true
        f.start(); f.input.finish(); f.drain()
        assertEquals(SpeechEvent.Failed, f.events.last())
        assertTrue(f.events.none { it is SpeechEvent.Text })
        f.engine.failure = false; f.engine.result = "Next utterance"
        f.start(); f.input.finish(); f.drain()
        assertEquals(SpeechEvent.Text("Next utterance"), f.events.last())
        f.close()
    }

    @Test fun destructionDiscardsPendingResultAndClosesSpeechResources() {
        val f = Fixture(); f.start(); f.input.finish(); f.worker.drain()
        f.close()
        assertTrue(f.engine.closed)
        assertTrue(f.events.none { it is SpeechEvent.Text })
        val count = f.events.size; f.start()
        assertEquals(count, f.events.size)
    }

    @Test fun lateEndCallbackFromCancelledCaptureCannotEndTheNextRecording() {
        val f = Fixture(); f.start(); val oldEnd = f.end!!
        f.input.cancel(); f.start(); oldEnd(); f.main.drain()
        assertFalse(f.recordings.last().stopped)
        assertTrue(f.events.none { it == SpeechEvent.Transcribing })
        f.close()
    }

    @Test fun recordingLimitDiscardsAudioInsteadOfSendingATruncatedRequest() {
        val f = Fixture(); f.start()
        f.recordings.single().failure = RecordingTooLong()
        f.end!!.invoke(); f.main.drain(); f.drain()
        assertEquals(SpeechEvent.TooLong, f.events.last())
        assertTrue(f.engine.languages.isEmpty())
        assertTrue(f.events.none { it is SpeechEvent.Text })
        f.close()
    }
}
