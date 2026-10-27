package sh.haven.app.desktop

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import sh.haven.core.data.preferences.AppWindowDef
import sh.haven.core.data.preferences.AppWindowDefList
import sh.haven.core.data.preferences.AppWindowOrigin
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.LocalSessionManager

class AppWindowLauncherTest {

    private fun def(id: String = "d1") = AppWindowDef(
        id = id,
        label = "Calc",
        command = "galculator",
        createdBy = AppWindowOrigin.USER,
        fullscreen = true,
        resolution = null,
        scale = null,
        runAsRoot = false,
    )

    private fun session(state: DesktopManager.DesktopState, port: Int = 5901) =
        DesktopManager.AppWindowSession(
            sessionId = "appwin-1",
            command = "galculator",
            displayNumber = 1,
            vncPort = port,
            state = state,
        )

    private fun launcher(
        defs: List<AppWindowDef>,
        dm: DesktopManager,
        repo: UserPreferencesRepository = mockk(relaxed = true),
    ): AppWindowLauncher {
        every { repo.appWindowDefs } returns flowOf(AppWindowDefList(defs))
        every { repo.appWindowDefaultResolution } returns flowOf("auto")
        every { repo.appWindowDefaultScale } returns flowOf(1f)
        coEvery { repo.upsertAppWindowDef(any(), any(), any(), any(), any(), any(), any()) } just Runs
        val lsm = mockk<LocalSessionManager>()
        every { lsm.desktopManager } returns dm
        return AppWindowLauncher(repo, lsm, mockk(relaxed = true))
    }

    @Test
    fun launchByIdDropsAMissingDef() = runBlocking {
        val dm = mockk<DesktopManager>()
        val l = launcher(defs = emptyList(), dm = dm)

        assertNull(l.launchById("nope"))

        verify(exactly = 0) { dm.startAppWindow(any(), any(), any(), any(), any()) }
    }

    @Test
    fun launchStartsTheRunningCageWithTheDefsCommand() = runBlocking {
        val dm = mockk<DesktopManager>()
        every { dm.isCageRuntimeReady() } returns true
        every { dm.startAppWindow(any(), any(), any(), runAsRoot = any()) } returns
            session(DesktopManager.DesktopState.RUNNING, port = 5907)
        val l = launcher(defs = listOf(def()), dm = dm)

        val message = l.launchById("d1")

        assertNull(message) // success → no user-facing message
        verify(exactly = 1) { dm.startAppWindow(any(), any(), any(), runAsRoot = any()) }
    }
}
