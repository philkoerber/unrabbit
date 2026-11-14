package dev.unrabbit.app

import dev.unrabbit.core.ChatSession
import dev.unrabbit.core.Message
import dev.unrabbit.core.PhotoAttachment
import dev.unrabbit.core.Role
import dev.unrabbit.core.SessionSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalStorageTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun legacyChatsMergeOnceWithoutLosingMessagesOrOverwritingTheOriginal() {
        val original = """{"version":1,"currentId":"old","hadPending":true,"chats":[
          {"id":"empty","title":"Leer","messages":[]},
          {"id":"new","title":"Ausflug","messages":[
            {"id":"n","role":"USER","text":"Wohin?"},
            {"id":"reply:n","role":"ASSISTANT","text":"Ans Wasser."}]},
          {"id":"old","title":"Radio","messages":[
            {"id":"o","role":"USER","text":"Radio bauen"},
            {"id":"reply:o","role":"ASSISTANT","text":"Eine Idee"}]}]}"""
        val legacy = File(context.noBackupFilesDir, "demo-chats.json").apply { writeText(original) }
        val storage = LocalStorage(context)
        val migrated = storage.load()!!
        assertFalse(storage.readFailed)
        assertEquals(listOf("n", "reply:n", "o", "reply:o"), migrated.messages.map { it.id })
        assertEquals(listOf("n", "n", "o", "o"), migrated.messages.map { it.requestId })
        val session = ChatSession(migrated)
        assertTrue(session.interruptedOnRestore)
        assertFalse(session.pending())
        val request = session.submit("Weiter")
        session.reply(request.id, "Bereit")
        storage.save(session.snapshot())
        assertEquals(original, legacy.readText())
        repeat(2) {
            val reopened = LocalStorage(context)
            assertEquals(session.snapshot(), reopened.load())
            reopened.save(reopened.load()!!)
        }
    }

    @Test fun streamRoundTripRetainsRequestLinksAndPendingMarker() {
        val storage = LocalStorage(context)
        val snapshot = SessionSnapshot(listOf(
            Message("a", Role.USER, "Ändern\nbitte", "a"),
            Message("reply:a", Role.ASSISTANT, "✓", "a"),
            Message("legacy", Role.ASSISTANT, "Ohne Anfrage-ID"),
            Message("photo-message", Role.USER, "", photos = listOf(PhotoAttachment(java.util.UUID.randomUUID().toString(), 800, 600)))
        ), hadPending = true)
        storage.save(snapshot)
        assertEquals(snapshot, LocalStorage(context).load())
    }

    @Test fun schemaTwoUpgradesWithoutLosingExistingMessages() {
        File(context.noBackupFilesDir, "demo-stream.json").writeText("""{"version":2,"hadPending":false,
          "messages":[{"id":"a","role":"USER","text":"Vor dem Foto","requestId":"a"}]}""")
        val storage = LocalStorage(context)
        val before = storage.load()!!
        assertEquals(listOf(Message("a", Role.USER, "Vor dem Foto", "a")), before.messages)
        storage.save(before)
        assertEquals(before, LocalStorage(context).load())
    }

    @Test fun schemaThreePhotoDraftSurvivesDiskAndIsNotResentAfterSubmission() {
        val photo = PhotoAttachment(java.util.UUID.randomUUID().toString(), 800, 600)
        val session = ChatSession()
        session.addPhoto(photo)
        val storage = LocalStorage(context)
        storage.save(session.snapshot())
        val restored = ChatSession(storage.load())
        assertEquals(listOf(photo), restored.draftPhotos)
        val request = restored.submit("Was ist das?")
        storage.save(restored.snapshot())
        val afterSend = ChatSession(storage.load())
        assertEquals(request.id, afterSend.messages.first().requestId)
        assertTrue(afterSend.draftPhotos.isEmpty())
        assertTrue(afterSend.submit("Noch eine Frage").photos.isEmpty())
    }

    @Test fun unreadableStreamIsPreservedInsteadOfFallingBackToStaleLegacyData() {
        val file = File(context.noBackupFilesDir, "demo-stream.json").apply { writeText("unreadable") }
        File(context.noBackupFilesDir, "demo-chats.json").writeText("""{"version":1,"currentId":"x","chats":[]}""")
        val storage = LocalStorage(context)
        assertNull(storage.load())
        assertTrue(storage.readFailed)
        assertFalse(file.exists())
        assertEquals("unreadable", context.noBackupFilesDir.listFiles()!!.single {
            it.name.startsWith("demo-stream-unreadable-")
        }.readText())
    }
}
