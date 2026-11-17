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
    private enum class Page { WELCOME, CHAT, SETTINGS, PAIRING, NETWORK, CAMERA, LANGUAGE }
    private val inkColor = 0xfff2f0e8.toInt()
    private val mutedColor = 0xffafb4a8.toInt()
    private val surfaceColor = android.graphics.Color.BLACK
    private val handler = Handler(Looper.getMainLooper())
    private val gesture = ButtonGesture()
    private lateinit var storage: LocalStorage
    private lateinit var session: ChatSession
    private lateinit var transport: ChatTransport
    private lateinit var photos: PhotoStore
    private lateinit var speech: SpeechInput
    private lateinit var replySpeech: ReplySpeech
    private var replyToSpeak: Pair<String, SpeechLanguage>? = null
    private var reading = false
    private lateinit var speechPreferences: SpeechPreferences
    private var languageReturn = Page.WELCOME
    private var transcribing = false
    private val pairingIo = Executors.newSingleThreadExecutor()
    @Volatile private var pairingApi: SshApi? = null
    private var pairingGeneration = 0
    private val photoIo = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    private var content: FrameLayout? = null
    private var outgoingContent: View? = null
    private var renderedPage: Page? = null
    private var renderedMenu = false
    private var settingsView: SettingsView? = null
    private var pairingView: PairingView? = null
    private var pairingReturn = Page.WELCOME
    private var networkView: NetworkSetupView? = null
    private var networkReturn = Page.WELCOME
    private var cameraView: PhotoCameraView? = null
    private var returnToRestAfterCamera = false
    private var resumed = false
    private val surface get() = requireNotNull(content)
    private lateinit var regular: Typeface
    private lateinit var touchSurface: View
    private lateinit var statusRail: StatusRailView
    private lateinit var deviceStatus: DeviceStatusMonitor
    private var feed: DeviceScrollView? = null
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
        speechPreferences = SpeechPreferences(this)
        speech = (application as UnrabbitApplication).createSpeechInput()
        replySpeech = (application as UnrabbitApplication).createReplySpeech()
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        transport = TransportFactory.create(this, photos)
        val snapshot = storage.load()
        val recover = transport.identity != null && snapshot?.backendId == transport.identity
        session = ChatSession(snapshot, resumePending = recover)
        if (session.interruptedOnRestore && !session.pending()) notice = "Previous request interrupted. It will not be retried automatically."
        if (storage.readFailed) notice = "Saved chat could not be loaded."
        save() // Also commits the one-time legacy migration; its source file stays intact.
        PhotoCleanupService.schedule(this) // Persist legacy photo ages before background deletion can start.
        root = FrameLayout(this).apply {
            setBackgroundColor(surfaceColor)
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        touchSurface = View(this).apply {
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
        root.addView(touchSurface, FrameLayout.LayoutParams(-1, -1))
        statusRail = StatusRailView(this).apply {
            tag = "unrabbit-status"
            onProgress = { updateChatVisibility(animate = false) }
        }
        root.addView(statusRail, FrameLayout.LayoutParams(-1, -1))
        observeVmConnection()
        deviceStatus = DeviceStatusMonitor(this) { statusRail.status = it }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        page = if (storage.preferences.getBoolean("demoAcknowledged", false)) Page.CHAT else Page.WELCOME
        render()
        session.activeRequests.forEach(::send)
    }

    override fun onStart() { super.onStart(); deviceStatus.start() }
    override fun onStop() { deviceStatus.stop(); super.onStop() }

    override fun onResume() {
        super.onResume()
        resumed = true
        photoIo.execute { photos.pruneExpired() }
        transport.resume()
        DeviceMode.enterIfManaged(this)
        DeviceMode.hideSystemBars(this)
        pairingView?.resumeCamera()
        cameraView?.resumeCamera()
        updateStatus(animateStatus = false)
    }

    override fun onPause() {
        resumed = false
        feed?.stopWheelScroll()
        cancelReply()
        transport.pause()
        if (pairingView?.isPairing == true) { cancelPairing(); pairingView?.showError("Interrupted. Scan again to retry.") }
        pairingView?.pauseCamera()
        cameraView?.pauseCamera()
        abortPress()
        cancelSpeech()
        save()
        if (page == Page.CHAT) {
            resting = true
            if (menu) { menu = false; render(animate = false) }
        }
        updateStatus(animateStatus = false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { abortPress(); cancelSpeech(); cancelReply() }
        else DeviceMode.hideSystemBars(this)
    }

    override fun onDestroy() {
        cameraView?.closeCamera()
        pairingView?.closeCamera()
        cancelPairing()
        speech.close()
        replySpeech.close()
        pairingIo.shutdownNow()
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
        if (page != Page.CHAT || menu) cancelReply()
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
        pairingView = null
        networkView = null
        feed = null
        chatNotice = null
        statusRail.visibility = if (page in listOf(Page.WELCOME, Page.CAMERA)) View.GONE else View.VISIBLE
        when (page) {
            Page.WELCOME -> renderWelcome()
            Page.CHAT -> renderChat(follow)
            Page.SETTINGS -> renderSettings()
            Page.PAIRING -> renderPairing()
            Page.NETWORK -> renderNetwork()
            Page.CAMERA -> renderCamera()
            Page.LANGUAGE -> renderLanguage()
        }
        if (menu) renderMenu()
        if (selectedIcon != null) navigation.indexOfFirst { (it.first as? MenuIconView)?.icon == selectedIcon }
            .takeIf { it >= 0 }?.let { selection = it }
        updateStatus()
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
        column.addView(text("Connect to Wi-Fi or mobile data.\nThen pair your VM.", 16f, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(24))
        })
        column.addView(navButton("Wi-Fi & SIM") { openNetwork() })
        column.addView(navButton("Language · ${speechPreferences.language.label}") { openLanguage() })
        column.addView(navButton("Connect VM") { openPairing() })
        if (TransportFactory.supportsDemo) column.addView(navButton("Open demo") {
            storage.preferences.edit().putBoolean("demoAcknowledged", true).apply()
            page = Page.CHAT
            selection = 0
            render()
        })
        column.addView(text("Hold the side button to speak.", 12f, true).apply { setPadding(dp(12), dp(12), 0, 0) })
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
                contentDescription = "${if (message.role == Role.USER) "You" else "Unrabbit"}: ${message.text}"
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
        val notice = notice ?: when {
            recording -> "Listening…"
            transcribing -> "Transcribing…"
            session.pending() -> "Thinking…"
            empty -> "Hold the side button to speak."
            else -> null
        }
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
            contentDescription = "Close menu"
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
        if (reading) { cancelReply(); return }
        if (transcribing) { cancelSpeech(showNotice = true); return }
        if (pairingView?.isScanning == true) return
        if (page == Page.CAMERA) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cameraView?.takePhoto()
            else requestPermissions(arrayOf(Manifest.permission.CAMERA), 72)
            return
        }
        if (page == Page.CHAT && resting) {
            resting = false
            updateStatus()
            return
        }
        if (navigation.isNotEmpty()) navigation[selection.coerceIn(navigation.indices)].second()
        else { menu = true; selection = 0; render() }
    }

    private fun observeVmConnection() {
        val client = transport
        if (client is VmTransport) client.observeConnection { state ->
            if (transport === client) {
                statusRail.vmState = state
                settingsView?.setConnectionState(state)
            }
        } else statusRail.vmState = if (PairingStore(this).hasPairing())
            VmConnectionState.DISCONNECTED else VmConnectionState.UNPAIRED
    }

    private fun renderSettings() {
        val paired = runCatching { PairingStore(this).load() }.getOrNull()
        settingsView = SettingsView(this, paired != null,
            onClose = { closeSettings() }, onConnect = { openPairing() },
            onNetwork = { openNetwork() }, onLanguage = { openLanguage() },
            language = speechPreferences.language.label,
            readAloud = speechPreferences.readAloud,
            onReadAloud = {
                speechPreferences.readAloud = !speechPreferences.readAloud
                cancelReply(); render(animate = false)
            }).also { panel ->
            panel.onSelection = { selection = it; highlightSelection() }
            navigation += panel.navigation
            panel.setConnectionState(statusRail.vmState)
            surface.addView(panel, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun openLanguage() {
        languageReturn = page
        page = Page.LANGUAGE; selection = 0; render()
    }

    private fun renderLanguage() {
        val close = { page = languageReturn; selection = 0; render() }
        val panel = SpeechLanguageView(this, speechPreferences.language, { language ->
            speechPreferences.language = language
            close()
        }, close)
        panel.onSelection = { selection = it; highlightSelection() }
        navigation += panel.navigation
        surface.addView(panel, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openPairing() {
        pairingReturn = page
        page = Page.PAIRING; menu = false; selection = 0; render()
        requestSetupScan()
    }

    private fun renderPairing() {
        pairingView = PairingView(this, ::pairVm, ::closePairing, ::requestSetupScan).also { panel ->
            panel.onSelection = { selection = it; highlightSelection() }
            panel.onNavigationChanged = {
                navigation.clear(); navigation += panel.navigation
                selection = 0; highlightSelection(); updateStatus(); DeviceMode.hideSystemBars(this)
                if (panel.isScanning) abortPress()
            }
            navigation += panel.navigation
            surface.addView(panel, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun closePairing() {
        cancelPairing()
        page = pairingReturn; selection = 0; render()
    }

    private fun openNetwork() {
        networkReturn = page
        page = Page.NETWORK; selection = 0; render()
    }

    private fun renderNetwork() {
        networkView = NetworkSetupView(this,
            onClose = { closeNetwork() },
            onSystemSettings = { mobile ->
                if (!DeviceMode.openNetworkSettings(this, mobile))
                    Toast.makeText(this, "Network settings unavailable.", Toast.LENGTH_SHORT).show()
            }).also { panel ->
            panel.onSelection = { selection = it; highlightSelection() }
            panel.onNavigationChanged = {
                navigation.clear(); navigation += panel.navigation
                selection = 0; highlightSelection()
            }
            navigation += panel.navigation
            surface.addView(panel, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun closeNetwork() { page = networkReturn; selection = 0; render() }

    private fun cancelPairing() {
        pairingGeneration++
        pairingApi?.close(); pairingApi = null
    }

    private fun pairVm(invitation: PairingQr) {
        val panel = pairingView ?: return
        if (session.pending()) { panel.showError("Finish the current request first."); return }
        cancelPairing()
        val generation = pairingGeneration
        val store = PairingStore(this)
        val vm = PairedVm(invitation.connection, invitation.serverId)
        val api = SshApi(vm, store::privateKey)
        pairingApi = api
        panel.setPairing(true)
        pairingIo.execute {
            val error = try { api.pair(invitation); null }
            catch (failure: HostIdentityFailure) { failure.message }
            catch (failure: PairingFailure) { failure.message }
            catch (_: Exception) { "Connection failed. Check the VM wizard and network, then scan again." }
            finally { api.close() }
            handler.post {
                if (isDestroyed || generation != pairingGeneration || pairingView !== panel) return@post
                pairingApi = null
                if (error != null) { panel.showError(error); return@post }
                try { store.save(vm) }
                catch (_: Exception) { panel.showError("Pairing could not be saved. Scan again."); return@post }
                transport.close()
                transport = TransportFactory.create(this, photos)
                observeVmConnection()
                if (resumed) transport.resume()
                storage.preferences.edit().putBoolean("demoAcknowledged", true).apply()
                save()
                notice = "Connected"; noticeRevision++
                page = Page.CHAT; menu = false; resting = false; selection = 0
                render()
            }
        }
    }

    private fun requestSetupScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) pairingView?.openScanner()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 71)
    }

    private fun openCamera() {
        if (page != Page.CHAT || menu || recording || transcribing) return
        if (session.draftPhotos.size >= 4) {
            resting = false; notice = "Send your photos with a message first."; noticeRevision++; render(); return
        }
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
        if (requestCode == 71 && page == Page.PAIRING) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) pairingView?.openScanner()
            else pairingView?.showError("Allow camera access to scan.")
        }
        if (requestCode == 72 && page == Page.CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && resumed) cameraView?.resumeCamera()
            else cameraView?.showError("Camera access required.")
        }
        if (requestCode == 73 && page == Page.CHAT) {
            notice = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                "Hold the side button again to speak." else "Microphone access required. Allow it to speak."
            noticeRevision++; resting = false; render()
        }
        DeviceMode.hideSystemBars(this)
    }

    private fun closeSettings() {
        val acknowledged = storage.preferences.getBoolean("demoAcknowledged", false)
        page = if (acknowledged) Page.CHAT else Page.WELCOME
        menu = acknowledged; selection = 0; render()
    }

    private fun submit(text: String) {
        if (session.draftPhotos.size > 4 && transport.identity != null) {
            notice = "Up to four photos per request."; noticeRevision++; render(); return
        }
        notice = null; noticeRevision++
        val request = session.submit(text)
        cancelReply()
        if (speechPreferences.readAloud && resumed) replyToSpeak = request.id to speechPreferences.language
        render(follow = true)
        if (save()) send(request)
        else { session.stopped(request.id); render() }
    }

    private fun send(request: ChatRequest) {
        transport.send(request) onResult@{ result ->
            if (!session.isActive(request.id)) return@onResult
            val follow = page == Page.CHAT && atBottom()
            when (result) {
                is TransportResult.Reply -> {
                    session.reply(request.id, result.text)
                    val spoken = replyToSpeak
                    if (spoken?.first == request.id) {
                        replyToSpeak = null
                        if (resumed && page == Page.CHAT && !menu && !recording && !transcribing && speechPreferences.readAloud) {
                            replySpeech.speak(result.text, spoken.second) { state ->
                                if (!resumed || isDestroyed) return@speak
                                reading = state == ReplySpeechState.PREPARING || state == ReplySpeechState.PLAYING
                                if (state == ReplySpeechState.FAILED && notice == null) {
                                    notice = "Read aloud unavailable. The reply is in the chat."; noticeRevision++
                                    if (page == Page.CHAT) render()
                                }
                            }
                        }
                    }
                }
                is TransportResult.Failure -> {
                    session.stopped(request.id)
                    notice = result.message; noticeRevision++
                }
                is TransportResult.Waiting -> { notice = result.message; noticeRevision++ }
            }
            save()
            if (page == Page.CHAT) render(follow && !menu) else updateStatus()
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
        notice = "Stopping …"
        render()
        requests.forEach { request ->
            transport.cancel(request.id) { acknowledged ->
                if (acknowledged) stopped = session.stopped(request.id) || stopped
                else if (session.isActive(request.id)) unconfirmed = true
                remaining--
                if (remaining == 0 && revision == noticeRevision) notice = when {
                    unconfirmed -> "Stop not confirmed."
                    stopped -> "Stopped."
                    else -> null
                }
                save()
                if (page == Page.CHAT) render() else updateStatus()
            }
        }
    }

    private fun updateStatus(animateStatus: Boolean = true) {
        val cameraVisible = page == Page.CAMERA || pairingView?.isScanning == true
        statusRail.visibility = if (page == Page.WELCOME || cameraVisible) View.GONE else View.VISIBLE
        touchSurface.importantForAccessibility = if (page == Page.CHAT && !menu)
            View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        touchSurface.contentDescription = if (resting) "Open chat" else "Hold to speak, tap for menu"
        statusRail.setCompact(!resting || recording || menu || page != Page.CHAT,
            animate = animateStatus)
        updateChatVisibility(animate = true)

    }

    private fun updateChatVisibility(animate: Boolean) {
        // Text appears only once the moving glyphs have entered the original left gutter.
        val visibleFraction = ((statusRail.compactFraction - .96f) / .04f).coerceIn(0f, 1f)
        listOfNotNull(feed, chatNotice).forEach { view ->
            val showText = !menu && !resting && (view === chatNotice || !recording)
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
        cancelReply() // Stop before the hold threshold, so speaker audio never enters the recording.
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
                    if (page != Page.CHAT || transcribing || !resumed) return@forEach
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 73)
                        return@forEach
                    }
                    if (menu) { menu = false; render() }
                    returnToRestAfterRecording = resting
                    resting = false
                    speech.start(speechPreferences.language, ::speechEvent)
                }
                GestureAction.SEND_RECORDING -> if (recording) {
                    speech.finish()
                }
                GestureAction.CANCEL_RECORDING -> {
                    if (recording) resting = returnToRestAfterRecording
                    cancelSpeech()
                }
            }
        }
    }

    private fun speechEvent(event: SpeechEvent) {
        if (isDestroyed || !resumed || page != Page.CHAT) return
        when (event) {
            SpeechEvent.Recording -> { recording = true; notice = null; noticeRevision++; render(animate = false) }
            SpeechEvent.Transcribing -> {
                recording = false; transcribing = true
                notice = "Transcribing …"; noticeRevision++; render(animate = false)
            }
            is SpeechEvent.Text -> { recording = false; transcribing = false; submit(event.text) }
            SpeechEvent.NoSpeech, SpeechEvent.Failed, SpeechEvent.TooLong, SpeechEvent.Unavailable -> {
                recording = false; transcribing = false; resting = false
                notice = when (event) {
                    SpeechEvent.NoSpeech -> "No speech detected."
                    SpeechEvent.TooLong -> "Recording too long. Speak for up to 30 seconds."
                    SpeechEvent.Unavailable -> "VM speech recognition unavailable. Check the connection and VM setup."
                    else -> "Speech recognition failed. Try speaking again."
                }
                noticeRevision++; render(animate = false)
            }
        }
    }

    private fun cancelSpeech(showNotice: Boolean = false) {
        val wasActive = recording || transcribing
        speech.cancel()
        recording = false; transcribing = false
        if (wasActive) {
            notice = if (showNotice) "Recording discarded." else null
            noticeRevision++
            if (page == Page.CHAT) render(animate = false) else updateStatus()
        }
    }

    private fun cancelReply() {
        replyToSpeak = null
        reading = false
        if (::replySpeech.isInitialized) replySpeech.cancel()
    }

    private fun rotate(amount: Float) {
        if (amount == 0f || page == Page.CAMERA) return
        if (pairingView?.isScanning == true) return
        if (gesture.held) { actions(gesture.rotate()); scheduleGesture(); return }
        actions(gesture.flushClick()); scheduleGesture()
        if (resting && page == Page.CHAT) { resting = false; updateStatus(); return }
        if (navigation.isNotEmpty()) {
            selection = (selection + amount.sign.toInt()).coerceIn(navigation.indices)
            highlightSelection()
            navigation[selection].first.let { it.requestRectangleOnScreen(android.graphics.Rect(0, 0, it.width, it.height)) }
        } else feed?.scrollByWheel((amount * dp(48)).roundToInt())
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
        // Text input keeps ordinary cursor/IME keys; the physical side button remains F8.
        if (networkView?.editingPassword == true && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_ENTER))
            return super.dispatchKeyEvent(event)
        // R1's physical keycode is deliberately not assumed. Never intercept POWER.
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_F8 -> {
                if (pairingView?.isScanning == true) return true
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0 && buttonKey == null) {
                        buttonKey = event.keyCode
                        if (page !in listOf(Page.SETTINGS, Page.PAIRING, Page.NETWORK, Page.CAMERA, Page.LANGUAGE)) press()
                    }
                } else if (event.action == KeyEvent.ACTION_UP && buttonKey == event.keyCode) {
                    buttonKey = null
                    if (event.isCanceled) abortPress()
                    else if (page in listOf(Page.SETTINGS, Page.PAIRING, Page.NETWORK, Page.CAMERA, Page.LANGUAGE)) shortClick()
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
            reading -> cancelReply()
            transcribing -> cancelSpeech(showNotice = true)
            page == Page.CAMERA -> closeCamera()
            menu -> { menu = false; render() }
            page == Page.SETTINGS -> closeSettings()
            page == Page.PAIRING -> closePairing()
            page == Page.NETWORK -> { if (networkView?.handleBack() != true) closeNetwork() }
            page == Page.LANGUAGE -> { page = languageReturn; selection = 0; render() }
            page == Page.CHAT && !resting -> { resting = true; updateStatus() }
            else -> Unit // The rest screen is the device home; Back must not expose Android.
        }
    }

    private fun save(): Boolean {
        return try { storage.save(session.snapshot().copy(backendId = transport.identity)); true }
        catch (_: Exception) { notice = "Chat could not be saved."; noticeRevision++; false }
    }
}
