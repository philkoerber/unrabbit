package dev.unrabbit.app

import com.jcraft.jsch.*
import org.json.JSONObject
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

internal class HostIdentityFailure : IOException("Server-Identität geändert. Verbindung blockiert.")
internal class PairingFailure(message: String) : IOException(message)
internal class DeviceAuthorizationFailure : IOException("Gerät nicht mehr berechtigt. VM erneut per QR verbinden.")
internal class VmProtocolFailure : IOException("Ungültige VM-Antwort. VM-Dienst prüfen.")

internal class PinnedHost(private val fingerprint: String) : HostKeyRepository {
    var rejected = false
        private set
    override fun check(host: String?, key: ByteArray?): Int {
        val actual = key?.let { "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(it)) }
        val valid = actual != null && MessageDigest.isEqual(actual.toByteArray(), fingerprint.toByteArray())
        if (!valid) rejected = true
        return if (valid) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }
    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID() = "unrabbit-pinned"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

/** SSH exec carries only the bounded Unrabbit protocol, never arbitrary user commands. */
internal class SshApi(private val vm: PairedVm, private val privateKey: () -> ByteArray) : VmApi {
    override val identity = vm.serverId
    @Volatile private var active: Session? = null
    @Volatile private var suspended = false

    private fun connect(password: String? = null): Session {
        if (suspended) throw IOException("Paused")
        val ssh = JSch()
        val pin = PinnedHost(vm.connection.hostKeyFingerprint)
        ssh.hostKeyRepository = pin
        if (password == null) {
            val key = privateKey()
            try { ssh.addIdentity("unrabbit-device", key, null, null) } finally { key.fill(0) }
        }
        val session = ssh.getSession(vm.connection.user, vm.connection.host, vm.connection.port)
        active = session
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", if (password == null) "publickey" else "password")
        session.setConfig("server_host_key", "ecdsa-sha2-nistp256")
        session.setConfig("kex", "ecdh-sha2-nistp256,diffie-hellman-group14-sha256")
        session.timeout = 15000
        if (password != null) session.setPassword(password)
        try {
            session.connect(10000)
            if (suspended) { session.disconnect(); throw IOException("Paused") }
            return session
        } catch (error: Exception) {
            session.disconnect(); ssh.removeAllIdentity()
            if (pin.rejected) throw HostIdentityFailure()
            if (error is JSchException && error.message.orEmpty().startsWith("Auth fail")) {
                if (password == null) throw DeviceAuthorizationFailure()
                throw PairingFailure("Kopplungscode abgelaufen oder verwendet. Neuen QR scannen.")
            }
            throw IOException("SSH-Verbindung fehlgeschlagen.", error)
        }
    }

    fun pair(invitation: PairingQr) {
        // Recover an enrollment whose acknowledgement was lost without reusing its code.
        try {
            verifyHealth()
            return
        } catch (_: DeviceAuthorizationFailure) { disconnect() }
        if (System.currentTimeMillis() / 1000 >= invitation.expiresAt) throw PairingFailure("QR abgelaufen. Bitte erneut scannen.")
        val bytes = privateKey()
        val public = try {
            val key = KeyPair.load(JSch(), bytes, null)
            try { ByteArrayOutputStream().also { key.writePublicKey(it, "unrabbit") }.toString("UTF-8").trim() }
            finally { key.dispose() }
        } finally { bytes.fill(0) }
        val session = connect(invitation.token)
        try {
            val result = execute(session, "unrabbit-pair", JSONObject().put("serverId", vm.serverId).put("publicKey", public))
            if (result.first != 200 || result.second.optString("serverId") != vm.serverId)
                throw PairingFailure("Kopplungscode abgelaufen oder verwendet. Neuen QR scannen.")
        } finally { disconnect() }
        verifyHealth() // A successful scan is not enough: prove possession of the new device key.
    }

    fun verifyHealth() {
        val result = request("GET", "/v1/health", null)
        if (result.first != 200 || result.second.optString("serverId") != vm.serverId)
            throw PairingFailure("VM antwortet nicht wie erwartet.")
    }

    override fun request(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> {
        if (suspended) throw IOException("Paused")
        val session = active?.takeIf { it.isConnected } ?: connect()
        return try { execute(session, "unrabbit-api", JSONObject().put("serverId", vm.serverId)
            .put("method", method).put("path", path).put("body", body ?: JSONObject.NULL)) }
        catch (error: Exception) {
            disconnect()
            when (error) {
                is IOException -> throw error
                is JSONException -> throw VmProtocolFailure()
                else -> throw IOException("SSH-Verbindung unterbrochen.", error)
            }
        }
    }

    private fun execute(session: Session, command: String, data: JSONObject): Pair<Int, JSONObject> {
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand(command)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(10000)
            output.use { it.write(data.toString().toByteArray()) }
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (result.size() + count > 512 * 1024) throw VmProtocolFailure()
                result.write(buffer, 0, count)
            }
            val reply = JSONObject(result.toString("UTF-8"))
            return reply.getInt("status") to reply.getJSONObject("body")
        } finally { channel.disconnect() }
    }
    private fun disconnect() { active?.disconnect(); active = null }
    override fun pause() { suspended = true; disconnect() }
    override fun resume() { suspended = false }
    override fun close() = pause()
}
