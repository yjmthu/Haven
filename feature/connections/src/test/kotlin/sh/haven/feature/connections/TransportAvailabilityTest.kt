package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #510 — the terminal build ships no RDP client, so offering it would create a
 * profile that can only fail at connect with "native library failed to load".
 */
class TransportAvailabilityTest {

    private val all = listOf(
        "SSH" to "SSH",
        "MOSH" to "Mosh",
        "VNC" to "VNC (Desktop)",
        "RDP" to "RDP (Desktop)",
    )

    private fun values(rdp: Boolean) =
        TransportAvailability.offered(all, rdp).map { it.first }

    @Test
    fun `a full build offers everything`() {
        assertEquals(all.map { it.first }, values(rdp = true))
    }

    @Test
    fun `a build without the native client drops RDP`() {
        val offered = values(rdp = false)

        assertFalse("RDP", "RDP" in offered)
    }

    /**
     * The gate must remove ONLY RDP. Filtering by "is it a desktop type" would
     * take VNC with it, and VNC's client is Kotlin — it works in every build,
     * including against a guest desktop the terminal build can still run over
     * X11Vnc.
     */
    @Test
    fun `nothing else is affected, VNC included`() {
        val offered = values(rdp = false)

        assertEquals(
            listOf("SSH", "MOSH", "VNC"),
            offered,
        )
        assertTrue("VNC must survive", "VNC" in offered)
    }

    /** Order is the caller's; filtering must not reshuffle the menu. */
    @Test
    fun `order is preserved`() {
        assertEquals(
            listOf("SSH", "MOSH", "VNC", "RDP"),
            values(rdp = true),
        )
    }
}
