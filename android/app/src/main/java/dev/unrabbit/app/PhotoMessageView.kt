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
        contentDescription = "Dein Foto"
    }
    private val missing = TextView(context).apply {
        setText(R.string.photo_unavailable); textSize = 14f; setTextColor(0xffafb4a8.toInt())
        typeface = resources.getFont(R.font.plex_regular); gravity = Gravity.CENTER; visibility = GONE
    }
    private var loading: Future<*>? = null
    init {
        tag = "photo:${photo.id}"
        addView(image, LayoutParams(-1, -1))
        addView(missing, LayoutParams(-1, -1))
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (image.drawable != null) return
        loading = executor.submit {
            val bitmap = store.thumbnail(photo)
            post {
                if (isAttachedToWindow) {
                    image.setImageBitmap(bitmap)
                    missing.visibility = if (bitmap == null) VISIBLE else GONE
                } else bitmap?.recycle()
            }
        }
    }
    override fun onDetachedFromWindow() { loading?.cancel(false); super.onDetachedFromWindow() }
}
