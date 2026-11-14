package dev.unrabbit.core

enum class GestureAction { SHORT_CLICK, DOUBLE_CLICK, START_RECORDING, SEND_RECORDING, CANCEL_RECORDING }

/** One physical press cannot both open a menu and send a recording. */
class ButtonGesture(private val holdMillis: Long = 420, private val doubleClickMillis: Long = 300) {
    private var started: Long? = null
    private var recording = false
    private var cancelled = false
    private var pendingClick: Long? = null
    private var doubleEnabled = false
    private var secondPress = false
    val held get() = started != null
    val hasPendingClick get() = pendingClick != null
    val nextDeadline: Long? get() = if (held) {
        if (!recording && !cancelled) started!! + holdMillis else null
    } else pendingClick?.plus(doubleClickMillis)

    fun press(now: Long, allowDoubleClick: Boolean = false): List<GestureAction> {
        if (held) return emptyList()
        val actions = tick(now).toMutableList()
        doubleEnabled = allowDoubleClick && actions.isEmpty()
        secondPress = doubleEnabled && pendingClick != null
        if (pendingClick != null && !secondPress) actions += GestureAction.SHORT_CLICK
        pendingClick = null
        started = now
        recording = false
        cancelled = false
        return actions
    }

    fun tick(now: Long): List<GestureAction> {
        val start = started ?: return if (pendingClick?.let { now - it >= doubleClickMillis } == true) flushClick() else emptyList()
        if (!recording && !cancelled && now - start >= holdMillis) {
            recording = true
            secondPress = false
            return listOf(GestureAction.START_RECORDING)
        }
        return emptyList()
    }

    fun release(now: Long): List<GestureAction> {
        if (!held) return emptyList()
        val actions = tick(now).toMutableList()
        if (!cancelled) when {
            recording -> actions += GestureAction.SEND_RECORDING
            secondPress -> actions += GestureAction.DOUBLE_CLICK
            doubleEnabled -> pendingClick = now
            else -> actions += GestureAction.SHORT_CLICK
        }
        clear()
        return actions
    }

    fun rotate(): List<GestureAction> {
        if (!held) return emptyList()
        val actions = if (recording && !cancelled) listOf(GestureAction.CANCEL_RECORDING) else emptyList()
        cancelled = true
        return actions
    }

    fun cancel(): List<GestureAction> {
        val actions = if (recording && !cancelled) listOf(GestureAction.CANCEL_RECORDING) else emptyList()
        clear()
        pendingClick = null
        return actions
    }

    fun flushClick(): List<GestureAction> {
        if (pendingClick == null) return emptyList()
        pendingClick = null
        return listOf(GestureAction.SHORT_CLICK)
    }

    private fun clear() { started = null; recording = false; cancelled = false; secondPress = false; doubleEnabled = false }
}
