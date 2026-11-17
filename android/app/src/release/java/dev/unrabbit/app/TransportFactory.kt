package dev.unrabbit.app

import android.content.Context
import dev.unrabbit.core.ChatTransport

object TransportFactory {
    const val supportsDemo = false
    internal fun createSpeechApi(context: Context): VmApi? = PairedTransport.speechApi(context)
    fun create(context: Context, photos: PhotoStore): ChatTransport =
        PairedTransport.create(context, photos) ?: ConnectionRequiredTransport("Pair your VM by QR first.")
}
