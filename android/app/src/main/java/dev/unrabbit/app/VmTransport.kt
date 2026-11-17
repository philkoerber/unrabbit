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
    @Volatile private var connectionEpoch = 0L
    private var healthTimer: ScheduledFuture<*>? = null
    private var connectionObserver: ((VmConnectionState) -> Unit)? = null
    private var connectionState = VmConnectionState.CHECKING

    /** The foreground UI observes a real health response, including the pinned VM identity. */
    fun observeConnection(observer: (VmConnectionState) -> Unit) {
        connectionObserver = observer
        observer(connectionState)
        if (!paused && !closed) scheduleHealth(0, connectionEpoch)
    }

    private fun publishConnection(state: VmConnectionState, epoch: Long = connectionEpoch) {
        main.post {
            if (closed || paused || epoch != connectionEpoch || state == connectionState) return@post
            connectionState = state
            connectionObserver?.invoke(state)
        }
    }

    @Synchronized private fun scheduleHealth(delay: Long, epoch: Long) {
        if (closed || paused || epoch != connectionEpoch || connectionObserver == null) return
        healthTimer?.cancel(false)
        healthTimer = io.schedule({
            if (closed || paused || epoch != connectionEpoch) return@schedule
            val state = try {
                val result = api.request("GET", "/v1/health", null)
                if (result.first == 200 && result.second.optString("serverId") == identity)
                    VmConnectionState.CONNECTED else VmConnectionState.DISCONNECTED
            } catch (_: Exception) { VmConnectionState.DISCONNECTED }
            publishConnection(state, epoch)
            scheduleHealth(30, epoch)
        }, delay, TimeUnit.SECONDS)
    }
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
            if (job.first == 410) throw Rejected("Photo deleted after seven days. Take a new photo.")
            if (job.first !in 200..299) throw Rejected("VM request rejected (${job.first}). Check the connection.")
            require(job.second.getString("id") == entry.request.id)
            if (entry.offline) { entry.offline = false; deliver(entry, TransportResult.Waiting(null)) }
            entry.delay = 2
            when (job.second.getString("state")) {
                "completed" -> deliver(entry, TransportResult.Reply(job.second.getString("answer")), terminal = true)
                "failed", "interrupted" -> deliver(entry, TransportResult.Failure(job.second.optString("error", "Request failed.")), terminal = true)
                "cancelled" -> deliver(entry, TransportResult.Failure("Stopped."), terminal = true)
                "queued", "running" -> schedule(entry, 2)
                else -> throw Rejected("Unknown VM request status.")
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
                deliver(entry, TransportResult.Waiting("Connection interrupted. Request remains queued."))
            }
            entry.delay = (entry.delay * 2).coerceAtMost(30)
            schedule(entry, entry.delay)
        } catch (_: Exception) {
            deliver(entry, TransportResult.Failure("Invalid VM response or image file."), terminal = true)
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
        if (request.photos.size > 4) throw Rejected("Up to four photos per request.")
        val images = JSONArray()
        request.photos.forEach { photo ->
            if (photo.isExpired(System.currentTimeMillis())) throw Rejected("Photo deleted after seven days. Take a new photo.")
            val file = photoFile(photo)
            if (!file.isFile || file.length() !in 4..(4 * 1024 * 1024)) throw Rejected("Image file missing or too large.")
            val encoded = JSONObject().put("id", photo.id).put("width", photo.width).put("height", photo.height)
                .put("jpeg", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            if (photo.capturedAt > 0) encoded.put("capturedAt", photo.capturedAt)
            images.put(encoded)
        }
        return JSONObject().put("id", request.id).put("text", request.text).put("photos", images)
    }

    private fun api(method: String, path: String, body: JSONObject? = null): Pair<Int, JSONObject> {
        val epoch = connectionEpoch
        return try {
            api.request(method, path, body).also {
                if (it.first in listOf(401, 403, 409) || it.first >= 500)
                    publishConnection(VmConnectionState.DISCONNECTED, epoch)
            }
        } catch (error: Exception) {
            publishConnection(VmConnectionState.DISCONNECTED, epoch)
            throw error
        }
    }

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

    @Synchronized override fun pause() {
        paused = true
        connectionEpoch++
        healthTimer?.cancel(false)
        entries.values.forEach { it.timer?.cancel(false) }
        api.pause()
    }
    @Synchronized override fun resume() {
        if (closed) return
        api.resume()
        paused = false
        connectionEpoch++
        connectionState = VmConnectionState.CHECKING
        connectionObserver?.invoke(connectionState)
        entries.values.forEach { schedule(it, 0) }
        scheduleHealth(0, connectionEpoch)
    }
    @Synchronized override fun close() {
        closed = true; pause(); api.close(); connectionObserver = null; entries.clear(); io.shutdownNow(); main.removeCallbacksAndMessages(null)
    }
    private class Rejected(message: String) : Exception(message)
}
