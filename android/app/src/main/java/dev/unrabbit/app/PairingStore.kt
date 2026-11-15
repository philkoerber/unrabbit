package dev.unrabbit.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import dev.unrabbit.core.ConnectionSettings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class PairedVm(val connection: ConnectionSettings, val serverId: String)

/** Private key is generated here, encrypted by Android Keystore and excluded from backup. */
internal class PairingStore(private val context: Context) {
    private val paired = AtomicFile(File(context.noBackupFilesDir, "paired-vm.json"))
    private val identity = AtomicFile(File(context.noBackupFilesDir, "device-key.enc"))
    fun hasPairing() = exists(paired)
    fun load(): PairedVm? {
        if (!hasPairing()) return null
        val data = JSONObject(paired.openRead().bufferedReader().use { it.readText() })
        require(data.getInt("version") == 1)
        val settings = ConnectionSettings(data.getString("host"), data.getInt("port"), data.getString("user"), data.getString("hostKeyFingerprint"))
        require(settings.validationError() == null && settings.hostKeyFingerprint.isNotBlank())
        val id = data.getString("serverId")
        require(UUID.fromString(id).toString() == id)
        return PairedVm(settings, id)
    }
    fun save(value: PairedVm) {
        val c = value.connection
        write(paired, JSONObject().put("version", 1).put("host", c.host).put("port", c.port).put("user", c.user)
            .put("hostKeyFingerprint", c.hostKeyFingerprint).put("serverId", value.serverId).toString().toByteArray())
    }
    @Synchronized fun privateKey(): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (exists(identity)) {
            val bytes = identity.openRead().use { it.readBytes() }
            require(bytes.size in 29..16384)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD("unrabbit.device-key.v1".toByteArray())
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        }
        val key = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        val bytes = try { ByteArrayOutputStream().also { key.writePrivateKey(it) }.toByteArray() } finally { key.dispose() }
        try {
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            cipher.updateAAD("unrabbit.device-key.v1".toByteArray())
            write(identity, cipher.iv + cipher.doFinal(bytes))
            return bytes
        } catch (error: Exception) { bytes.fill(0); throw error }
    }
    private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("unrabbit-device-wrap-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("unrabbit-device-wrap-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun write(file: AtomicFile, data: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
