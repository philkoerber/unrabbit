package dev.unrabbit.app

import android.content.Context
import dev.unrabbit.core.ChatTransport
import dev.unrabbit.core.ChatRequest
import dev.unrabbit.core.TransportResult
import java.io.File

/** Installed by the emulator-only developer helper through run-as, never an intent extra. */
object TransportFactory {
    const val supportsDemo = true
    internal fun createSpeechApi(context: Context): VmApi? {
        PairedTransport.speechApi(context)?.let { return it }
        val file = File(context.noBackupFilesDir, "unrabbit-local.json")
        return if (file.exists()) HttpApi(LocalVmConfig.parse(file.readText())) else null
    }
    fun create(context: Context, photos: PhotoStore): ChatTransport {
        PairedTransport.create(context, photos)?.let { return it }
        val file = File(context.noBackupFilesDir, "unrabbit-local.json")
        if (!file.exists()) return DemoTransport()
        return try { VmHttpTransport(LocalVmConfig.parse(file.readText()), photos::file) }
        catch (_: Exception) { InvalidLocalTransport() }
    }
}

private class InvalidLocalTransport : ChatTransport {
    override fun send(request: ChatRequest, onResult: (TransportResult) -> Unit) {
        onResult(TransportResult.Failure("Invalid local VM configuration. Reconnect the development setup."))
    }
    override fun cancel(requestId: String, onAcknowledged: (Boolean) -> Unit) = onAcknowledged(false)
    override fun close() {}
}
