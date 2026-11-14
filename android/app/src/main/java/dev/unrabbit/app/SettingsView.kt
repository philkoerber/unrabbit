package dev.unrabbit.app

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import dev.unrabbit.core.ConnectionSettings
import kotlin.math.roundToInt

/** Connection data arrives by QR. Only the numeric SSH port can be changed on the device. */
@SuppressLint("ViewConstructor")
class SettingsView(
    context: Context,
    connection: ConnectionSettings,
    private val onSave: (ConnectionSettings?) -> Unit,
    private val onClose: () -> Unit,
    private val onRequestScan: () -> Unit,
) : FrameLayout(context) {
    private val regular = resources.getFont(R.font.plex_regular)
    private val baseNavigation = mutableListOf<Pair<View, () -> Unit>>()
    val navigation: List<Pair<View, () -> Unit>> get() = if (scanner != null || portWheel != null) emptyList() else baseNavigation
    val isScanning: Boolean get() = scanner != null
    var onSelection: (Int) -> Unit = {}
    var onNavigationChanged: (Int) -> Unit = {}
    private var scanner: SetupScannerView? = null
    private var portWheel: PortWheelView? = null
    private var scanSelection = 0
    private var draft = connection
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val error = label("", 14f, true).apply {
        visibility = GONE; setPadding(dp(16), dp(10), 0, 0)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private fun value(tagName: String, size: Float = 20f) = label("", size).apply {
        tag = tagName; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        setPadding(0, dp(2), 0, dp(2))
    }
    private val host = value("vm-host")
    private val port = value("vm-port")
    private val user = value("vm-user")
    private val fingerprint = value("vm-host-key", 13f).apply { maxLines = 2 }
    private val scroll = DeviceScrollView(context).apply {
        isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false; defaultFocusHighlightEnabled = false
    }

    init {
        tag = "unrabbit-settings"; isClickable = true; isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        defaultFocusHighlightEnabled = false
        column.addView(label("Einstellungen", 22f).apply { setPadding(dp(16), 0, 0, dp(12)) })
        fun field(title: String, value: TextView, scans: Boolean, action: () -> Unit) {
            val row = SettingRow(context)
            row.contentDescription = if (scans) title + " scannen" else title
            val body = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val labels = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 12f, true)); addView(value, LinearLayout.LayoutParams(-1, -2))
            }
            body.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            if (scans) body.addView(MenuIconView(context, MenuIcon.SCAN).apply {
                isClickable = false; isFocusable = false; showSelectionDot = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(body, LinearLayout.LayoutParams(-1, -2))
            register(row, action)
            column.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        field("VM-Adresse", host, true) { scanSelection = 0; onRequestScan() }
        field("SSH-Port", port, false) { openPortWheel() }
        field("Benutzer", user, true) { scanSelection = 2; onRequestScan() }
        field("Host-Schlüssel", fingerprint, true) { scanSelection = 3; onRequestScan() }
        refreshValues()
        column.addView(error)
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val cancel = MenuIconView(context, MenuIcon.CLOSE).apply { contentDescription = "Abbrechen" }
        register(cancel) { onClose() }
        footer.addView(cancel, LinearLayout.LayoutParams(dp(72), dp(48)))
        val save = MenuIconView(context, MenuIcon.SAVE)
        register(save) { saveDraft() }
        footer.addView(save, LinearLayout.LayoutParams(dp(72), dp(48)))
        column.addView(footer)
        scroll.addView(column)
        addView(scroll, LayoutParams(-1, -1).apply {
            leftMargin = dp(24); rightMargin = dp(24); topMargin = dp(24); bottomMargin = dp(24)
        })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun label(value: String, size: Float, secondary: Boolean = false) = TextView(context).apply {
        text = value; typeface = regular; textSize = size; includeFontPadding = false
        setTextColor(context.getColorStateList(if (secondary) R.color.selection_secondary else R.color.selection_text))
    }
    private fun register(view: View, action: () -> Unit) {
        val index = baseNavigation.size
        baseNavigation += view to action
        view.setOnClickListener { onSelection(index); action() }
    }
    fun showError(message: String) { error.text = message; error.visibility = VISIBLE }
    fun openScanner() {
        if (scanner != null || portWheel != null) return
        scroll.visibility = INVISIBLE
        scanner = SetupScannerView(context, { applyScan(it); closeScanner() }, { closeScanner() }).also {
            showOverlay(it)
        }
        onNavigationChanged(0)
    }
    fun applyScan(connection: ConnectionSettings) {
        require(connection.validationError() == null)
        draft = connection
        refreshValues(); error.visibility = GONE
    }
    private fun refreshValues() {
        host.text = draft.host.ifBlank { "Nicht eingerichtet" }
        port.text = String.format(java.util.Locale.ROOT, "%d", draft.port)
        user.text = draft.user
        fingerprint.text = draft.hostKeyFingerprint.ifBlank { "Nicht gesetzt" }
    }
    private fun closeScanner() {
        val old = scanner ?: return
        scanner = null; old.closeCamera(); hideOverlay(old)
        onNavigationChanged(scanSelection)
    }
    private fun openPortWheel() {
        if (portWheel != null || scanner != null) return
        scroll.visibility = INVISIBLE
        portWheel = PortWheelView(context, draft.port, { value ->
            draft = draft.copy(port = value); refreshValues(); closePortWheel()
        }, { closePortWheel() }).also { showOverlay(it) }
        onNavigationChanged(0)
    }
    private fun closePortWheel() {
        val old = portWheel ?: return
        portWheel = null; hideOverlay(old)
        onNavigationChanged(1)
    }
    private fun showOverlay(view: View) {
        addView(view, LayoutParams(-1, -1))
        if (ValueAnimator.areAnimatorsEnabled()) {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(240).start()
        }
    }
    private fun hideOverlay(view: View) {
        scroll.visibility = VISIBLE
        disableActions(view)
        view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.animate().cancel()
        if (ValueAnimator.areAnimatorsEnabled()) view.animate().alpha(0f).setDuration(160).withEndAction { removeView(view) }.start()
        else removeView(view)
        requestFocus()
    }
    private fun disableActions(view: View) {
        view.isEnabled = false; view.isClickable = false
        if (view is ViewGroup) for (i in 0 until view.childCount) disableActions(view.getChildAt(i))
    }
    fun handleWheel(amount: Float): Boolean {
        if (scanner != null) return true
        val wheel = portWheel ?: return false
        wheel.rotate(amount); return true
    }
    fun handleButton(): Boolean {
        if (scanner != null) return true // QR recognition is automatic; the key is not a shutter here.
        val wheel = portWheel ?: return false
        wheel.confirm(); return true
    }
    fun handleBack(): Boolean = when {
        scanner != null -> { closeScanner(); true }
        portWheel != null -> { closePortWheel(); true }
        else -> false
    }
    fun pauseCamera() { scanner?.pauseCamera() }
    fun resumeCamera() { scanner?.resumeCamera() }
    private fun saveDraft() {
        val connection = draft.takeUnless { it.host.isBlank() }
        val problem = connection?.validationError()
        if (problem != null) {
            showError(problem)
            error.post { error.requestRectangleOnScreen(android.graphics.Rect(0, 0, error.width, error.height)) }
        } else onSave(connection)
    }
    override fun onDetachedFromWindow() { scanner?.closeCamera(); super.onDetachedFromWindow() }
}

/** Fine rules and a small focus dot replace the platform's button and field chrome. */
private class SettingRow(context: Context) : LinearLayout(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = context.getColor(R.color.unrabbit_accent)
    private val density = resources.displayMetrics.density
    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setPadding((16 * density).roundToInt(), (6 * density).roundToInt(), 0, (4 * density).roundToInt())
        minimumHeight = (52 * density).roundToInt()
        isClickable = true
        isFocusable = true
        defaultFocusHighlightEnabled = false
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val active = isSelected || isActivated || isPressed
        ink.color = if (active) (accent and 0x00ffffff) or 0x99000000.toInt() else 0x22f2f0e8
        ink.strokeWidth = density * .7f
        canvas.drawLine(16 * density, height - density, width.toFloat(), height - density, ink)
        if (active) {
            ink.color = accent
            canvas.drawCircle(3 * density, height / 2f, 2.3f * density, ink)
        }
    }
    override fun setSelected(selected: Boolean) { super.setSelected(selected); invalidate() }
    override fun setActivated(activated: Boolean) { super.setActivated(activated); invalidate() }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
    }
}
