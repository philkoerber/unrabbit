package dev.unrabbit.app

import android.app.Application
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig

/** Select an actually present lens, including devices that advertise a nonexistent second camera. */
open class UnrabbitApplication : Application(), CameraXConfig.Provider {
    internal open fun createSpeechInput(): SpeechInput = PushToTalkInput(this)
    override fun getCameraXConfig(): CameraXConfig {
        val manager = getSystemService(CameraManager::class.java)
        val facing = runCatching {
            manager.cameraIdList.map { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }
        }.getOrDefault(emptyList())
        val selector = when {
            CameraCharacteristics.LENS_FACING_BACK in facing -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraCharacteristics.LENS_FACING_FRONT in facing -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> null
        }
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig()).apply {
            if (selector != null) setAvailableCamerasLimiter(selector)
        }.build()
    }
}
