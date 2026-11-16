package dev.unrabbit.app

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

internal class SpeechUnavailable : IOException()

internal interface SpeechRecognizer : AutoCloseable {
    fun transcribe(samples: FloatArray, language: SpeechLanguage, cancelled: AtomicBoolean): String?
    fun cancel()
}

/** One ephemeral audio request, separate from durable chat jobs. Cancellation closes its connection. */
internal class VmSpeechRecognizer(
    private val apiFactory: () -> VmApi?,
    private val encode: (FloatArray, AtomicBoolean) -> ByteArray = SpeechAudioEncoder::encode,
) : SpeechRecognizer {
    constructor(context: Context) : this({ TransportFactory.createSpeechApi(context.applicationContext) })
    private val lock = Any()
    private var active: VmApi? = null

    override fun transcribe(samples: FloatArray, language: SpeechLanguage, cancelled: AtomicBoolean): String? {
        if (cancelled.get()) return null
        val api = apiFactory() ?: throw SpeechUnavailable()
        var audio: ByteArray? = null
        try {
            synchronized(lock) {
                if (cancelled.get()) return null
                active = api
                api.resume()
            }
            audio = encode(samples, cancelled)
            if (cancelled.get()) return null
            val payload = JSONObject().put("format", "aac-adts").put("language", language.code)
                .put("audio", Base64.getEncoder().encodeToString(audio))
            val response = api.request("POST", "/v1/speech/transcribe", payload)
            if (cancelled.get()) return null
            if (response.first in listOf(404, 429, 503)) throw SpeechUnavailable()
            if (response.first != 200 || response.second.optString("language") != language.code) throw VmProtocolFailure()
            val text = response.second.get("text")
            if (text !is String || text.length > 16_000) throw VmProtocolFailure()
            return text.trim()
        } catch (failure: Exception) {
            if (cancelled.get()) return null
            throw failure
        } finally {
            audio?.fill(0)
            synchronized(lock) { if (active === api) active = null; api.close() }
        }
    }
    override fun cancel() = synchronized(lock) { active?.close(); active = null }
    override fun close() = cancel()
}
