package sh.haven.app.workspace

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.WorkspaceRepository
import sh.haven.core.data.repository.WorkspaceWithItems
import sh.haven.core.ssh.Session
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SessionStatus
import sh.haven.core.ssh.Transport

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var workspaceRepo: WorkspaceRepository
    private lateinit var launcher: WorkspaceLauncher
    private lateinit var registry: SessionManagerRegistry
    private lateinit var connRepo: ConnectionRepository
    private lateinit var vm: WorkspaceViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        workspaceRepo = mockk(relaxed = true) {
            every { observeAll() } returns flowOf(emptyList())
        }
        launcher = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(WorkspaceLaunchState.Idle)
        }
        registry = mockk()
        connRepo = mockk(relaxed = true)
        vm = WorkspaceViewModel(workspaceRepo, launcher, registry, connRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun launchDelegatesToLauncher() = runTest(testDispatcher) {
        coEvery { launcher.launch("ws-1") } just Runs

        vm.launch("ws-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { launcher.launch("ws-1") }
    }

    @Test
    fun cancelDelegatesToLauncher() {
        every { launcher.cancel() } just Runs
        vm.cancel()
        verify(exactly = 1) { launcher.cancel() }
    }

    @Test
    fun acknowledgeDelegatesToLauncher() {
        every { launcher.acknowledge() } just Runs
        vm.acknowledge()
        verify(exactly = 1) { launcher.acknowledge() }
    }

    @Test
    fun saveRejectsBlankName() = runTest(testDispatcher) {
        vm.save(name = "  ", items = listOf(terminalItem("ws-1", "p1")))
        advanceUntilIdle()
        coVerify(exactly = 0) { workspaceRepo.save(any(), any()) }
    }

    @Test
    fun saveRejectsEmptyItems() = runTest(testDispatcher) {
        vm.save(name = "Work", items = emptyList())
        advanceUntilIdle()
        coVerify(exactly = 0) { workspaceRepo.save(any(), any()) }
    }

    @Test
    fun saveTrimsNameAndPropagatesItems() = runTest(testDispatcher) {
        coEvery { workspaceRepo.save(any(), any()) } just Runs
        val items = listOf(terminalItem("placeholder", "p1"))

        vm.save(name = "  Work  ", items = items)
        advanceUntilIdle()

        coVerify {
            workspaceRepo.save(
                profile = match { it.name == "Work" },
                items = match { savedItems ->
                    savedItems.size == 1 &&
                        savedItems.single().connectionProfileId == "p1"
                },
            )
        }
    }

    @Test
    fun renameUpdatesProfileNameAndKeepsItems() = runTest(testDispatcher) {
        val original = WorkspaceProfile(id = "ws-1", name = "Old", createdAt = 100L)
        val items = listOf(terminalItem("ws-1", "p1"))
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns
            WorkspaceWithItems(original, items)
        coEvery { workspaceRepo.save(any(), any()) } just Runs

        vm.rename("ws-1", "  New  ")
        advanceUntilIdle()

        coVerify {
            workspaceRepo.save(
                profile = match { it.id == "ws-1" && it.name == "New" && it.createdAt == 100L },
                items = match { it == items },
            )
        }
    }

    @Test
    fun renameDoesNothingForBlankName() = runTest(testDispatcher) {
        vm.rename("ws-1", "   ")
        advanceUntilIdle()
        coVerify(exactly = 0) { workspaceRepo.save(any(), any()) }
        coVerify(exactly = 0) { workspaceRepo.getWorkspace(any()) }
    }

    @Test
    fun deleteDelegatesToRepository() = runTest(testDispatcher) {
        coEvery { workspaceRepo.delete("ws-1") } just Runs

        vm.delete("ws-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepo.delete("ws-1") }
    }

    @Test
    fun captureMapsTransportsToKindsAndFiltersUnconnected() = runTest(testDispatcher) {
        every { registry.allSessions } returns listOf(
            session("ssh-id", "p-ssh", Transport.SSH, SessionStatus.CONNECTED),
            session("mosh-id", "p-mosh", Transport.MOSH, SessionStatus.CONNECTED),
            session("rdp-id", "p-rdp", Transport.RDP, SessionStatus.CONNECTED),
            // Filtered: not connected.
            session("dropped-id", "p-dropped", Transport.SSH, SessionStatus.DISCONNECTED),
            session("connecting-id", "p-conn", Transport.SSH, SessionStatus.CONNECTING),
        )

        val drafts = vm.captureFromSingletons("ws-new")
        // Exactly the 3 connected session items.
        assertEquals(3, drafts.size)
        assertEquals(
            listOf("p-ssh", "p-mosh", "p-rdp"),
            drafts.map { it.item.connectionProfileId },
        )
        assertEquals(
            listOf(
                WorkspaceItem.Kind.TERMINAL,    // SSH
                WorkspaceItem.Kind.TERMINAL,    // MOSH
                WorkspaceItem.Kind.DESKTOP,      // RDP
            ),
            drafts.map { it.item.kind },
        )
        assertTrue(drafts.all { it.item.workspaceId == "ws-new" })
        assertEquals(listOf(0, 1, 2), drafts.map { it.item.sortOrder })
    }

    @Test
    fun captureLabelsTerminalWithHostManagerAndSessionName() = runTest(testDispatcher) {
        coEvery { connRepo.getById("p-ssh") } returns
            sh.haven.core.data.db.entities.ConnectionProfile(
                id = "p-ssh", label = "msi", host = "msi-z790", username = "ian",
            )
        every { registry.allSessions } returns listOf(
            session("ssh-id", "p-ssh", Transport.SSH, SessionStatus.CONNECTED,
                sessionName = "haven", sessionManagerLabel = "tmux"),
        )

        val draft = vm.captureFromSingletons("ws-1").single()

        assertEquals("msi-z790", draft.host)
        assertEquals("tmux", draft.sessionType)
        assertEquals("haven", draft.item.sessionName)
    }

    @Test
    fun captureSkipsEtWhenDisconnected() = runTest(testDispatcher) {
        every { registry.allSessions } returns listOf(
            session("et-id", "p-et", Transport.ET, SessionStatus.ERROR),
            session("ssh-id", "p-ssh", Transport.SSH, SessionStatus.CONNECTED),
        )

        val drafts = vm.captureFromSingletons("ws-1")

        assertEquals(1, drafts.size)
        assertEquals("p-ssh", drafts.single().item.connectionProfileId)
    }

    private fun session(
        id: String,
        profileId: String,
        transport: Transport,
        status: SessionStatus,
        sessionName: String? = null,
        sessionManagerLabel: String? = null,
    ): Session = object : Session {
        override val sessionId = id
        override val profileId = profileId
        override val label = "$transport label"
        override val status = status
        override val transport = transport
        override val sessionName = sessionName
        override val sessionManagerLabel = sessionManagerLabel
    }

    private fun terminalItem(workspaceId: String, profileId: String) = WorkspaceItem(
        workspaceId = workspaceId,
        kind = WorkspaceItem.Kind.TERMINAL,
        connectionProfileId = profileId,
    )
}
