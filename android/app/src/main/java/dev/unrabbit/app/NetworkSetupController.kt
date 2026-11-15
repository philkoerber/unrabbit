package dev.unrabbit.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager

internal enum class WifiSecurity { OPEN, WPA2, SYSTEM }
internal data class NearbyWifi(val name: String, val security: WifiSecurity, val signal: Int)
internal data class NetworkSetupStatus(val wifi: String, val sim: String, val connected: Boolean, val hasSim: Boolean)

/** Credentials go straight to Android's Wi-Fi store, never to our preferences, VM or logs. */
@Suppress("DEPRECATION")
internal class NetworkSetupController(
    context: Context,
    private val onStatus: (NetworkSetupStatus) -> Unit,
    private val onNetworks: (List<NearbyWifi>) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onConnected: () -> Unit,
) {
    private val context = context.applicationContext
    private val wifi = this.context.getSystemService(WifiManager::class.java)
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val policy = this.context.getSystemService(DevicePolicyManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var networkRegistered = false
    private var pendingNetwork: Int? = null
    val managed get() = policy?.isDeviceOwnerApp(context.packageName) == true
    private val timeout = Runnable {
        pendingNetwork = null
        onMessage("Nicht verbunden. Passwort und WLAN prüfen, dann erneut versuchen.")
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!started) return
            refresh()
            when (intent.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> readNetworks()
                WifiManager.WIFI_STATE_CHANGED_ACTION -> if (wifi?.isWifiEnabled == true) readNetworks()
                WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> if (pendingNetwork != null &&
                    intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1) == WifiManager.ERROR_AUTHENTICATING) {
                    pendingNetwork = null
                    handler.removeCallbacks(timeout)
                    onMessage("WLAN-Anmeldung fehlgeschlagen. Passwort prüfen.")
                }
            }
        }
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { refresh() }
        override fun onLost(network: Network) { refresh() }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
            addAction("android.intent.action.SIM_STATE_CHANGED")
        }
        // Protected platform broadcasts; status is re-read rather than trusting their payloads.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        try {
            connectivity?.registerDefaultNetworkCallback(callback, handler)
            networkRegistered = connectivity != null
        } catch (_: SecurityException) { networkRegistered = false }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        context.unregisterReceiver(receiver)
        if (networkRegistered) connectivity?.unregisterNetworkCallback(callback)
        networkRegistered = false
        pendingNetwork = null
        handler.removeCallbacksAndMessages(null)
    }

    fun refresh() {
        if (!started) return
        val capabilities = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val wifiConnected = connected && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val info = try { wifi?.connectionInfo } catch (_: SecurityException) { null }
        val name = info?.ssid?.takeUnless { it == WifiManager.UNKNOWN_SSID }?.removeSurrounding("\"")
        val wifiText = when {
            wifiConnected -> name?.let { "WLAN · $it" } ?: "WLAN verbunden"
            wifi?.isWifiEnabled == false -> "WLAN ausgeschaltet"
            else -> "WLAN nicht verbunden"
        }
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val simState = try { telephony?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN }
            catch (_: SecurityException) { TelephonyManager.SIM_STATE_UNKNOWN }
        val sim = when (simState) {
            TelephonyManager.SIM_STATE_ABSENT -> ""
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "SIM gesperrt · PIN erforderlich"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "SIM gesperrt · PUK erforderlich"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "SIM durch Anbieter gesperrt"
            TelephonyManager.SIM_STATE_READY ->
                if (connected && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
                    "Mobilfunk verbunden" else "SIM bereit"
            else -> "SIM nicht verfügbar"
        }
        onStatus(NetworkSetupStatus(wifiText, sim, connected,
            simState !in listOf(TelephonyManager.SIM_STATE_ABSENT, TelephonyManager.SIM_STATE_UNKNOWN)))
        if (pendingNetwork != null && wifiConnected && info?.networkId == pendingNetwork) {
            pendingNetwork = null
            handler.removeCallbacks(timeout)
            onConnected()
        }
    }

    /** Only explicitly provisioned device owners may use Android's direct Wi-Fi configuration API. */
    fun scan() {
        if (!managed || wifi == null) { onMessage("WLAN bitte im Verbindungsdialog auswählen."); return }
        try {
            val admin = ComponentName(context, UnrabbitAdminReceiver::class.java)
            for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
                if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
                    policy.setPermissionGrantState(admin, context.packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
            // Android requires Location enabled to discover SSIDs; we never request a location fix.
            if (Build.VERSION.SDK_INT >= 30) policy.setLocationEnabled(admin, true)
            if (!wifi.isWifiEnabled && !wifi.setWifiEnabled(true)) { onMessage("WLAN konnte nicht eingeschaltet werden."); return }
            onMessage("WLANs werden gesucht …")
            readNetworks()
            if (!wifi.startScan()) onMessage("Zuletzt gefundene WLANs. Erneute Suche ist gleich möglich.")
        } catch (_: SecurityException) { onMessage("WLAN-Suche nicht freigegeben. Den Android-Verbindungsdialog verwenden.") }
    }

    private fun readNetworks() {
        if (!managed) return
        try {
            val networks = wifi?.scanResults.orEmpty().filter { it.SSID.isNotBlank() }.map {
                NearbyWifi(it.SSID, security(it.capabilities), it.level)
            }.sortedByDescending { it.signal }.distinctBy { it.name to it.security }
            onNetworks(networks)
        } catch (_: SecurityException) { onMessage("WLAN-Suche braucht die Standortfreigabe von Android.") }
    }

    fun connect(network: NearbyWifi, password: String): Boolean {
        if (!managed || wifi == null || network.security == WifiSecurity.SYSTEM) return false
        if (network.security == WifiSecurity.WPA2 && !validPassword(password)) {
            onMessage("Das WLAN-Passwort braucht 8–63 Zeichen oder 64 Hex-Zeichen.")
            return false
        }
        try {
            val config = WifiConfiguration().apply {
                SSID = quote(network.name)
                if (network.security == WifiSecurity.OPEN) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                else {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    preSharedKey = if (password.length == 64 && password.all { it in "0123456789abcdefABCDEF" }) password else quote(password)
                }
            }
            val id = wifi.addNetwork(config)
            if (id < 0) { onMessage("WLAN konnte nicht gespeichert werden."); return false }
            pendingNetwork = id
            if (!wifi.enableNetwork(id, true)) { pendingNetwork = null; onMessage("WLAN konnte nicht verbunden werden."); return false }
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, 30_000)
            onMessage("Verbinde mit ${network.name} …")
            return true
        } catch (_: SecurityException) { onMessage("WLAN-Verbindung nicht freigegeben."); return false }
    }

    companion object {
        internal fun security(capabilities: String): WifiSecurity = when {
            "EAP" in capabilities || "WEP" in capabilities || "WAPI" in capabilities -> WifiSecurity.SYSTEM
            "PSK" in capabilities -> WifiSecurity.WPA2
            "SAE" in capabilities || "OWE" in capabilities || "RSN" in capabilities || "WPA" in capabilities || "DPP" in capabilities -> WifiSecurity.SYSTEM
            else -> WifiSecurity.OPEN
        }
        internal fun validPassword(value: String) = value.length in 8..63 ||
            (value.length == 64 && value.all { it in "0123456789abcdefABCDEF" })
        internal fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
