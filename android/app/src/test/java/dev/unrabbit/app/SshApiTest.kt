package dev.unrabbit.app

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SshApiTest {
    @Test fun realSshPairingUsesOwnKeyAndSurvivesLostEnrollmentAcknowledgement() {
        val python = System.getProperty("unrabbit.testPython", "").orEmpty()
        assumeTrue("Set UNRABBIT_TEST_PYTHON to a Python with setup dependencies", python.isNotEmpty())
        val root = File(requireNotNull(System.getProperty("unrabbit.repo")))
        val process = ProcessBuilder(python, File(root, "vm/tests/ssh_fixture.py").path).start()
        val executor = Executors.newSingleThreadExecutor()
        val key = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        val bytes = ByteArrayOutputStream().also { key.writePrivateKey(it) }.toByteArray()
        key.dispose()
        var api: SshApi? = null
        try {
            val line = executor.submit<String> { process.inputStream.bufferedReader().readLine() }.get(10, TimeUnit.SECONDS)
            val invitation = SetupQr.parse(line)
            val vm = PairedVm(invitation.connection, invitation.serverId)
            val wrong = SshApi(vm.copy(connection = vm.connection.copy(hostKeyFingerprint = "SHA256:" + "A".repeat(43)))) { bytes.copyOf() }
            try { assertThrows(HostIdentityFailure::class.java) { wrong.pair(invitation) } } finally { wrong.close() }
            api = SshApi(vm) { bytes.copyOf() }
            assertThrows(DeviceAuthorizationFailure::class.java) { api!!.verifyHealth() }
            api.pair(invitation)
            api.close()
            // Same device recovers with its key even though the code was consumed.
            api = SshApi(vm) { bytes.copyOf() }
            api.pair(invitation)
            val id = UUID.randomUUID().toString()
            val voice = JSONObject().put("format", "aac-adts").put("language", "en")
                .put("audio", java.util.Base64.getEncoder().encodeToString(byteArrayOf(-1, -15, 96, 64, 1, 31, -4, 0)))
            val spoken = api.request("POST", "/v1/speech/transcribe", voice)
            assertEquals(200, spoken.first)
            assertEquals("SSH speech fixture", spoken.second.getString("text"))
            val photo = JSONObject().put("id", UUID.randomUUID().toString()).put("width", 8).put("height", 8)
                .put("jpeg", java.util.Base64.getEncoder().encodeToString(byteArrayOf(-1, -40, 1, 2, -1, -39)))
            val job = JSONObject().put("id", id).put("text", "SSH integration").put("photos", JSONArray().put(photo))
            assertEquals(200, api.request("POST", "/v1/jobs", job).first)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var result: JSONObject
            do {
                result = api.request("GET", "/v1/jobs/$id", null).second
                if (result.optString("state") != "completed") Thread.sleep(30)
            } while (result.optString("state") != "completed" && System.nanoTime() < deadline)
            assertEquals("completed", result.getString("state"))
            assertTrue(result.getString("answer").contains("1 Bild"))
            api.pause(); api.resume(); api.verifyHealth()
            assertEquals(result.getString("answer"), api.request("POST", "/v1/jobs", job).second.getString("answer"))
        } finally {
            api?.close(); bytes.fill(0)
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            executor.shutdownNow()
        }
    }
}
