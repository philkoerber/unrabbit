package dev.unrabbit.app

import android.os.Looper
import android.util.Base64
import dev.unrabbit.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.io.InputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class VmHttpTransportTest {
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var config: LocalVmConfig
    private lateinit var transport: VmHttpTransport
    private lateinit var image: File
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val results = CopyOnWriteArrayList<TransportResult>()
    private val count = AtomicInteger()
    @Volatile private var job: JSONObject? = null
    @Volatile private var state = "queued"
    @Volatile private var status = 200
    @Volatile private var auth = ""
    @Volatile private var identity = ""

    @Before fun setup() {
        server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        serverThread = Thread {
            while (!server.isClosed) try { server.accept().use { socket ->
            socket.soTimeout = 2000
            val input = socket.getInputStream().buffered()
            val path = line(input).split(" ")[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = line(input)
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val bytesIn = ByteArray(headers["content-length"]?.toInt() ?: 0)
            var offset = 0
            while (offset < bytesIn.size) {
                val n = input.read(bytesIn, offset, bytesIn.size - offset)
                if (n < 0) throw IOException("Incomplete request")
                offset += n
            }
            count.incrementAndGet()
            auth = headers["authorization"].orEmpty()
            identity = headers["x-unrabbit-server"].orEmpty()
            val body = bytesIn.toString(Charsets.UTF_8)
            requests += path to body
            var code = status
            if (status == 200 && path == "/v1/jobs") job = JSONObject(body)
            if (path.endsWith("/cancel")) state = "cancelled"
            if (status == 200 && job == null && !path.endsWith("/cancel")) code = 404
            val result = if (code != 200) JSONObject().put("error", "test") else
                JSONObject().put("id", job?.getString("id")).put("state", state).put("answer", "Vom Server")
            val bytes = (if (code == 503) "<h1>Temporarily unavailable</h1>" else result.toString()).toByteArray()
            socket.getOutputStream().use {
                it.write("HTTP/1.1 $code Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                it.write(bytes)
            }
            } } catch (_: IOException) { /* A paused client may disconnect mid-response. */ }
        }.apply { isDaemon = true; start() }
        config = LocalVmConfig("http://127.0.0.1:${server.localPort}", "x".repeat(43), UUID.randomUUID().toString())
        image = File.createTempFile("unrabbit-test-", ".jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -39)) }
        transport = VmHttpTransport(config) { image }
    }
    private fun line(input: InputStream): String {
        val value = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw IOException("End of stream")
            if (byte == 10) return value.toString().trimEnd('\r')
            value.append(byte.toChar())
        }
    }
    @After fun cleanup() { transport.close(); server.close(); serverThread.join(3000); image.delete() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 6_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("Condition timed out", condition())
    }
    private fun submit(withPhoto: Boolean = false): ChatRequest {
        val photos = if (withPhoto) listOf(PhotoAttachment(UUID.randomUUID().toString(), 32, 24)) else emptyList()
        return ChatRequest(UUID.randomUUID().toString(), "Eine Frage", photos).also { transport.send(it, results::add) }
    }

    @Test fun roundTripSendsAuthenticationPhotoAndOriginalRequestIdentity() {
        state = "completed"
        val request = submit(withPhoto = true)
        assertEquals(0, count.get()) // Starts paused, just like an activity before onResume.
        transport.resume()
        await { results.any { it is TransportResult.Reply } }
        assertEquals("Bearer ${config.token}", auth)
        assertEquals(config.serverId, identity)
        assertEquals(request.id, job!!.getString("id"))
        val photo = job!!.getJSONArray("photos").getJSONObject(0)
        assertArrayEquals(image.readBytes(), Base64.decode(photo.getString("jpeg"), Base64.DEFAULT))
        assertEquals(1, requests.count { it.first == "/v1/jobs" })
        assertEquals(listOf(TransportResult.Reply("Vom Server")), results)
    }

    @Test fun resumeFetchesCompletedWorkWithoutRepostingAndPauseStopsPolling() {
        submit(); transport.resume()
        await { job != null }
        transport.pause()
        Thread.sleep(100)
        val before = count.get()
        state = "completed"
        Thread.sleep(2200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, count.get())
        assertTrue(results.none { it is TransportResult.Reply })
        transport.resume()
        await { results.any { it is TransportResult.Reply } }
        assertEquals(1, requests.count { it.first == "/v1/jobs" })
    }

    @Test fun transientFailureKeepsRequestAndResumeRetriesIt() {
        status = 503
        submit(); transport.resume()
        await { results.any { it is TransportResult.Waiting } }
        assertTrue(results.none { it is TransportResult.Failure })
        transport.pause(); status = 200; state = "completed"; transport.resume()
        await { results.any { it is TransportResult.Reply } }
        assertEquals(1, requests.count { it.first == "/v1/jobs" })
    }

    @Test fun cancellationIsAcknowledgedByTheServerAndStopsPolling() {
        val request = submit(); transport.resume()
        await { job != null }
        var acknowledged = false
        transport.cancel(request.id) { acknowledged = it }
        await { acknowledged }
        assertEquals("cancelled", state)
        assertTrue(results.none { it is TransportResult.Reply })
    }

    @Test fun unauthorizedServerIsTerminalAndDoesNotUploadContent() {
        status = 401
        submit(); transport.resume()
        await { results.any { it is TransportResult.Failure } }
        assertTrue(requests.none { it.first == "/v1/jobs" })
    }

    @Test fun configurationRejectsNonLoopbackUrlsCredentialsAndPaths() {
        fun raw(endpoint: String) = JSONObject().put("version", 1).put("endpoint", endpoint)
            .put("token", config.token).put("serverId", config.serverId).toString()
        assertEquals(config, LocalVmConfig.parse(raw(config.endpoint)))
        listOf("http://example.net:8787", "https://127.0.0.1:8787", "http://127.0.0.1:8787/path",
            "http://user@127.0.0.1:8787", "http://127.0.0.1:8787?token=x", "http://127.0.0.1:8787#x").forEach {
            assertThrows(IllegalArgumentException::class.java) { LocalVmConfig.parse(raw(it)) }
        }
    }
    @Test fun revokedDeviceAndInvalidProtocolFailWithoutEndlessOfflineRetry() {
        for (failure in listOf(DeviceAuthorizationFailure(), VmProtocolFailure())) {
            val received = CopyOnWriteArrayList<TransportResult>()
            val calls = AtomicInteger()
            val api = object : VmApi {
                override val identity = UUID.randomUUID().toString()
                override fun request(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> {
                    calls.incrementAndGet()
                    throw failure
                }
            }
            val client = VmTransport(api) { image }
            try {
                client.send(ChatRequest(UUID.randomUUID().toString(), "hello", emptyList()), received::add)
                client.resume()
                await { received.isNotEmpty() }
                assertEquals(listOf(TransportResult.Failure(failure.message.orEmpty())), received)
                client.pause(); client.resume()
                Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, calls.get())
            } finally { client.close() }
        }
    }
}
