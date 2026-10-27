package sh.haven.feature.terminal

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk

import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import sh.haven.core.et.EtSessionManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager

class TerminalViewModelTest {

    // The ViewModel's init launches several `sessions.collect { syncSessions() }`
    // collectors. Without a pinned Main dispatcher viewModelScope falls back
    // to EmptyCoroutineContext and they run on Dispatchers.Default — real
    // threads, parallel with the test body — where a reconcile to zero tabs
    // calls resetModifiers() and drops the Ctrl/Alt locks a test just set.
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var sessionManager: SshSessionManager
    private lateinit var moshSessionManager: MoshSessionManager
    private lateinit var etSessionManager: EtSessionManager
    private lateinit var localSessionManager: LocalSessionManager
    private lateinit var umlGuestManager: sh.haven.core.local.uml.UmlGuestManager
    private lateinit var btSerialSessionManager: sh.haven.core.btserial.BtSerialSessionManager
    private lateinit var bleSerialSessionManager: sh.haven.core.bleserial.BleSerialSessionManager
    private lateinit var bleSerialSessions:
        MutableStateFlow<Map<String, sh.haven.core.bleserial.BleSerialSessionManager.SessionState>>
    private lateinit var usbSerialSessionManager: sh.haven.core.usbserial.UsbSerialSessionManager
    private lateinit var sshEmulatorOwner: SshTerminalEmulatorOwner
    private lateinit var connectionRepository: sh.haven.core.data.repository.ConnectionRepository
    private lateinit var attachCoordinator: sh.haven.feature.sftp.attach.TerminalAttachCoordinator
    private lateinit var viewModel: TerminalViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionManager = SshSessionManager(mockk(relaxed = true), mockk(relaxed = true))
        moshSessionManager = mockk<MoshSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        etSessionManager = mockk<EtSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        localSessionManager = mockk<LocalSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        umlGuestManager = mockk<sh.haven.core.local.uml.UmlGuestManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        btSerialSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        // #465: stubbed ONCE here, then driven by mutating the flow. The
        // ViewModel's init launches collectors that read these mocks, so a
        // later `every { sessions } ...` would put MockK into recording mode
        // while another thread is invoking the same mock — which throws
        // MockKException rather than failing an assertion, and only when the
        // collectors happen to be live (CI, loaded runner).
        bleSerialSessions = MutableStateFlow(emptyMap())
        bleSerialSessionManager = mockk(relaxed = true) {
            every { sessions } returns bleSerialSessions
        }
        usbSerialSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        // No connect-time bundle in unit tests (the real emulator needs JNI),
        // so the SSH adopt branch skips — matching the old isReadyForTerminal skip.
        sshEmulatorOwner = mockk(relaxed = true) {
            every { bundleFor(any()) } returns null
        }
        connectionRepository = mockk(relaxed = true)
        attachCoordinator = mockk(relaxed = true)
        viewModel = newViewModel()
    }

    private fun newViewModel(): TerminalViewModel = TerminalViewModel(
        mockk(relaxed = true),
        sessionManager,
        mockk(relaxed = true), // SshSessionAttacher
        moshSessionManager,
        etSessionManager,
        btSerialSessionManager,
        bleSerialSessionManager,
        usbSerialSessionManager,
        mockk<sh.haven.core.usb.UsbBroker>(relaxed = true),
        localSessionManager,
        umlGuestManager,
        mockk(relaxed = true), // HostKeyVerifier
        mockk(relaxed = true), // FidoAuthenticator
        mockk(relaxed = true), // KeystoreSshKeySigner
        mockk(relaxed = true), // UserPreferencesRepository
        InlineImageConsentRegistry(),
        connectionRepository,
        mockk(relaxed = true), // TunnelResolver
        sh.haven.core.data.agent.AgentUiCommandBus(),
        sh.haven.core.data.message.UserMessageBus(),
        attachCoordinator,
        sh.haven.feature.terminal.agent.TerminalSessionRegistry(),
        sshEmulatorOwner,
        mockk(relaxed = true), // BarcodeDecoder
        mockk(relaxed = true), // TextRecognizer
    )

    @After
    fun tearDown() {
        // A tab-creating test leaves the VM's collectors and any in-flight
        // withContext(IO) hop alive; their next Main resume lands after
        // resetMain and the uncaught exception is charged to the NEXT test.
        // Pump the dispatcher with real time while Main is still set so
        // those continuations land here instead.
        repeat(30) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        }
        Dispatchers.resetMain()
        io.mockk.unmockkStatic(android.os.Looper::class)
        io.mockk.unmockkObject(org.connectbot.terminal.TerminalEmulatorFactory.Companion)
    }

    @Test
    fun `initially has no tabs`() {
        assertEquals(0, viewModel.tabs.value.size)
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    // #688: v5.89.24 crashed on launch (NPE in TerminalViewModel.<init>).
    // The #601 fix wrapped syncSessions() in syncMutex.withLock, but the
    // mutex was declared AFTER the init block that launches the session
    // collectors. viewModelScope is Dispatchers.Main.immediate, so a
    // collector's first StateFlow emission runs syncSessions() synchronously
    // inside the constructor - before Kotlin's declaration-order property
    // initialiser assigns the Mutex - dereferencing a null syncMutex. The
    // reporter's stack (HiltViewModelFactory.createViewModel -> <init> ->
    // collect -> emit -> NPE) is exactly that synchronous path. The class
    // harness pins Main to a StandardTestDispatcher, whose launch bodies are
    // queued rather than run in the caller's stack, so no existing test ever
    // executed a collector during construction. This test pins an eager
    // (Unconfined) Main so the first emission runs the reconcile inside the
    // constructor, reproducing the device path: pre-fix the NPE surfaces
    // here, post-fix construction completes.
    @Test
    fun `constructing the view model with an eager main dispatcher does not crash`() {
        val eager = UnconfinedTestDispatcher()
        Dispatchers.setMain(eager)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        var uncaught: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, t -> uncaught = t }
        try {
            val vm = try {
                newViewModel()
            } catch (t: Throwable) {
                uncaught = uncaught ?: t
                null
            }
            assertNull("constructing TerminalViewModel on an eager Main dispatcher crashed (#688)", uncaught)
            assertNotNull(vm)
            assertEquals(0, vm!!.tabs.value.size)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            Dispatchers.setMain(testDispatcher)
        }
    }

    // v5.89.0 bug, user-reported: Take photo / Send file from a LOCAL shell
    // tab passed the local tab's DB profile id as the attach carrier, so the
    // Files tab pre-selected a local destination the pick banner can never
    // confirm (there is no upload protocol to a local destination) and the
    // flow dead-ended with nothing offered. The local profile must be
    // stripped before the request is published so the banner offers remote
    // destinations, exactly as it does for the other attach origins.
    private fun kotlinx.coroutines.test.TestScope.runAttach(profileId: String?) {
        coEvery { attachCoordinator.attach(any(), any(), any(), any()) } returns null
        // Uri.parse is stubbed to null under unit tests (returnDefaultValues),
        // and the flow only forwards the uri — a mock does the same job.
        viewModel.runAttachFlow(
            sourceUri = mockk<android.net.Uri>(relaxed = true),
            fileName = "photo.jpg",
            fileSize = 1234L,
            initialProfileId = profileId,
        )
        advanceUntilIdle()
    }

    @Test
    fun `attach from a local tab strips the local carrier profile`() = runTest(testDispatcher) {
        coEvery { connectionRepository.getById("profile-under-test") } returns
            sh.haven.core.data.db.entities.ConnectionProfile(
                label = "Local Shell", host = "", username = "", connectionType = "LOCAL",
            )

        runAttach("profile-under-test")

        coVerify(exactly = 1) { attachCoordinator.attach(any(), any(), any(), null) }
    }

    @Test
    fun `attach from a remote tab keeps the carrier profile`() = runTest(testDispatcher) {
        coEvery { connectionRepository.getById("profile-under-test") } returns
            sh.haven.core.data.db.entities.ConnectionProfile(
                label = "Server", host = "server.example.com", username = "ian",
            )

        runAttach("profile-under-test")

        coVerify(exactly = 1) {
            attachCoordinator.attach(any(), any(), any(), "profile-under-test")
        }
    }

    @Test
    fun `syncSessions with no sessions produces no tabs`() {
        runBlocking { viewModel.syncSessions() }
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `syncSessions skips CONNECTING sessions`() {
        val client = mockk<SshClient>(relaxed = true)
        sessionManager.registerSession("profile1", "Server", client)
        // Status is CONNECTING, no shell channel

        runBlocking { viewModel.syncSessions() }
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `syncSessions skips CONNECTED sessions without shell channel`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = sessionManager.registerSession("profile1", "Server", client)
        sessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)
        // No shell channel attached

        runBlocking { viewModel.syncSessions() }
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `selectTab with no tabs is no-op`() {
        viewModel.selectTab(2)
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    @Test
    fun `closeTab removes from session manager`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = sessionManager.registerSession("profile1", "Server", client)
        viewModel.closeTab(sessionId)

        assertEquals(null, sessionManager.getSession(sessionId))
    }

    @Test
    fun `closeTab tears down a BLE-serial session`() {
        // Regression: removeTabAndSync did not know the serial transports, so a
        // serial session survived the close and syncSessions rebuilt its tab.
        val sessionId = "ble-1"
        // Drive the already-stubbed flow rather than re-stubbing the mock (#465).
        bleSerialSessions.value = mapOf(sessionId to mockk(relaxed = true))

        viewModel.closeTab(sessionId)

        verify { bleSerialSessionManager.removeSession(sessionId) }
    }

    @Test
    fun `closeSession removes all sessions for profile`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        sessionManager.registerSession("profile1", "Server", c1)
        sessionManager.registerSession("profile1", "Server", c2)

        viewModel.closeSession("profile1")

        assertEquals(0, sessionManager.getSessionsForProfile("profile1").size)
    }

    @Test
    fun `selectTabByProfileId with no matching tab is no-op`() {
        viewModel.selectTabByProfileId("nonexistent")
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    @Test
    fun `selectTabBySessionId with no matching tab is no-op`() {
        viewModel.selectTabBySessionId("nonexistent")
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    // The toolbar dispatches its nav keys by key code, not bytes, so a tapped
    // Ctrl only reaches them through this mask. It was hardcoded to 0, which is
    // why Ctrl+End sent a bare End.
    @Test
    fun `a tapped Ctrl reaches the toolbar's own keys as the vterm mask`() {
        assertEquals(0, viewModel.toolbarModifierMask())

        viewModel.toggleCtrl()
        assertEquals(4, viewModel.toolbarModifierMask())

        viewModel.toggleAlt()
        assertEquals(6, viewModel.toolbarModifierMask())
    }

    @Test
    fun `a one-shot Ctrl is spent by the keystroke that used it`() {
        viewModel.toggleCtrl()
        viewModel.clearStickyModifiers()

        assertEquals(false, viewModel.ctrlActive.value)
        assertEquals(0, viewModel.toolbarModifierMask())
    }

    // #522, round two: the requester's follow-up settled the lock's meaning —
    // locked (blue) means every keypress carries Ctrl until the user taps it
    // off. No keystroke budget: v5.87.8's release-after-two was both broken in
    // practice (a second consume site spent the modifier after ONE press) and
    // not what the requester wanted once they had it under their fingers.
    @Test
    fun `a second Ctrl tap locks it, and it survives one keystroke`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()

        assertEquals(true, viewModel.ctrlLocked.value)
        assertEquals(true, viewModel.ctrlActive.value)

        viewModel.clearStickyModifiers()

        assertEquals("a lock is not spent by one keystroke", true, viewModel.ctrlActive.value)
        assertEquals(4, viewModel.toolbarModifierMask())
    }

    @Test
    fun `a locked Ctrl survives any number of keystrokes`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()

        repeat(5) { viewModel.clearStickyModifiers() }

        assertEquals(true, viewModel.ctrlLocked.value)
        assertEquals(true, viewModel.ctrlActive.value)
        assertEquals(4, viewModel.toolbarModifierMask())
    }

    // #522, round three: the lock outlived the sessions it was locked for.
    // The requester's flow — lock Ctrl, C C to leave Claude Code, D for
    // Ctrl+D to exit the shell — ends the last session with the lock still
    // on, and the next connection started with Ctrl pre-locked. The lock
    // now dies with the last tab. (Deliberately NOT on per-tab close: the
    // toolbar state is shared and a surviving tab may be mid-use of it.)
    @Test
    fun `a locked Ctrl does not survive the last session ending`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()
        viewModel.toggleAlt()
        viewModel.toggleAlt()
        assertEquals(true, viewModel.ctrlLocked.value)
        assertEquals(true, viewModel.altLocked.value)

        // Every session manager is empty, so this reconciles to zero tabs —
        // the state right after Ctrl+D closed the final session.
        runBlocking { viewModel.syncSessions() }

        assertEquals(false, viewModel.ctrlLocked.value)
        assertEquals(false, viewModel.ctrlActive.value)
        assertEquals(false, viewModel.altLocked.value)
        assertEquals(false, viewModel.altActive.value)
        assertEquals(0, viewModel.toolbarModifierMask())
    }

    /**
     * Pins the mechanism behind the modifier-lock flake (five CI sightings, a
     * different family member each time).
     *
     * The ViewModel's init starts eight `sessions.collect { syncSessions() }`
     * collectors and every mocked flow replays `emptyMap()`, so construction
     * queues eight reconciles to zero tabs — each of which calls
     * resetModifiers() and drops the locks. Pinned to the test dispatcher they
     * are inert; running them on purpose here shows exactly what used to land
     * at a random moment on a loaded runner and fail the assertion at the top
     * of the family.
     */
    @Test
    fun `a queued init reconcile is what used to wipe a lock mid-test`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()
        assertEquals("locked before the stray reconcile", true, viewModel.ctrlLocked.value)

        // The reconcile hops to Dispatchers.IO for its profile batch, so its
        // resumption is scheduled back onto the test dispatcher only when the
        // off-main work finishes; a single runCurrent() can observe the lock
        // before the wipe lands (CI flake on a loaded runner, 10-11). Pump
        // until the reconcile completes, bounded.
        val deadline = System.currentTimeMillis() + 2_000
        while (viewModel.ctrlLocked.value && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        }

        assertEquals(
            "the init collectors reconcile to zero tabs and clear the lock",
            false,
            viewModel.ctrlLocked.value,
        )
    }

    @Test
    fun `a third Ctrl tap unlocks it immediately`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()

        assertEquals(false, viewModel.ctrlLocked.value)
        assertEquals(false, viewModel.ctrlActive.value)
    }

    @Test
    fun `Ctrl can be locked again after a manual unlock`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()

        viewModel.toggleCtrl()
        viewModel.toggleCtrl()
        viewModel.clearStickyModifiers()

        assertEquals(true, viewModel.ctrlLocked.value)
        assertEquals(true, viewModel.ctrlActive.value)
    }

    @Test
    fun `Alt locks independently of Ctrl`() {
        viewModel.toggleAlt()
        viewModel.toggleAlt()
        viewModel.toggleCtrl()

        viewModel.clearStickyModifiers()

        assertEquals("the one-shot Ctrl is spent", false, viewModel.ctrlActive.value)
        assertEquals("the locked Alt is not", true, viewModel.altActive.value)
        assertEquals(2, viewModel.toolbarModifierMask())
    }

    // #713: When the active tab's TerminalEmulator resizes (e.g. active tab in
    // alt-screen reflowing to keyboard height, or rotating), its onResize
    // callback must NOT loop over _tabs.value and call tab.resize(cols, rows)
    // on inactive tabs — because inactive tabs are not composed, their local
    // TerminalEmulator stays at its old dimensions while the remote PTY is
    // resized out from under it, desynchronizing remote vs local dimensions.
    @Test
    fun `resizing one tab does not resize another tab's remote transport out from under its emulator`() =
        runTest(testDispatcher) {
            val sshSession1 = mockk<sh.haven.core.ssh.TerminalSession>(relaxed = true)
            val sshSession2 = mockk<sh.haven.core.ssh.TerminalSession>(relaxed = true)
            val resizeSinks = mutableMapOf<String, (org.connectbot.terminal.TerminalDimensions) -> Unit>()

            val s1 = sessionManager.registerSession("p1", "Server 1", mockk(relaxed = true))
            sessionManager.updateStatus(s1, SshSessionManager.SessionState.Status.CONNECTED)
            val s2 = sessionManager.registerSession("p2", "Server 2", mockk(relaxed = true))
            sessionManager.updateStatus(s2, SshSessionManager.SessionState.Status.CONNECTED)

            val bundle1 = SshTerminalEmulatorOwner.Bundle(
                sessionId = s1,
                profileId = "p1",
                label = "Server 1",
                oscHandler = OscHandler(),
                cwdFlow = MutableStateFlow(null),
                hyperlinkFlow = MutableStateFlow(null),
                mouseTracker = MouseModeTracker(),
                recorder = null,
            ).apply {
                emulator = mockk(relaxed = true)
                feedOutput = { _, _, _ -> }
                session = sshSession1
            }
            val bundle2 = SshTerminalEmulatorOwner.Bundle(
                sessionId = s2,
                profileId = "p2",
                label = "Server 2",
                oscHandler = OscHandler(),
                cwdFlow = MutableStateFlow(null),
                hyperlinkFlow = MutableStateFlow(null),
                mouseTracker = MouseModeTracker(),
                recorder = null,
            ).apply {
                emulator = mockk(relaxed = true)
                feedOutput = { _, _, _ -> }
                session = sshSession2
            }

            every { sshEmulatorOwner.bundleFor(s1) } returns bundle1
            every { sshEmulatorOwner.bundleFor(s2) } returns bundle2
            every { sshEmulatorOwner.setResizeSink(any(), any()) } answers {
                resizeSinks[firstArg()] = secondArg()
            }

            viewModel.syncSessions()
            assertEquals(2, viewModel.tabs.value.size)

            // Active tab 1's emulator fires onResize(51 cols, 23 rows)
            resizeSinks.getValue(s1).invoke(
                org.connectbot.terminal.TerminalDimensions(rows = 23, columns = 51),
            )

            verify(exactly = 1) { sshSession1.resize(51, 23) }
            verify(exactly = 0) { sshSession2.resize(any(), any()) }
        }
}

