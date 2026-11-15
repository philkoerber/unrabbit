package dev.unrabbit.app

import android.os.Handler
import android.os.Looper
import android.util.Base64
import dev.unrabbit.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal interface VmApi {
    val identity: String
    fun request(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject>
    fun pause() {}
    fun resume() {}
    fun close() {}
}

/** Durable outbox shared by production SSH and the debug-only loopback adapter. */
internal open class VmTransport(private val api: VmApi,
    private val photoFile: (PhotoAttachment) -> File) : ChatTransport {
    override val identity = api.identity
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val entries = ConcurrentHashMap<String, Entry>()
    @Volatile private var paused = true
    @Volatile private var closed = false
    private class Entry(val request: ChatRequest, val callback: (TransportResult) -> Unit) {
        @Volatile var timer: ScheduledFuture<*>? = null
        var delay = 2L
        var offline = false
    }

    override fun send(request: ChatRequest, onResult: (TransportResult) -> Unit) {
        check(!closed)
        val entry = Entry(request, onResult)
        check(entries.putIfAbsent(request.id, entry) == null)
        if (!paused) schedule(entry, 0)
    }

    @Synchronized private fun schedule(entry: Entry, delay: Long) {
        if (closed || paused || entries[entry.request.id] !== entry) return
        entry.timer?.cancel(false)
        entry.timer = io.schedule({ poll(entry) }, delay, TimeUnit.SECONDS)
    }

    private fun poll(entry: Entry) {
        if (closed || paused || entries[entry.request.id] !== entry) return
        try {
            val path = "/v1/jobs/${entry.request.id}"
            val current = api("GET", path)
            val job = if (current.first == 404) api("POST", "/v1/jobs", payload(entry.request)) else current
            if (job.first == 429 || job.first >= 500) throw IOException("Server busy")
            if (job.first !in 200..299) throw Rejected("VM-Anfrage abgelehnt (${job.first}). Verbindung prüfen.")
            require(job.second.getString("id") == entry.request.id)
            if (entry.offline) { entry.offline = false; deliver(entry, TransportResult.Waiting(null)) }
            entry.delay = 2
            when (job.second.getString("state")) {
                "completed" -> deliver(entry, TransportResult.Reply(job.second.getString("answer")), terminal = true)
                "failed", "interrupted" -> deliver(entry, TransportResult.Failure(job.second.optString("error", "Auftrag fehlgeschlagen.")), terminal = true)
                "cancelled" -> deliver(entry, TransportResult.Failure("Angehalten."), terminal = true)
                "queued", "running" -> schedule(entry, 2)
                else -> throw Rejected("Unbekannter VM-Auftragsstatus.")
            }
        } catch (error: Rejected) {
            deliver(entry, TransportResult.Failure(error.message.orEmpty()), terminal = true)
        } catch (error: HostIdentityFailure) {
            deliver(entry, TransportResult.Failure(error.message.orEmpty()), terminal = true)
        } catch (error: DeviceAuthorizationFailure) {
            deliver(entry, TransportResult.Failure(error.message.orEmpty()), terminal = true)
        } catch (error: VmProtocolFailure) {
            deliver(entry, TransportResult.Failure(error.message.orEmpty()), terminal = true)
        } catch (_: IOException) {
            if (!entry.offline) {
                entry.offline = true
                deliver(entry, TransportResult.Waiting("Verbindung unterbrochen. Anfrage bleibt vorgemerkt."))
            }
            entry.delay = (entry.delay * 2).coerceAtMost(30)
            schedule(entry, entry.delay)
        } catch (_: Exception) {
            deliver(entry, TransportResult.Failure("Ungültige VM-Antwort oder Bilddatei."), terminal = true)
        }
    }

    private fun deliver(entry: Entry, result: TransportResult, terminal: Boolean = false) {
        main.post {
            if (closed || paused || entries[entry.request.id] !== entry) return@post
            if (terminal) { entries.remove(entry.request.id); entry.timer?.cancel(false) }
            entry.callback(result)
        }
    }

    private fun payload(request: ChatRequest): JSONObject {
        if (request.photos.size > 4) throw Rejected("Höchstens vier Bilder pro Anfrage.")
        val images = JSONArray()
        request.photos.forEach { photo ->
            val file = photoFile(photo)
            if (!file.isFile || file.length() !in 4..(4 * 1024 * 1024)) throw Rejected("Bilddatei fehlt oder ist zu groß.")
            images.put(JSONObject().put("id", photo.id).put("width", photo.width).put("height", photo.height)
                .put("jpeg", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)))
        }
        return JSONObject().put("id", request.id).put("text", request.text).put("photos", images)
    }

    private fun api(method: String, path: String, body: JSONObject? = null) = api.request(method, path, body)

    override fun cancel(requestId: String, onAcknowledged: (Boolean) -> Unit) {
        io.execute {
            val entry = entries[requestId]
            val result = runCatching { api("POST", "/v1/jobs/$requestId/cancel", JSONObject()) }.getOrNull()
            main.post {
                if (closed) return@post
                val stopped = result?.first == 200 && result.second.optString("state") == "cancelled"
                if (stopped) { entries.remove(requestId)?.timer?.cancel(false) }
                onAcknowledged(stopped)
                if (!stopped && entry != null) schedule(entry, 0)
            }
        }
    }

    override fun pause() {
        paused = true
        entries.values.forEach { it.timer?.cancel(false) }
        api.pause()
    }
    override fun resume() {
        if (closed) return
        api.resume()
        paused = false
        entries.values.forEach { schedule(it, 0) }
    }
    override fun close() {
        closed = true; pause(); api.close(); entries.clear(); io.shutdownNow(); main.removeCallbacksAndMessages(null)
    }
    private class Rejected(message: String) : Exception(message)
}
