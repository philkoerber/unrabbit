package dev.unrabbit.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
class VmSpeechRecognizerTest {
    private class Api : VmApi {
        override val identity = "test-vm"
        var request: JSONObject? = null
        var closed = false
        var code = 200
        var result = JSONObject().put("text", "From your VM").put("language", "de")
        var beforeResult: () -> Unit = {}
        override fun request(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> {
            assertEquals("POST", method); assertEquals("/v1/speech/transcribe", path)
            request = body
            beforeResult()
            return code to result
        }
        override fun close() { closed = true }
    }

    @Test fun uploadsEncodedAudioWithLanguageReturnsTextAndErasesBuffer() {
        val api = Api(); val audio = byteArrayOf(1, 2, 3)
        val engine = VmSpeechRecognizer({ api }) { _, _ -> audio }
        assertEquals("From your VM", engine.transcribe(FloatArray(10), SpeechLanguage.GERMAN, AtomicBoolean(false)))
        assertEquals("aac-adts", api.request!!.getString("format"))
        assertEquals("de", api.request!!.getString("language"))
        assertArrayEquals(byteArrayOf(1, 2, 3), Base64.getDecoder().decode(api.request!!.getString("audio")))
        assertTrue(audio.all { it == 0.toByte() }); assertTrue(api.closed)
    }

    @Test fun cancellationDuringEncodingSendsNothingAndLateVmReplyIsDiscarded() {
        val api = Api(); val cancelled = AtomicBoolean(false)
        lateinit var engine: VmSpeechRecognizer
        engine = VmSpeechRecognizer({ api }) { _, flag -> flag.set(true); engine.cancel(); byteArrayOf(1) }
        assertNull(engine.transcribe(FloatArray(10), SpeechLanguage.GERMAN, cancelled))
        assertNull(api.request); assertTrue(api.closed)
        cancelled.set(false)
        engine = VmSpeechRecognizer({ api }) { _, flag -> flag.set(true); throw CancellationException() }
        assertNull(engine.transcribe(FloatArray(10), SpeechLanguage.GERMAN, cancelled))
        assertNull(api.request)
        cancelled.set(false)
        engine = VmSpeechRecognizer({ api }) { _, _ -> byteArrayOf(2) }
        api.beforeResult = { cancelled.set(true); engine.cancel() }
        assertNull(engine.transcribe(FloatArray(10), SpeechLanguage.GERMAN, cancelled))
        assertTrue(api.closed)
    }

    @Test fun unavailableOldOrBusyVmDoesNotReturnTextOrRetry() {
        assertThrows(SpeechUnavailable::class.java) {
            VmSpeechRecognizer({ null }).transcribe(FloatArray(10), SpeechLanguage.GERMAN, AtomicBoolean(false))
        }
        for (status in listOf(404, 429, 503)) {
            val api = Api().apply { code = status }
            val engine = VmSpeechRecognizer({ api }) { _, _ -> byteArrayOf(1) }
            assertThrows(SpeechUnavailable::class.java) { engine.transcribe(FloatArray(10), SpeechLanguage.GERMAN, AtomicBoolean(false)) }
            assertTrue(api.closed)
        }
    }

    @Test fun silenceIsEmptyAndMalformedOrWrongLanguageRepliesFail() {
        val api = Api(); val engine = VmSpeechRecognizer({ api }) { _, _ -> byteArrayOf(1) }
        api.result = JSONObject().put("text", "").put("language", "de")
        assertEquals("", engine.transcribe(FloatArray(10), SpeechLanguage.GERMAN, AtomicBoolean(false)))
        for (reply in listOf(JSONObject().put("text", 123).put("language", "de"),
            JSONObject().put("text", "wrong turn").put("language", "en"))) {
            api.result = reply
            assertThrows(VmProtocolFailure::class.java) { engine.transcribe(FloatArray(10), SpeechLanguage.GERMAN, AtomicBoolean(false)) }
        }
    }
}
