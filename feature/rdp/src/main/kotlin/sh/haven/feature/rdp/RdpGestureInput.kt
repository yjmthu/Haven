package sh.haven.feature.rdp

/** All callbacks belong to one captured session, including releases after a tab switch. */
class RdpGestureInput(
    val move: (Int, Int) -> Unit,
    val click: (Int, Int, Int) -> Unit,
    val startDrag: (Int, Int) -> Unit,
    val endDrag: () -> Unit,
    val scroll: (Int) -> Unit,
    val key: (Int, Boolean) -> Unit,
) {
    internal fun modifiedClick(x: Int, y: Int, button: Int, modifiers: List<Int>) {
        try {
            modifiers.forEach { key(it, true) }
            click(x, y, button)
        } finally {
            modifiers.asReversed().forEach { key(it, false) }
        }
    }
}

/** Owns the button/modifier presses of a drag and releases them at most once. */
internal class RdpDragHold(private val input: RdpGestureInput) {
    var active = false
        private set
    private var modifiers: List<Int> = emptyList()

    fun start(x: Int, y: Int, modifiers: List<Int>) {
        check(!active)
        this.modifiers = modifiers.toList()
        active = true
        modifiers.forEach { input.key(it, true) }
        input.startDrag(x, y)
    }

    fun finish() {
        if (!active) return
        active = false
        val held = modifiers
        modifiers = emptyList()
        try {
            input.endDrag()
        } finally {
            held.asReversed().forEach { input.key(it, false) }
        }
    }
}
