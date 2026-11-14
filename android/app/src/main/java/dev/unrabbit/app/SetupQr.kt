package dev.unrabbit.app

import dev.unrabbit.core.ConnectionSettings
import org.json.JSONTokener

/** A bounded, versioned connection draft. Never accepts credentials, commands or private keys. */
object SetupQr {
    fun parse(raw: String): ConnectionSettings {
        require(raw.toByteArray(Charsets.UTF_8).size <= 4096) { "Einrichtungs-QR ist zu groß." }
        val reader = JSONTokener(raw)
        val fields = linkedMapOf<String, Any>()
        val allowed = setOf("type", "version", "host", "port", "user", "hostKeyFingerprint")
        require(reader.nextClean() == '{') { "Kein Unrabbit-Einrichtungs-QR." }
        do {
            val key = reader.nextValue()
            require(key is String && key in allowed && key !in fields) { "Unbekannte oder doppelte QR-Felder. Keine privaten Schlüssel importieren." }
            require(reader.nextClean() == ':')
            fields[key] = reader.nextValue()
            val next = reader.nextClean()
            if (next == '}') break
            require(next == ',')
        } while (true)
        require(reader.nextClean() == '\u0000')
        require(fields["type"] == "unrabbit.setup" && fields["version"] == 1) { "Unbekanntes QR-Format." }
        require(fields["host"] is String && fields["user"] is String && fields["port"] is Int)
        require(fields["hostKeyFingerprint"] == null || fields["hostKeyFingerprint"] is String)
        return ConnectionSettings(fields["host"] as String, fields["port"] as Int, fields["user"] as String,
            fields["hostKeyFingerprint"] as? String ?: "").also { require(it.validationError() == null) { it.validationError()!! } }
    }
}
