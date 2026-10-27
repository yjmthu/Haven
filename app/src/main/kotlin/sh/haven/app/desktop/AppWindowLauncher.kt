package sh.haven.app.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import sh.haven.core.data.message.UserMessage
import sh.haven.core.data.message.UserMessageBus
import sh.haven.core.data.preferences.AppWindowDef
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.LocalSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts a saved app window (cage kiosk), with no composed UI required.
 * Extracted from [DesktopViewModel.launchAppWindow] so a home-screen
 * launcher shortcut can reach it on a cold start.
 */
@Singleton
class AppWindowLauncher @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository,
    localSessionManager: LocalSessionManager,
    private val userMessageBus: UserMessageBus,
) {
    private val desktopManager: DesktopManager = localSessionManager.desktopManager

    /** Resolve [defId] from prefs and [launch] it; a deleted def drops silently (returns null). */
    suspend fun launchById(defId: String): String? {
        val def = preferencesRepository.appWindowDefs.first().items.firstOrNull { it.id == defId }
            ?: return null
        return launch(def)
    }

    /**
     * Start [def]'s cage and present it. Returns null on success, or a
     * user-facing message — a read-only-fallback note when root mode was
     * requested but `fakeroot` is unavailable, or the launch error — for
     * the caller to surface.
     */
    suspend fun launch(def: AppWindowDef): String? = withContext(Dispatchers.IO) {
        // startAppWindow blocks (up to ~15s) polling the cage's VNC port, so
        // the whole thing runs off the caller's thread — the shortcut path
        // invokes this from MainScope (the main thread) and must not freeze
        // the UI that renders the present overlay.
        val resolution = def.resolution ?: preferencesRepository.appWindowDefaultResolution.first()
        val scale = def.scale ?: preferencesRepository.appWindowDefaultScale.first()
        // The cage runs the app as a single-app sway kiosk, so sway+wayvnc must
        // be installed. Install on demand (slow, non-streaming) with a heads-up.
        if (!desktopManager.isCageRuntimeReady()) {
            userMessageBus.emit(
                UserMessage(
                    "Installing the cage runtime (sway/wayvnc) for ${def.label} — this can take a minute…",
                    UserMessage.Severity.INFO,
                ),
            )
            if (!desktopManager.ensureCageRuntime()) {
                return@withContext "Couldn't install the cage runtime (sway/wayvnc) for ${def.label}"
            }
        }
        val rooted = if (def.runAsRoot) desktopManager.ensureRunAsRoot() else false
        val session = desktopManager.startAppWindow(def.command, resolution, scale, runAsRoot = rooted, multiWindow = def.multiWindow, swayRules = def.swayRules)
        if (session.state != DesktopManager.DesktopState.RUNNING) {
            return@withContext "Couldn't launch ${def.label}: ${session.errorMessage ?: "failed to start"}"
        }
        // Preserve the def's own resolution/scale/runAsRoot choice (null = use global).
        preferencesRepository.upsertAppWindowDef(
            def.label, def.command, def.createdBy, def.fullscreen, def.resolution, def.scale, def.runAsRoot,
        )
        if (def.runAsRoot && !rooted) {
            "Root mode needs the fakeroot package — ${def.label} will run read-only"
        } else {
            null
        }
    }
}
