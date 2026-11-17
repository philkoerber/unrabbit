package dev.unrabbit.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ReplySpeechTest {
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(4, TimeUnit.SECONDS))
    private class Api : VmApi {
        override val identity = "fixture"
        val texts = CopyOnWriteArrayList<String>()
        val closed = CountDownLatch(1)
        var beforeResult: (Int) -> Unit = {}
        var status = 200
        var malformed = false
        override fun request(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> {
            assertEquals("POST", method); assertEquals("/v1/speech/synthesize", path)
            texts += body!!.getString("text")
            beforeResult(texts.size)
            val bytes = byteArrayOf(0, 0, 0, 24) + "ftypM4A ".toByteArray()
            return status to JSONObject().put("language", body.getString("language")).put("format", "aac-mp4")
                .put("audio", if (malformed) "!!!!" else Base64.getEncoder().encodeToString(bytes))
        }
        override fun close() { closed.countDown() }
    }
    private class Player : ReplyAudioPlayer {
        val buffers = CopyOnWriteArrayList<ByteArray>()
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val completed = CountDownLatch(1)
        override fun play(audio: ByteArray, onStarted: () -> Unit) { buffers += audio; started.countDown(); onStarted() }
        override fun awaitCompletion() { check(completed.await(4, TimeUnit.SECONDS)) }
        override fun close() { buffers.forEach { it.fill(0) }; completed.countDown(); closed.countDown() }
    }

    @Test fun proseKeepsWordsAndNumbersButOmitsCodeMarkdownAndRawUrls() {
        val text = "# Hello\n**Three** emails at 10:30. [Notes](https://example.net).\n```kotlin\nsecretCode()\n```\n- Next item. https://example.net/raw"
        assertEquals("Hello Three emails at 10:30. Notes. Next item.", SpokenText.chunks(text).joinToString(" "))
        val long = ("This is a complete sentence. ").repeat(40)
        val chunks = SpokenText.chunks(long)
        assertTrue(chunks.first().length <= 160 && chunks.all { it.length <= 240 })
        assertEquals(long.trim(), chunks.joinToString(" "))
        assertTrue(SpokenText.chunks("```private code without closing fence").isEmpty())
    }

    @Test fun prefetchesOnlyNextChunkDuringPlaybackAndCancelsWithoutLateAudioOrCallbacks() {
        val api = Api(); val player = Player(); val prefetched = CountDownLatch(1); val release = CountDownLatch(1)
        api.beforeResult = { if (it == 2) { prefetched.countDown(); check(release.await(4, TimeUnit.SECONDS)) } }
        val events = CopyOnWriteArrayList<ReplySpeechState>()
        val output = VmReplySpeech({ api }, { player }, { it() })
        try {
            output.speak("Hello, there. ".repeat(70), SpeechLanguage.ENGLISH, events::add)
            await(player.started); await(prefetched)
            assertEquals(2, api.texts.size)
            assertEquals(1, player.buffers.size)
            output.cancel()
            val count = events.size
            release.countDown(); await(api.closed); await(player.closed)
            output.close()
            assertEquals(count, events.size)
            assertEquals(1, player.buffers.size)
            assertTrue(player.buffers.single().all { it == 0.toByte() })
        } finally { release.countDown(); output.close() }
    }

    @Test fun newTurnDropsStaleQueuedEventsAndReusesOutputAfterCancellation() {
        val queued = CopyOnWriteArrayList<() -> Unit>()
        val api = Api(); val player = Player().apply { completed.countDown() }
        val old = CopyOnWriteArrayList<ReplySpeechState>(); val next = CopyOnWriteArrayList<ReplySpeechState>()
        val output = VmReplySpeech({ api }, { player }, queued::add)
        try {
            output.speak("Old response", SpeechLanguage.ENGLISH, old::add)
            await(player.closed)
            output.speak("```code only```", SpeechLanguage.GERMAN, next::add)
            // Deterministic draining: this first callback must already belong to the new turn.
            queued.toList().forEach { it() }
            assertTrue(old.isEmpty())
            assertTrue(next.contains(ReplySpeechState.PREPARING))
        } finally { output.close() }
    }

    @Test fun unavailableOrMalformedAudioFailsOnceWithoutPlaybackOrRetry() {
        for (status in listOf(404, 429, 500, 503, 200)) {
            val api = Api().apply { this.status = status; malformed = status == 200 }
            val player = Player(); val failed = CountDownLatch(1)
            val events = CopyOnWriteArrayList<ReplySpeechState>()
            val output = VmReplySpeech({ api }, { player }, { it() })
            try {
                output.speak("Hello.", SpeechLanguage.ENGLISH) { events += it; if (it == ReplySpeechState.FAILED) failed.countDown() }
                await(failed); await(player.closed)
                assertEquals(listOf(ReplySpeechState.PREPARING, ReplySpeechState.FAILED), events)
                assertEquals(1, api.texts.size); assertTrue(player.buffers.isEmpty())
            } finally { output.close() }
        }
    }
}
