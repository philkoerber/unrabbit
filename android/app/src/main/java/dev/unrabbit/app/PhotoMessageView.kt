package dev.unrabbit.app

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.unrabbit.core.PhotoAttachment
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/** A non-selectable photo in the same stream as speech messages. */
@SuppressLint("ViewConstructor")
class PhotoMessageView(context: Context, private val photo: PhotoAttachment, private val store: PhotoStore,
    private val executor: ExecutorService) : FrameLayout(context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Your photo"
    }
    private val missing = TextView(context).apply {
        setText(R.string.photo_unavailable); textSize = 14f; setTextColor(0xffafb4a8.toInt())
        typeface = resources.getFont(R.font.plex_regular); gravity = Gravity.CENTER; visibility = GONE
    }
    private var loading: Future<*>? = null
    private val expiry = Runnable { showExpired() }
    private fun showExpired() {
        image.setImageDrawable(null)
        missing.setText(R.string.photo_expired)
        missing.visibility = VISIBLE
        executor.execute { store.discard(photo) }
    }
    init {
        tag = "photo:${photo.id}"
        addView(image, LayoutParams(-1, -1))
        addView(missing, LayoutParams(-1, -1))
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val now = System.currentTimeMillis()
        if (photo.isExpired(now)) { showExpired(); return }
        if (photo.capturedAt > 0) postDelayed(expiry, photo.capturedAt + PhotoAttachment.RETENTION_MILLIS - now)
        if (image.drawable != null) return
        loading = executor.submit {
            val bitmap = store.thumbnail(photo)
            post {
                if (isAttachedToWindow && photo.isExpired(System.currentTimeMillis())) {
                    bitmap?.recycle(); showExpired()
                } else if (isAttachedToWindow) {
                    image.setImageBitmap(bitmap)
                    missing.visibility = if (bitmap == null) VISIBLE else GONE
                } else bitmap?.recycle()
            }
        }
    }
    override fun onDetachedFromWindow() {
        loading?.cancel(false)
        removeCallbacks(expiry)
        image.setImageDrawable(null)
        super.onDetachedFromWindow()
    }
}
