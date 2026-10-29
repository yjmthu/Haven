package sh.haven.feature.rdp

import sh.haven.core.data.preferences.RdpRightClickGesture

internal enum class RdpClickAction { NONE, LEFT, RIGHT, MIDDLE }

/** Classifies one contact sequence, including fingers that lift before the last one. */
internal class RdpGestureState(
    private val rightClickGesture: RdpRightClickGesture,
    private val touchSlop: Float,
    private val followUpTouch: Boolean = false,
) {
    var maxPointers = 1
        private set
    private var pressedPointers = 1
    var singleTravel = 0f
        private set
    private var centroidTravel = 0f
    private var spanTravel = 0f
    private var transformed = false
    private var clickConsumed = false
    private var dragged = false
    var waitingForRelease = false
        private set

    fun updatePointerCount(count: Int) {
        pressedPointers = count
        maxPointers = maxOf(maxPointers, count)
    }

    fun moveSingle(distance: Float) {
        singleTravel += distance
    }

    fun moveMultiple(centroidDistance: Float, spanDistance: Float, pinching: Boolean) {
        centroidTravel += centroidDistance
        spanTravel += spanDistance
        // Returning to the starting position must not turn a zoom/scroll into a tap.
        transformed = transformed || pinching || centroidTravel >= touchSlop || spanTravel >= touchSlop
    }

    fun startDrag() {
        dragged = true
    }

    fun interruptDrag() {
        clickConsumed = true
        waitingForRelease = true
    }

    fun longPressAction(): RdpClickAction = when {
        clickConsumed || dragged || transformed || waitingForRelease -> RdpClickAction.NONE
        pressedPointers == 2 && maxPointers == 2 &&
            centroidTravel < touchSlop && spanTravel < touchSlop -> RdpClickAction.MIDDLE
        rightClickGesture == RdpRightClickGesture.SINGLE_FINGER_LONG_PRESS &&
            pressedPointers == 1 && maxPointers == 1 && singleTravel < touchSlop &&
            !followUpTouch -> RdpClickAction.RIGHT
        else -> RdpClickAction.NONE
    }

    fun consumeLongPress(action: RdpClickAction) {
        check(action == RdpClickAction.RIGHT || action == RdpClickAction.MIDDLE)
        clickConsumed = true
        // A right-click opens a menu; the remainder of that contact is not another input.
        if (action == RdpClickAction.RIGHT) waitingForRelease = true
    }

    fun tapAction(): RdpClickAction = when {
        clickConsumed || dragged || transformed || waitingForRelease -> RdpClickAction.NONE
        maxPointers == 1 && singleTravel < touchSlop -> RdpClickAction.LEFT
        rightClickGesture == RdpRightClickGesture.TWO_FINGER_TAP && maxPointers == 2 &&
            centroidTravel < touchSlop && spanTravel < touchSlop -> RdpClickAction.RIGHT
        else -> RdpClickAction.NONE
    }
}
