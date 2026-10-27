package sh.haven.app.workspace

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.WorkspaceRepository
import sh.haven.core.data.repository.WorkspaceWithItems
import sh.haven.core.ssh.SshSessionAttacher
import sh.haven.core.ssh.SshSessionManager

class WorkspaceLauncherTest {

    @Test
    fun sortPutsTerminalsFirstThenFileBrowserDesktop() {
        val ws = "ws-1"
        val items = listOf(
            item(ws, "d", WorkspaceItem.Kind.DESKTOP, "vnc-prof"),
            item(ws, "f", WorkspaceItem.Kind.FILE_BROWSER, "ssh-prof"),
            item(ws, "t", WorkspaceItem.Kind.TERMINAL, "ssh-prof"),
        )

        val sorted = WorkspaceLauncher.sortByKindPriority(items)

        assertEquals(
            listOf("t", "f", "d"),
            sorted.map { it.id },
        )
    }

    @Test
    fun sortIsStableWithinAKindUsingSortOrderThenId() {
        val ws = "ws-1"
        val a = item(ws, "a", WorkspaceItem.Kind.TERMINAL, "p1", sortOrder = 1)
        val b = item(ws, "b", WorkspaceItem.Kind.TERMINAL, "p2", sortOrder = 0)
        val c = item(ws, "c", WorkspaceItem.Kind.TERMINAL, "p3", sortOrder = 0)

        val sorted = WorkspaceLauncher.sortByKindPriority(listOf(a, c, b))

        // sortOrder 0 comes before 1; tie at 0 broken by id (b < c).
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun launchUnknownWorkspaceReportsFailedState() = runBlocking {
        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("missing") } returns null
        val launcher = launcher(workspaceRepo, mockk(), mockk(), mockk(), mockk())

        launcher.launch("missing")

        val state = launcher.state.value
        assertTrue("expected Failed, got $state", state is WorkspaceLaunchState.Failed)
        assertEquals(
            "workspace not found",
            (state as WorkspaceLaunchState.Failed).reason,
        )
    }

    @Test
    fun launchAttachesTerminalsThenEmitsNonTerminalCommandsInPriorityOrder() = runBlocking {
        val sshProfile = profile("ssh-1", connectionType = "SSH")
        val vncProfile = profile("vnc-1", connectionType = "VNC")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "vnc-item", WorkspaceItem.Kind.DESKTOP, "vnc-1"),
            item("ws-1", "ssh-item", WorkspaceItem.Kind.TERMINAL, "ssh-1", sessionName = "main"),
            item("ws-1", "sftp-item", WorkspaceItem.Kind.FILE_BROWSER, "ssh-1", path = "/srv"),
        )

        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns
            WorkspaceWithItems(ws, items)

        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-1") } returns sshProfile
        coEvery { connRepo.getById("vnc-1") } returns vncProfile

        val (bus, emitted) = recordingBus()
        val attacher = attacherMock()
        coEvery { attacher.ensureAttached("ssh-1", "main") } returns
            SshSessionAttacher.Result.Attached("s-1")

        val launcher = launcher(workspaceRepo, connRepo, bus, mockk(), attacher)
        launcher.launch("ws-1")

        val state = launcher.state.value
        assertTrue("expected Completed, got $state", state is WorkspaceLaunchState.Completed)
        assertTrue(
            "all items succeed",
            (state as WorkspaceLaunchState.Completed).items.all { it.status == ItemProgress.Status.Succeeded },
        )
        // The terminal attached directly (no bus). Then: focus the terminal
        // screen, then FILE_BROWSER → DESKTOP.
        assertEquals(3, emitted.size)
        assertEquals("s-1", (emitted[0] as AgentUiCommand.FocusTerminalSession).sessionId)
        assertTrue("second should be NavigateToSftpPath",
            emitted[1] is AgentUiCommand.NavigateToSftpPath)
        assertTrue("third should be OpenRemoteDesktop",
            emitted[2] is AgentUiCommand.OpenRemoteDesktop)
        // SFTP item carries the path through.
        assertEquals(
            "/srv",
            (emitted[1] as AgentUiCommand.NavigateToSftpPath).path,
        )
    }

    @Test
    fun launchMarksItemFailedWhenProfileIsMissing() = runBlocking {
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "term-item", WorkspaceItem.Kind.TERMINAL, "missing-profile"),
        )

        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns
            WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("missing-profile") } returns null
        val (bus, _) = recordingBus()

        val launcher = launcher(workspaceRepo, connRepo, bus, mockk(), mockk())
        launcher.launch("ws-1")

        val state = launcher.state.value
        assertTrue("expected Completed (with failure inside)", state is WorkspaceLaunchState.Completed)
        val itemProgress = (state as WorkspaceLaunchState.Completed).items.single()
        assertEquals(ItemProgress.Status.Failed, itemProgress.status)
        assertEquals("profile missing", itemProgress.message)
    }

    @Test
    fun launchMarksItemFailedWhenProfileKindMismatches() = runBlocking {
        // SSH profile referenced as a DESKTOP — wrong kind.
        val sshProfile = profile("ssh-1", connectionType = "SSH")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "bad", WorkspaceItem.Kind.DESKTOP, "ssh-1"),
        )

        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns
            WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-1") } returns sshProfile
        val (bus, _) = recordingBus()

        val launcher = launcher(workspaceRepo, connRepo, bus, mockk(), mockk())
        launcher.launch("ws-1")

        val itemProgress = (launcher.state.value as WorkspaceLaunchState.Completed).items.single()
        assertEquals(ItemProgress.Status.Failed, itemProgress.status)
        assertNotNull(itemProgress.message)
        assertTrue(itemProgress.message!!.contains("not a desktop"))
    }

    @Test
    fun cancelMarksRemainingItemsSkipped() = runBlocking {
        val sshProfile = profile("ssh-1", connectionType = "SSH")
        val vncProfile = profile("vnc-1", connectionType = "VNC")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "term", WorkspaceItem.Kind.TERMINAL, "ssh-1", sessionName = "main"),
            item("ws-1", "vnc", WorkspaceItem.Kind.DESKTOP, "vnc-1"),
        )

        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns
            WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-1") } returns sshProfile
        coEvery { connRepo.getById("vnc-1") } returns vncProfile
        val (bus, emitted) = recordingBus()

        // Cancel lands while the terminal attach is in flight — the desktop
        // item behind it must skip, and no navigation fires post-cancel.
        lateinit var launcher: WorkspaceLauncher
        val attacher = attacherMock()
        coEvery { attacher.ensureAttached("ssh-1", "main") } answers {
            launcher.cancel()
            SshSessionAttacher.Result.Attached("s-1")
        }
        launcher = launcher(workspaceRepo, connRepo, bus, mockk(), attacher)

        launcher.launch("ws-1")

        val state = launcher.state.value
        assertTrue("expected Cancelled, got $state", state is WorkspaceLaunchState.Cancelled)
        val items2 = (state as WorkspaceLaunchState.Cancelled).items
        assertEquals(ItemProgress.Status.Succeeded, items2[0].status) // term ran
        assertEquals(ItemProgress.Status.Skipped, items2[1].status)   // vnc skipped
        assertEquals("cancelled", items2[1].message)
        assertTrue("no commands after cancel", emitted.isEmpty())
    }

    @Test
    fun launchPropagatesBusOverflowAsFailure() = runBlocking {
        val vncProfile = profile("vnc-1", connectionType = "VNC")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(item("ws-1", "vnc", WorkspaceItem.Kind.DESKTOP, "vnc-1"))

        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns
            WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("vnc-1") } returns vncProfile
        val bus = mockk<AgentUiCommandBus>()
        every { bus.emit(any()) } returns false  // persistent overflow

        val launcher = launcher(workspaceRepo, connRepo, bus, mockk(), mockk())
        launcher.launch("ws-1")

        val itemProgress = (launcher.state.value as WorkspaceLaunchState.Completed).items.single()
        assertEquals(ItemProgress.Status.Failed, itemProgress.status)
        assertEquals("ui bus overflow", itemProgress.message)
    }

    @Test
    fun coldPlanDialsOncePerProfileThenAttachesTheRest() = runBlocking {
        // Two terminal items on one SSH profile, nothing connected. The first
        // NoLiveConnection triggers ONE interactive dial (ConnectProfile with
        // the item's session name); once CONNECTED the remaining item attaches
        // over the same connection via the attacher.
        val sshProfile = profile("ssh-1", connectionType = "SSH")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "term-a", WorkspaceItem.Kind.TERMINAL, "ssh-1", sessionName = "cctv", sortOrder = 0),
            item("ws-1", "term-b", WorkspaceItem.Kind.TERMINAL, "ssh-1", sessionName = "civic", sortOrder = 1),
        )

        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-1") } returns sshProfile
        val (bus, emitted) = recordingBus()

        val attacher = attacherMock()
        coEvery { attacher.ensureAttached("ssh-1", "cctv") } returns
            SshSessionAttacher.Result.NoLiveConnection
        coEvery { attacher.ensureAttached("ssh-1", "civic") } returns
            SshSessionAttacher.Result.Attached("s-2")

        val launcher = launcher(workspaceRepo, connRepo, bus, sessionManager("ssh-1"), attacher)
        launcher.launch("ws-1")

        // One dial carrying term-a's session name, then the focus command —
        // term-b attached directly, no OpenTerminalSession hop.
        assertEquals(2, emitted.size)
        assertEquals("cctv", (emitted[0] as AgentUiCommand.ConnectProfile).sessionName)
        assertEquals("s-2", (emitted[1] as AgentUiCommand.FocusTerminalSession).sessionId)
        val state = launcher.state.value as WorkspaceLaunchState.Completed
        assertTrue("both items succeed", state.items.all { it.status == ItemProgress.Status.Succeeded })
    }

    @Test
    fun oldWorkspaceWithoutSessionNamesFallsBackToProfileRememberedSessions() = runBlocking {
        // Workspace saved before the feature: items carry no sessionName. The
        // profile still remembers its open sessions (lastSessionName), so restore
        // reattaches by those in order instead of showing the picker.
        val sshProfile = profile("ssh-1", connectionType = "SSH", lastSessionName = "cctv|civic")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "term-a", WorkspaceItem.Kind.TERMINAL, "ssh-1", sortOrder = 0),
            item("ws-1", "term-b", WorkspaceItem.Kind.TERMINAL, "ssh-1", sortOrder = 1),
        )
        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns WorkspaceWithItems(ws, items)
        val savedItems = slot<List<WorkspaceItem>>()
        coEvery { workspaceRepo.save(any(), capture(savedItems)) } just Runs
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-1") } returns sshProfile
        val (bus, emitted) = recordingBus()

        val attacher = attacherMock()
        coEvery { attacher.ensureAttached("ssh-1", "cctv") } returns
            SshSessionAttacher.Result.NoLiveConnection
        coEvery { attacher.ensureAttached("ssh-1", "civic") } returns
            SshSessionAttacher.Result.Attached("s-2")

        launcher(workspaceRepo, connRepo, bus, sessionManager("ssh-1"), attacher)
            .launch("ws-1")

        assertEquals("cctv", (emitted[0] as AgentUiCommand.ConnectProfile).sessionName)
        coVerify { attacher.ensureAttached("ssh-1", "civic") }
        // Self-heal: the fallback-resolved names are written back onto the
        // items (same ids), so the next restore pins them explicitly.
        assertEquals(
            mapOf("term-a" to "cctv", "term-b" to "civic"),
            savedItems.captured.associate { it.id to it.sessionName },
        )
    }

    @Test
    fun staleSessionNameIsReportedAsRecreated() = runBlocking {
        // The host's list command knows only "cctv" — attaching "civic" will
        // recreate it (`new-session -A`), and the item says so instead of
        // silently handing back an empty session.
        val sshProfile = profile("ssh-1", connectionType = "SSH")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "term-a", WorkspaceItem.Kind.TERMINAL, "ssh-1", sessionName = "cctv"),
            item("ws-1", "term-b", WorkspaceItem.Kind.TERMINAL, "ssh-1", sessionName = "civic"),
        )
        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-1") } returns sshProfile
        val (bus, _) = recordingBus()

        val attacher = mockk<SshSessionAttacher>()
        coEvery { attacher.listRemoteSessions("ssh-1") } returns listOf("cctv")
        coEvery { attacher.ensureAttached("ssh-1", "cctv") } returns
            SshSessionAttacher.Result.Attached("s-a")
        coEvery { attacher.ensureAttached("ssh-1", "civic") } returns
            SshSessionAttacher.Result.Attached("s-b")

        val launcher = launcher(workspaceRepo, connRepo, bus, mockk(), attacher)
        launcher.launch("ws-1")

        val state = launcher.state.value as WorkspaceLaunchState.Completed
        val byId = state.items.associateBy { it.itemId }
        assertEquals(ItemProgress.Status.Succeeded, byId.getValue("term-a").status)
        assertEquals(null, byId.getValue("term-a").message)
        assertEquals(ItemProgress.Status.Succeeded, byId.getValue("term-b").status)
        assertTrue(byId.getValue("term-b").message!!.contains("recreated"))
    }

    @Test
    fun duplicateNamesInRememberedPoolAreNotHandedToTwoItems() = runBlocking {
        // A lastSessionName that lists the same session twice ("cctv|cctv|haven")
        // must not resolve two nameless items to "cctv" — the second would
        // collapse into the first via AlreadyLive, silently dropping a tab. The
        // pool is de-duped, so the third item gets the distinct "haven".
        val sshProfile = profile("ssh-1", connectionType = "SSH", lastSessionName = "cctv|cctv|haven")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "term-a", WorkspaceItem.Kind.TERMINAL, "ssh-1", sortOrder = 0),
            item("ws-1", "term-b", WorkspaceItem.Kind.TERMINAL, "ssh-1", sortOrder = 1),
        )
        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns WorkspaceWithItems(ws, items)
        coEvery { workspaceRepo.save(any(), any()) } just Runs
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-1") } returns sshProfile
        val (bus, _) = recordingBus()

        val attacher = attacherMock()
        coEvery { attacher.ensureAttached("ssh-1", any()) } returns
            SshSessionAttacher.Result.Attached("s")

        launcher(workspaceRepo, connRepo, bus, mockk(), attacher).launch("ws-1")

        // term-a → cctv, term-b → haven (NOT the duplicate cctv).
        coVerify { attacher.ensureAttached("ssh-1", "cctv") }
        coVerify { attacher.ensureAttached("ssh-1", "haven") }
        coVerify(exactly = 0) { attacher.ensureAttached("ssh-1", "civic") }
    }

    @Test
    fun relaunchOverLiveSessionsIsIdempotent() = runBlocking {
        // Everything already up: no dial, no duplicate tabs — items succeed
        // via AlreadyLive and the launcher just refocuses the terminal.
        val sshProfile = profile("ssh-1", connectionType = "SSH")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "term-a", WorkspaceItem.Kind.TERMINAL, "ssh-1", sessionName = "cctv"),
            item("ws-1", "term-b", WorkspaceItem.Kind.TERMINAL, "ssh-1", sessionName = "civic"),
        )
        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-1") } returns sshProfile
        val (bus, emitted) = recordingBus()

        val attacher = attacherMock()
        coEvery { attacher.ensureAttached("ssh-1", "cctv") } returns
            SshSessionAttacher.Result.AlreadyLive("s-a")
        coEvery { attacher.ensureAttached("ssh-1", "civic") } returns
            SshSessionAttacher.Result.AlreadyLive("s-b")

        val launcher = launcher(workspaceRepo, connRepo, bus, mockk(), attacher)
        launcher.launch("ws-1")

        val state = launcher.state.value as WorkspaceLaunchState.Completed
        assertTrue(state.items.all { it.status == ItemProgress.Status.Succeeded })
        assertEquals(1, emitted.size)
        assertEquals("s-a", (emitted[0] as AgentUiCommand.FocusTerminalSession).sessionId)
    }

    @Test
    fun plansOnDifferentProfilesFailIndependently() = runBlocking {
        val profileA = profile("ssh-a", connectionType = "SSH")
        val profileB = profile("ssh-b", connectionType = "SSH")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "term-a", WorkspaceItem.Kind.TERMINAL, "ssh-a", sessionName = "one"),
            item("ws-1", "term-b", WorkspaceItem.Kind.TERMINAL, "ssh-b", sessionName = "two"),
        )
        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-a") } returns profileA
        coEvery { connRepo.getById("ssh-b") } returns profileB
        val (bus, _) = recordingBus()

        val attacher = attacherMock()
        coEvery { attacher.ensureAttached("ssh-a", "one") } returns
            SshSessionAttacher.Result.Failed("shell closed")
        coEvery { attacher.ensureAttached("ssh-b", "two") } returns
            SshSessionAttacher.Result.Attached("s-b")

        val launcher = launcher(workspaceRepo, connRepo, bus, mockk(), attacher)
        launcher.launch("ws-1")

        val state = launcher.state.value as WorkspaceLaunchState.Completed
        val byId = state.items.associateBy { it.itemId }
        assertEquals(ItemProgress.Status.Failed, byId.getValue("term-a").status)
        assertEquals("shell closed", byId.getValue("term-a").message)
        assertEquals(ItemProgress.Status.Succeeded, byId.getValue("term-b").status)
    }

    @Test
    fun dialTimeoutFailsOnlyThatProfilesPlan() = runTest {
        // Profile A's dial never reaches CONNECTED (45s virtual timeout);
        // profile B attaches fine. The launch as a whole completes — one
        // stuck host no longer blocks the workspace.
        val profileA = profile("ssh-a", connectionType = "SSH")
        val profileB = profile("ssh-b", connectionType = "SSH")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "term-a", WorkspaceItem.Kind.TERMINAL, "ssh-a", sessionName = "one"),
            item("ws-1", "term-b", WorkspaceItem.Kind.TERMINAL, "ssh-b", sessionName = "two"),
        )
        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-a") } returns profileA
        coEvery { connRepo.getById("ssh-b") } returns profileB
        val (bus, _) = recordingBus()

        val attacher = attacherMock()
        coEvery { attacher.ensureAttached("ssh-a", "one") } returns
            SshSessionAttacher.Result.NoLiveConnection
        coEvery { attacher.ensureAttached("ssh-b", "two") } returns
            SshSessionAttacher.Result.Attached("s-b")

        // No session ever appears for ssh-a — the await must time out.
        val sm = mockk<SshSessionManager>()
        every { sm.sessions } returns MutableStateFlow(emptyMap())

        val launcher = launcher(workspaceRepo, connRepo, bus, sm, attacher)
        launcher.launch("ws-1")

        val state = launcher.state.value as WorkspaceLaunchState.Completed
        val byId = state.items.associateBy { it.itemId }
        assertEquals(ItemProgress.Status.Failed, byId.getValue("term-a").status)
        assertTrue(byId.getValue("term-a").message!!.contains("did not come up"))
        assertEquals(ItemProgress.Status.Succeeded, byId.getValue("term-b").status)
    }

    @Test
    fun namelessDialDoesNotBlockAndFollowersReportUnavailable() = runBlocking {
        // No stored or remembered session names at all: the dial shows the
        // interactive picker, so the launcher must not wait on CONNECTED, and
        // a second nameless item can't ride a connection that isn't up yet.
        val sshProfile = profile("ssh-1", connectionType = "SSH")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "term-a", WorkspaceItem.Kind.TERMINAL, "ssh-1", sortOrder = 0),
            item("ws-1", "term-b", WorkspaceItem.Kind.TERMINAL, "ssh-1", sortOrder = 1),
        )
        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-1") } returns sshProfile
        val (bus, emitted) = recordingBus()

        val attacher = attacherMock()
        coEvery { attacher.ensureAttached("ssh-1", null) } returns
            SshSessionAttacher.Result.NoLiveConnection

        val launcher = launcher(workspaceRepo, connRepo, bus, mockk(), attacher)
        launcher.launch("ws-1")

        assertEquals(1, emitted.size)
        val dial = emitted[0] as AgentUiCommand.ConnectProfile
        assertEquals(null, dial.sessionName)
        val state = launcher.state.value as WorkspaceLaunchState.Completed
        val byId = state.items.associateBy { it.itemId }
        assertEquals(ItemProgress.Status.Succeeded, byId.getValue("term-a").status)
        assertEquals(ItemProgress.Status.Failed, byId.getValue("term-b").status)
        assertEquals("connection unavailable", byId.getValue("term-b").message)
    }

    // ---- helpers ----

    private fun launcher(
        workspaceRepo: WorkspaceRepository,
        connRepo: ConnectionRepository,
        bus: AgentUiCommandBus,
        sessionManager: SshSessionManager,
        attacher: SshSessionAttacher,
    ) = WorkspaceLauncher(workspaceRepo, connRepo, bus, sessionManager, attacher)

    /**
     * A strict attacher mock with the reconciliation probe defaulted to
     * "unknowable" (null) so tests opt in to remote-list ground truth
     * explicitly; stub `ensureAttached` per test.
     */
    private fun attacherMock(): SshSessionAttacher = mockk {
        coEvery { listRemoteSessions(any()) } returns null
    }

    private fun recordingBus(): Pair<AgentUiCommandBus, MutableList<AgentUiCommand>> {
        val bus = mockk<AgentUiCommandBus>()
        val emitted = mutableListOf<AgentUiCommand>()
        every { bus.emit(any()) } answers {
            emitted += firstArg<AgentUiCommand>()
            true
        }
        return bus to emitted
    }

    /** A session manager whose flow reports the profile CONNECTED, so a dial's await resolves. */
    private fun sessionManager(connectedProfileId: String): SshSessionManager {
        val sm = mockk<SshSessionManager>()
        val session = mockk<SshSessionManager.SessionState> {
            every { profileId } returns connectedProfileId
            every { status } returns SshSessionManager.SessionState.Status.CONNECTED
        }
        every { sm.sessions } returns MutableStateFlow(mapOf("s-1" to session))
        return sm
    }

    private fun item(
        workspaceId: String,
        id: String,
        kind: WorkspaceItem.Kind,
        connectionProfileId: String?,
        path: String? = null,
        sessionName: String? = null,
        sortOrder: Int = 0,
    ) = WorkspaceItem(
        id = id,
        workspaceId = workspaceId,
        kind = kind,
        connectionProfileId = connectionProfileId,
        path = path,
        sessionName = sessionName,
        sortOrder = sortOrder,
    )

    private fun profile(
        id: String,
        connectionType: String,
        lastSessionName: String? = null,
    ) = ConnectionProfile(
        id = id,
        label = "$id label",
        host = "host.example",
        port = 22,
        username = "user",
        connectionType = connectionType,
        lastSessionName = lastSessionName,
    )
}
