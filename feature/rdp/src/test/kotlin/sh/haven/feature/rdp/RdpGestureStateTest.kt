package sh.haven.feature.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.preferences.RdpRightClickGesture

class RdpGestureStateTest {
    private fun gesture(
        mode: RdpRightClickGesture = RdpRightClickGesture.DEFAULT,
        followUp: Boolean = false,
    ) = RdpGestureState(mode, touchSlop = 10f, followUpTouch = followUp)

    @Test fun `plain tap remains a left click in either setting`() {
        RdpRightClickGesture.entries.forEach { mode ->
            val state = gesture(mode)
            state.moveSingle(2f)
            state.updatePointerCount(0)
            assertEquals(RdpClickAction.LEFT, state.tapAction())
        }
    }

    @Test fun `two finger tap is the default right click`() {
        val state = gesture()
        state.updatePointerCount(2)
        state.updatePointerCount(1)
        state.updatePointerCount(0)
        assertEquals(RdpClickAction.RIGHT, state.tapAction())
    }

    @Test fun `single finger long press mode disables two finger right click`() {
        val state = gesture(RdpRightClickGesture.SINGLE_FINGER_LONG_PRESS)
        state.updatePointerCount(2)
        state.updatePointerCount(0)
        assertEquals(RdpClickAction.NONE, state.tapAction())
    }

    @Test fun `single finger long press fires once and consumes the release`() {
        val state = gesture(RdpRightClickGesture.SINGLE_FINGER_LONG_PRESS)
        assertEquals(RdpClickAction.RIGHT, state.longPressAction())
        state.consumeLongPress(RdpClickAction.RIGHT)
        assertTrue(state.waitingForRelease)
        assertEquals(RdpClickAction.NONE, state.longPressAction())
        state.updatePointerCount(0)
        assertEquals(RdpClickAction.NONE, state.tapAction())
    }

    @Test fun `default single finger hold does not right click`() {
        assertEquals(RdpClickAction.NONE, gesture().longPressAction())
    }

    @Test fun `moving before the hold threshold cancels the right click`() {
        val state = gesture(RdpRightClickGesture.SINGLE_FINGER_LONG_PRESS)
        state.moveSingle(6f)
        state.moveSingle(5f)
        assertEquals(RdpClickAction.NONE, state.longPressAction())
        assertEquals(RdpClickAction.NONE, state.tapAction())
    }

    @Test fun `second finger cancels the single finger hold candidate`() {
        val state = gesture(RdpRightClickGesture.SINGLE_FINGER_LONG_PRESS)
        state.updatePointerCount(2)
        assertEquals(RdpClickAction.MIDDLE, state.longPressAction())
        state.updatePointerCount(1)
        assertEquals(RdpClickAction.NONE, state.longPressAction())
    }

    @Test fun `two finger hold remains a middle click in either setting`() {
        RdpRightClickGesture.entries.forEach { mode ->
            val state = gesture(mode)
            state.updatePointerCount(2)
            assertEquals(RdpClickAction.MIDDLE, state.longPressAction())
            state.consumeLongPress(RdpClickAction.MIDDLE)
            assertEquals(RdpClickAction.NONE, state.longPressAction())
            assertEquals(RdpClickAction.NONE, state.tapAction())
        }
    }

    @Test fun `symmetric pinch with a fixed centroid does not right click`() {
        val state = gesture()
        state.updatePointerCount(2)
        state.moveMultiple(0f, 4f, pinching = true)
        state.moveMultiple(0f, 4f, pinching = true)
        state.updatePointerCount(0)
        assertEquals(RdpClickAction.NONE, state.tapAction())
        assertEquals(RdpClickAction.NONE, state.longPressAction())
    }

    @Test fun `small pinch remains consumed even below touch slop`() {
        val state = gesture()
        state.updatePointerCount(2)
        state.moveMultiple(0f, 3f, pinching = true)
        assertEquals(RdpClickAction.NONE, state.tapAction())
    }

    @Test fun `scrolling out and back does not become a tap`() {
        val state = gesture()
        state.updatePointerCount(2)
        state.moveMultiple(6f, 0f, pinching = false)
        state.moveMultiple(6f, 0f, pinching = false)
        state.updatePointerCount(0)
        assertEquals(RdpClickAction.NONE, state.tapAction())
    }

    @Test fun `tap then drag candidate takes priority over single finger long press`() {
        val state = gesture(RdpRightClickGesture.SINGLE_FINGER_LONG_PRESS, followUp = true)
        assertEquals(RdpClickAction.NONE, state.longPressAction())
        state.moveSingle(12f)
        state.startDrag()
        state.updatePointerCount(0)
        assertEquals(RdpClickAction.NONE, state.tapAction())
    }

    @Test fun `interrupted drag ignores the rest of its contact sequence`() {
        val state = gesture()
        state.startDrag()
        state.updatePointerCount(2)
        state.interruptDrag()
        assertTrue(state.waitingForRelease)
        assertEquals(RdpClickAction.NONE, state.longPressAction())
        state.updatePointerCount(0)
        assertEquals(RdpClickAction.NONE, state.tapAction())
        assertEquals(RdpClickAction.LEFT, gesture().tapAction())
    }

    @Test fun `three fingers do not fall back to a two finger click or hold`() {
        val state = gesture()
        state.updatePointerCount(3)
        state.updatePointerCount(2)
        assertEquals(RdpClickAction.NONE, state.longPressAction())
        state.updatePointerCount(0)
        assertEquals(RdpClickAction.NONE, state.tapAction())
    }

    @Test fun `unknown saved setting keeps the existing default`() {
        assertEquals(RdpRightClickGesture.TWO_FINGER_TAP, RdpRightClickGesture.fromId(null))
        assertEquals(RdpRightClickGesture.TWO_FINGER_TAP, RdpRightClickGesture.fromId("future-mode"))
        assertEquals(
            RdpRightClickGesture.SINGLE_FINGER_LONG_PRESS,
            RdpRightClickGesture.fromId("single_finger_long_press"),
        )
    }
}
