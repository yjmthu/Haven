package sh.haven.feature.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RdpGestureInputTest {
    private fun input(events: MutableList<String>, owner: String = "rdp") = RdpGestureInput(
        move = { x, y -> events += "$owner:move:$x,$y" },
        click = { _, _, button -> events += "$owner:click:$button" },
        startDrag = { _, _ -> events += "$owner:left-down" },
        endDrag = { events += "$owner:left-up" },
        scroll = { delta -> events += "$owner:scroll:$delta" },
        key = { code, pressed -> events += "$owner:key:$code:$pressed" },
    )

    @Test fun `drag releases its captured modifiers once after interruption`() {
        val events = mutableListOf<String>()
        val modifiers = mutableListOf(29, 42)
        val drag = RdpDragHold(input(events))
        drag.start(10, 20, modifiers)
        modifiers.clear() // The keyboard clears its armed state as the drag starts.
        drag.finish()
        drag.finish() // A finally block may finish an already interrupted drag.
        assertFalse(drag.active)
        assertEquals(listOf(
            "rdp:key:29:true", "rdp:key:42:true", "rdp:left-down",
            "rdp:left-up", "rdp:key:42:false", "rdp:key:29:false",
        ), events)
    }

    @Test fun `gesture cleanup keeps its original session after the active tab changes`() {
        val events = mutableListOf<String>()
        var activeInput = input(events, "first")
        val drag = RdpDragHold(activeInput)
        drag.start(0, 0, listOf(29))
        activeInput = input(events, "second")
        activeInput.move(5, 6)
        drag.finish()
        assertEquals(listOf(
            "first:key:29:true", "first:left-down", "second:move:5,6",
            "first:left-up", "first:key:29:false",
        ), events)
    }

    @Test fun `cancelled gesture releases both mouse and modifiers through finally`() {
        val events = mutableListOf<String>()
        val drag = RdpDragHold(input(events))
        try {
            try {
                drag.start(0, 0, listOf(29))
                throw java.util.concurrent.CancellationException("canvas removed")
            } finally {
                drag.finish()
            }
        } catch (_: java.util.concurrent.CancellationException) {
            // Same cleanup path as a cancelled pointer-input coroutine.
        }
        assertEquals(listOf(
            "rdp:key:29:true", "rdp:left-down", "rdp:left-up", "rdp:key:29:false",
        ), events)
    }

    @Test fun `click releases modifiers even if sending the click fails`() {
        val events = mutableListOf<String>()
        val target = RdpGestureInput(
            move = { _, _ -> }, click = { _, _, _ -> error("send failed") },
            startDrag = { _, _ -> }, endDrag = {}, scroll = {},
            key = { code, pressed -> events += "$code:$pressed" },
        )
        try {
            target.modifiedClick(0, 0, 3, listOf(29, 42))
        } catch (_: IllegalStateException) {
            // Releases must still be submitted.
        }
        assertEquals(listOf("29:true", "42:true", "42:false", "29:false"), events)
    }
}
