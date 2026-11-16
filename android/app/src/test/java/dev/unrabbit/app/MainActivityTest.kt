package dev.unrabbit.app

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpeechTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityTest {
    @Before fun microphonePermission() {
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun views(activity: MainActivity) = descendants(activity.window.decorView)
    private fun click(activity: MainActivity, label: String) {
        views(activity).first {
            (it is Button && it.text.toString() == label) ||
                (it.isClickable && it.contentDescription == label)
        }.performClick()
        // Let the fixture deliver its initial window-focus event and the welcome transition.
        if (label == "Demo öffnen") shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    }
    private fun down(activity: MainActivity, code: Int = KeyEvent.KEYCODE_DPAD_LEFT) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    }
    private fun up(activity: MainActivity, code: Int = KeyEvent.KEYCODE_DPAD_LEFT) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
    private fun tap(activity: MainActivity, code: Int = KeyEvent.KEYCODE_DPAD_LEFT) {
        down(activity, code); up(activity, code)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(310))
    }
    private fun doubleTap(activity: MainActivity) {
        down(activity); up(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        down(activity); up(activity)
    }
    private fun openSettings(activity: MainActivity) {
        tap(activity) // Rest → chat.
        tap(activity) // Chat → menu.
        tap(activity)
    }

    @Test fun doublePressFromRestOrChatOpensCameraWithoutMenuOrAccidentalPhoto() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen")
        doubleTap(activity)
        assertEquals(1, views(activity).filterIsInstance<PhotoCameraView>().size)
        assertEquals(listOf(MenuIcon.CLOSE), views(activity).filterIsInstance<MenuIconView>().map { it.icon })
        assertTrue(LocalStorage(activity).load()!!.messages.isEmpty())
        tap(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        tap(activity, KeyEvent.KEYCODE_DPAD_UP)
        val close = views(activity).filterIsInstance<MenuIconView>().single()
        assertFalse(close.isSelected || close.isFocusable)
        assertEquals(View.GONE, views(activity).filterIsInstance<StatusRailView>().single().visibility)
        click(activity, "Kamera schließen")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(0f, views(activity).filterIsInstance<StatusRailView>().single().compactFraction, .001f)
        tap(activity) // Chat
        doubleTap(activity)
        assertEquals(1, views(activity).filterIsInstance<PhotoCameraView>().size)
        click(activity, "Kamera schließen")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(1f, views(activity).filterIsInstance<StatusRailView>().single().compactFraction, .001f)
        assertTrue(LocalStorage(activity).load()!!.messages.isEmpty())
        controller.pause().stop().destroy()
    }

    @Test fun photoResultAppendsOnceAndSurvivesRestartWithoutFakeAnalysis() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen"); doubleTap(activity)
        val photo = dev.unrabbit.core.PhotoAttachment(java.util.UUID.randomUUID().toString(), 800, 600)
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "attachPhoto",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(dev.unrabbit.core.PhotoAttachment::class.java, photo))
        val snapshot = LocalStorage(activity).load()!!
        assertEquals(listOf(photo), snapshot.messages.single().photos)
        assertFalse(snapshot.hadPending)
        assertEquals(1, views(activity).filterIsInstance<PhotoMessageView>().size)
        assertTrue(views(activity).none { it is PhotoCameraView })
        controller.pause().stop().destroy()
        val next = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(snapshot, LocalStorage(next.get()).load())
        assertEquals(1, views(next.get()).filterIsInstance<PhotoMessageView>().size)
        next.pause().stop().destroy()
    }

    @Test fun draftPhotoWaitsThroughCancelledSpeechAndReachesTransportWithTheNextUtteranceOnly() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen")
        val sent = mutableListOf<dev.unrabbit.core.ChatRequest>()
        val transport = object : dev.unrabbit.core.ChatTransport {
            override fun send(request: dev.unrabbit.core.ChatRequest, onResult: (dev.unrabbit.core.TransportResult) -> Unit) { sent += request }
            override fun cancel(requestId: String, onAcknowledged: (Boolean) -> Unit) = onAcknowledged(true)
            override fun close() = Unit
        }
        org.robolectric.util.ReflectionHelpers.setField(activity, "transport", transport)
        doubleTap(activity)
        val photo = dev.unrabbit.core.PhotoAttachment(java.util.UUID.randomUUID().toString(), 800, 600)
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "attachPhoto",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(dev.unrabbit.core.PhotoAttachment::class.java, photo))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(sent.isEmpty())
        assertNull(LocalStorage(activity).load()!!.messages.single().requestId)
        down(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        tap(activity, KeyEvent.KEYCODE_DPAD_UP)
        up(activity)
        assertTrue(sent.isEmpty())
        assertNull(LocalStorage(activity).load()!!.messages.single().requestId)
        down(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        up(activity)
        assertEquals(listOf(photo), sent.single().photos)
        assertTrue(sent.single().text.isNotBlank())
        assertEquals(sent.single().id, LocalStorage(activity).load()!!.messages.first().requestId)
        down(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        up(activity)
        assertEquals(2, sent.size)
        assertTrue(sent.last().photos.isEmpty())
        controller.pause().stop().destroy()
    }

    @Test fun leavingTheAppDuringTheDoublePressWindowCancelsThePendingClick() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen")
        down(activity); up(activity)
        controller.pause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        controller.resume()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue(views(activity).none { it is PhotoCameraView || it is MenuIconView })
        assertEquals(0f, views(activity).filterIsInstance<StatusRailView>().single().compactFraction, .001f)
        controller.pause().stop().destroy()
    }

    @Test fun logoStopsSchedulingWhileActivityIsPausedAndResumesOnWake() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val logo = views(controller.get()).filterIsInstance<LogoView>().single()
        // There is no real window manager in this fixture; simulate its visibility notification.
        fun showWindow() {
            val attachInfo = org.robolectric.util.ReflectionHelpers.getField<Any>(logo, "mAttachInfo")
            org.robolectric.util.ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
            logo.dispatchWindowVisibilityChanged(View.VISIBLE)
        }
        showWindow()
        // Legacy Robolectric shadows View.draw; invoke this view's actual drawing callback.
        fun drawLogo() = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(logo, "onDraw",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.graphics.Canvas::class.java, android.graphics.Canvas()))
        drawLogo()
        assertTrue(logo.hasScheduledFrame)
        controller.pause()
        assertFalse(logo.hasScheduledFrame)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        drawLogo() // Even a late redraw must not restart the paused loop.
        assertFalse(logo.hasScheduledFrame)
        controller.resume()
        showWindow()
        drawLogo()
        assertTrue(logo.hasScheduledFrame)
        controller.pause().stop().destroy()
    }

    @Test fun holdCreatesPlainChatMessagesAndShortPressOpensRealActions() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen")
        down(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        up(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val messages = views(activity).filterIsInstance<TextView>().filter { it.text.contains("Radio", true) }
        assertEquals(2, messages.size)
        assertTrue(messages.none { it.isClickable || it.isFocusable || it is Button })
        assertTrue(views(activity).filterIsInstance<TextView>().none { it.text.contains("ICH HÖRE ZU") })
        tap(activity)
        val icons = views(activity).filterIsInstance<MenuIconView>()
        assertEquals(listOf(MenuIcon.SETTINGS, MenuIcon.CLOSE), icons.map { it.icon })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertEquals(icons[0].left, icons[1].left)
        assertTrue(icons[0].bottom <= icons[1].top)
        assertTrue(icons[0].isSelected)
        tap(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(icons[1].isSelected)
        tap(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(icons[0].isSelected)
        assertTrue(views(activity).filterIsInstance<TextView>().none { it.isShown })
        controller.pause().stop().destroy()
    }

    @Test fun wheelDuringHoldCancelsWithoutAddingMessage() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen")
        down(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        down(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        up(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(views(activity).filterIsInstance<TextView>().none { it.text.contains("Radio", true) })
        controller.pause().stop().destroy()
    }

    @Test fun leftKeyRepeatSendsOnlyOnceAndReplyArrivingDuringAnotherHoldIsNotHiddenAfterCancel() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen")
        down(activity)
        activity.dispatchKeyEvent(KeyEvent(0, 200, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 1))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        up(activity)
        assertEquals(1, LocalStorage(activity).load()!!.messages.size)
        down(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(2, LocalStorage(activity).load()!!.messages.size)
        tap(activity, KeyEvent.KEYCODE_DPAD_UP)
        up(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(2, views(activity).filterIsInstance<TextView>().count { it.text.contains("Radio", true) })
        assertEquals(1f, views(activity).filterIsInstance<ScrollView>().single().alpha, .001f)
        assertEquals(2, LocalStorage(activity).load()!!.messages.size)
        controller.pause().stop().destroy()
    }

    @Test fun menuStopCancelsAllCurrentDemoRequestsAndLaterInputStillWorks() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen")
        repeat(2) {
            down(activity)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            up(activity)
        }
        tap(activity)
        assertEquals(listOf(MenuIcon.SETTINGS, MenuIcon.STOP, MenuIcon.CLOSE),
            views(activity).filterIsInstance<MenuIconView>().map { it.icon })
        tap(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        tap(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(2, LocalStorage(activity).load()!!.messages.size)
        assertTrue(views(activity).filterIsInstance<TextView>().any { it.text == "Angehalten." })
        down(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        up(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(4, LocalStorage(activity).load()!!.messages.size)
        controller.pause().stop().destroy()
    }

    @Test fun settingsUseVerticalActionsAndHostScanWithoutTextInputOrCameraWheelActions() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen"); openSettings(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        val panel = views(activity).filterIsInstance<SettingsView>().single()
        val rows = panel.navigation.map { it.first }
        assertTrue(rows.zipWithNext().all { (a, b) -> a.bottom <= b.top })
        val icons = views(activity).filterIsInstance<MenuIconView>()
        assertEquals(4, panel.navigation.size)
        assertTrue(icons.none { it.icon == MenuIcon.SAVE })
        assertTrue(views(activity).none { it is EditText })
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.CAMERA)
        tap(activity) // The settings action directly opens the separate scanner flow.
        val pairing = views(activity).filterIsInstance<PairingView>().single()
        assertTrue(views(activity).none { it is SettingsView })
        assertTrue(pairing.isScanning)
        assertTrue(pairing.navigation.isEmpty())
        assertEquals(View.GONE, views(activity).filterIsInstance<StatusRailView>().single().visibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, views(activity).filterIsInstance<LogoView>().single().importantForAccessibility)
        tap(activity, KeyEvent.KEYCODE_DPAD_DOWN); tap(activity)
        assertTrue(pairing.isScanning)
        assertTrue(LocalStorage(activity).load()!!.messages.isEmpty())
        val close = views(activity).filterIsInstance<MenuIconView>().single { it.contentDescription == "Scan abbrechen" }
        assertFalse(close.isSelected || close.isFocusable)
        close.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertTrue(views(activity).none { it is PairingView })
        assertTrue(views(activity).none { it is SetupScannerView })
        assertEquals(View.VISIBLE, views(activity).filterIsInstance<StatusRailView>().single().visibility)
        assertTrue(views(activity).filterIsInstance<SettingsView>().single().navigation.first().first.isSelected)
        controller.pause().stop().destroy()
    }

    @Test fun restartAndMenuKeepTheSameStreamWithoutChatManagement() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen")
        down(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        up(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        controller.pause().stop().destroy()
        val next = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue(views(next.get()).filterIsInstance<TextView>().any { it.text.contains("kleines Radio") })
        tap(next.get()) // Leave the rest screen without executing an action.
        tap(next.get())
        down(next.get(), KeyEvent.KEYCODE_DPAD_DOWN)
        up(next.get(), KeyEvent.KEYCODE_DPAD_DOWN)
        tap(next.get()) // Close menu; continue in the same stream.
        assertTrue(views(next.get()).filterIsInstance<TextView>().any { it.text.contains("kleines Radio") })
        down(next.get())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        up(next.get())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(4, LocalStorage(next.get()).load()!!.messages.size)
        assertTrue(views(next.get()).filterIsInstance<TextView>().none { it.text.contains("Neues Gespräch") })
        next.pause().stop().destroy()
    }

    @Test fun backgroundSurvivesChatUpdatesAndListeningTemporarilyHidesText() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen")
        val background = views(activity).filterIsInstance<LogoView>().single()
        assertFalse(background.foregroundVisible)
        down(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        up(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertSame(background, views(activity).filterIsInstance<LogoView>().single())
        assertTrue(background.foregroundVisible)
        assertEquals((background.parent as View).height, background.height)

        down(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        val feed = views(activity).filterIsInstance<ScrollView>().single()
        assertFalse(background.foregroundVisible)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, feed.importantForAccessibility)
        assertEquals(0f, feed.alpha, .01f)
        down(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        up(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertTrue(background.foregroundVisible)
        assertEquals(1f, feed.alpha, .01f)
        assertEquals(2, views(activity).filterIsInstance<TextView>().count { it.text.contains("Radio", true) })
        controller.pause().stop().destroy()
    }

    @Test fun restClockShrinksContinuouslyIntoTheUnchangedChatMargin() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen")
        down(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        up(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val rail = views(activity).filterIsInstance<StatusRailView>().single()
        val feed = views(activity).filterIsInstance<ScrollView>().single()
        val originalLeft = feed.left
        assertEquals((24 * activity.resources.displayMetrics.density).toInt(), originalLeft)
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(0f, rail.compactFraction, .001f)
        assertEquals(View.INVISIBLE, feed.visibility)
        val frames = mutableListOf<Float>()
        val update = rail.onProgress
        rail.onProgress = { frames += it; update?.invoke(it) }
        tap(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue(frames.any { it > 0f && it < 1f })
        assertSame(rail, views(activity).filterIsInstance<StatusRailView>().single())
        assertSame(feed, views(activity).filterIsInstance<ScrollView>().single())
        assertEquals(1f, rail.compactFraction, .001f)
        assertEquals(1f, feed.alpha, .001f)
        assertEquals(originalLeft, feed.left)
        controller.pause().stop().destroy()
    }

    @Test fun settingsHaveNoManualFieldsAndClosingKeepsChat() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen"); openSettings(activity)
        val panel = views(activity).filterIsInstance<SettingsView>().single()
        assertEquals(listOf("VM verbinden", "WLAN & SIM", "Sprache · English", "Zurück"), panel.navigation.map { it.first.contentDescription.toString() })
        assertTrue(views(activity).none { it is EditText || it.tag in listOf("vm-host", "vm-port", "vm-user", "vm-host-key") })
        click(activity, "Zurück")
        assertFalse(PairingStore(activity).hasPairing())
        assertTrue(LocalStorage(activity).load()!!.messages.isEmpty())
        controller.pause().stop().destroy()
    }

    @Test fun welcomeConnectOpensScannerInOneTapAndBothBackActionsReturnToWelcome() {
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.CAMERA)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        repeat(2) { attempt ->
            click(activity, "VM verbinden")
            assertTrue(views(activity).filterIsInstance<PairingView>().single().isScanning)
            assertTrue(views(activity).none { it is SettingsView })
            if (attempt == 0) click(activity, "Scan abbrechen") else {
                @Suppress("DEPRECATION")
                activity.onBackPressed()
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            assertTrue(views(activity).none { it is SettingsView || it is PairingView || it is SetupScannerView })
            assertTrue(views(activity).filterIsInstance<Button>().any { it.text == "VM verbinden" })
            assertFalse(LocalStorage(activity).preferences.getBoolean("demoAcknowledged", false))
        }
        controller.pause().stop().destroy()
    }

    @Test fun deniedCameraCanBeRetriedInTheSameFlowWithoutOpeningSettings() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "VM verbinden")
        activity.onRequestPermissionsResult(71, arrayOf(android.Manifest.permission.CAMERA),
            intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        val pairing = views(activity).filterIsInstance<PairingView>().single()
        assertFalse(pairing.isScanning)
        assertEquals(listOf("QR erneut scannen", "Zurück"), pairing.navigation.map { it.first.contentDescription })
        assertTrue(views(activity).none { it is SettingsView })
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.CAMERA)
        click(activity, "QR erneut scannen")
        assertTrue(pairing.isScanning)
        click(activity, "Scan abbrechen")
        assertFalse(PairingStore(activity).hasPairing())
        controller.pause().stop().destroy()
    }

    @Test fun connectionErrorOffersRetryAndReturnsToItsOriginalMenu() {
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.CAMERA)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen"); openSettings(activity)
        val before = LocalStorage(activity).load()
        click(activity, "VM verbinden")
        val pairing = views(activity).filterIsInstance<PairingView>().single()
        pairing.closeCamera(); pairing.setPairing(true)
        assertEquals(listOf("Zurück"), pairing.navigation.map { it.first.contentDescription })
        pairing.showError("Verbindung fehlgeschlagen.")
        click(activity, "QR erneut scannen")
        assertTrue(pairing.isScanning)
        click(activity, "Scan abbrechen")
        assertEquals(1, views(activity).filterIsInstance<SettingsView>().size)
        assertEquals(before, LocalStorage(activity).load())
        assertFalse(PairingStore(activity).hasPairing())
        controller.pause().stop().destroy()
    }

    @Test fun menuCrossfadeCanBeInterruptedAndRestIsDeviceHome() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen")
        val logo = views(activity).filterIsInstance<LogoView>().single()
        tap(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        // Exercise the transition at its start via the accessible click action; gesture timing is tested separately.
        logo.performClick()
        val opening = views(activity).single { it.tag == "unrabbit-content" }
        assertEquals(0f, opening.alpha, .001f)
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        tap(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        val menu = views(activity).single { it.tag == "unrabbit-content" }
        assertEquals(1f, menu.alpha, .001f)
        assertEquals(0f, menu.translationY, .001f)
        assertEquals(2, views(activity).filterIsInstance<MenuIconView>().size)
        assertSame(logo, views(activity).filterIsInstance<LogoView>().single())
        @Suppress("DEPRECATION")
        activity.onBackPressed() // Menu → chat.
        @Suppress("DEPRECATION")
        activity.onBackPressed() // Chat → rest.
        @Suppress("DEPRECATION")
        activity.onBackPressed() // Rest stays inside Unrabbit.
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun languageCanBeChosenDuringSetupAndChangedInSettingsWithoutPairingOrHistoryLoss() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Sprache · English")
        click(activity, "Deutsch")
        assertEquals(SpeechLanguage.GERMAN, SpeechPreferences(activity).language)
        assertTrue(views(activity).filterIsInstance<Button>().any { it.text == "VM verbinden" })
        assertFalse(PairingStore(activity).hasPairing())
        click(activity, "Demo öffnen")
        openSettings(activity)
        click(activity, "Sprache · Deutsch")
        click(activity, "English")
        assertTrue(views(activity).any { it is SettingsView })
        assertEquals(SpeechLanguage.ENGLISH, SpeechPreferences(activity).language)
        assertTrue(LocalStorage(activity).load()!!.messages.isEmpty())
        controller.pause().stop().destroy()
    }

    @Test fun deniedMicrophoneDoesNotSubmitTextOrConsumePhotoDrafts() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Demo öffnen")
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        down(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        up(activity)
        activity.onRequestPermissionsResult(73, arrayOf(android.Manifest.permission.RECORD_AUDIO),
            intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        assertTrue(LocalStorage(activity).load()!!.messages.isEmpty())
        assertTrue(views(activity).filterIsInstance<TextView>().any { it.text.contains("Mikrofonzugriff fehlt") })
        controller.pause().stop().destroy()
    }
}

/** Existing UI scenarios use an injected recognizer, never a production simulated speech path. */
class SpeechTestApplication : UnrabbitApplication() {
    internal override fun createSpeechInput(): SpeechInput = object : SpeechInput {
        private var listener: ((SpeechEvent) -> Unit)? = null
        override fun start(language: SpeechLanguage, listener: (SpeechEvent) -> Unit) {
            this.listener = listener; listener(SpeechEvent.Recording)
        }
        override fun finish() {
            val callback = listener ?: return
            listener = null
            callback(SpeechEvent.Transcribing)
            callback(SpeechEvent.Text("Ich habe eine Idee für ein kleines Radio."))
        }
        override fun cancel() { listener = null }
        override fun close() = cancel()
    }
}
