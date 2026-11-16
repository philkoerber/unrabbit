package dev.unrabbit.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface SpeechEvent {
    data object Recording : SpeechEvent
    data object Transcribing : SpeechEvent
    data class Text(val text: String) : SpeechEvent
    data object NoSpeech : SpeechEvent
    data object Failed : SpeechEvent
    data object TooLong : SpeechEvent
    data object Unavailable : SpeechEvent
}

internal interface SpeechInput : AutoCloseable {
    fun start(language: SpeechLanguage, listener: (SpeechEvent) -> Unit)
    fun finish()
    fun cancel()
}

/** Turn identity prevents a cancelled recording or a late VM transcript from submitting text. */
internal class PushToTalkInput(
    private val capture: (() -> Unit) -> SpeechCapture,
    private val recognizer: SpeechRecognizer,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val post: (() -> Unit) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) : SpeechInput {
    constructor(context: Context) : this({ onEnd -> MicrophoneCapture(context.applicationContext, onEnd) }, VmSpeechRecognizer(context))
    private class Turn(val language: SpeechLanguage, val listener: (SpeechEvent) -> Unit) {
        val cancelled = AtomicBoolean(false)
        var microphone: SpeechCapture? = null
        var transcribing = false
    }
    private var current: Turn? = null
    private var closed = false

    override fun start(language: SpeechLanguage, listener: (SpeechEvent) -> Unit) {
        if (closed || current != null) return
        val turn = Turn(language, listener)
        current = turn
        try {
            turn.microphone = capture { post { if (current === turn && !turn.transcribing) finish() } }
            listener(SpeechEvent.Recording)
        } catch (_: Exception) { current = null; listener(SpeechEvent.Failed) }
    }

    override fun finish() {
        val turn = current ?: return
        if (turn.transcribing) return
        turn.transcribing = true
        val audio = turn.microphone?.finish() ?: return
        turn.listener(SpeechEvent.Transcribing)
        worker.execute {
            var samples: FloatArray? = null
            val event = try {
                samples = audio.get()
                val text = if (turn.cancelled.get() || samples.isEmpty()) ""
                    else recognizer.transcribe(samples, turn.language, turn.cancelled).orEmpty()
                when {
                    text.isBlank() -> SpeechEvent.NoSpeech
                    text.length > 16_000 -> SpeechEvent.Failed
                    else -> SpeechEvent.Text(text)
                }
            } catch (failure: ExecutionException) {
                if (failure.cause is RecordingTooLong) SpeechEvent.TooLong else SpeechEvent.Failed
            } catch (_: SpeechUnavailable) { SpeechEvent.Unavailable }
            catch (_: Exception) { SpeechEvent.Failed }
            catch (_: LinkageError) { SpeechEvent.Failed }
            finally { samples?.fill(0f) }
            post {
                if (!closed && current === turn && !turn.cancelled.get()) {
                    current = null
                    turn.listener(event)
                }
            }
        }
    }

    override fun cancel() {
        val turn = current ?: return
        turn.cancelled.set(true)
        current = null
        turn.microphone?.cancel()
        recognizer.cancel()
    }

    override fun close() {
        if (closed) return
        cancel(); closed = true
        worker.execute { recognizer.close() }
        worker.shutdown()
    }
}
