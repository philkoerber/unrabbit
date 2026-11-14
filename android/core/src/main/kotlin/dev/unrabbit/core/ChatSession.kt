package dev.unrabbit.core

import java.util.UUID

enum class Role { USER, ASSISTANT }
data class PhotoAttachment(val id: String, val width: Int, val height: Int) {
    init {
        require(UUID.fromString(id).toString() == id) { "Invalid photo ID" }
        require(width in 1..2048 && height in 1..2048) { "Invalid photo dimensions" }
    }
}
data class Message(val id: String, val role: Role, val text: String, val requestId: String? = null,
    val photos: List<PhotoAttachment> = emptyList())
data class SessionSnapshot(val messages: List<Message>, val hadPending: Boolean = false)
data class ChatRequest(val id: String, val text: String, val photos: List<PhotoAttachment> = emptyList())

/** Pure chat state. Android input, storage, audio and transport do not belong here. */
class ChatSession(snapshot: SessionSnapshot? = null, private val nextId: () -> String = { UUID.randomUUID().toString() }) {
    private val items = snapshot?.messages?.toMutableList() ?: mutableListOf()
    private val active = mutableMapOf<String, ChatRequest>()
    val interruptedOnRestore = snapshot?.hadPending == true
    val messages: List<Message> get() = items.toList()
    val activeRequests: List<ChatRequest> get() = active.values.toList()
    val draftPhotos: List<PhotoAttachment> get() = items.filter { it.role == Role.USER && it.requestId == null }.flatMap { it.photos }

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
            if (message.role == Role.USER && message.requestId == null && message.photos.isNotEmpty())
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
    fun snapshot() = SessionSnapshot(messages, active.isNotEmpty())
}

sealed interface TransportResult {
    data class Reply(val text: String) : TransportResult
    data class Failure(val message: String) : TransportResult
}

/** Implementations deliver callbacks on the UI thread; IDs survive reconnects. */
interface ChatTransport {
    fun send(request: ChatRequest, onResult: (TransportResult) -> Unit)
    fun cancel(requestId: String, onAcknowledged: (Boolean) -> Unit)
    fun close()
}
