package dev.unrabbit.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ReplySpeechState { PREPARING, PLAYING, FINISHED, FAILED }

internal interface ReplySpeech : AutoCloseable {
    fun speak(text: String, language: SpeechLanguage, listener: (ReplySpeechState) -> Unit)
    fun cancel()
}

/** Keep prose readable; never recite Markdown syntax, fenced code or raw web addresses. */
internal object SpokenText {
    fun chunks(source: String): List<String> {
        var text = source.take(16_000)
            .replace(Regex("(?s)```.*?(?:```|$)|~~~.*?(?:~~~|$)"), " ")
            .replace(Regex("!?\\[([^]\\n]+)]\\([^\\n)]*\\)"), "$1")
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("(?m)^\\s{0,3}(?:#{1,6}\\s+|[-*+]\\s+|>\\s*)"), "")
            .replace(Regex("[*_`~|]"), "")
            .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val result = mutableListOf<String>()
        while (text.isNotEmpty()) {
            val limit = if (result.isEmpty()) 160 else 240
            if (text.length <= limit) { result += text; break }
            val prefix = text.take(limit + 1)
            val sentence = Regex("[.!?;:]\\s").findAll(prefix).lastOrNull()?.range?.first?.plus(1)
            var end = sentence ?: prefix.lastIndexOf(' ').takeIf { it > 0 } ?: limit
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            result += text.take(end).trim()
            text = text.substring(end).trimStart()
        }
        return result
    }
}

internal interface ReplyAudioPlayer : AutoCloseable {
    /** Starts asynchronously. Ownership of the byte array passes to the player. */
    fun play(audio: ByteArray, onStarted: () -> Unit)
    fun awaitCompletion()
}

/** One chunk plays while the next is generated. At most two compressed clips live in memory. */
internal class VmReplySpeech(
    private val apiFactory: () -> VmApi?,
    private val playerFactory: () -> ReplyAudioPlayer,
    private val post: (() -> Unit) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) : ReplySpeech {
    constructor(context: Context) : this(
        { TransportFactory.createSpeechApi(context.applicationContext) },
        { MemoryReplyPlayer(context.applicationContext) })
    private val worker = Executors.newSingleThreadExecutor()
    private val disconnects = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private class Turn {
        val cancelled = AtomicBoolean(false)
        var api: VmApi? = null
        var player: ReplyAudioPlayer? = null
    }
    private var current: Turn? = null
    private var closed = false

    override fun speak(text: String, language: SpeechLanguage, listener: (ReplySpeechState) -> Unit) {
        cancel()
        val chunks = SpokenText.chunks(text)
        val turn = synchronized(lock) {
            if (closed) return
            Turn().also { current = it }
        }
        fun emit(state: ReplySpeechState) = post {
            synchronized(lock) {
                if (!closed && current === turn && !turn.cancelled.get()) listener(state)
            }
        }
        emit(ReplySpeechState.PREPARING)
        worker.execute {
            try {
                if (turn.cancelled.get()) return@execute
                if (chunks.isEmpty()) { emit(ReplySpeechState.FINISHED); return@execute }
                val api = apiFactory() ?: throw SpeechUnavailable()
                val player = playerFactory()
                synchronized(lock) {
                    if (turn.cancelled.get()) { api.close(); player.close(); return@execute }
                    turn.api = api; turn.player = player; api.resume()
                }
                var playing = false
                for (chunk in chunks) {
                    if (turn.cancelled.get()) return@execute
                    val response = api.request("POST", "/v1/speech/synthesize",
                        JSONObject().put("text", chunk).put("language", language.code))
                    if (turn.cancelled.get()) return@execute
                    if (response.first != 200 || response.second.optString("format") != "aac-mp4" ||
                        response.second.optString("language") != language.code) throw VmProtocolFailure()
                    val encoded = response.second.get("audio")
                    if (encoded !is String || encoded.length !in 1..349_528) throw VmProtocolFailure()
                    val audio = Base64.getDecoder().decode(encoded)
                    try {
                        if (audio.size !in 12..262_144 || String(audio, 4, 4, Charsets.US_ASCII) != "ftyp") throw VmProtocolFailure()
                        if (playing) player.awaitCompletion()
                        synchronized(lock) {
                            if (turn.cancelled.get()) { audio.fill(0); return@execute }
                            player.play(audio) { emit(ReplySpeechState.PLAYING) }
                        }
                        playing = true
                    } catch (failure: Exception) { audio.fill(0); throw failure }
                }
                player.awaitCompletion()
                emit(ReplySpeechState.FINISHED)
            } catch (_: Exception) {
                emit(ReplySpeechState.FAILED) // Text remains in the chat; audio is never retried.
            } finally {
                val resources = synchronized(lock) {
                    (turn.api to turn.player).also { turn.api = null; turn.player = null }
                }
                resources.second?.close()
                resources.first?.close()
            }
        }
    }

    override fun cancel() = synchronized(lock) {
        current?.let { turn ->
            turn.cancelled.set(true)
            turn.player?.close() // Release the speaker on main before any SSH teardown.
            turn.api?.let { api -> disconnects.execute { api.close() } }
            turn.api = null
        }
        current = null
    }
    override fun close() = synchronized(lock) { closed = true; cancel(); worker.shutdown(); disconnects.shutdown() }
}
