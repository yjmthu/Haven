package sh.haven.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import sh.haven.core.data.NativeCrashLog
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.stepca.CertRenewalWorker
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class HavenApp : Application(), Configuration.Provider {

    @Inject lateinit var preferencesRepository: UserPreferencesRepository
    @Inject lateinit var launcherShortcutManager: sh.haven.app.launcher.LauncherShortcutManager
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var prootManager: sh.haven.core.local.ProotManager
    @Inject lateinit var guestServiceManager: sh.haven.core.local.GuestServiceManager
    @Inject lateinit var sessionManagerRegistry: sh.haven.core.ssh.SessionManagerRegistry
    @Inject lateinit var backupAutoSyncScheduler: sh.haven.app.backup.BackupAutoSyncScheduler
    @Inject lateinit var backupAutoPullScheduler: sh.haven.app.backup.BackupAutoPullScheduler
    @Inject lateinit var sshTerminalEmulatorOwner: sh.haven.feature.terminal.SshTerminalEmulatorOwner
    @Inject lateinit var updateNotifier: sh.haven.app.update.UpdateNotifier
    @Inject lateinit var usbMountCorrelator: sh.haven.app.usb.UsbMountCorrelator

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Required by [Configuration.Provider] so the Hilt-aware worker
     * factory is wired before any [androidx.work.WorkManager] lookup.
     * [CertRenewalWorker] (#133 phase 2b) needs `@AssistedInject` deps,
     * which require this. Other workers (e.g. ReticulumWorker) keep
     * working unchanged — plain-constructor workers don't go through
     * the factory.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Before any SSH session exists: on some devices AndroidKeyStore wins an
        // unpinned raw-key JCE lookup, which breaks every curve25519 key exchange
        // on the sshlib engine. No-op on devices with a sane provider order (#451).
        sh.haven.core.ssh.RawKeyProviderOrder.apply()

        // Register the connect-time SSH emulator provider before any SSH session
        // can be created, so tmux's attach-time probes are answered live (#290 #2).
        sshTerminalEmulatorOwner.start()

        // Apply the saved theme override to AppCompatDelegate before any
        // activity is created, so the cold-launch splash window uses the
        // user's chosen mode rather than only the system uiMode (#153).
        // Synchronous DataStore read is intentional here — must happen
        // before the first activity, and the cost is bounded.
        val savedMode = try {
            runBlocking { preferencesRepository.theme.first() }
        } catch (_: Exception) {
            UserPreferencesRepository.ThemeMode.SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(savedMode.toNightMode())

        // Register Shizuku binder listeners early so the async callback
        // has time to fire before any UI checks isShizukuAvailable().
        sh.haven.core.local.WaylandSocketHelper.initShizukuListeners()

        // Recover the tombstone from any previous native crash (#509, #517).
        // Haven records its logcat from inside its own process, so a native
        // signal kills the recorder along with everything else and the backtrace
        // is written after Haven is already gone — the one log that identifies a
        // native crash was the one Haven structurally could not capture. This is
        // the next launch asking the system what happened to the last one.
        //
        // Off the main thread: it reads a file and a system service, and nothing
        // about startup should wait on diagnostics.
        appScope.launch {
            try {
                NativeCrashLog(this@HavenApp).refresh()
            } catch (e: Exception) {
                android.util.Log.w("HavenApp", "native crash sweep failed: ${e.message}")
            }
            // Same next-launch pattern for non-crash deaths (#494): fold the
            // system's kill records (force-stop, freezer, low memory, ANR)
            // into the process-exit log, so the Connections screen and the
            // get_process_exits MCP verb can say who killed the last process
            // instead of a reporter needing adb.
            try {
                sh.haven.core.data.ProcessExitLog(this@HavenApp).refresh()
            } catch (e: Exception) {
                android.util.Log.w("HavenApp", "process exit sweep failed: ${e.message}")
            }
        }

        // Mirror saved connections (#699) and workspaces into Android
        // launcher long-press shortcuts — "Connect <profile>" and
        // "Open <workspace>" entries from the home screen. Self-observing —
        // recomputes on every repo change.
        launcherShortcutManager.start()

        // Schedule the daily step-ca cert-renewal check (#133 phase 2b).
        // Idempotent (KEEP policy); cheap when the user has no CAs
        // configured — the worker enumerates SshKeys and exits early.
        CertRenewalWorker.schedule(this)

        // Auto-push backup watch (#359) — same pattern: inert until the user
        // enables auto-sync in Settings → Backup → Sync to a remote.
        backupAutoSyncScheduler.start(appScope)
        backupAutoPullScheduler.start(appScope)

        // Opt-in update check (#578). Same pattern again: returns without a
        // network request unless the switch is on, the copy was signed with the
        // GitHub-release key, and a day has passed since the last look.
        updateNotifier.start(appScope)

        // Extend the shell-prompt terminator set used for command-on-attach
        // detection with the user's custom prompt characters (#280). Replays
        // the current value on subscribe, so it's armed before the first
        // session opens. TerminalSession keeps it in a process-wide field
        // (no DataStore dependency in core:ssh).
        preferencesRepository.terminalPromptChars
            .distinctUntilChanged()
            .onEach { extra ->
                sh.haven.core.ssh.TerminalSession.promptTerminators =
                    sh.haven.core.ssh.TerminalSession.DEFAULT_PROMPT_TERMINATORS +
                    extra.filterNot { it.isWhitespace() }.toSet()
            }
            .launchIn(appScope)

        // #418 debug: bridge the "RDP progressive upgrade" preference into the
        // process-global RdpDebugToggles the native decoder reads at connect.
        // Replays on subscribe (seeds at startup) and updates live when the
        // Settings → Diagnostics toggle changes. Temporary until the upgrade
        // decode is verified and enabled by default.
        preferencesRepository.rdpProgressiveUpgrade
            .distinctUntilChanged()
            .onEach { sh.haven.core.rdp.RdpDebugToggles.progressiveUpgrade = it }
            .launchIn(appScope)

        // #425: bridge the "RDP H.264/AVC420 (KRDP)" preference into the same
        // process-global toggles so the native side advertises AVC and the
        // MediaCodec decoder is registered at connect.
        preferencesRepository.rdpAvcEnabled
            .distinctUntilChanged()
            .onEach { sh.haven.core.rdp.RdpDebugToggles.avcEnabled = it }
            .launchIn(appScope)

        // Auto-detect a USB mass-storage drive on plug-in and offer (via a
        // notification) to open it in a VM (#287). Runtime receiver — fires only
        // while the app process is alive, so the notification tap always lands
        // on a live UI. The open itself is still a deliberate tap, never automatic.
        registerUsbDriveAttachReceiver()

        // #603: seed the USB mount correlator's "preexisting volumes" set so
        // the weak (no-attach-baseline) match can never claim a volume that
        // was already mounted before this process started (e.g. a microSD).
        appScope.launch { usbMountCorrelator.onAppStarted() }
    }

    private val usbDriveAttachReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) return
            val device: android.hardware.usb.UsbDevice? =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(
                        android.hardware.usb.UsbManager.EXTRA_DEVICE,
                        android.hardware.usb.UsbDevice::class.java,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                }
            if (device == null) return
            // Off the main thread: the interface scan + notification post touch
            // USB metadata and the NotificationManager binder, which a stalled
            // USB stack can make block — never on the broadcast thread.
            appScope.launch {
                if (isMassStorageDevice(device)) {
                    // Record the pre-mount volume set for the #603 route
                    // correlation BEFORE vold's mount can land (it follows the
                    // attach by ~0.5–2s). Must stay before the notification so
                    // a slow snapshot never runs after the mount appears.
                    usbMountCorrelator.onDeviceAttached(device.deviceName)
                    postUsbDriveDetectedNotification(device)
                }
            }
        }
    }

    private fun registerUsbDriveAttachReceiver() {
        val filter = android.content.IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDriveAttachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbDriveAttachReceiver, filter)
        }
    }

    private fun isMassStorageDevice(device: android.hardware.usb.UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == USB_CLASS_MASS_STORAGE) return true
        }
        return false
    }

    /**
     * "USB drive detected — tap to open it" heads-up. The content intent carries
     * [ACTION_OPEN_USB_DRIVE] + the device name; [MainActivity] re-publishes it
     * onto the UI command bus (→ Desktop, open the drive). One stable id so
     * re-plugging coalesces rather than spamming.
     */
    private fun postUsbDriveDetectedNotification(device: android.hardware.usb.UsbDevice) {
        val mgr = NotificationManagerCompat.from(this)
        if (!mgr.areNotificationsEnabled()) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(USB_DRIVE_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(USB_DRIVE_CHANNEL_ID, "USB drives", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Offers to open a plugged-in USB drive in a small Linux VM so its " +
                        "files are readable — even Linux-formatted (ext4/GPT) drives Android can't open."
                },
            )
        }
        val launch = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_USB_DRIVE
            putExtra(EXTRA_USB_DEVICE_NAME, device.deviceName)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, ACTION_OPEN_USB_DRIVE.hashCode(), launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val name = device.productName ?: "USB drive"
        val builder = NotificationCompat.Builder(this, USB_DRIVE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("USB drive detected")
            .setContentText("Tap to open $name's files in Haven.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Tap to open $name in a small on-device Linux VM and browse its files — even " +
                        "Linux-formatted (ext4/GPT) drives your phone can't read directly. Read-only.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
        try {
            mgr.notify(USB_DRIVE_NOTIF_ID, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify; ignore.
        }
    }

    /**
     * Start [SshConnectionService] from the Application context. The
     * service's onStartCommand calls startForeground within Android's
     * 5-second budget, so this is safe from a non-Activity context
     * provided the MCP toggle was changed while the app is in the
     * foreground (it always is — the toggle lives in Settings). If a
     * background restart ever flips the preference (none currently
     * does), the OS may refuse the start; the catch keeps Haven alive.
     */
    private fun startSessionServiceIfPossible() {
        try {
            val intent = Intent(this, sh.haven.core.ssh.SshConnectionService::class.java)
            startForegroundService(intent)
        } catch (e: Exception) {
            android.util.Log.w("HavenApp", "startForegroundService for MCP keep-alive failed: ${e.message}")
        }
    }

    companion object {
        private const val USB_DRIVE_CHANNEL_ID = "haven-usb-drive-detected"
        private const val USB_DRIVE_NOTIF_ID = 287_287
        private const val USB_CLASS_MASS_STORAGE = 8

        /** Explicit-intent action on the "USB drive detected" notification tap. */
        const val ACTION_OPEN_USB_DRIVE = "sh.haven.app.action.OPEN_USB_DRIVE"
        const val EXTRA_USB_DEVICE_NAME = "sh.haven.app.extra.USB_DEVICE_NAME"
    }
}

internal fun UserPreferencesRepository.ThemeMode.toNightMode(): Int = when (this) {
    UserPreferencesRepository.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    UserPreferencesRepository.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    UserPreferencesRepository.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
}
