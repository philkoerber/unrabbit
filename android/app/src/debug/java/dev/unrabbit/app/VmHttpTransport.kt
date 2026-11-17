package dev.unrabbit.app

import android.os.Handler
import android.os.Looper
import android.util.Base64
import dev.unrabbit.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal data class LocalVmConfig(val endpoint: String, val token: String, val serverId: String) {
    companion object {
        fun parse(raw: String): LocalVmConfig {
            require(raw.length <= 4096)
            val data = JSONObject(raw)
            require(data.getInt("version") == 1)
            val endpoint = data.getString("endpoint")
            val url = URI(endpoint)
            require(url.scheme == "http" && url.host == "127.0.0.1" && url.port in 1..65535)
            require(url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null && url.rawPath.isNullOrEmpty())
            val token = data.getString("token")
            require(Regex("[A-Za-z0-9_-]{43}").matches(token))
            val id = data.getString("serverId")
            require(UUID.fromString(id).toString() == id)
            return LocalVmConfig(endpoint, token, id)
        }
    }
}

internal class VmHttpTransport(config: LocalVmConfig, photoFile: (PhotoAttachment) -> File) :
    VmTransport(HttpApi(config), photoFile)

internal class HttpApi(private val config: LocalVmConfig) : VmApi {
    override val identity = config.serverId
    @Volatile private var paused = true
    @Volatile private var connection: HttpURLConnection? = null
    override fun request(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> {
        if (paused) throw IOException("Paused")
        val conn = URI(config.endpoint + path).toURL().openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5000; conn.readTimeout = if (path.startsWith("/v1/speech/")) 45_000 else 5000
            conn.setRequestProperty("Authorization", "Bearer ${config.token}")
            conn.setRequestProperty("X-Unrabbit-Server", config.serverId)
            if (body != null) {
                val bytes = body.toString().toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val text = stream?.use { source ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 512 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            } ?: "{}"
            // An intermediary may return an empty/HTML 5xx body. Its status must still
            // remain retryable instead of turning a transient outage into lost work.
            return code to if (code in 200..299) JSONObject(text) else JSONObject()
        } finally { conn.disconnect(); connection = null }
    }

    override fun pause() { paused = true; connection?.disconnect() }
    override fun resume() { paused = false }
    override fun close() = pause()
}
