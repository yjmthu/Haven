package sh.haven.app.desktop

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.vnc.VncClient

/**
 * #700: VNC typing must not push the typed text to the remote clipboard.
 * Both typing paths used to call `copyText(text)` before `typeText(text)`,
 * so every IME delta (including partial composition updates) landed on the
 * remote clipboard. The explicit clipboard action
 * ([VncDesktopSession.sendClipboardText]) is unchanged and still covered by
 * [RemoteDesktopSessionTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopViewModelVncTypingTest {

    private val testDispatcher = StandardTestDispatcher()

    // The VM's init blocks collect these flows; a relaxed mock returns
    // null for them and the collectors throw, so stub real (empty) ones.
    private val commandBus = sh.haven.core.data.agent.AgentUiCommandBus()

    private lateinit var vm: DesktopViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        vm = DesktopViewModel(
            sshSessionManager = mockk(relaxed = true),
            connectionLogRepository = mockk(relaxed = true),
            preferencesRepository = mockk(relaxed = true),
            connectionRepository = mockk(relaxed = true),
            tunnelResolver = mockk(relaxed = true),
            agentUiCommandBus = commandBus,
            desktopSessionRegistry = mockk(relaxed = true),
            tlsCertVerifier = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `typeVncText types without pushing the text to the remote clipboard`() = runTest {
        val client = mockk<VncClient>(relaxed = true)
        // activeTab is a stateIn(WhileSubscribed) flow — keep a subscriber
        // alive so it computes the injected tab.
        backgroundScope.launch { vm.activeTab.collect { } }
        vm.addTabForTest(DesktopTab.Vnc(id = "t1", label = "t1", client = client))
        advanceUntilIdle()

        vm.typeVncText("hello")

        // typeText fires on the IO-limited dispatcher; once it has, any
        // copyText call (which preceded it in the same coroutine) would
        // already have been recorded.
        verify(timeout = 3000, atLeast = 1) { client.typeText("hello") }
        verify(exactly = 0) { client.copyText(any()) }
    }

    /**
     * #723: view-only mode drops keyboard, mouse and remote-clipboard input
     * on the active VNC tab; toggling it back off restores dispatch.
     */
    @Test
    fun `view-only drops input and the toggle restores it`() = runTest {
        val client = mockk<VncClient>(relaxed = true)
        backgroundScope.launch { vm.activeTab.collect { } }
        val tab = DesktopTab.Vnc(id = "t1", label = "t1", client = client)
        vm.addTabForTest(tab)
        advanceUntilIdle()

        vm.toggleVncViewOnly()
        assertTrue(tab._viewOnly.value)

        vm.typeVncText("hello")
        vm.sendClick(10, 20)
        vm.scrollUp()
        vm.sendVncClipboardText("clip")
        advanceUntilIdle()

        verify(exactly = 0) { client.typeText(any()) }
        verify(exactly = 0) { client.moveMouse(any(), any()) }
        verify(exactly = 0) { client.updateMouseButton(any(), any()) }
        verify(exactly = 0) { client.copyText(any()) }

        vm.toggleVncViewOnly()
        assertFalse(tab._viewOnly.value)

        vm.typeVncText("hello")
        vm.sendClick(10, 20)
        advanceUntilIdle()
        verify(timeout = 3000, atLeast = 1) { client.typeText("hello") }
        verify(timeout = 3000, atLeast = 1) { client.moveMouse(10, 20) }
    }
}
