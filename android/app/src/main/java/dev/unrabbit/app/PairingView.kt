package dev.unrabbit.app

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** One flow for scanning, connecting and retrying, independent of the settings menu. */
@SuppressLint("ViewConstructor")
internal class PairingView(
    context: Context,
    private val onPair: (PairingQr) -> Unit,
    private val onClose: () -> Unit,
    private val onRequestScan: () -> Unit,
) : FrameLayout(context) {
    private val items = mutableListOf<Pair<View, () -> Unit>>()
    val navigation get() = when {
        isScanning -> emptyList()
        isPairing -> items.takeLast(1)
        else -> items
    }
    val isScanning get() = scanner != null
    var isPairing = false
        private set
    var onSelection: (Int) -> Unit = {}
    var onNavigationChanged: () -> Unit = {}
    private var scanner: SetupScannerView? = null
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val status = label("Kamera wird geöffnet …", 15f).apply {
        tag = "pairing-status"; setTextColor(0xffafb4a8.toInt())
        setPadding(dp(16), dp(12), dp(12), dp(20))
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    init {
        tag = "unrabbit-pairing"
        isClickable = true; isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        defaultFocusHighlightEnabled = false
        column.addView(label("VM verbinden", 24f).apply { setPadding(dp(16), 0, 0, 0) })
        column.addView(status)
        fun action(text: String, callback: () -> Unit) {
            val row = SettingsActionView(context, text)
            val invoke = { if (row.isEnabled) callback() }
            row.setOnClickListener { onSelection(navigation.indexOfFirst { it.first === row }); invoke() }
            items += row to invoke
            column.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        action("QR erneut scannen", onRequestScan)
        action("Zurück", onClose)
        addView(column, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { leftMargin = dp(24); rightMargin = dp(24) })
    }

    fun openScanner() {
        if (isScanning || isPairing) return
        column.visibility = GONE
        scanner = SetupScannerView(context, ::applyScan, onClose).also { addView(it, LayoutParams(-1, -1)) }
        onNavigationChanged()
    }

    fun applyScan(invitation: PairingQr) {
        if (isPairing) return
        closeCamera()
        setPairing(true)
        onPair(invitation)
    }

    fun setPairing(active: Boolean) {
        isPairing = active
        column.visibility = VISIBLE
        items.first().first.isEnabled = !active
        items.first().first.visibility = if (active) GONE else VISIBLE
        if (active) status.text = "Verbinde …"
        onNavigationChanged()
    }

    fun showError(message: String) { setPairing(false); status.text = message }
    fun pauseCamera() { scanner?.pauseCamera() }
    fun resumeCamera() { scanner?.resumeCamera() }
    fun closeCamera() {
        scanner?.let { it.closeCamera(); removeView(it) }
        scanner = null
    }
    override fun onDetachedFromWindow() { closeCamera(); super.onDetachedFromWindow() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; typeface = resources.getFont(R.font.plex_regular); includeFontPadding = false
        setTextColor(0xfff2f0e8.toInt())
    }
}
