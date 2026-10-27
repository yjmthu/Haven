package sh.haven.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.haven.core.data.preferences.TabVisibility
import sh.haven.core.ui.navigation.Screen

/**
 * #navbar-visibility — unit tests for the pure [visibleScreens] tab filter.
 *
 * This function is the single source of truth for which bottom-nav tabs render;
 * it layers the per-tab [TabVisibility] preference (SHOW / HIDE / AUTO) on top
 * of the built-in usage rule. It is pure (no Compose, no Android runtime), so
 * plain JUnit covers it the same way [NavStateViewModelTest] covers the nav host.
 */
class VisibleScreensTest {

    private fun names(screens: List<Screen>) = screens.map { it.route }

    // ---- AUTO default behaviour (no per-tab preference set) ----

    @Test
    fun `auto defaults show connections and settings and always-visible tabs`() {
        val screens = visibleScreens(
            screenOrder = emptyList(),
            tabVisibility = emptyMap(),
            hasTerminalProfiles = false,
        )
        val routes = names(screens)
        assertEquals(Screen.Connections.route, routes.first())
        // Always-visible regardless of usage: Connections, Desktop, Keys, Sftp, Settings.
        listOf(Screen.Connections, Screen.Desktop, Screen.Keys, Screen.Sftp, Screen.Settings)
            .forEach { assertEquals("expected ${it.route} present", true, it.route in routes) }
        // Usage-gated tabs absent with no profiles / no session:
        assertEquals("Terminal hidden without profiles", false, Screen.Terminal.route in routes)
        // Default order: all present tabs in Screen.entries order.
        assertEquals(
            Screen.entries.map { it.route }.filter { it in routes },
            routes,
        )
    }

    @Test
    fun `auto terminal appears only when a terminal profile exists`() {
        val none = visibleScreens(emptyList(), emptyMap(), hasTerminalProfiles = false)
        assertEquals(false, Screen.Terminal.route in names(none))
        val some = visibleScreens(emptyList(), emptyMap(), hasTerminalProfiles = true)
        assertEquals(true, Screen.Terminal.route in names(some))
    }

    // ---- SHOW forces visible ----

    @Test
    fun `show forces the tab visible even when the usage rule would hide it`() {
        val prefs = mapOf(Screen.Terminal.route to TabVisibility.SHOW)
        val screens = visibleScreens(emptyList(), prefs, hasTerminalProfiles = false)
        assertEquals(true, Screen.Terminal.route in names(screens))
    }

    // ---- HIDE forces hidden ----

    @Test
    fun `hide removes an always-visible tab from the nav`() {
        val prefs = mapOf(Screen.Desktop.route to TabVisibility.HIDE)
        val screens = visibleScreens(emptyList(), prefs, hasTerminalProfiles = false)
        assertEquals(false, Screen.Desktop.route in names(screens))
    }

    @Test
    fun `hide removes a usage-visible tab`() {
        val prefs = mapOf(Screen.Terminal.route to TabVisibility.HIDE)
        val screens = visibleScreens(emptyList(), prefs, hasTerminalProfiles = true)
        assertEquals(false, Screen.Terminal.route in names(screens))
    }

    // ---- Preference takes priority over the usage rule both ways ----

    @Test
    fun `show beats a would-be-hidden usage rule and hide beats a would-be-shown one`() {
        val prefs = mapOf(
            Screen.Terminal.route to TabVisibility.SHOW, // no profile, forced on
            Screen.Desktop.route to TabVisibility.HIDE, // always-on, forced off
        )
        val screens = visibleScreens(emptyList(), prefs, hasTerminalProfiles = false)
        val routes = names(screens)
        assertEquals(true, Screen.Terminal.route in routes)
        assertEquals(false, Screen.Desktop.route in routes)
    }

    // ---- Settings guard ----

    @Test
    fun `settings can never be hidden so the visibility control stays reachable`() {
        val prefs = mapOf(
            Screen.Settings.route to TabVisibility.HIDE,
            Screen.Connections.route to TabVisibility.HIDE,
        )
        val screens = visibleScreens(emptyList(), prefs, hasTerminalProfiles = false)
        val routes = names(screens)
        // Connections and Settings are isAlwaysVisible; their HIDE is ignored.
        assertEquals(true, Screen.Settings.route in routes)
        assertEquals(true, Screen.Connections.route in routes)
    }

    // ---- Ordering ----

    @Test
    fun `user screen order is respected for visible tabs`() {
        val order = listOf(
            Screen.Settings.route,
            Screen.Keys.route,
            Screen.Desktop.route,
            Screen.Connections.route,
        )
        val screens = visibleScreens(
            screenOrder = order,
            tabVisibility = emptyMap(),
            hasTerminalProfiles = false,
        )
        // The four user-ordered tabs keep that exact order; the remainder not
        // in the user's list (Sftp) follows in default Screen.entries order.
        // Terminal is absent — no profile.
        assertEquals(
            listOf(
                Screen.Settings.route,
                Screen.Keys.route,
                Screen.Desktop.route,
                Screen.Connections.route,
                Screen.Sftp.route,
            ),
            names(screens),
        )
    }

    @Test
    fun `hidden tabs are excluded from ordered output while order of the rest is kept`() {
        val order = listOf(
            Screen.Desktop.route,
            Screen.Keys.route,
            Screen.Sftp.route,
            Screen.Terminal.route,
        )
        val prefs = mapOf(
            Screen.Desktop.route to TabVisibility.HIDE,
            Screen.Terminal.route to TabVisibility.SHOW,
        )
        val screens = visibleScreens(
            screenOrder = order,
            tabVisibility = prefs,
            hasTerminalProfiles = false,
        )
        // Desktop hidden; Keys then Sftp keep user order; Terminal shown; the
        // default-order remainder (Connections, Settings) is appended, so
        // the final list is Keys, Sftp, Terminal, Connections, Settings.
        val routes = names(screens)
        assertEquals(false, Screen.Desktop.route in routes)
        assertEquals(Screen.Keys.route, routes.first())
        assertEquals(true, Screen.Terminal.route in routes)
        assertEquals(
            listOf(
                Screen.Keys.route,
                Screen.Sftp.route,
                Screen.Terminal.route,
                Screen.Connections.route,
                Screen.Settings.route,
            ),
            routes,
        )
    }
}
