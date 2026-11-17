package dev.unrabbit.core

import java.util.UUID

enum class Role { USER, ASSISTANT }
data class PhotoAttachment(val id: String, val width: Int, val height: Int, val capturedAt: Long = 0L) {
    companion object { const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000 }
    fun isExpired(now: Long) = capturedAt > 0 && now - capturedAt >= RETENTION_MILLIS
    init {
        require(UUID.fromString(id).toString() == id) { "Invalid photo ID" }
        require(width in 1..2048 && height in 1..2048) { "Invalid photo dimensions" }
        require(capturedAt >= 0) { "Invalid photo capture time" }
    }
}
data class Message(val id: String, val role: Role, val text: String, val requestId: String? = null,
    val photos: List<PhotoAttachment> = emptyList())
data class SessionSnapshot(val messages: List<Message>, val hadPending: Boolean = false,
    val pendingRequestIds: List<String> = emptyList(), val backendId: String? = null)
data class ChatRequest(val id: String, val text: String, val photos: List<PhotoAttachment> = emptyList())

/** Pure chat state. Android input, storage, audio and transport do not belong here. */
class ChatSession(snapshot: SessionSnapshot? = null, private val nextId: () -> String = { UUID.randomUUID().toString() },
    resumePending: Boolean = false, private val now: () -> Long = System::currentTimeMillis) {
    private val items = snapshot?.messages?.toMutableList() ?: mutableListOf()
    private val active = mutableMapOf<String, ChatRequest>()
    init {
        if (resumePending) snapshot?.pendingRequestIds?.forEach { id ->
            val message = items.single { it.id == id && it.role == Role.USER && it.text.isNotBlank() }
            active[id] = ChatRequest(id, message.text, items.filter { it.requestId == id }.flatMap { it.photos })
        }
    }
    val interruptedOnRestore = snapshot?.hadPending == true
    val messages: List<Message> get() = items.toList()
    val activeRequests: List<ChatRequest> get() = active.values.toList()
    val draftPhotos: List<PhotoAttachment> get() = items.filter { it.role == Role.USER && it.requestId == null }
        .flatMap { it.photos }.filterNot { it.isExpired(now()) }

    /** Visible local draft. Only a later text submission assigns these photos to a request. */
    fun addPhoto(photo: PhotoAttachment): Message {
        require(items.none { message -> message.photos.any { it.id == photo.id } }) { "Photo already attached" }
        return Message(nextId(), Role.USER, "", photos = listOf(photo)).also { items += it }
    }

    /** One visible stream, independent requests. Routing and execution order belong to the VM. */
    fun submit(text: String): ChatRequest {
        val clean = text.trim()
        require(clean.isNotEmpty() && clean.length <= 16_000) { "Message must contain 1–16000 characters" }
        val request = ChatRequest(nextId(), clean, draftPhotos)
        // Keep photos in their existing place in the stream; the shared request ID records
        // which utterance sent them, even across restarts and out-of-order replies.
        items.replaceAll { message ->
            if (message.role == Role.USER && message.requestId == null && message.photos.any { it in request.photos })
                message.copy(requestId = request.id) else message
        }
        items += Message(request.id, Role.USER, clean, request.id)
        active[request.id] = request
        return request
    }

    /** Append in arrival order, retaining the origin even when answers arrive out of order. */
    fun reply(requestId: String, text: String): Boolean {
        if (active.remove(requestId) == null) return false
        items += Message("reply:$requestId", Role.ASSISTANT, text, requestId)
        return true
    }

    fun isActive(requestId: String) = active.containsKey(requestId)
    fun pending() = active.isNotEmpty()

    /** Call only after cancellation was acknowledged or this request failed. */
    fun stopped(requestId: String) = active.remove(requestId) != null
    fun snapshot() = SessionSnapshot(messages, active.isNotEmpty(), active.keys.toList())
}

sealed interface TransportResult {
    data class Reply(val text: String) : TransportResult
    data class Failure(val message: String) : TransportResult
    data class Waiting(val message: String?) : TransportResult
}

/** Implementations deliver callbacks on the UI thread; IDs survive reconnects. */
interface ChatTransport {
    val identity: String? get() = null
    fun send(request: ChatRequest, onResult: (TransportResult) -> Unit)
    fun cancel(requestId: String, onAcknowledged: (Boolean) -> Unit)
    fun resume() {}
    fun pause() {}
    fun close()
}
