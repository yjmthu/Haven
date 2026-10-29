package sh.haven.core.data.preferences

/** The RDP canvas gesture used to send a right-button click. */
enum class RdpRightClickGesture(val id: String) {
    TWO_FINGER_TAP("two_finger_tap"),
    SINGLE_FINGER_LONG_PRESS("single_finger_long_press");

    companion object {
        val DEFAULT = TWO_FINGER_TAP

        fun fromId(id: String?): RdpRightClickGesture =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
