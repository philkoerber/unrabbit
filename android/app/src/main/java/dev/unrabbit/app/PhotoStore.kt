package dev.unrabbit.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.AtomicFile
import dev.unrabbit.core.PhotoAttachment
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Private JPEGs, separate from the message JSON; no gallery or external storage access. */
class PhotoStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "photos")
    private val cache = context.cacheDir

    fun newCaptureFile(): File = File.createTempFile("unrabbit-capture-", ".jpg", cache)
    fun file(photo: PhotoAttachment) = File(directory, "${photo.id}.jpg")

    /** Run off the UI thread. Decode applies EXIF orientation; re-encoding drops camera metadata. */
    fun importCapture(source: File) = importCapture(ImageDecoder.createSource(source))

    internal fun importCapture(source: ImageDecoder.Source): PhotoAttachment {
        val bitmap = decode(source, 2048)
        try {
            check(directory.isDirectory || directory.mkdirs()) { "Photo storage unavailable" }
            val photo = PhotoAttachment(UUID.randomUUID().toString(), bitmap.width, bitmap.height, System.currentTimeMillis())
            val destination = AtomicFile(file(photo))
            val stream = destination.startWrite()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)) { "Photo encoding failed" }
                destination.finishWrite(stream)
                check(file(photo).setLastModified(photo.capturedAt)) { "Photo timestamp unavailable" }
            } catch (error: Exception) {
                destination.failWrite(stream)
                throw error
            }
            return photo
        } finally { bitmap.recycle() }
    }

    fun thumbnail(photo: PhotoAttachment): Bitmap? {
        if (photo.isExpired(System.currentTimeMillis())) { discard(photo); return null }
        return runCatching { decode(ImageDecoder.createSource(file(photo)), 512) }.getOrNull()
    }
    fun discard(photo: PhotoAttachment) { file(photo).delete() }

    /** Also remove abandoned camera/atomic-write files; reads never renew their age. */
    fun pruneExpired(now: Long = System.currentTimeMillis()): Int {
        val candidates = directory.listFiles().orEmpty().toList() + cache.listFiles().orEmpty().filter {
            it.name.startsWith("unrabbit-capture-") && it.extension == "jpg"
        }
        return candidates.count { it.isFile && now - it.lastModified() >= PhotoAttachment.RETENTION_MILLIS && it.delete() }
    }

    private fun decode(source: ImageDecoder.Source, maxEdge: Int): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = (maxEdge.toFloat() / max(info.size.width, info.size.height)).coerceAtMost(1f)
        decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
    }
}
