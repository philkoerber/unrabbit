package dev.unrabbit.core

import org.junit.Assert.*
import org.junit.Test

class ButtonGestureTest {
    @Test fun singleClickWaitsForTheDoublePressWindowOnlyWhenEnabled() {
        val button = ButtonGesture()
        button.press(0, true)
        assertTrue(button.release(50).isEmpty())
        assertEquals(350L, button.nextDeadline)
        assertTrue(button.tick(349).isEmpty())
        assertEquals(listOf(GestureAction.SHORT_CLICK), button.tick(350))
        assertTrue(button.tick(800).isEmpty())
    }
    @Test fun doublePressNeverFiresASingleClickOrStartsRecording() {
        val button = ButtonGesture()
        button.press(0, true); button.release(50)
        button.press(200, true)
        assertTrue(button.tick(350).isEmpty()) // The first click's deadline no longer applies.
        assertEquals(listOf(GestureAction.DOUBLE_CLICK), button.release(400))
        assertTrue(button.tick(1000).isEmpty())
    }
    @Test fun holdingTheSecondPressIsSpeechNotADoubleClick() {
        val button = ButtonGesture()
        button.press(0, true); button.release(50); button.press(200, true)
        assertEquals(listOf(GestureAction.START_RECORDING), button.tick(620))
        assertEquals(listOf(GestureAction.SEND_RECORDING), button.release(800))
        assertFalse(button.hasPendingClick)
    }
    @Test fun wheelAndLifecycleCancelPendingDoublePressWithoutLaterActions() {
        val button = ButtonGesture()
        button.press(0, true); button.release(50); button.press(200, true)
        button.rotate()
        assertTrue(button.release(250).isEmpty())
        assertTrue(button.tick(800).isEmpty())
        button.press(900, true); button.release(950); button.cancel()
        assertTrue(button.tick(1300).isEmpty())
    }
    @Test fun lateTimerDoesNotTurnSeparatedClicksIntoADoubleClick() {
        val button = ButtonGesture()
        button.press(0, true); button.release(50)
        assertEquals(listOf(GestureAction.SHORT_CLICK), button.press(400, true))
        assertEquals(listOf(GestureAction.SHORT_CLICK), button.release(450))
        assertTrue(button.tick(1000).isEmpty())
    }
    @Test fun shortPressOnlyClicks() {
        val button = ButtonGesture()
        button.press(100)
        assertEquals(listOf(GestureAction.SHORT_CLICK), button.release(200))
    }
    @Test fun longPressStartsOnceAndSendsOnceDespiteKeyRepeats() {
        val button = ButtonGesture()
        button.press(100)
        button.press(200)
        assertEquals(listOf(GestureAction.START_RECORDING), button.tick(520))
        assertTrue(button.tick(900).isEmpty())
        assertEquals(listOf(GestureAction.SEND_RECORDING), button.release(1000))
        assertTrue(button.release(1100).isEmpty())
    }
    @Test fun scrollCancelsRecordingAndReleaseDoesNotSendOrClick() {
        val button = ButtonGesture()
        button.press(0)
        button.tick(420)
        assertEquals(listOf(GestureAction.CANCEL_RECORDING), button.rotate())
        assertTrue(button.release(1000).isEmpty())
    }
    @Test fun scrollBeforeThresholdAlsoCancelsPress() {
        val button = ButtonGesture()
        button.press(0)
        button.rotate()
        assertTrue(button.tick(500).isEmpty())
        assertTrue(button.release(600).isEmpty())
    }
    @Test fun lifecycleCancellationNeverSends() {
        val button = ButtonGesture()
        button.press(0)
        button.tick(420)
        assertEquals(listOf(GestureAction.CANCEL_RECORDING), button.cancel())
        assertTrue(button.release(900).isEmpty())
    }
    @Test fun delayedTimerStillTreatsHeldReleaseAsRecording() {
        val button = ButtonGesture()
        button.press(0)
        assertEquals(listOf(GestureAction.START_RECORDING, GestureAction.SEND_RECORDING), button.release(800))
    }
}
