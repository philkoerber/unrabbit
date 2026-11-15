package dev.unrabbit.app

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import android.graphics.BitmapFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupQrTest {
    private val fingerprint = "SHA256:" + "A".repeat(43)
    private val expiry get() = System.currentTimeMillis() / 1000 + 600
    private val valid get() = """{"type":"unrabbit.setup","version":2,"host":"vm.example.net","port":2222,"user":"device","hostKeyFingerprint":"$fingerprint","serverId":"12345678-1234-1234-1234-123456789abc","pairingToken":"${"A".repeat(43)}","expiresAt":$expiry}"""
    @Test fun qrImageDecodesCompleteInvitationAndStartsPairingWithoutSaveButton() {
        val matrix = QRCodeWriter().encode(valid, BarcodeFormat.QR_CODE, 640, 640)
        val pixels = IntArray(640*640) { if (matrix[it%640, it/640]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val decoded = SetupQrReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(640, 640, pixels))))
        val invitation = SetupQr.parse(decoded.text)
        assertEquals("vm.example.net", invitation.connection.host)
        assertEquals(2222, invitation.connection.port)
        assertEquals(fingerprint, invitation.connection.hostKeyFingerprint)
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        var received: PairingQr? = null
        val screen = SettingsView(context, null, { received = it }, {}, {})
        screen.applyScan(invitation)
        assertSame(invitation, received)
        assertFalse(PairingStore(context).hasPairing())
        assertEquals(2, screen.navigation.size)
        assertEquals("PairingQr(redacted)", invitation.toString())
    }
    @Test fun pythonWizardImagesDecodeWithTheCameraReaderAcrossDifferentPatterns() {
        val python = System.getProperty("unrabbit.testPython", "").orEmpty()
        assumeTrue("Set UNRABBIT_TEST_PYTHON to a Python with setup dependencies", python.isNotEmpty())
        val root = File(requireNotNull(System.getProperty("unrabbit.repo")))
        val process = ProcessBuilder(python, File(root, "vm/tests/qr_fixture.py").path).start()
        try {
            var count = 0
            process.inputStream.bufferedReader().forEachLine { line ->
                val png = java.util.Base64.getDecoder().decode(line)
                val image = BitmapFactory.decodeByteArray(png, 0, png.size)
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                val decoded = SetupQrReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels))))
                val invitation = SetupQr.parse(decoded.text, now = 1899999900)
                assertEquals(2222 + count, invitation.connection.port)
                count++
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertEquals(128, count)
        } finally { process.destroyForcibly() }
    }
    @Test fun expiredIncompleteLegacyAndAmbiguousPayloadsAreRejected() {
        val raw = valid
        val invalid = listOf(
            "-----BEGIN OPENSSH PRIVATE KEY-----", raw.replace("2222", "\"2222\""),
            raw.replace("2222", "22.5"), raw.replace("device", "root"),
            raw.replace("vm.example.net", "https://vm.example.net"),
            raw.replace("vm.example.net", "vm;touch /tmp/no"),
            raw.replace("\"version\":2", "\"version\":1"),
            raw.replace("\"port\":2222", "\"port\":22,\"port\":2222"),
            raw.dropLast(1) + ",\"privateKey\":\"secret\"}",
            raw.dropLast(1) + ",\"command\":\"whoami\"}", raw + " trailing", "x".repeat(4097),
            raw.replace(fingerprint, ""), raw.replace("\"pairingToken\"", "\"password\"")
        )
        invalid.forEach { assertTrue(runCatching { SetupQr.parse(it) }.isFailure) }
        assertThrows(IllegalArgumentException::class.java) { SetupQr.parse(raw, now = expiry + 1) }
    }
    @Test fun twoVisibleInvitationsDoNotChooseAnArbitraryVm() {
        val size = 400
        val first = QRCodeWriter().encode(valid, BarcodeFormat.QR_CODE, size, size)
        val second = QRCodeWriter().encode(valid.replace("2222", "2223"), BarcodeFormat.QR_CODE, size, size)
        val width = size * 2
        val pixels = IntArray(width * size) {
            val x = it % width; val y = it / width
            if ((if (x < size) first[x, y] else second[x - size, y])) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, size, pixels)))
        assertThrows(NotFoundException::class.java) { SetupQrReader().decode(bitmap) }
    }
    @Test fun pinnedHostRejectsOtherKeys() {
        val key = byteArrayOf(1, 2, 3)
        val pin = "SHA256:" + java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(key))
        assertEquals(com.jcraft.jsch.HostKeyRepository.OK, PinnedHost(pin).check("vm", key))
        val check = PinnedHost(pin)
        assertEquals(com.jcraft.jsch.HostKeyRepository.CHANGED, check.check("vm", byteArrayOf(1, 2, 4)))
        assertTrue(check.rejected)
    }
}
