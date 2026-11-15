package dev.unrabbit.app

import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class VmConnectionTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("Connection status did not arrive", condition())
    }

    @Test fun idleConnectionChecksIdentityAndRecoversWithoutSubmittingAJob() {
        var answer = "wrong-server"
        var offline = false
        val calls = CopyOnWriteArrayList<String>()
        val states = CopyOnWriteArrayList<VmConnectionState>()
        val api = object : VmApi {
            override val identity = "expected-server"
            override fun request(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> {
                calls += "$method $path"
                if (offline) throw IOException("offline")
                return 200 to JSONObject().put("serverId", answer)
            }
        }
        val client = VmTransport(api) { error("Health must not load photos") }
        try {
            client.observeConnection(states::add)
            assertTrue(calls.isEmpty())
            assertEquals(listOf(VmConnectionState.CHECKING), states)
            client.resume()
            await { states.last() == VmConnectionState.DISCONNECTED }
            client.pause(); answer = api.identity; client.resume()
            await { states.last() == VmConnectionState.CONNECTED }
            client.pause(); offline = true; client.resume()
            await { states.last() == VmConnectionState.DISCONNECTED }
            client.pause(); offline = false; client.resume()
            await { states.last() == VmConnectionState.CONNECTED }
            assertEquals(List(4) { "GET /v1/health" }, calls)
        } finally { client.close() }
    }

    @Test fun responseFromPausedSessionCannotShowAConnectedBadge() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val states = CopyOnWriteArrayList<VmConnectionState>()
        val api = object : VmApi {
            override val identity = "expected-server"
            override fun request(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                finished.countDown()
                return 200 to JSONObject().put("serverId", identity)
            }
        }
        val client = VmTransport(api) { error("Health must not load photos") }
        try {
            client.observeConnection(states::add); client.resume()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            client.pause(); release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            // The I/O response may post after pause; it belongs to an old lifecycle epoch.
            Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle()
            assertFalse(states.contains(VmConnectionState.CONNECTED))
        } finally { release.countDown(); client.close() }
    }
}
