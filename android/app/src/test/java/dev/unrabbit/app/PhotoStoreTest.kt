package dev.unrabbit.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import dev.unrabbit.core.PhotoAttachment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import java.util.UUID
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoStoreTest {
    @Test fun cleanupDeletesOnlyExpiredImagesIncludingAbandonedCaptures() {
        val context = RuntimeEnvironment.getApplication()
        val store = PhotoStore(context)
        val now = System.currentTimeMillis()
        val old = PhotoAttachment(UUID.randomUUID().toString(), 32, 24, now - PhotoAttachment.RETENTION_MILLIS)
        val recent = old.copy(id = UUID.randomUUID().toString(), capturedAt = now - 1000)
        for (photo in listOf(old, recent)) store.file(photo).apply {
            parentFile!!.mkdirs(); writeText("private image"); assertTrue(setLastModified(photo.capturedAt))
        }
        val abandoned = store.newCaptureFile().apply { writeText("raw capture"); setLastModified(old.capturedAt) }
        val unrelated = File(context.cacheDir, "other-cache.txt").apply { writeText("keep"); setLastModified(old.capturedAt) }
        assertEquals(2, store.pruneExpired(now))
        assertFalse(store.file(old).exists())
        assertFalse(abandoned.exists())
        assertTrue(store.file(recent).exists())
        assertTrue(unrelated.exists())
        assertEquals(0, store.pruneExpired(now))
        assertEquals(recent.capturedAt, store.file(recent).lastModified())
    }

    @Test fun expiredImageIsDeletedOnAccessWithoutWaitingForBackgroundMaintenance() {
        val store = PhotoStore(RuntimeEnvironment.getApplication())
        val expired = PhotoAttachment(UUID.randomUUID().toString(), 32, 24,
            System.currentTimeMillis() - PhotoAttachment.RETENTION_MILLIS)
        store.file(expired).apply { parentFile!!.mkdirs(); writeText("old photo") }
        assertNull(store.thumbnail(expired))
        assertFalse(store.file(expired).exists())
    }
    @Test fun captureBecomesABoundedPrivateJpegAndRemainsReadableAfterReopening() {
        val context = RuntimeEnvironment.getApplication()
        val store = PhotoStore(context)
        val source = store.newCaptureFile()
        val bitmap = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        bitmap.recycle()
        // Robolectric's native codec cannot read Android file descriptors on macOS.
        // Use the same decoder with an in-memory source; file capture/preview is tested on the emulator.
        val photo = store.importCapture(ImageDecoder.createSource(ByteBuffer.wrap(source.readBytes())))
        assertEquals(2048, photo.width)
        assertEquals(1024, photo.height)
        assertTrue(store.file(photo).canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + "/photos/"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val bytes = PhotoStore(context).file(photo).readBytes()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(photo.width, bounds.outWidth)
        assertEquals(photo.height, bounds.outHeight)
        val reopened = PhotoStore(context)
        reopened.discard(photo)
        assertNull(reopened.thumbnail(photo))
        source.delete()
    }

    @Test fun invalidImagesAndPathLikeAttachmentIdsAreRejected() {
        val store = PhotoStore(RuntimeEnvironment.getApplication())
        val source = store.newCaptureFile().apply { writeText("not an image") }
        assertThrows(Exception::class.java) { store.importCapture(source) }
        assertThrows(IllegalArgumentException::class.java) { PhotoAttachment("../outside", 100, 100) }
        assertNull(store.thumbnail(PhotoAttachment(UUID.randomUUID().toString(), 100, 100)))
        source.delete()
    }
}
