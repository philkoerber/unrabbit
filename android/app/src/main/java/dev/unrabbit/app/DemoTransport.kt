package dev.unrabbit.app

import android.os.Handler
import android.os.Looper
import dev.unrabbit.core.*

/** Explicitly local simulation. No socket, SSH, microphone or model calls. */
class DemoTransport : ChatTransport {
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<String, Runnable>()
    override fun send(request: ChatRequest, onResult: (TransportResult) -> Unit) {
        check(!callbacks.containsKey(request.id))
        val task = Runnable {
            callbacks.remove(request.id)
            val answer = when {
                request.photos.isNotEmpty() -> "Photos and your message are queued together. Connect your VM to analyze images."
                request.text.contains("radio", ignoreCase = true) -> "A radio for stories. Which story would you like to hear first?"
                request.text.contains("outside", ignoreCase = true) -> "Where would you like to go: the water or the woods?"
                else -> "Tell me more. What interests you most about it?"
            }
            onResult(TransportResult.Reply(answer))
        }
        callbacks[request.id] = task
        handler.postDelayed(task, 1800)
    }
    override fun cancel(requestId: String, onAcknowledged: (Boolean) -> Unit) {
        callbacks.remove(requestId)?.let(handler::removeCallbacks)
        onAcknowledged(true)
    }
    override fun close() { callbacks.values.forEach(handler::removeCallbacks); callbacks.clear() }
}
