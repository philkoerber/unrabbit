package dev.unrabbit.app

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

class UnrabbitAdminReceiver : DeviceAdminReceiver()

/** Kiosk privileges only exist after explicit device-owner provisioning, never by ordinary install. */
object DeviceMode {
    /** Allow only the resolved Android settings package while its network dialog is in front. */
    fun openNetworkSettings(activity: Activity, mobile: Boolean): Boolean {
        val actions = if (mobile) listOf(Settings.ACTION_NETWORK_OPERATOR_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
            else if (Build.VERSION.SDK_INT >= 29) listOf(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)
            else listOf(Settings.ACTION_WIFI_SETTINGS)
        for (action in actions) {
            val intent = Intent(action)
            val target = activity.packageManager.resolveActivity(intent, PackageManager.MATCH_SYSTEM_ONLY) ?: continue
            intent.component = ComponentName(target.activityInfo.packageName, target.activityInfo.name)
            val policy = activity.getSystemService(DevicePolicyManager::class.java)
            val managed = policy.isDeviceOwnerApp(activity.packageName)
            try {
                if (managed) policy.setLockTaskPackages(ComponentName(activity, UnrabbitAdminReceiver::class.java),
                    arrayOf(activity.packageName, target.activityInfo.packageName))
                activity.startActivity(intent)
                return true
            } catch (_: RuntimeException) {
                if (managed) policy.setLockTaskPackages(ComponentName(activity, UnrabbitAdminReceiver::class.java), arrayOf(activity.packageName))
            }
        }
        return false
    }

    fun enterIfManaged(activity: Activity) {
        val policy = activity.getSystemService(DevicePolicyManager::class.java)
        if (!policy.isDeviceOwnerApp(activity.packageName)) return
        val admin = ComponentName(activity, UnrabbitAdminReceiver::class.java)
        val home = ComponentName(activity.packageName, "${activity.packageName}.Home")
        activity.packageManager.setComponentEnabledSetting(home, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        policy.addPersistentPreferredActivity(admin, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
        }, home)
        policy.setLockTaskPackages(admin, arrayOf(activity.packageName))
        policy.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        policy.setStatusBarDisabled(admin, true)
        policy.setKeyguardDisabled(admin, true)
        policy.addUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS)
        policy.setPermissionGrantState(admin, activity.packageName, Manifest.permission.CAMERA, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        val locked = activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val mode = activity.getSystemService(ActivityManager::class.java).lockTaskModeState
        if (!locked && mode == ActivityManager.LOCK_TASK_MODE_NONE && policy.isLockTaskPermitted(activity.packageName)) activity.startLockTask()
    }

    @Suppress("DEPRECATION")
    fun hideSystemBars(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            activity.window.setDecorFitsSystemWindows(false)
            activity.window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        } else activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}
