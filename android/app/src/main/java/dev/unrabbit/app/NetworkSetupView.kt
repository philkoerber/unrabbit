package dev.unrabbit.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlin.math.roundToInt

/** The same network setup is reachable on first start and from settings. */
@SuppressLint("ViewConstructor")
internal class NetworkSetupView(
    context: Context,
    private val onClose: () -> Unit,
    private val onSystemSettings: (Boolean) -> Unit,
) : FrameLayout(context) {
    private enum class Page { HOME, LIST, PASSWORD }
    private var page = Page.HOME
    private val items = mutableListOf<Pair<View, () -> Unit>>()
    val navigation: List<Pair<View, () -> Unit>> get() = items.filter { it.first.visibility == VISIBLE }
    var onNavigationChanged: () -> Unit = {}
    var onSelection: (Int) -> Unit = {}
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isFillViewport = true; isVerticalScrollBarEnabled = false
        addView(column, LayoutParams(-1, -2))
    }
    private var status = NetworkSetupStatus("WLAN wird geprüft …", "SIM wird geprüft …", false, false)
    private var networks = emptyList<NearbyWifi>()
    private var wifiLabel: TextView? = null
    private var simLabel: TextView? = null
    private var message: TextView? = null
    private var simButton: Button? = null
    private var password: EditText? = null
    private var keyboardVisible = false
    private var passwordMessage = false
    val editingPassword get() = password?.hasFocus() == true
    private val controller = NetworkSetupController(context, { updateStatus(it) }, { updateNetworks(it) },
        { message?.text = it; passwordMessage = true; message?.visibility = VISIBLE }, { showHome() })

    init {
        tag = "unrabbit-network"
        isSaveEnabled = false
        addView(scroll, LayoutParams(-1, -1))
        if (Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener { _, insets ->
            keyboardVisible = insets.isVisible(WindowInsets.Type.ime())
            setPadding(0, 0, 0, insets.getInsets(WindowInsets.Type.ime()).bottom)
            column.setPadding(dp(24), dp(if (keyboardVisible) 12 else 28), dp(24), dp(24))
            if (page == Page.PASSWORD) message?.visibility = if (keyboardVisible && !passwordMessage) GONE else VISIBLE
            insets
        }
        showHome()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun label(value: String, size: Float = 15f) = TextView(context).apply {
        text = value; textSize = size; typeface = resources.getFont(R.font.plex_regular)
        setTextColor(if (size >= 22f) 0xfff2f0e8.toInt() else 0xffafb4a8.toInt())
        includeFontPadding = false
        setPadding(dp(16), dp(4), dp(12), dp(12))
    }
    private fun reset(title: String) {
        clearPassword()
        column.removeAllViews(); items.clear(); wifiLabel = null; simLabel = null; simButton = null
        column.gravity = if (page == Page.HOME) Gravity.CENTER_VERTICAL else Gravity.TOP
        column.setPadding(dp(24), dp(28), dp(24), dp(24))
        column.addView(label(title, 24f))
        message = null
        passwordMessage = false
        scroll.scrollTo(0, 0)
    }
    private fun button(value: String, action: () -> Unit): Button {
        val view = SettingsActionView(context, value).apply {
            setOnClickListener { if (isEnabled) { onSelection(navigation.indexOfFirst { it.first === this }); action() } }
        }
        items += view to { if (view.isEnabled) action() }
        column.addView(view, LinearLayout.LayoutParams(-1, -2))
        return view
    }
    private fun changed() { onNavigationChanged(); requestApplyInsets() }

    private fun showHome() {
        page = Page.HOME; reset("WLAN & SIM")
        wifiLabel = label(status.wifi).also { column.addView(it) }
        button("WLAN auswählen") {
            if (controller.managed) { showNetworks(); controller.scan() }
            else onSystemSettings(false)
        }
        simLabel = label(status.sim).also { it.visibility = if (status.hasSim) VISIBLE else GONE; column.addView(it) }
        simButton = button("SIM / Mobilfunk") { onSystemSettings(true) }.apply { visibility = if (status.hasSim) VISIBLE else GONE }
        button("Zurück", onClose).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        changed()
    }

    private fun showNetworks() {
        page = Page.LIST; reset("WLAN")
        message = label(if (networks.isEmpty()) "WLANs werden gesucht …" else "Netzwerk auswählen").also { column.addView(it) }
        networks.forEach { network ->
            button(network.name) {
                if (network.security == WifiSecurity.SYSTEM) onSystemSettings(false) else showPassword(network)
            }
        }
        button("Erneut suchen") { controller.scan() }
        button("Weitere Optionen") { onSystemSettings(false) }
        button("Zurück") { showHome() }
        changed()
    }

    private fun showPassword(network: NearbyWifi) {
        page = Page.PASSWORD; reset(network.name)
        message = label(if (network.security == WifiSecurity.OPEN) "Offenes WLAN · ohne Passwort" else "WLAN-Passwort").also { column.addView(it) }
        if (network.security == WifiSecurity.WPA2) {
            password = EditText(context).apply {
                tag = "wifi-password"; hint = "Passwort"; contentDescription = "WLAN-Passwort"
                textSize = 18f; typeface = resources.getFont(R.font.plex_regular)
                setTextColor(0xfff2f0e8.toInt()); setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_DONE) { connect(network); true } else false
                }
            }.also { column.addView(it, LinearLayout.LayoutParams(-1, dp(52)).apply { leftMargin = dp(16); rightMargin = dp(12) }) }
        }
        button("Verbinden") { connect(network) }
        button("Zurück") { showNetworks() }
        changed()
    }

    private fun connect(network: NearbyWifi) {
        if (controller.connect(network, password?.text?.toString().orEmpty())) clearPassword(forgetField = false)
    }
    private fun clearPassword(forgetField: Boolean = true) {
        password?.let {
            it.text.clear(); it.clearFocus()
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0)
        }
        if (forgetField) password = null
    }
    private fun updateStatus(value: NetworkSetupStatus) {
        status = value
        wifiLabel?.text = value.wifi; simLabel?.text = value.sim
        val simVisibility = if (value.hasSim) VISIBLE else GONE
        val navigationChanged = simButton?.visibility?.let { it != simVisibility } == true
        simButton?.visibility = simVisibility
        simLabel?.visibility = simVisibility
        if (navigationChanged) changed()
    }
    private fun updateNetworks(value: List<NearbyWifi>) {
        if (networks == value) return
        networks = value
        if (page == Page.LIST) showNetworks()
    }
    fun handleBack(): Boolean = when (page) {
        Page.PASSWORD -> { showNetworks(); true }
        Page.LIST -> { showHome(); true }
        Page.HOME -> false
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); controller.start() }
    override fun onDetachedFromWindow() { controller.stop(); clearPassword(); super.onDetachedFromWindow() }
}
