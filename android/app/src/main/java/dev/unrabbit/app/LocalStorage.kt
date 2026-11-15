package dev.unrabbit.app

import android.content.Context
import android.util.AtomicFile
import dev.unrabbit.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LocalStorage(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "demo-stream.json"))
    private val legacyFile = AtomicFile(File(context.noBackupFilesDir, "demo-chats.json"))
    val preferences = context.getSharedPreferences("unrabbit-development", Context.MODE_PRIVATE)
    var readFailed = false
        private set

    fun load(): SessionSnapshot? {
        val source = when {
            hasData(file) -> file
            hasData(legacyFile) -> legacyFile
            else -> return null
        }
        return try {
            val root = JSONObject(source.openRead().bufferedReader().use { it.readText() })
            val items = if (source === legacyFile) {
                require(root.getInt("version") == 1)
                val chats = root.getJSONArray("chats")
                val currentId = root.getString("currentId")
                val oldestFirst = (0 until chats.length()).reversed().map(chats::getJSONObject)
                require(oldestFirst.map { it.getString("id") }.distinct().size == oldestFirst.size)
                // Legacy data has no timestamps. Preserve each chat's order, with the last
                // open chat at the end. Keep the entire original file as a recovery copy.
                (oldestFirst.filter { it.getString("id") != currentId } +
                    oldestFirst.filter { it.getString("id") == currentId })
                    .flatMap { readMessages(it.getJSONArray("messages"), legacy = true) }
            } else {
                require(root.getInt("version") in 2..4)
                readMessages(root.getJSONArray("messages"))
            }
            require(items.map { it.id }.distinct().size == items.size)
            val pending = root.optJSONArray("pendingRequestIds")?.let { array ->
                (0 until array.length()).map(array::getString)
            }.orEmpty()
            require(pending.distinct().size == pending.size)
            pending.forEach { id -> require(items.any { it.id == id && it.role == Role.USER && it.text.isNotBlank() }) }
            SessionSnapshot(items, root.optBoolean("hadPending"), pending,
                if (root.has("backendId")) root.getString("backendId") else null)
        } catch (_: Exception) {
            // Preserve the unreadable file for recovery instead of silently overwriting it.
            readFailed = true
            source.baseFile.renameTo(File(source.baseFile.parentFile, "${source.baseFile.nameWithoutExtension}-unreadable-${System.currentTimeMillis()}.json"))
            null
        }
    }

    private fun hasData(source: AtomicFile) = source.baseFile.exists() || File(source.baseFile.path + ".bak").exists()

    private fun readMessages(array: JSONArray, legacy: Boolean = false) = (0 until array.length()).map { index ->
        val message = array.getJSONObject(index)
        val id = message.getString("id")
        val role = Role.valueOf(message.getString("role"))
        val requestId = if (legacy) {
            if (role == Role.USER) id else if (id.startsWith("reply:")) id.removePrefix("reply:") else null
        } else if (message.has("requestId")) message.getString("requestId") else null
        val photos = message.optJSONArray("photos")?.let { array ->
            (0 until array.length()).map { i -> array.getJSONObject(i).let {
                PhotoAttachment(it.getString("id"), it.getInt("width"), it.getInt("height"))
            } }
        }.orEmpty()
        Message(id, role, message.getString("text"), requestId, photos)
    }

    fun save(snapshot: SessionSnapshot) {
        val messages = JSONArray()
        snapshot.messages.forEach { message ->
            val photos = JSONArray()
            message.photos.forEach { photos.put(JSONObject().put("id", it.id).put("width", it.width).put("height", it.height)) }
            messages.put(JSONObject().put("id", message.id).put("role", message.role.name)
                .put("text", message.text).put("requestId", message.requestId).put("photos", photos))
        }
        val data = JSONObject().put("version", 4).put("hadPending", snapshot.hadPending)
            .put("pendingRequestIds", JSONArray(snapshot.pendingRequestIds)).put("backendId", snapshot.backendId)
            .put("messages", messages).toString().toByteArray()
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

}
