package dev.unrabbit.core

/** Non-secret connection draft. The workspace and execution permissions are server-owned. */
data class ConnectionSettings(val host: String, val port: Int, val user: String, val hostKeyFingerprint: String = "") {
    fun validationError(): String? = when {
        host.length !in 1..253 || !Regex("[A-Za-z0-9._:%\\[\\]-]+").matches(host) -> "Bitte eine VM-Adresse ohne URL oder Benutzer angeben."
        port !in 1..65535 -> "Der SSH-Port muss zwischen 1 und 65535 liegen."
        user == "root" -> "Für die App einen eigenen Benutzer ohne root oder sudo verwenden."
        !Regex("[a-z_][a-z0-9_-]{0,31}").matches(user) -> "Bitte einen gültigen Linux-Benutzernamen angeben."
        hostKeyFingerprint.isNotEmpty() && !Regex("SHA256:[A-Za-z0-9+/]{43}").matches(hostKeyFingerprint) -> "Ungültiger öffentlicher Host-Schlüssel-Fingerabdruck."
        else -> null
    }
}
