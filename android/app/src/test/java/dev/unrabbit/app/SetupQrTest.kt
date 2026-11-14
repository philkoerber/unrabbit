package dev.unrabbit.app

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import dev.unrabbit.core.ConnectionSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupQrTest {
    private val fingerprint = "SHA256:" + "A".repeat(43)
    private val valid = """{"type":"unrabbit.setup","version":1,"host":"vm.example.net","port":2222,"user":"device","hostKeyFingerprint":"$fingerprint"}"""
    @Test fun qrImageDecodesIntoPublicConnectionDraft() {
        val matrix = QRCodeWriter().encode(valid, BarcodeFormat.QR_CODE, 512, 512)
        val pixels = IntArray(512*512) { if (matrix[it%512, it/512]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(512, 512, pixels))))
        assertEquals(ConnectionSettings("vm.example.net", 2222, "device", fingerprint), SetupQr.parse(decoded.text))
    }
    @Test fun credentialsCommandsAndAmbiguousPayloadsAreRejected() {
        val invalid = listOf(
            "-----BEGIN OPENSSH PRIVATE KEY-----", valid.replace("2222", "\"2222\""),
            valid.replace("2222", "22.5"), valid.replace("device", "root"),
            valid.replace("vm.example.net", "https://vm.example.net"),
            valid.replace("vm.example.net", "vm;touch /tmp/no"),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"port\":2222", "\"port\":22,\"port\":2222"),
            valid.dropLast(1) + ",\"privateKey\":\"secret\"}",
            valid.dropLast(1) + ",\"command\":\"whoami\"}", valid + " trailing", "x".repeat(4097),
            valid.replace(fingerprint, "not-a-public-fingerprint")
        )
        invalid.forEach { assertTrue(runCatching { SetupQr.parse(it) }.isFailure) }
    }
    @Test fun scanChangesOnlyDraftUntilUserSaves() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val storage = LocalStorage(context)
        var saved: ConnectionSettings? = null
        val screen = SettingsView(context, storage.connection(), { saved = it }, {}, {})
        screen.applyScan(SetupQr.parse(valid))
        assertEquals("", storage.connection().host)
        assertNull(saved)
        screen.navigation.last().second()
        assertEquals(SetupQr.parse(valid), saved)
    }
}
