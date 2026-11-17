package dev.unrabbit.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.Surface
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.unrabbit.core.PhotoAttachment
import java.util.concurrent.ExecutorService
import kotlin.math.roundToInt

/** A scoped camera lifecycle: only this visible screen may own a photo capture session. */
@SuppressLint("ViewConstructor")
class PhotoCameraView(context: Context, private val store: PhotoStore, private val io: ExecutorService,
    private val onPhoto: (PhotoAttachment) -> Unit, onClose: () -> Unit
) : FrameLayout(context), LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override fun getLifecycle(): Lifecycle = registry
    private val main = ContextCompat.getMainExecutor(context)
    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
        contentDescription = "Camera preview"
    }
    private val status = TextView(context).apply {
        textSize = 14f; setTextColor(0xffafb4a8.toInt()); typeface = resources.getFont(R.font.plex_regular)
        gravity = Gravity.CENTER; accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var initializing = false
    private var active = false
    private var closed = false
    private var ready = false
    private var busy = false
    private var generation = 0

    init {
        tag = "unrabbit-camera"
        isClickable = true // Consume preview touches; never pass them to the chat below.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        registry.currentState = Lifecycle.State.CREATED
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(previewView, LayoutParams(-1, -1))
        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> bindCamera() }
        addView(status, LayoutParams(-1, dp(64), Gravity.CENTER_VERTICAL).apply {
            leftMargin = dp(24); rightMargin = dp(24)
        })
        addOverlayCloseControl("Close camera", onClose)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()

    fun showError(message: String) { status.text = message; status.visibility = VISIBLE }

    fun resumeCamera() {
        if (closed) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showError("Camera access required."); return
        }
        active = true
        registry.currentState = Lifecycle.State.RESUMED
        if (capture != null || initializing) return
        if (provider != null) { bindCamera(); return }
        initializing = true
        showError("Opening camera …")
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            initializing = false
            if (!closed) try {
                provider = future.get()
                bindCamera()
            } catch (_: Exception) { showError("Camera unavailable.") }
        }, main)
    }

    private fun bindCamera() {
        if (closed || capture != null) return
        val cameraProvider = provider ?: return
        val viewport = previewView.viewPort ?: return // Wait until the preview is attached and measured.
        try {
            val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
                else CameraSelector.DEFAULT_FRONT_CAMERA
            val livePreview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(90).setTargetRotation(display?.rotation ?: Surface.ROTATION_0).build()
            val group = UseCaseGroup.Builder().addUseCase(livePreview).addUseCase(imageCapture)
                .setViewPort(viewport).build()
            val camera = cameraProvider.bindToLifecycle(this, selector, group)
            preview = livePreview; capture = imageCapture
            camera.cameraInfo.cameraState.observe(this) { state ->
                ready = state.type == CameraState.Type.OPEN && state.error == null
                if (state.error != null) showError("Camera unavailable.")
                else if (ready && !busy) status.visibility = GONE
            }
        } catch (_: Exception) { showError("Camera unavailable.") }
    }

    fun takePhoto() {
        if (!active || closed || busy) return
        val camera = capture
        if (!ready || camera == null) { resumeCamera(); return }
        val temporary = try { store.newCaptureFile() } catch (_: Exception) {
            showError("Photo could not be saved."); return
        }
        busy = true
        val attempt = ++generation
        camera.targetRotation = display?.rotation ?: Surface.ROTATION_0
        camera.takePicture(ImageCapture.OutputFileOptions.Builder(temporary).build(), main,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    if (closed || !active || attempt != generation) { temporary.delete(); return }
                    io.execute {
                        val photo = try { store.importCapture(temporary) } catch (_: Exception) { null }
                        finally { temporary.delete() }
                        main.execute {
                            if (closed || !active || attempt != generation) photo?.let(store::discard)
                            else {
                                busy = false
                                if (photo != null) onPhoto(photo)
                                else showError("Photo could not be saved.")
                            }
                        }
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    temporary.delete()
                    main.execute {
                        if (!closed && attempt == generation) {
                            busy = false
                            showError("Capture failed. Try again.")
                        }
                    }
                }
            })
    }

    fun pauseCamera() {
        if (closed) return
        active = false; ready = false; busy = false; generation++
        registry.currentState = Lifecycle.State.CREATED
    }

    fun closeCamera() {
        if (closed) return
        pauseCamera(); closed = true
        registry.currentState = Lifecycle.State.DESTROYED
        val useCases = listOfNotNull(preview, capture).toTypedArray()
        if (useCases.isNotEmpty()) provider?.unbind(*useCases)
    }
    override fun onDetachedFromWindow() { closeCamera(); super.onDetachedFromWindow() }
}
