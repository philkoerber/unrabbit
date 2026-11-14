package dev.unrabbit.app

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import android.window.OnBackInvokedDispatcher
import dev.unrabbit.core.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.sign

class MainActivity : Activity() {
    private enum class Page { WELCOME, CHAT, SETTINGS, CAMERA }
    private val inkColor = 0xfff2f0e8.toInt()
    private val mutedColor = 0xffafb4a8.toInt()
    private val surfaceColor = 0xff171915.toInt()
    private val handler = Handler(Looper.getMainLooper())
    private val gesture = ButtonGesture()
    private lateinit var storage: LocalStorage
    private lateinit var session: ChatSession
    private lateinit var transport: ChatTransport
    private lateinit var photos: PhotoStore
    private val photoIo = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    private var content: FrameLayout? = null
    private var outgoingContent: View? = null
    private var renderedPage: Page? = null
    private var renderedMenu = false
    private var settingsView: SettingsView? = null
    private var cameraView: PhotoCameraView? = null
    private var returnToRestAfterCamera = false
    private var resumed = false
    private val surface get() = requireNotNull(content)
    private lateinit var regular: Typeface
    private var logo: LogoView? = null
    private lateinit var statusRail: StatusRailView
    private lateinit var deviceStatus: DeviceStatusMonitor
    private var feed: ScrollView? = null
    private var chatNotice: View? = null
    private var position: Int? = null
    private var notice: String? = null
    private var noticeRevision = 0
    private val navigation = mutableListOf<Pair<View, () -> Unit>>()
    private var selection = 0
    private var page = Page.WELCOME
    private var menu = false
    private var recording = false
    private var resting = true
    private var returnToRestAfterRecording = false
    private var buttonKey: Int? = null
    private val holdCheck = Runnable { actions(gesture.tick(SystemClock.uptimeMillis())); scheduleGesture() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        regular = resources.getFont(R.font.plex_regular)
        storage = LocalStorage(this)
        photos = PhotoStore(this)
        session = ChatSession(storage.load())
        transport = DemoTransport()
        if (session.interruptedOnRestore) notice = "Demo unterbrochen. Bei Bedarf erneut senden."
        if (storage.readFailed) notice = "Gespeicherte Demo konnte nicht geladen werden."
        save() // Also commits the one-time legacy migration; its source file stays intact.
        root = FrameLayout(this).apply {
            setBackgroundColor(surfaceColor)
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        logo = LogoView(this).apply {
            tag = "unrabbit-background"
            setOnClickListener { shortClick() }
            setOnTouchListener { _, event ->
                if (page != Page.CHAT || menu) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { press(); true }
                    MotionEvent.ACTION_UP -> { release(); true }
                    MotionEvent.ACTION_CANCEL -> { abortPress(); true }
                    else -> true
                }
            }
        }
        root.addView(logo, FrameLayout.LayoutParams(-1, -1))
        statusRail = StatusRailView(this).apply {
            tag = "unrabbit-status"
            onProgress = { progress ->
                logo?.restFraction = 1 - progress
                updateChatVisibility(animate = false)
            }
        }
        root.addView(statusRail, FrameLayout.LayoutParams(-1, -1))
        deviceStatus = DeviceStatusMonitor(this) { statusRail.status = it }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        page = if (storage.preferences.getBoolean("demoAcknowledged", false)) Page.CHAT else Page.WELCOME
        render()
    }

    override fun onStart() { super.onStart(); deviceStatus.start() }
    override fun onStop() { deviceStatus.stop(); super.onStop() }

    override fun onResume() {
        super.onResume()
        resumed = true
        DeviceMode.enterIfManaged(this)
        DeviceMode.hideSystemBars(this)
        settingsView?.resumeCamera()
        cameraView?.resumeCamera()
        updateLogo(animateStatus = false)
    }

    override fun onPause() {
        resumed = false
        logo?.setPlaybackActive(false)
        settingsView?.pauseCamera()
        cameraView?.pauseCamera()
        abortPress()
        save()
        if (page == Page.CHAT) {
            resting = true
            if (menu) { menu = false; render(animate = false) }
        }
        updateLogo(animateStatus = false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) abortPress()
        else DeviceMode.hideSystemBars(this)
    }

    override fun onDestroy() {
        cameraView?.closeCamera()
        photoIo.shutdown()
        handler.removeCallbacksAndMessages(null)
        content?.animate()?.cancel()
        outgoingContent?.let { it.animate().cancel(); root.overlay.remove(it) }
        transport.close()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun text(value: String, size: Float = 20f, muted: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(if (muted) mutedColor else inkColor)
        typeface = regular
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun rememberPosition() { feed?.takeIf { it.height > 0 }?.let { position = it.scrollY } }
    private fun atBottom(): Boolean {
        val scroll = feed ?: return true
        val child = scroll.getChildAt(0) ?: return true
        return child.height - scroll.height - scroll.scrollY <= dp(24)
    }

    private fun motionEnabled() = ValueAnimator.areAnimatorsEnabled()

    private fun render(follow: Boolean = false, animate: Boolean = true) {
        rememberPosition()
        cameraView?.closeCamera(); cameraView = null
        val selectedIcon = if (renderedMenu && menu) (navigation.getOrNull(selection)?.first as? MenuIconView)?.icon else null
        outgoingContent?.let { it.animate().cancel(); root.overlay.remove(it) }
        outgoingContent = null
        val previous = content
        val transition = animate && previous != null && motionEnabled() &&
            (renderedPage != page || renderedMenu != menu)
        previous?.let { it.animate().cancel(); root.removeView(it) }
        content = FrameLayout(this).apply { tag = "unrabbit-content" }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        navigation.clear()
        settingsView = null
        feed = null
        chatNotice = null
        statusRail.visibility = if (page == Page.WELCOME || page == Page.CAMERA) View.GONE else View.VISIBLE
        when (page) {
            Page.WELCOME -> renderWelcome()
            Page.CHAT -> renderChat(follow)
            Page.SETTINGS -> renderSettings()
            Page.CAMERA -> renderCamera()
        }
        if (menu) renderMenu()
        if (selectedIcon != null) navigation.indexOfFirst { (it.first as? MenuIconView)?.icon == selectedIcon }
            .takeIf { it >= 0 }?.let { selection = it }
        updateLogo()
        highlightSelection()
        root.requestFocus()
        renderedPage = page
        renderedMenu = menu
        if (transition) {
            // An overlay retains the outgoing pixels without retaining touch or accessibility actions.
            previous.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            root.overlay.add(previous)
            outgoingContent = previous
            previous.animate().alpha(0f).setDuration(160).withEndAction {
                root.overlay.remove(previous)
                if (outgoingContent === previous) outgoingContent = null
            }.start()
            surface.alpha = 0f
            surface.translationY = dp(if (menu) 12 else if (page == Page.CHAT) 0 else 8).toFloat()
            surface.animate().alpha(1f).translationY(0f).setDuration(240)
                .setInterpolator(PathInterpolator(.22f, .7f, .18f, 1f)).start()
        }
    }

    private fun renderWelcome() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        column.addView(text("Unrabbit", 28f))
        column.addView(text("Lokale Demo\nKeine Mikrofonaufnahme oder VM-Verbindung.", 16f, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(24))
        })
        column.addView(navButton("Demo öffnen") {
            storage.preferences.edit().putBoolean("demoAcknowledged", true).apply()
            page = Page.CHAT
            selection = 0
            render()
        })
        surface.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    }

    private fun renderChat(follow: Boolean) {
        val empty = session.messages.isEmpty()
        val scroll = DeviceScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            defaultFocusHighlightEnabled = false
        }
        val messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        session.messages.forEach { message ->
            message.photos.forEach { photo ->
                val width = resources.displayMetrics.widthPixels - dp(76)
                val height = (width * photo.height.toFloat() / photo.width).roundToInt().coerceAtMost(dp(240))
                messages.addView(PhotoMessageView(this, photo, photos, photoIo), LinearLayout.LayoutParams(-1, height).apply {
                    leftMargin = dp(28); bottomMargin = dp(24)
                })
            }
            if (message.text.isNotEmpty()) messages.addView(text(message.text, if (message.role == Role.USER) 20f else 22f, message.role == Role.USER).apply {
                gravity = if (message.role == Role.USER) Gravity.END else Gravity.START
                setPadding(if (message.role == Role.USER) dp(28) else 0, 0, 0, dp(24))
                contentDescription = "${if (message.role == Role.USER) "Du" else "Unrabbit"}: ${message.text}"
                isFocusable = false
                isClickable = false
            }, LinearLayout.LayoutParams(-1, -2))
        }
        scroll.addView(messages)
        scroll.visibility = if (empty) View.GONE else View.VISIBLE
        surface.addView(scroll, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            topMargin = dp(28); leftMargin = dp(24); rightMargin = dp(24); bottomMargin = dp(24)
        })
        feed = scroll
        val savedPosition = position
        scroll.post { scroll.scrollTo(0, if (follow || savedPosition == null) messages.height - scroll.height else savedPosition) }
        val notice = notice
        if (notice != null) {
            val status = text(notice, 14f, true).apply {
                setBackgroundColor(surfaceColor)
                setPadding(dp(24), dp(8), dp(24), dp(12))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            surface.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            chatNotice = status
            (scroll.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(58)
        }
    }

    private fun renderMenu() {
        navigation.clear()
        feed?.visibility = View.INVISIBLE
        chatNotice?.visibility = View.INVISIBLE
        surface.addView(View(this).apply {
            contentDescription = "Menü schließen"
            setOnClickListener { menu = false; render() }
        }, FrameLayout.LayoutParams(-1, -1))
        val column = LinearLayout(this).apply {
            tag = "unrabbit-menu"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        fun item(icon: MenuIcon, action: () -> Unit) {
            val button = MenuIconView(this, icon).apply { setOnClickListener { action() } }
            navigation += button to action
            column.addView(button, LinearLayout.LayoutParams(dp(72), dp(64)))
        }
        item(MenuIcon.SETTINGS) { menu = false; page = Page.SETTINGS; selection = 0; render() }
        if (session.pending()) item(MenuIcon.STOP) { stopPending() }
        item(MenuIcon.CLOSE) { menu = false; render() }
        surface.addView(column, FrameLayout.LayoutParams(dp(72), -2, Gravity.CENTER))
    }

    private fun navButton(label: String, action: () -> Unit): Button {
        val button = Button(this).apply {
            text = label
            typeface = regular
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(getColorStateList(R.color.selection_text))
            setBackgroundColor(Color.TRANSPARENT)
            defaultFocusHighlightEnabled = false
            isAllCaps = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { action() }
        }
        navigation += button to action
        return button
    }

    private fun highlightSelection() {
        selection = selection.coerceIn(0, (navigation.size - 1).coerceAtLeast(0))
        navigation.forEachIndexed { index, (button, _) ->
            button.isSelected = index == selection
        }
    }

    private fun shortClick() {
        if (settingsView?.handleButton() == true) return
        if (page == Page.CAMERA) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cameraView?.takePhoto()
            else requestPermissions(arrayOf(Manifest.permission.CAMERA), 72)
            return
        }
        if (page == Page.CHAT && resting) {
            resting = false
            updateLogo()
            return
        }
        if (navigation.isNotEmpty()) navigation[selection.coerceIn(navigation.indices)].second()
        else { menu = true; selection = 0; render() }
    }

    private fun renderSettings() {
        settingsView = SettingsView(this, storage.connection(),
            onSave = { connection ->
                if (connection != null) storage.saveConnection(connection)
                else storage.preferences.edit().remove("vmHost").remove("vmPort").remove("vmUser").remove("vmHostKeyFingerprint").apply()
                page = Page.CHAT; menu = false; selection = 0; render()
            }, onClose = { closeSettings() }, onRequestScan = { requestSetupScan() }).also { panel ->
            panel.onSelection = { selection = it; highlightSelection() }
            panel.onNavigationChanged = { position ->
                navigation.clear(); navigation += panel.navigation
                selection = position; highlightSelection(); updateLogo(); DeviceMode.hideSystemBars(this)
                if (panel.isScanning) abortPress()
            }
            navigation += panel.navigation
            surface.addView(panel, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun requestSetupScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) settingsView?.openScanner()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 71)
    }

    private fun openCamera() {
        if (page != Page.CHAT || menu) return
        returnToRestAfterCamera = resting
        page = Page.CAMERA; selection = 0
        render()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 72)
    }

    private fun renderCamera() {
        cameraView = PhotoCameraView(this, photos, photoIo, { photo -> attachPhoto(photo) }, { closeCamera() }).also { camera ->
            surface.addView(camera, FrameLayout.LayoutParams(-1, -1))
            if (resumed) camera.resumeCamera()
        }
    }

    private fun attachPhoto(photo: PhotoAttachment) {
        if (page != Page.CAMERA) { photos.discard(photo); return }
        session.addPhoto(photo)
        notice = null; noticeRevision++
        page = Page.CHAT; resting = false; selection = 0
        save(); render(follow = true)
    }

    private fun closeCamera() {
        page = Page.CHAT; resting = returnToRestAfterCamera; selection = 0
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 71 && page == Page.SETTINGS) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) settingsView?.openScanner()
            else settingsView?.showError("Kamerazugriff fehlt.")
        }
        if (requestCode == 72 && page == Page.CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && resumed) cameraView?.resumeCamera()
            else cameraView?.showError("Kamerazugriff fehlt.")
        }
        DeviceMode.hideSystemBars(this)
    }

    private fun closeSettings() {
        page = Page.CHAT; menu = true; selection = 0; render()
    }

    private fun submit(text: String) {
        notice = null; noticeRevision++
        val request = session.submit(text)
        render(follow = true)
        save()
        send(request)
    }

    private fun send(request: ChatRequest) {
        transport.send(request) onResult@{ result ->
            if (!session.isActive(request.id)) return@onResult
            val follow = page == Page.CHAT && atBottom()
            when (result) {
                is TransportResult.Reply -> session.reply(request.id, result.text)
                is TransportResult.Failure -> {
                    session.stopped(request.id)
                    notice = result.message; noticeRevision++
                }
            }
            save()
            if (page == Page.CHAT) render(follow && !menu) else updateLogo()
        }
    }

    private fun stopPending() {
        val requests = session.activeRequests
        menu = false
        if (requests.isEmpty()) { render(); return }
        val revision = ++noticeRevision
        var remaining = requests.size
        var unconfirmed = false
        var stopped = false
        notice = "Anhalten angefragt …"
        render()
        requests.forEach { request ->
            transport.cancel(request.id) { acknowledged ->
                if (acknowledged) stopped = session.stopped(request.id) || stopped
                else if (session.isActive(request.id)) unconfirmed = true
                remaining--
                if (remaining == 0 && revision == noticeRevision) notice = when {
                    unconfirmed -> "Anhalten nicht bestätigt."
                    stopped -> "Demo angehalten."
                    else -> null
                }
                save()
                if (page == Page.CHAT) render() else updateLogo()
            }
        }
    }

    private fun updateLogo(animateStatus: Boolean = true) {
        val cameraVisible = page == Page.CAMERA || settingsView?.isScanning == true
        statusRail.visibility = if (page == Page.WELCOME || cameraVisible) View.GONE else View.VISIBLE
        logo?.setPlaybackActive(resumed && !cameraVisible)
        logo?.importantForAccessibility = if (cameraVisible) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        val textVisible = page != Page.CHAT || session.messages.isNotEmpty() || notice != null || menu
        logo?.foregroundVisible = textVisible && !recording && (page != Page.CHAT || !resting || menu)
        statusRail.setCompact(!resting || recording || menu || page != Page.CHAT,
            animate = animateStatus)
        updateChatVisibility(animate = true)
        logo?.setMode(when {
            recording -> LogoMode.LISTENING
            page == Page.CHAT && session.pending() -> LogoMode.WAITING
            else -> LogoMode.QUIET
        })
    }

    private fun updateChatVisibility(animate: Boolean) {
        val showText = !recording && !menu && !resting
        // Text appears only once the moving glyphs have entered the original left gutter.
        val visibleFraction = ((statusRail.compactFraction - .96f) / .04f).coerceIn(0f, 1f)
        listOfNotNull(feed, chatNotice).forEach { view ->
            view.animate().cancel()
            if (view.visibility != View.GONE) view.visibility = if (resting || menu) View.INVISIBLE else View.VISIBLE
            val alpha = if (showText) visibleFraction else 0f
            if (!animate || !ValueAnimator.areAnimatorsEnabled()) view.alpha = alpha
            else view.animate().alpha(alpha).setDuration(180).start()
            view.importantForAccessibility = if (showText && visibleFraction == 1f) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    private fun press() {
        if (gesture.held) return
        actions(gesture.press(SystemClock.uptimeMillis(), page == Page.CHAT && !menu))
        scheduleGesture()
    }

    private fun release() {
        actions(gesture.release(SystemClock.uptimeMillis()))
        scheduleGesture()
    }

    private fun scheduleGesture() {
        handler.removeCallbacks(holdCheck)
        gesture.nextDeadline?.let { handler.postAtTime(holdCheck, it) }
    }

    private fun abortPress() {
        buttonKey = null
        handler.removeCallbacks(holdCheck)
        actions(gesture.cancel())
    }

    private fun actions(events: List<GestureAction>) {
        events.forEach { action ->
            when (action) {
                GestureAction.SHORT_CLICK -> shortClick()
                GestureAction.DOUBLE_CLICK -> openCamera()
                GestureAction.START_RECORDING -> {
                    if (page != Page.CHAT) return@forEach
                    if (menu) { menu = false; render() }
                    returnToRestAfterRecording = resting
                    resting = false
                    recording = true
                    updateLogo()
                }
                GestureAction.SEND_RECORDING -> if (recording) {
                    recording = false
                    submit(when (session.draftPhotos.size) {
                        0 -> "Ich habe eine Idee für ein kleines Radio."
                        1 -> "Was fällt dir auf diesem Bild auf?"
                        else -> "Was fällt dir auf diesen Bildern auf?"
                    })
                }
                GestureAction.CANCEL_RECORDING -> {
                    if (recording) resting = returnToRestAfterRecording
                    recording = false
                    updateLogo()
                }
            }
        }
    }

    private fun rotate(amount: Float) {
        if (amount == 0f || page == Page.CAMERA) return
        if (settingsView?.handleWheel(amount) == true) return
        if (gesture.held) { actions(gesture.rotate()); scheduleGesture(); return }
        actions(gesture.flushClick()); scheduleGesture()
        if (resting && page == Page.CHAT) { resting = false; updateLogo(); return }
        if (navigation.isNotEmpty()) {
            selection = (selection + amount.sign.toInt()).coerceIn(navigation.indices)
            highlightSelection()
            navigation[selection].first.let { it.requestRectangleOnScreen(android.graphics.Rect(0, 0, it.width, it.height)) }
        } else feed?.scrollBy(0, (amount * dp(48)).roundToInt())
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val rotary = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            val value = if (rotary != 0f) rotary else event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (value != 0f) { rotate(-value); return true }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // R1's physical keycode is deliberately not assumed. Never intercept POWER.
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_F8 -> {
                if (settingsView?.isScanning == true) return true
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0 && buttonKey == null) {
                        buttonKey = event.keyCode
                        if (page != Page.SETTINGS && page != Page.CAMERA) press()
                    }
                } else if (event.action == KeyEvent.ACTION_UP && buttonKey == event.keyCode) {
                    buttonKey = null
                    if (event.isCanceled) abortPress()
                    else if (page == Page.SETTINGS || page == Page.CAMERA) shortClick()
                    else release()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) rotate(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1f else 1f)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Native back handling for the development shell")
    override fun onBackPressed() = back()

    private fun back() {
        when {
            gesture.held || gesture.hasPendingClick -> abortPress()
            page == Page.CAMERA -> closeCamera()
            menu -> { menu = false; render() }
            page == Page.SETTINGS -> { if (settingsView?.handleBack() != true) closeSettings() }
            page == Page.CHAT && !resting -> { resting = true; updateLogo() }
            else -> Unit // The rest screen is the device home; Back must not expose Android.
        }
    }

    private fun save() {
        try { storage.save(session.snapshot()) }
        catch (_: Exception) { notice = "Demo konnte nicht gespeichert werden."; noticeRevision++ }
    }
}
