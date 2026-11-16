package dev.unrabbit.app

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Private WAV fixtures exercise the device AAC encoder and paired VM ASR, without creating agent jobs. */
internal object SpeechDeviceCheck {
    fun run(test: Instrumentation, report: Bundle) {
        val context = test.targetContext
        check(android.os.Process.myUid() != 0)
        test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory = File(context.cacheDir, "speech-probe")
        val manifest = JSONArray(File(directory, "fixtures.json").readText())
        var bytes = 0
        var encodeMillis = 0L
        var allMatched = true
        var onEncoded: () -> Unit = {}
        val engine = VmSpeechRecognizer({ TransportFactory.createSpeechApi(context) }) { samples, cancelled ->
            val begin = SystemClock.elapsedRealtime()
            SpeechAudioEncoder.encode(samples, cancelled).also {
                bytes = it.size; encodeMillis = SystemClock.elapsedRealtime() - begin
                onEncoded()
            }
        }
        try {
            repeat(manifest.length()) { i ->
                val fixture = manifest.getJSONObject(i)
                val name = fixture.getString("file")
                require(name.matches(Regex("[a-z0-9-]+\\.wav")))
                val samples = wav(File(directory, name))
                val language = SpeechLanguage.entries.single { it.code == fixture.getString("language") }
                val begin = SystemClock.elapsedRealtime()
                val transcript = engine.transcribe(samples, language, AtomicBoolean(false)).orEmpty()
                report.putLong("case_${i}_ms", SystemClock.elapsedRealtime() - begin)
                val matches = normalize(transcript) == normalize(fixture.getString("expected"))
                allMatched = allMatched && matches
                report.putLong("case_${i}_encode_ms", encodeMillis)
                report.putInt("case_${i}_audio_bytes", bytes)
                if (!matches) report.putString("case_${i}_mismatch", transcript)
                samples.fill(0f)
                report.putString("case_$i", if (matches) "passed" else "mismatch")
            }
            val cancelled = AtomicBoolean(true)
            check(engine.transcribe(FloatArray(16_000), SpeechLanguage.ENGLISH, cancelled) == null)
            report.putString("cancelled", "passed")
            val first = manifest.getJSONObject(0)
            val samples = wav(File(directory, first.getString("file")))
            val language = SpeechLanguage.entries.single { it.code == first.getString("language") }
            val inFlight = AtomicBoolean(false)
            val encoded = CountDownLatch(1)
            onEncoded = { encoded.countDown() }
            val canceller = Thread {
                check(encoded.await(10, TimeUnit.SECONDS))
                SystemClock.sleep(500)
                inFlight.set(true)
                engine.cancel()
            }
            try {
                val begin = SystemClock.elapsedRealtime()
                canceller.start()
                val result = engine.transcribe(samples, language, inFlight)
                canceller.join()
                check(result == null)
                report.putLong("cancel_in_flight_ms", SystemClock.elapsedRealtime() - begin)
                // A cancelled upload may already be running on the VM; no agent job is created.
                var recovered: String? = null
                repeat(10) {
                    if (recovered == null) try {
                        recovered = engine.transcribe(samples, language, AtomicBoolean(false)).orEmpty()
                    } catch (_: SpeechUnavailable) { SystemClock.sleep(500) }
                }
                check(recovered != null)
                check(normalize(recovered.orEmpty()) == normalize(first.getString("expected")))
                report.putString("reused_after_cancel", "passed")
            } finally { canceller.join(); samples.fill(0f) }
            report.putString("speech", if (allMatched) "passed" else "mismatch")
            report.putInt("uid", android.os.Process.myUid())
        } finally { engine.close(); directory.deleteRecursively() }
    }
    private fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun wav(file: File): FloatArray {
        val bytes = file.readBytes()
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var valid = false
        var samples: FloatArray? = null
        while (offset + 8 <= bytes.size) {
            val kind = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = buffer.getInt(offset + 4)
            val start = offset + 8
            require(length >= 0 && start + length <= bytes.size)
            if (kind == "fmt ") {
                require(length >= 16)
                valid = buffer.getShort(start).toInt() == 1 && buffer.getShort(start + 2).toInt() == 1 &&
                    buffer.getInt(start + 4) == 16_000 && buffer.getShort(start + 14).toInt() == 16
            }
            if (kind == "data") samples = FloatArray(length / 2) { buffer.getShort(start + it * 2) / 32768f }
            offset = start + length + length % 2
        }
        require(valid)
        return requireNotNull(samples)
    }
}
