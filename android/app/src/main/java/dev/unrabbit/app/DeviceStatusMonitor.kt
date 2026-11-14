package dev.unrabbit.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat

enum class WifiStatus(val description: String) {
    CONNECTED("WLAN verbunden"), LOCAL("WLAN verbunden, Internet nicht bestätigt"),
    DISCONNECTED("WLAN nicht verbunden"), OFF("WLAN aus"), UNKNOWN("WLAN-Status unbekannt")
}

data class DeviceStatus(
    val hour: String = "–", val minute: String = "–",
    val batteryPercent: Int? = null, val charging: Boolean = false,
    val wifi: WifiStatus = WifiStatus.UNKNOWN
)

/** Read-only system observations. No scanning, location access, sockets or polling. */
class DeviceStatusMonitor(context: Context, private val onChange: (DeviceStatus) -> Unit) {
    private val context = context.applicationContext
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val wifi = this.context.getSystemService(WifiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val networks = mutableMapOf<Network, NetworkCapabilities>()
    private var started = false
    private var networkRegistered = false
    private var batteryPercent: Int? = null
    private var charging = false
    private var last: DeviceStatus? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!started) return
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) readBattery(intent)
            publish()
        }
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!started) return
            networks[network] = capabilities
            publish()
        }
        override fun onLost(network: Network) {
            if (!started) return
            networks.remove(network)
            publish()
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        // All filter actions are protected Android broadcasts, including privileged Wi-Fi senders.
        val sticky = if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
        if (sticky?.action == Intent.ACTION_BATTERY_CHANGED) readBattery(sticky)
        networks.clear()
        try {
            val request = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            connectivity?.registerNetworkCallback(request, callback, handler)
            networkRegistered = connectivity != null
            connectivity?.allNetworks?.forEach { network ->
                connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) networks[network] = capabilities
                }
            }
        } catch (_: SecurityException) { networkRegistered = false }
        publish()
    }

    fun stop() {
        if (!started) return
        started = false
        context.unregisterReceiver(receiver)
        if (networkRegistered) connectivity?.unregisterNetworkCallback(callback)
        networkRegistered = false
        networks.clear()
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        batteryPercent = if (level >= 0 && scale > 0) (level.toLong() * 100 / scale).toInt().coerceIn(0, 100) else null
        charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun publish() {
        val time = DateFormat.format("HH:mm", System.currentTimeMillis()).toString().split(':')
        val wifiState = try {
            when {
                wifi == null -> WifiStatus.UNKNOWN
                !wifi.isWifiEnabled -> WifiStatus.OFF
                !networkRegistered -> WifiStatus.UNKNOWN
                networks.values.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } -> WifiStatus.CONNECTED
                networks.isNotEmpty() -> WifiStatus.LOCAL
                else -> WifiStatus.DISCONNECTED
            }
        } catch (_: SecurityException) { WifiStatus.UNKNOWN }
        val next = DeviceStatus(time[0], time[1], batteryPercent, charging, wifiState)
        if (next != last) { last = next; onChange(next) }
    }
}
