package dev.unrabbit.app

import android.content.Context
import dev.unrabbit.core.*

internal object PairedTransport {
    fun speechApi(context: Context): VmApi? {
        val store = PairingStore(context)
        if (!store.hasPairing()) return null
        return SshApi(requireNotNull(store.load()), store::privateKey)
    }
    fun create(context: Context, photos: PhotoStore): ChatTransport? {
        val store = PairingStore(context)
        if (!store.hasPairing()) return null
        return try {
            val vm = requireNotNull(store.load())
            VmTransport(SshApi(vm, store::privateKey), photos::file)
        } catch (_: Exception) { ConnectionRequiredTransport("Pairing could not be read. Pair the VM again by QR.") }
    }
}

internal class ConnectionRequiredTransport(private val message: String) : ChatTransport {
    override fun send(request: ChatRequest, onResult: (TransportResult) -> Unit) = onResult(TransportResult.Failure(message))
    override fun cancel(requestId: String, onAcknowledged: (Boolean) -> Unit) = onAcknowledged(false)
    override fun close() {}
}
