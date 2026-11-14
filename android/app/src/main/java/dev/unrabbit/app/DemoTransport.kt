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
                request.photos.isNotEmpty() -> "Bilder und Nachricht sind zusammen vorgemerkt. Bildauswertung kommt mit der VM-Verbindung."
                request.text.contains("radio", ignoreCase = true) -> "Ein Radio für Geschichten. Welche Geschichte würdest du als Erstes hören wollen?"
                request.text.contains("raus", ignoreCase = true) -> "Wohin zieht es dich: ans Wasser oder in den Wald?"
                else -> "Erzähl weiter. Was daran interessiert dich besonders?"
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
