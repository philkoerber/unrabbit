package dev.unrabbit.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Small on-device probe for the real platform JSON implementation, without a test-framework APK. */
class SetupQrDeviceProbe : Instrumentation() {
    private var speech = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); speech = arguments?.getString("probe") == "speech"; start() }

    override fun onStart() {
        val report = Bundle()
        var code = Activity.RESULT_OK
        try {
            if (speech) {
                SpeechDeviceCheck.run(this, report)
                finish(code, report)
                return
            }
            val now = System.currentTimeMillis() / 1000
            val raw = """{"type":"unrabbit.setup","version":2,"host":"vm.example.net","port":2222,"user":"device","hostKeyFingerprint":"SHA256:${"A".repeat(43)}","serverId":"12345678-1234-1234-1234-123456789abc","pairingToken":"${"A".repeat(43)}","expiresAt":${now + 600}}"""
            val invitation = SetupQr.parse(raw, now)
            check(invitation.connection.port == 2222 && invitation.connection.user == "device")
            val expired = runCatching { SetupQr.parse(raw, now + 600) }.exceptionOrNull()
            check(expired is ExpiredSetupQr)
            report.putString("fixture", "passed")
            // Optional local diagnosis fixture: never checked in or displayed in test output.
            val fixture = File(targetContext.cacheDir, "qr-device-probe.json")
            if (fixture.exists()) {
                try {
                    val real = SetupQr.parse(fixture.readText())
                    check(real.expiresAt > System.currentTimeMillis() / 1000)
                    report.putString("privateInvitation", "passed")
                } finally { fixture.delete() }
            }
            report.putString("probe", "passed")
        } catch (failure: Exception) {
            code = Activity.RESULT_CANCELED
            report.putString("probe", "failed")
            // Exception messages can contain input data. Only class and project frame locations leave the device.
            report.putString("failureType", failure.javaClass.simpleName)
            report.putString("frames", failure.stackTrace.filter { it.className.startsWith("dev.unrabbit.") }
                .joinToString("; ") { "${it.fileName}:${it.lineNumber}" })
        }
        finish(code, report)
    }
}
