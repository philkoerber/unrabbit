package dev.unrabbit.app

import android.content.Context
import dev.unrabbit.core.ChatTransport

object TransportFactory {
    const val supportsDemo = false
    fun create(context: Context, photos: PhotoStore): ChatTransport =
        PairedTransport.create(context, photos) ?: ConnectionRequiredTransport("VM zuerst per QR verbinden.")
}
