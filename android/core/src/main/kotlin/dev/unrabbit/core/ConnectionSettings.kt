package dev.unrabbit.core

/** Non-secret connection draft. The workspace and execution permissions are server-owned. */
data class ConnectionSettings(val host: String, val port: Int, val user: String, val hostKeyFingerprint: String = "") {
    fun validationError(): String? = when {
        host.length !in 1..253 || !Regex("[A-Za-z0-9._:%\\[\\]-]+").matches(host) -> "Enter a VM address without a URL scheme or username."
        port !in 1..65535 -> "SSH port must be between 1 and 65535."
        user == "root" -> "Use a dedicated app user without root or sudo access."
        !Regex("[a-z_][a-z0-9_-]{0,31}").matches(user) -> "Enter a valid Linux username."
        hostKeyFingerprint.isNotEmpty() && !Regex("SHA256:[A-Za-z0-9+/]{43}").matches(hostKeyFingerprint) -> "Invalid public host key fingerprint."
        else -> null
    }
}
