package dev.unrabbit.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.*
import com.journeyapps.barcodescanner.camera.CenterCropStrategy
import dev.unrabbit.core.ConnectionSettings
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class SetupScannerView(context: Context, private val onResult: (ConnectionSettings) -> Unit, onClose: () -> Unit) : FrameLayout(context) {
    private val preview = BarcodeView(context).apply {
        setUseTextureView(true)
        previewScalingStrategy = CenterCropStrategy()
        contentDescription = context.getString(R.string.scan_hint)
    }
    private var active = false
    private var closed = false
    private val status = TextView(context).apply {
        visibility = GONE
        textSize = 14f; setTextColor(0xfff2f0e8.toInt()); typeface = resources.getFont(R.font.plex_regular)
        gravity = Gravity.CENTER; accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { setColor(0xcc171915.toInt()); cornerRadius = dp(8).toFloat() }
    }
    private val retry = Runnable { if (active && isAttachedToWindow) { status.visibility = GONE; decode() } }
    init {
        tag = "unrabbit-scanner"
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(0xff171915.toInt())
        preview.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        preview.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() {}
            override fun previewStarted() {}
            override fun previewStopped() {}
            override fun cameraClosed() {}
            override fun cameraError(error: Exception) {
                active = false; removeCallbacks(retry); showError(R.string.scan_camera_unavailable)
            }
        })
        addView(preview, LayoutParams(-1, -1))
        addView(status, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(24); rightMargin = dp(24); bottomMargin = dp(24)
        })
        addOverlayCloseControl("Scan abbrechen", onClose)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun showError(message: Int) { status.setText(message); status.visibility = VISIBLE }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Decode the visible image, with no invisible central target or decorative frame.
        preview.framingRectSize = Size(w, h)
    }
    private fun decode() {
        preview.decodeSingle { result ->
            if (active) {
                val settings = runCatching { SetupQr.parse(result.text) }.getOrNull()
                if (settings != null) { pauseCamera(); onResult(settings) }
                else { showError(R.string.scan_invalid); postDelayed(retry, 1200) }
            }
        }
    }
    fun resumeCamera() {
        if (active || closed) return
        active = true
        status.visibility = GONE
        try { preview.resume(); decode() }
        catch (_: RuntimeException) { active = false; showError(R.string.scan_camera_unavailable) }
    }
    fun pauseCamera() { active = false; removeCallbacks(retry); preview.pause() }
    fun closeCamera() { closed = true; pauseCamera() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); resumeCamera() }
    override fun onDetachedFromWindow() { closeCamera(); super.onDetachedFromWindow() }
}
