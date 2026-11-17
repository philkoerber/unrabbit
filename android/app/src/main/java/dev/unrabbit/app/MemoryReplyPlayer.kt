package dev.unrabbit.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** All MediaPlayer/focus operations run on main; no temporary audio files or background playback. */
internal class MemoryReplyPlayer(context: Context) : ReplyAudioPlayer {
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val closed = AtomicBoolean(false)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) close()
        }, main).build()
    private var hasFocus = false
    private var player: MediaPlayer? = null
    private var source: BytesSource? = null
    @Volatile private var completion = CountDownLatch(0)
    @Volatile private var failure = false

    override fun play(audio: ByteArray, onStarted: () -> Unit) {
        if (closed.get()) { audio.fill(0); throw IOException("Playback stopped") }
        val done = CountDownLatch(1)
        completion = done
        val task = {
            if (closed.get()) { audio.fill(0); done.countDown() }
            else try {
                releasePlayer()
                if (!hasFocus) {
                    hasFocus = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    if (!hasFocus) throw IOException("Audio focus unavailable")
                }
                val bytes = BytesSource(audio)
                source = bytes
                val media = MediaPlayer()
                player = media
                media.setAudioAttributes(attributes)
                media.setOnPreparedListener {
                    if (closed.get() || player !== it) return@setOnPreparedListener
                    try { it.start(); onStarted() }
                    catch (_: Exception) { failure = true; releasePlayer(); done.countDown() }
                }
                media.setOnCompletionListener { if (player === it) { releasePlayer(); done.countDown() } }
                media.setOnErrorListener { failed, _, _ ->
                    if (player === failed) { failure = true; releasePlayer(); done.countDown() }
                    true
                }
                media.setDataSource(bytes)
                media.prepareAsync()
            } catch (_: Exception) { audio.fill(0); failure = true; releasePlayer(); done.countDown() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task() else main.post(task)
    }

    override fun awaitCompletion() {
        if (!completion.await(40, TimeUnit.SECONDS) || failure || closed.get()) {
            close(); throw IOException("Playback stopped or unavailable")
        }
    }

    private fun releasePlayer() {
        player?.let { it.setOnCompletionListener(null); it.setOnErrorListener(null); it.setOnPreparedListener(null); it.release() }
        player = null; source?.close(); source = null
    }
    override fun close() {
        closed.set(true)
        completion.countDown()
        val release = {
            releasePlayer()
            if (hasFocus) { audioManager.abandonAudioFocusRequest(focus); hasFocus = false }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release() else main.post(release)
    }
    private class BytesSource(private val bytes: ByteArray) : MediaDataSource() {
        @Synchronized override fun getSize() = bytes.size.toLong()
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= bytes.size) return -1
            val count = minOf(size, bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        @Synchronized override fun close() { bytes.fill(0) }
    }
}
