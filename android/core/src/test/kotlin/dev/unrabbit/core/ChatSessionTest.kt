package dev.unrabbit.core

import org.junit.Assert.*
import org.junit.Test

class ChatSessionTest {
    @Test fun expiredDraftsStayInHistoryButDoNotJoinOrBlockTheNextRequest() {
        var now = 1_000L
        val session = ChatSession(now = { now })
        val expired = PhotoAttachment(java.util.UUID.randomUUID().toString(), 32, 24, now)
        session.addPhoto(expired)
        now += PhotoAttachment.RETENTION_MILLIS - 1
        assertEquals(listOf(expired), session.draftPhotos)
        now++
        assertTrue(session.draftPhotos.isEmpty())
        val fresh = expired.copy(id = java.util.UUID.randomUUID().toString(), capturedAt = now)
        session.addPhoto(fresh)
        val request = session.submit("Das neue Bild")
        assertEquals(listOf(fresh), request.photos)
        assertEquals(listOf(expired, fresh), session.messages.flatMap { it.photos })
        val restored = ChatSession(session.snapshot(), resumePending = true, now = { now })
        assertEquals(listOf(fresh), restored.activeRequests.single().photos)
        assertTrue(restored.draftPhotos.isEmpty())
    }
    @Test fun photoJoinsTheSameStreamWithoutInventingAnAnalysisOrBlockingPendingWork() {
        val session = ChatSession()
        val request = session.submit("Eine Frage")
        val photo = PhotoAttachment(java.util.UUID.randomUUID().toString(), 1200, 1600)
        val message = session.addPhoto(photo)
        assertEquals(listOf(photo), message.photos)
        assertTrue(message.text.isEmpty())
        assertEquals(listOf(request), session.activeRequests)
        assertThrows(IllegalArgumentException::class.java) { session.addPhoto(photo) }
        session.reply(request.id, "Eine Antwort")
        assertEquals(session.messages, ChatSession(session.snapshot()).messages)
    }

    @Test fun photosWaitForSpeechAcrossRepliesAndRestartAndJoinOnlyTheNextRequest() {
        val session = ChatSession()
        val older = session.submit("Noch eine andere Aufgabe")
        val first = PhotoAttachment(java.util.UUID.randomUUID().toString(), 800, 600)
        val second = PhotoAttachment(java.util.UUID.randomUUID().toString(), 600, 800)
        val firstMessage = session.addPhoto(first)
        session.reply(older.id, "Erledigt") // A reply between photos and speech must not lose the draft.
        val secondMessage = session.addPhoto(second)
        assertFalse(session.pending())
        assertEquals(listOf(first, second), session.draftPhotos)
        val restored = ChatSession(session.snapshot())
        assertEquals(listOf(first, second), restored.draftPhotos)
        val request = restored.submit("Vergleiche diese beiden Bilder")
        assertEquals(listOf(first, second), request.photos)
        assertEquals(request.id, restored.messages.single { it.id == firstMessage.id }.requestId)
        assertEquals(request.id, restored.messages.single { it.id == secondMessage.id }.requestId)
        assertEquals(listOf(first, second), restored.messages.flatMap { it.photos }) // No duplicate image bubbles.
        assertTrue(restored.draftPhotos.isEmpty())
        assertTrue(restored.submit("Nächster Gedanke").photos.isEmpty())
        assertTrue(ChatSession(restored.snapshot()).submit("Nach Neustart").photos.isEmpty())
    }

    @Test fun invalidSpeechAndStoppingOlderWorkDoNotConsumeDraftPhotos() {
        val session = ChatSession()
        val older = session.submit("Aufgabe")
        val photo = PhotoAttachment(java.util.UUID.randomUUID().toString(), 800, 600)
        session.addPhoto(photo)
        val before = session.snapshot()
        assertThrows(IllegalArgumentException::class.java) { session.submit(" \n ") }
        assertThrows(IllegalArgumentException::class.java) { session.submit("a".repeat(16_001)) }
        assertEquals(before, session.snapshot())
        session.stopped(older.id)
        assertEquals(listOf(photo), session.draftPhotos)
        assertEquals(listOf(photo), session.submit("Was ist das?").photos)
    }
    @Test fun requestsRunIndependentlyAndOutOfOrderRepliesKeepTheirOrigin() {
        val session = ChatSession()
        val first = session.submit("Am Radio weiterbauen")
        val second = session.submit("Ein Ausflug")
        assertEquals(listOf(first, second), session.activeRequests)
        assertTrue(session.reply(second.id, "Ans Wasser?"))
        assertTrue(session.isActive(first.id))
        assertFalse(session.reply(second.id, "Doppelt"))
        assertTrue(session.reply(first.id, "Zum Radio: bereit."))
        assertFalse(session.pending())
        assertEquals(listOf("Am Radio weiterbauen", "Ein Ausflug", "Ans Wasser?", "Zum Radio: bereit."), session.messages.map { it.text })
        assertEquals(listOf(first.id, second.id, second.id, first.id), session.messages.map { it.requestId })
    }

    @Test fun stoppingOneRequestDoesNotCancelOtherOrLaterRequests() {
        val session = ChatSession()
        val first = session.submit("Ein Ausflug")
        val second = session.submit("Noch ein Gedanke")
        assertTrue(session.stopped(first.id))
        assertFalse(session.reply(first.id, "Zu spät"))
        assertTrue(session.isActive(second.id))
        val third = session.submit("Eine neue Idee")
        assertEquals(listOf(second, third), session.activeRequests)
    }

    @Test fun stoppingTheCapturedRequestsIgnoresDuplicateAndLateCallbacks() {
        val session = ChatSession()
        val request = session.submit("Erster Gedanke")
        session.submit("Zweiter Gedanke")
        session.activeRequests.forEach { assertTrue(session.stopped(it.id)) }
        assertFalse(session.stopped(request.id))
        assertFalse(session.reply(request.id, "Zu spät"))
        assertEquals(2, session.messages.size)
        assertFalse(session.pending())
        assertNotNull(session.submit("Neuer Anfang"))
    }

    @Test fun processRestartRestoresHistoryWithoutReplayingWork() {
        val session = ChatSession()
        session.submit("Nicht automatisch wiederholen")
        val restored = ChatSession(session.snapshot())
        assertTrue(restored.interruptedOnRestore)
        assertFalse(restored.pending())
        assertEquals(session.messages, restored.messages)
        assertFalse(restored.reply(session.activeRequests.single().id, "Alter Callback"))
    }

    @Test fun emptyMessageDoesNotCreateHistory() {
        val session = ChatSession()
        assertThrows(IllegalArgumentException::class.java) { session.submit(" \n ") }
        assertTrue(session.messages.isEmpty())
    }

    @Test fun networkRecoveryRetainsExactRequestIdsAndPhotosWithoutDuplicatingMessages() {
        val session = ChatSession()
        val photo = PhotoAttachment(java.util.UUID.randomUUID().toString(), 32, 24)
        session.addPhoto(photo)
        val first = session.submit("Bild ansehen")
        val second = session.submit("Und dann?")
        val restored = ChatSession(session.snapshot(), resumePending = true)
        assertEquals(listOf(first, second), restored.activeRequests)
        assertEquals(session.messages, restored.messages)
        assertTrue(restored.reply(first.id, "Antwort nach Wiederaufnahme"))
        assertFalse(restored.reply(first.id, "Doppelt"))
        restored.stopped(second.id)
        assertTrue(restored.snapshot().pendingRequestIds.isEmpty())
        assertFalse(ChatSession(restored.snapshot(), resumePending = true).pending())
    }
}
