package dev.unrabbit.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class NetworkSetupTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun click(root: View, label: String) = descendants(root).filterIsInstance<Button>().first { it.text.toString() == label }.performClick()

    @Test fun firstStartOffersNetworkSetupAndBackDoesNotAcknowledgeDemoOrPairing() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val root = activity.get().window.decorView
        shadowOf(activity.get().getSystemService(TelephonyManager::class.java)).setSimState(TelephonyManager.SIM_STATE_ABSENT)
        click(root, "Wi-Fi & SIM")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        val network = descendants(root).filterIsInstance<NetworkSetupView>().single()
        assertEquals(listOf("Choose Wi-Fi", "Back"), network.navigation.map { it.first.contentDescription.toString() })
        val rail = descendants(root).filterIsInstance<StatusRailView>().single()
        assertEquals(View.VISIBLE, rail.visibility)
        assertEquals(1f, rail.compactFraction, .001f)
        click(network, "Back")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertTrue(descendants(root).filterIsInstance<Button>().any { it.text == "Connect VM" })
        assertFalse(LocalStorage(activity.get()).preferences.getBoolean("demoAcknowledged", false))
        assertFalse(PairingStore(activity.get()).hasPairing())
        activity.pause().stop().destroy()
    }

    @Test fun ordinaryAppCannotSaveWifiCredentialsWithoutDeviceOwnerProvisioning() {
        val context = RuntimeEnvironment.getApplication() as Context
        val wifi = context.getSystemService(WifiManager::class.java)
        val setup = NetworkSetupController(context, {}, {}, {}, {})
        assertFalse(setup.managed)
        val before = wifi.configuredNetworks.size
        assertFalse(setup.connect(NearbyWifi("Home", WifiSecurity.WPA2, -40), "not-a-real-password"))
        assertEquals(before, wifi.configuredNetworks.size)
    }

    @Test fun leavingPasswordPageDiscardsSecretAndDoesNotWriteAppHistory() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val context = activity.get()
        val before = LocalStorage(context).load()
        val network = NetworkSetupView(context, {}, {})
        context.setContentView(network)
        val wifi = NearbyWifi("A \"quoted\" network", WifiSecurity.WPA2, -45)
        fun passwordPage() = ReflectionHelpers.callInstanceMethod<Unit>(network, "showPassword",
            ReflectionHelpers.ClassParameter.from(NearbyWifi::class.java, wifi))
        passwordPage()
        val field = descendants(network).filterIsInstance<EditText>().single()
        field.setText("not-a-real-password")
        assertTrue(network.handleBack())
        assertEquals("", field.text.toString())
        passwordPage()
        assertEquals("", descendants(network).filterIsInstance<EditText>().single().text.toString())
        assertEquals(before, LocalStorage(context).load())
        activity.pause().stop().destroy()
    }

    @Test fun secureNetworksNeverFallBackToAnOpenConfiguration() {
        for (capabilities in listOf("[WPA2-EAP-CCMP][ESS]", "[RSN-SAE-CCMP][ESS]", "[OWE][ESS]", "[WEP][ESS]"))
            assertEquals(WifiSecurity.SYSTEM, NetworkSetupController.security(capabilities))
        assertEquals(WifiSecurity.WPA2, NetworkSetupController.security("[RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals(WifiSecurity.OPEN, NetworkSetupController.security("[ESS]"))
        assertFalse(NetworkSetupController.validPassword("short"))
        assertFalse(NetworkSetupController.validPassword("z".repeat(64)))
        assertTrue(NetworkSetupController.validPassword("a0".repeat(32)))
        assertEquals("\"A \\\"quoted\\\" \\\\ name\"", NetworkSetupController.quote("A \"quoted\" \\ name"))
    }
}
