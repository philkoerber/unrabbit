package dev.unrabbit.app

import dev.unrabbit.core.ConnectionSettings
import org.json.JSONTokener
import java.util.UUID

class PairingQr(val connection: ConnectionSettings, val serverId: String, val token: String, val expiresAt: Long) {
    override fun toString() = "PairingQr(redacted)"
}

/** Only complete, expiring pairing invitations are accepted. No manual connection drafts. */
object SetupQr {
    fun parse(raw: String, now: Long = System.currentTimeMillis() / 1000): PairingQr {
        require(raw.toByteArray(Charsets.UTF_8).size <= 4096) { "Einrichtungs-QR ist zu groß." }
        val reader = JSONTokener(raw)
        val fields = linkedMapOf<String, Any>()
        val allowed = setOf("type", "version", "host", "port", "user", "hostKeyFingerprint", "serverId", "pairingToken", "expiresAt")
        require(reader.nextClean() == '{') { "Kein Unrabbit-Einrichtungs-QR." }
        do {
            val key = reader.nextValue()
            require(key is String && key in allowed && key !in fields) { "Unbekannte oder doppelte QR-Felder." }
            require(reader.nextClean() == ':')
            fields[key] = reader.nextValue()
            val next = reader.nextClean()
            if (next == '}') break
            require(next == ',')
        } while (true)
        require(reader.nextClean() == '\u0000' && fields.keys == allowed)
        require(fields["type"] == "unrabbit.setup" && fields["version"] == 2) { "Bitte einen neuen QR im VM-Wizard erstellen." }
        require(fields["host"] is String && fields["user"] is String && fields["port"] is Int)
        val connection = ConnectionSettings(fields["host"] as String, fields["port"] as Int, fields["user"] as String,
            fields["hostKeyFingerprint"] as? String ?: "")
        require(connection.validationError() == null && connection.hostKeyFingerprint.isNotBlank() && !connection.host.startsWith("-"))
        val serverId = fields["serverId"] as? String ?: error("Server-ID fehlt.")
        require(UUID.fromString(serverId).toString() == serverId)
        val token = fields["pairingToken"] as? String ?: error("Kopplungscode fehlt.")
        require(Regex("[A-Za-z0-9_-]{43}").matches(token))
        val expires = fields["expiresAt"]
        require(expires is Int || expires is Long)
        val expiry = (expires as Number).toLong()
        require(expiry > now && expiry <= now + 900) { "QR abgelaufen. Bitte den aktuellen QR scannen." }
        return PairingQr(connection, serverId, token, expiry)
    }
}
