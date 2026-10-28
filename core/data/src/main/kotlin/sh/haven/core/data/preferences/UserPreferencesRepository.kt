package sh.haven.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import sh.haven.core.security.CredentialEncryption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) {
    private val biometricEnabledKey = booleanPreferencesKey("biometric_enabled")
    private val terminalFontSizeKey = intPreferencesKey("terminal_font_size")

    // Horizontal margin around the terminal grid in dp (#745). Shrinking the
    // measured width makes termlib recompute columns and resize the PTY.
    private val terminalHorizontalPaddingKey = intPreferencesKey("terminal_horizontal_padding")

    // Mail message-list pinch-zoom factor, multiplied onto the terminal font size.
    private val mailFontScaleKey = floatPreferencesKey("mail_font_scale")
    private val terminalScrollbackRowsKey = intPreferencesKey("terminal_scrollback_rows")
    private val rdpDesktopWidthKey = intPreferencesKey("rdp_desktop_width")
    private val rdpDesktopHeightKey = intPreferencesKey("rdp_desktop_height")
    private val terminalTapToPositionCursorKey = booleanPreferencesKey("terminal_tap_to_position_cursor")

    // #753: raise a system notification when a terminal rings BEL while Haven
    // is backgrounded. Default on — the request's whole point is being told
    // about a finished build when you're not looking at the terminal.
    private val terminalBellNotificationKey = booleanPreferencesKey("terminal_bell_notification")

    // #418 debug: enable RemoteFX-Progressive WBT_TILE_UPGRADE refinement
    // decoding for RDP. Off by default while the upgrade path is verified.
    private val rdpProgressiveUpgradeKey = booleanPreferencesKey("rdp_progressive_upgrade")

    // #425: advertise EGFX H.264/AVC420 so H.264-only servers (KRDP) render.
    // Off by default while the MediaCodec decode path is verified on-device.
    private val rdpAvcEnabledKey = booleanPreferencesKey("rdp_avc_enabled")

    // Absolute path to a user-chosen Nerd Font (or any TTF/OTF). #123.
    private val terminalFontPathKey = stringPreferencesKey("terminal_font_path")
    private val terminalAmbiwidthModeKey = stringPreferencesKey("terminal_ambiwidth_mode")
    private val ambiwidthKey = booleanPreferencesKey("ambiwidth")

    // Extra trailing prompt characters (beyond $ # % > ❯) for command-on-attach
    // detection — supports custom prompts ending in e.g. » or 尺 (#280).
    private val terminalPromptCharsKey = stringPreferencesKey("terminal_prompt_chars")

    // LANG value exported into the local proot/Android shell (#282).
    private val terminalLocaleKey = stringPreferencesKey("terminal_locale")
    private val themeKey = stringPreferencesKey("theme")
    private val sessionManagerKey = stringPreferencesKey("session_manager")
    private val reticulumRpcKeyKey = stringPreferencesKey("reticulum_rpc_key")
    private val reticulumHostKey = stringPreferencesKey("reticulum_host")
    private val reticulumPortKey = intPreferencesKey("reticulum_port")
    private val terminalColorSchemeKey = stringPreferencesKey("terminal_color_scheme")
    private val terminalAutoSwitchSchemeKey = booleanPreferencesKey("terminal_auto_switch_scheme")
    private val terminalLightColorSchemeKey = stringPreferencesKey("terminal_light_color_scheme")
    private val terminalDarkColorSchemeKey = stringPreferencesKey("terminal_dark_color_scheme")
    private val terminalApplySchemePaletteKey = booleanPreferencesKey("terminal_apply_scheme_palette")
    private val terminalBackgroundOpacityKey = floatPreferencesKey("terminal_background_opacity")
    private val terminalInlineImagesKey = stringPreferencesKey("terminal_inline_images")
    private val toolbarRowsKey = intPreferencesKey("toolbar_rows") // legacy
    private val toolbarRow1Key = stringPreferencesKey("toolbar_row1") // legacy
    private val toolbarRow2Key = stringPreferencesKey("toolbar_row2") // legacy
    private val toolbarLayoutKey = stringPreferencesKey("toolbar_layout")
    private val snippetLibraryKey = stringPreferencesKey("snippet_library")
    private val toolbarMinButtonWidthKey = intPreferencesKey("toolbar_min_button_width")
    private val toolbarKeyScaleKey = floatPreferencesKey("toolbar_key_scale")
    private val navBlockModeKey = stringPreferencesKey("nav_block_mode")
    private val toolbarUniformGridKey = booleanPreferencesKey("toolbar_uniform_grid")
    private val editModeControlsPlacementKey = stringPreferencesKey("edit_mode_controls_placement")
    private val desktopKeyPlacementKey = stringPreferencesKey("desktop_key_placement")
    private val fullscreenButtonCornerKey = stringPreferencesKey("fullscreen_button_corner")
    private val rdpChipAnchorKey = stringPreferencesKey("rdp_fullscreen_chip_anchor")
    private val sessionCommandOverrideKey = stringPreferencesKey("session_command_override")
    private val sftpSortModeKey = stringPreferencesKey("sftp_sort_mode")
    private val lockTimeoutKey = stringPreferencesKey("lock_timeout")
    private val screenSecurityKey = booleanPreferencesKey("screen_security")
    private val showSearchButtonKey = booleanPreferencesKey("show_search_button")
    private val showCopyOutputButtonKey = booleanPreferencesKey("show_copy_output_button")
    private val keepScreenOnInTerminalKey = booleanPreferencesKey("keep_screen_on_in_terminal")
    private val autoShowKeyboardInTerminalKey = booleanPreferencesKey("auto_show_keyboard_in_terminal")
    private val hideNavBarInTerminalKey = booleanPreferencesKey("hide_nav_bar_in_terminal")
    private val connectionLoggingEnabledKey = booleanPreferencesKey("connection_logging_enabled")
    private val excludeFromRecentsKey = booleanPreferencesKey("exclude_from_recents")
    private val backupSyncProfileIdKey = stringPreferencesKey("backup_sync_profile_id")
    private val backupSyncPathKey = stringPreferencesKey("backup_sync_path")
    private val backupAutoSyncEnabledKey = booleanPreferencesKey("backup_auto_sync_enabled")
    private val backupAutoPullEnabledKey = booleanPreferencesKey("backup_auto_pull_enabled")
    private val backupAutoPullIntervalMinutesKey = intPreferencesKey("backup_auto_pull_interval_minutes")

    // CredentialEncryption-wrapped backup passphrase for background pushes (#359).
    private val backupSyncPassphraseKey = stringPreferencesKey("backup_sync_passphrase")

    // Session command for the Custom (X11) desktop (#361).
    private val customDesktopCommandKey = stringPreferencesKey("custom_desktop_command")

    // Which backend the Desktop screen's primary "Start" uses: "quick" (UML)
    // or "full" (proot). Unset = never chosen (falls back to quick when the
    // UML payload is present, else full). Set the moment the user starts a
    // desktop or taps the switch link, so the choice is last-used, not a
    // silent default flip for existing proot users.
    private val primaryDesktopBackendKey = stringPreferencesKey("primary_desktop_backend")

    // Last-started UML kind ("x11"/"wayland") and proot DE id, so the primary
    // Start re-launches what the user actually used rather than a fixed one.
    private val lastUmlKindKey = stringPreferencesKey("last_uml_kind")
    private val mailAutomationEnabledKey = booleanPreferencesKey("mail_automation_enabled")
    private val mailDeleteToBinKey = booleanPreferencesKey("mail_delete_to_bin")
    private val tabVisibilityKey = stringPreferencesKey("tab_visibility")

    // Legacy master toggle (#160), now superseded by per-tab [tabVisibilityKey].
    // Kept only for a read-time migration fallback; never written.
    private val alwaysShowAllTabsKey = booleanPreferencesKey("always_show_all_tabs")
    private val tailscaleDiscoveryEnabledKey = booleanPreferencesKey("tailscale_discovery_enabled")
    private val remoteClipboardToLocalKey = booleanPreferencesKey("remote_clipboard_to_local")
    private val verboseLoggingEnabledKey = booleanPreferencesKey("verbose_logging_enabled")
    private val mouseInputEnabledKey = booleanPreferencesKey("mouse_input_enabled")
    private val swipeArrowsModeKey = booleanPreferencesKey("swipe_arrows_mode")
    private val terminalRightClickKey = booleanPreferencesKey("terminal_right_click")
    private val allowStandardKeyboardKey = booleanPreferencesKey("allow_standard_keyboard")
    private val rawKeyboardModeKey = booleanPreferencesKey("raw_keyboard_mode")

    // Custom keyboard mode (#115 follow-up) — when on, overrides the
    // Secure/Standard inferential logic in ImeInputView and uses the
    // ime_flag_* toggles below to assemble EditorInfo manually.
    private val keyboardCustomModeKey = booleanPreferencesKey("keyboard_custom_mode")
    private val imeFlagNoSuggestionsKey = booleanPreferencesKey("ime_flag_no_suggestions")
    private val imeFlagVisiblePasswordKey = booleanPreferencesKey("ime_flag_visible_password")
    private val imeFlagAutoCorrectKey = booleanPreferencesKey("ime_flag_auto_correct")
    private val imeFlagFullEditorKey = booleanPreferencesKey("ime_flag_full_editor")
    private val imeFlagNoExtractUiKey = booleanPreferencesKey("ime_flag_no_extract_ui")
    private val imeFlagNoPersonalizedLearningKey = booleanPreferencesKey("ime_flag_no_personalized_learning")
    private val interceptCtrlShiftVKey = booleanPreferencesKey("intercept_ctrl_shift_v")
    private val reflowTerminalOnKeyboardKey = booleanPreferencesKey("reflow_terminal_on_keyboard")
    private val terminalTabTitlesFollowSessionKey = booleanPreferencesKey("terminal_tab_titles_follow_session")
    private val showTerminalTabBarKey = booleanPreferencesKey("show_terminal_tab_bar")
    private val reorderHintShownKey = booleanPreferencesKey("reorder_hint_shown")
    private val screenOrderKey = stringPreferencesKey("screen_order")
    private val sshKeyOrderKey = stringPreferencesKey("ssh_key_order")
    private val keysSortModeKey = stringPreferencesKey("keys_sort_mode")
    private val keysCollapsedSectionsKey = stringPreferencesKey("keys_collapsed_sections")
    private val batteryPromptDismissedKey = booleanPreferencesKey("battery_prompt_dismissed")
    private val batteryPromptNeverAskKey = booleanPreferencesKey("battery_prompt_never_ask")
    private val batteryLastKnownExemptKey = booleanPreferencesKey("battery_last_known_exempt")

    // Desktop (RDP/VNC) keyboard source: system IME (default) or Haven's own
    // floating panel. See [desktopBuiltinKeyboard].
    private val desktopBuiltinKeyboardKey = booleanPreferencesKey("desktop_builtin_keyboard")

    // Trackpad-mode speed multiplier (#696). 1f = the 1:1 finger→cursor ratio.
    private val desktopTouchpadSensitivityKey = floatPreferencesKey("desktop_touchpad_sensitivity")

    // Built-in keyboard's floating-panel geometry (landscape): pinch scale and
    // its position as fractions of the viewer, so it survives screen changes.
    private val desktopKeyboardScaleKey = floatPreferencesKey("desktop_keyboard_scale")
    private val desktopKeyboardXFractionKey = floatPreferencesKey("desktop_keyboard_x_fraction")
    private val desktopKeyboardYFractionKey = floatPreferencesKey("desktop_keyboard_y_fraction")

    // Experimental GPU stack for accelerated Wayland/cage desktops. Off (default)
    // = virgl/virpipe (GL 2.1, present works on every path). On = venus
    // (Vulkan→Mali) + zink (modern GL, ~3.2 core) with the wl_shm CPU-copy WSI
    // present path. Native Vulkan apps present; GL-via-zink present is slow. See
    // DesktopManager.gpuPassthroughEnv + project_virgl_cage_gpu_accel memory note.
    // Output-only audio bridge for proot apps (#257). On = a PulseAudio daemon
    // runs in the active distro and its monitor PCM is played through AudioTrack;
    // desktop launch scripts source /etc/profile.d/pulse.sh so apps get
    // PULSE_SERVER. Off (default) = no audio, no behaviour change.
    // Opt-in update check (#578). Off by default: Haven is used by people who
    // care what their software talks to, and a request to a code-hosting site
    // on every launch is not a thing to start doing to everyone unasked. Only
    // ever consulted when the running copy was signed with the GitHub-release
    // key — see UpdateChecker.
    private val updateCheckEnabledKey = booleanPreferencesKey("update_check_enabled")
    private val updateCheckLastRunKey = longPreferencesKey("update_check_last_run_ms")
    private val updateCheckLastNotifiedKey = stringPreferencesKey("update_check_last_notified_version")
    private val bandwidthAutoSuggestKey = booleanPreferencesKey("bandwidth_auto_suggest")

    val biometricEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[biometricEnabledKey] ?: false
    }

    /** Prevent screenshots and screen recording (FLAG_SECURE). */
    val screenSecurity: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[screenSecurityKey] ?: false
    }

    suspend fun setScreenSecurity(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[screenSecurityKey] = enabled
        }
    }

    /** Show search button in terminal tab bar. Sends session manager's native search keys. */
    val showSearchButton: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showSearchButtonKey] ?: false
    }

    suspend fun setShowSearchButton(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showSearchButtonKey] = enabled
        }
    }

    /** Show copy-last-output button in terminal tab bar. Requires shell OSC 133 integration. */
    val showCopyOutputButton: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showCopyOutputButtonKey] ?: false
    }

    suspend fun setShowCopyOutputButton(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showCopyOutputButtonKey] = enabled
        }
    }

    /**
     * #418 debug: decode RemoteFX-Progressive WBT_TILE_UPGRADE refinement
     * tiles over RDP. Off by default — the upgrade decode is not yet verified
     * against real Windows streams, so a mis-decode could paint garbage. Meant
     * for capture verification before it becomes the default. Bridged to the
     * native decoder via `RdpDebugToggles` in HavenApp.
     */
    // #496: on by default since v5.86.40. Windows sends 500+ refinement tiles
    // per 15s even on an idle desktop, and dropping them is what produced the
    // ringing around text reported there.
    val rdpProgressiveUpgrade: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[rdpProgressiveUpgradeKey] ?: true
    }

    suspend fun setRdpProgressiveUpgrade(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[rdpProgressiveUpgradeKey] = enabled
        }
    }

    /**
     * #425: advertise EGFX H.264/AVC420. Needed for KRDP (KDE's RDP server),
     * which only encodes H.264 and won't fall back to ClearCodec/RemoteFX.
     * Bridged to the native negotiation + MediaCodec decoder via
     * `RdpDebugToggles` in HavenApp. Off by default while it's verified.
     */
    val rdpAvcEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[rdpAvcEnabledKey] ?: true
    }

    suspend fun setRdpAvcEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[rdpAvcEnabledKey] = enabled
        }
    }

    /** Keep the screen on while a terminal tab is foregrounded. Off by default. */
    val keepScreenOnInTerminal: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keepScreenOnInTerminalKey] ?: false
    }

    suspend fun setKeepScreenOnInTerminal(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[keepScreenOnInTerminalKey] = enabled
        }
    }

    /**
     * Raise the soft keyboard automatically when a terminal tab becomes active.
     * On by default (#675): turning it off is the opt-out for read-only
     * sessions, so the existing behaviour is what a fresh install gets.
     */
    val autoShowKeyboardInTerminal: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[autoShowKeyboardInTerminalKey] ?: true
    }

    suspend fun setAutoShowKeyboardInTerminal(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[autoShowKeyboardInTerminalKey] = enabled
        }
    }

    /**
     * Hide the app's own bottom tab bar while the Terminal screen is selected
     * (#521). Only Haven's chrome: the system status and navigation bars stay
     * exactly as they are, unlike the fullscreen toggle which hides both.
     * Off by default.
     */
    val hideNavBarInTerminal: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[hideNavBarInTerminalKey] ?: false
    }

    suspend fun setHideNavBarInTerminal(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[hideNavBarInTerminalKey] = enabled
        }
    }

    /** Record connection events (connect, disconnect, errors). Off by default. */
    val connectionLoggingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[connectionLoggingEnabledKey] ?: false
    }

    suspend fun setConnectionLoggingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[connectionLoggingEnabledKey] = enabled
        }
    }

    /**
     * Hide Haven's task card from the recents screen (#239). Off by default —
     * sessions keep running either way; this only affects the recents UI
     * (ActivityManager.AppTask.setExcludeFromRecents, applied by MainActivity).
     */
    val excludeFromRecents: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[excludeFromRecentsKey] ?: false
    }

    suspend fun setExcludeFromRecents(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[excludeFromRecentsKey] = enabled
        }
    }

    /**
     * Destination for encrypted backup push/pull (#323): the id of an existing
     * connection profile, and the file path on it. Null profile = not configured.
     * The path defaults to `haven-backup.enc` when unset.
     */
    val backupSyncProfileId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[backupSyncProfileIdKey]
    }

    val backupSyncPath: Flow<String> = dataStore.data.map { prefs ->
        prefs[backupSyncPathKey] ?: "haven-backup.enc"
    }

    suspend fun setBackupSyncDestination(profileId: String?, path: String) {
        dataStore.edit { prefs ->
            if (profileId == null) prefs.remove(backupSyncProfileIdKey)
            else prefs[backupSyncProfileIdKey] = profileId
            prefs[backupSyncPathKey] = path.ifBlank { "haven-backup.enc" }
        }
    }

    /**
     * Automatic backup push (#359): when on, a background job re-pushes the
     * encrypted backup to the configured remote shortly after config changes
     * (plus a daily catch-up). Requires keeping the backup passphrase on the
     * device — stored [CredentialEncryption]-wrapped, same as connection
     * passwords — because a background job can't prompt for it. Disabling
     * deletes the stored passphrase.
     */
    val backupAutoSyncEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[backupAutoSyncEnabledKey] ?: false
    }

    suspend fun setBackupAutoSync(enabled: Boolean, passphrase: String?) {
        val encrypted = if (enabled) passphrase?.let { CredentialEncryption.encrypt(context, it) } else null
        dataStore.edit { prefs ->
            prefs[backupAutoSyncEnabledKey] = enabled
            if (enabled) {
                if (encrypted != null) {
                    prefs[backupSyncPassphraseKey] = encrypted
                }
            } else {
                val autoPullEnabled = prefs[backupAutoPullEnabledKey] ?: false
                if (!autoPullEnabled) {
                    prefs.remove(backupSyncPassphraseKey)
                }
            }
        }
    }

    val backupAutoPullEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[backupAutoPullEnabledKey] ?: false
    }

    val backupAutoPullIntervalMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[backupAutoPullIntervalMinutesKey] ?: 1440
    }

    suspend fun setBackupAutoPull(enabled: Boolean, passphrase: String?) {
        val encrypted = if (enabled) passphrase?.let { CredentialEncryption.encrypt(context, it) } else null
        dataStore.edit { prefs ->
            prefs[backupAutoPullEnabledKey] = enabled
            if (enabled) {
                if (encrypted != null) {
                    prefs[backupSyncPassphraseKey] = encrypted
                }
            } else {
                val autoSyncEnabled = prefs[backupAutoSyncEnabledKey] ?: false
                if (!autoSyncEnabled) {
                    prefs.remove(backupSyncPassphraseKey)
                }
            }
        }
    }

    suspend fun setBackupAutoPullInterval(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[backupAutoPullIntervalMinutesKey] = minutes
        }
    }

    suspend fun saveBackupSyncPassphrase(passphrase: String) {
        val encrypted = CredentialEncryption.encrypt(context, passphrase)
        dataStore.edit { prefs ->
            prefs[backupSyncPassphraseKey] = encrypted
        }
    }

    suspend fun clearBackupSyncPassphrase() {
        dataStore.edit { prefs ->
            val autoSyncEnabled = prefs[backupAutoSyncEnabledKey] ?: false
            val autoPullEnabled = prefs[backupAutoPullEnabledKey] ?: false
            if (!autoSyncEnabled && !autoPullEnabled) {
                prefs.remove(backupSyncPassphraseKey)
            }
        }
    }

    val backupSyncPassphraseFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[backupSyncPassphraseKey]?.let { decryptSyncPassphraseOrNull(it) }
    }

    /** The stored auto-sync passphrase, decrypted; null when auto-sync is off or the
     *  stored ciphertext can no longer be decrypted (e.g. the Android Keystore key
     *  didn't survive an app reinstall / device restore — Keystore keys are excluded
     *  from Android's own backup mechanism by design, so a restored DataStore file
     *  can carry ciphertext its own Keystore will never decrypt again). */
    suspend fun backupSyncPassphrase(): String? =
        dataStore.data.first()[backupSyncPassphraseKey]?.let { decryptSyncPassphraseOrNull(it) }

    private fun decryptSyncPassphraseOrNull(encrypted: String): String? =
        try {
            CredentialEncryption.decrypt(context, encrypted)
        } catch (_: Exception) {
            // Broad on purpose: a lost Keystore key surfaces as
            // GeneralSecurityException, a corrupt stored value as
            // IllegalArgumentException (Base64), and an unparseable restored
            // Tink keyset as IOException — all mean the same thing here, and
            // any of them uncaught is the same launch crash-loop. Mirrors
            // CredentialEncryption.isEncrypted's catch.
            null
        }

    /**
     * Emits the current state on collect and again on every preferences write —
     * the auto-push change signal (#359). Exposed as Flow<Unit> so callers
     * outside core/data don't need the DataStore types on their classpath.
     */
    val preferenceChanges: Flow<Unit> = dataStore.data.map { }

    /**
     * User-supplied session command for the "Custom command (X11)" desktop
     * (#361) — what runs against the Haven-owned Xvnc display in place of a
     * catalog DE's fixed startCommands (e.g. `dbus-launch startxfce4`).
     * Blank = not configured; the launch path refuses to start on blank.
     */
    val customDesktopCommand: Flow<String> = dataStore.data.map { prefs ->
        prefs[customDesktopCommandKey] ?: ""
    }

    suspend fun setCustomDesktopCommand(command: String) {
        dataStore.edit { prefs -> prefs[customDesktopCommandKey] = command.trim() }
    }

    /**
     * Which backend the Desktop screen's primary Start button drives —
     * "quick" (the UML guest) or "full" (proot). Empty until the user has
     * chosen; the UI then falls back by capability, not by assumption.
     */
    val primaryDesktopBackend: Flow<String> = dataStore.data.map { prefs ->
        prefs[primaryDesktopBackendKey] ?: ""
    }

    suspend fun setPrimaryDesktopBackend(backend: String) {
        dataStore.edit { prefs -> prefs[primaryDesktopBackendKey] = backend }
    }

    /** The last-started UML desktop kind ("x11"/"wayland"); empty if none. */
    val lastUmlKind: Flow<String> = dataStore.data.map { prefs ->
        prefs[lastUmlKindKey] ?: ""
    }

    suspend fun setLastUmlKind(kind: String) {
        dataStore.edit { prefs -> prefs[lastUmlKindKey] = kind }
    }

    /**
     * Master switch for inbound-email automation (Mail Rules). Off by default. While on
     * (and ≥1 enabled rule exists) Haven runs a foreground watch that polls for new mail —
     * a persistent notification + some battery cost — so it stays opt-in and one tap away
     * from off.
     */
    val mailAutomationEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[mailAutomationEnabledKey] ?: false
    }

    suspend fun setMailAutomationEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[mailAutomationEnabledKey] = enabled
        }
    }

    /**
     * Whether deleting an email moves it to the Bin/Trash folder (recoverable) rather than
     * expunging it. On by default. On Gmail a plain delete already lands in Trash; this
     * matters most for plain IMAP servers where an expunge is permanent.
     */
    val mailDeleteToBin: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[mailDeleteToBinKey] ?: true
    }

    suspend fun setMailDeleteToBin(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[mailDeleteToBinKey] = enabled
        }
    }

    /**
     * Per-tab bottom-nav visibility (#navbar-visibility): a map of screen
     * route -> [TabVisibility], holding only non-[TabVisibility.AUTO] entries.
     * [TabVisibility.AUTO] (the default for any absent route) lets the built-in
     * usage rule decide; [TabVisibility.SHOW] forces the tab visible and
     * [TabVisibility.HIDE] removes it from the nav bar and the pager.
     *
     * Migration fallback: the retired master "always show all tabs" toggle
     * ([alwaysShowAllTabsKey]) forced every tab on. For a user who had it on
     * and has not yet chosen per-tab settings, fall back to SHOW for every
     * screen that does not default to always-visible (Connections and
     * Settings always are), preserving their previous experience. The moment
     * they save any per-tab setting ([setNavigationTabs]) the map is written and
     * this fallback no longer applies. Stateless: the legacy key is read but
     * never written or cleared.
     */
    val tabVisibility: Flow<Map<String, TabVisibility>> = dataStore.data.map { prefs ->
        val raw = prefs[tabVisibilityKey]
        if (raw == null) {
            if (prefs[alwaysShowAllTabsKey] == true) {
                // Preserve the old master-toggle experience: pin every tab that
                // used to depend on it to SHOW (Connections/Settings are already
                // always visible and have nothing to migrate).
                screenRoutesForcedVisibleByLegacy().associateWith { TabVisibility.SHOW }
            } else {
                emptyMap()
            }
        } else {
            raw.split(",").mapNotNull { pair ->
                val parts = pair.split("=")
                if (parts.size != 2) return@mapNotNull null
                val v = TabVisibility.fromName(parts[1])
                if (v == TabVisibility.AUTO) return@mapNotNull null
                parts[0].trim() to v
            }.toMap()
        }
    }

    /**
     * Screen routes whose *default* (AUTO) visibility is not always-on — i.e. the
     * ones the legacy master toggle used to affect. Hard-coded rather than
     * referencing [sh.haven.core.ui.navigation.Screen] because core/data has no
     * dependency on core/ui.
     */
    private fun screenRoutesForcedVisibleByLegacy(): List<String> =
        listOf("terminal", "desktop", "keys", "sftp", "mail")

    /**
     * Gate for Tailscale peer discovery (#654). On by default; off stops the
     * quad-100 LocalAPI probe from reading the peer list at all, independent
     * of whether the Tailscale app is installed.
     */
    val tailscaleDiscoveryEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[tailscaleDiscoveryEnabledKey] ?: true
    }

    suspend fun setTailscaleDiscoveryEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[tailscaleDiscoveryEnabledKey] = enabled }
    }

    /**
     * Whether a remote desktop's clipboard is pushed to the device clipboard.
     * Off by default: an unattended remote can otherwise inject content into
     * the phone's clipboard (security-review #15).
     */
    val remoteClipboardToLocalEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[remoteClipboardToLocalKey] ?: false
    }

    suspend fun setRemoteClipboardToLocalEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[remoteClipboardToLocalKey] = enabled
        }
    }


    /** Capture SSH protocol details (key exchange, auth, ciphers). Off by default. */
    val verboseLoggingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[verboseLoggingEnabledKey] ?: false
    }

    suspend fun setVerboseLoggingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[verboseLoggingEnabledKey] = enabled
        }
    }

    /**
     * Desktop (RDP/VNC) keyboard: "system" (default) raises the IME, "builtin"
     * shows Haven's own small floating keyboard instead. Android gives an app
     * no way to make the IME float — that is entirely the input method's own
     * decision — so the built-in panel is the only guarantee of a floating
     * keyboard in both orientations, with no dependence on the user's IME.
     */
    val desktopBuiltinKeyboard: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[desktopBuiltinKeyboardKey] ?: false
    }

    suspend fun setDesktopBuiltinKeyboard(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[desktopBuiltinKeyboardKey] = enabled
        }
    }

    /**
     * Pinch zoom of the built-in keyboard's floating (landscape) panel.
     * Remembered across sessions; the portrait panel is always docked full
     * width and ignores it.
     */
    val desktopKeyboardScale: Flow<Float> = dataStore.data.map { prefs ->
        prefs[desktopKeyboardScaleKey] ?: 1f
    }

    /** Horizontal placement of the floating panel, as a share of its travel. */
    val desktopKeyboardXFraction: Flow<Float> = dataStore.data.map { prefs ->
        prefs[desktopKeyboardXFractionKey] ?: 0f
    }

    /** Height of the floating panel above the bottom edge, as a share of the view. */
    val desktopKeyboardYFraction: Flow<Float> = dataStore.data.map { prefs ->
        prefs[desktopKeyboardYFractionKey] ?: 0.27f
    }

    suspend fun setDesktopKeyboardLayout(scale: Float, xFraction: Float, yFraction: Float) {
        dataStore.edit { prefs ->
            prefs[desktopKeyboardScaleKey] = scale
            prefs[desktopKeyboardXFractionKey] = xFraction
            prefs[desktopKeyboardYFractionKey] = yFraction
        }
    }

    /**
     * Relative-motion speed multiplier for desktop TOUCHPAD mode (#696).
     * 1f (default) = the current 1:1 mapping of finger travel to remote
     * cursor travel; above 1f the cursor travels further per finger
     * distance, below 1f gives finer pointer control. Shared by the VNC
     * and RDP touchpad paths so both behave the same.
     */
    val desktopTouchpadSensitivity: Flow<Float> = dataStore.data.map { prefs ->
        prefs[desktopTouchpadSensitivityKey] ?: 1f
    }

    suspend fun setDesktopTouchpadSensitivity(value: Float) {
        dataStore.edit { prefs ->
            prefs[desktopTouchpadSensitivityKey] = value
        }
    }

    /**
     * When true (default), VNC sessions on slow connections will surface a
     * banner suggesting a colour-depth downshift (#107). The user picks
     * "switch" or "dismiss"; nothing happens automatically beyond the
     * suggestion. Disable to silence the banner entirely.
     */
    val bandwidthAutoSuggest: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[bandwidthAutoSuggestKey] ?: true
    }

    suspend fun setBandwidthAutoSuggest(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[bandwidthAutoSuggestKey] = enabled
        }
    }

    /** Forward taps/long-press as mouse clicks to TUI apps (htop, mc, vim). */
    val mouseInputEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[mouseInputEnabledKey] ?: true
    }

    suspend fun setMouseInputEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[mouseInputEnabledKey] = enabled
        }
    }

    /**
     * #524: swipes send arrow keys everywhere, not only on the alternate
     * screen — command history at a shell prompt without arrow keys on the
     * toolbar. Toggled from the toolbar's Swipe key; persisted because the
     * point is to permanently replace the four arrow-key slots.
     */
    val swipeArrowsMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[swipeArrowsModeKey] ?: false
    }

    suspend fun setSwipeArrowsMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[swipeArrowsModeKey] = enabled
        }
    }

    /** Send long-press as right-click to TUI apps instead of starting text selection. */
    val terminalRightClick: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[terminalRightClickKey] ?: false
    }

    suspend fun setTerminalRightClick(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[terminalRightClickKey] = enabled
        }
    }

    /** Use standard keyboard (voice, swipe, autocomplete) instead of secure password-style input. */
    val allowStandardKeyboard: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[allowStandardKeyboardKey] ?: false
    }

    suspend fun setAllowStandardKeyboard(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[allowStandardKeyboardKey] = enabled
            // Raw, Standard, and Custom are mutually exclusive; turning
            // one on automatically turns the others off so the toolbar
            // state stays consistent with the IME behaviour.
            if (enabled) {
                prefs[rawKeyboardModeKey] = false
                prefs[keyboardCustomModeKey] = false
            }
        }
    }

    /**
     * When true, the terminal returns no InputConnection at all — Gboard
     * has nothing to decorate, so its mic, suggestion strip, and AI Core
     * writing assist cannot appear. Physical keyboard input still flows
     * through `View.dispatchKeyEvent`. Soft-keyboard input comes through as
     * raw key events only; no IME composition (so no CJK).
     */
    val rawKeyboardMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[rawKeyboardModeKey] ?: false
    }

    suspend fun setRawKeyboardMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[rawKeyboardModeKey] = enabled
            if (enabled) {
                prefs[allowStandardKeyboardKey] = false
                prefs[keyboardCustomModeKey] = false
            }
        }
    }

    /**
     * When on, ImeInputView ignores the Secure/Standard preset logic
     * and instead reads the six ime_flag_* toggles below to assemble
     * the EditorInfo it returns. Mutually exclusive with Standard and
     * Raw modes; turning it on clears the others.
     */
    val keyboardCustomMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keyboardCustomModeKey] ?: false
    }

    suspend fun setKeyboardCustomMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[keyboardCustomModeKey] = enabled
            if (enabled) {
                prefs[allowStandardKeyboardKey] = false
                prefs[rawKeyboardModeKey] = false
            }
        }
    }

    /**
     * Custom IME flag toggles. Defaults match the Secure preset so a
     * user flipping into Custom mode without further changes preserves
     * their current behaviour. Each flow exposes the live value; each
     * setter persists it. Effects only fire while Custom mode is on.
     */
    val imeFlagNoSuggestions: Flow<Boolean> = dataStore.data.map { it[imeFlagNoSuggestionsKey] ?: true }
    val imeFlagVisiblePassword: Flow<Boolean> = dataStore.data.map { it[imeFlagVisiblePasswordKey] ?: true }
    val imeFlagAutoCorrect: Flow<Boolean> = dataStore.data.map { it[imeFlagAutoCorrectKey] ?: false }
    val imeFlagFullEditor: Flow<Boolean> = dataStore.data.map { it[imeFlagFullEditorKey] ?: false }
    val imeFlagNoExtractUi: Flow<Boolean> = dataStore.data.map { it[imeFlagNoExtractUiKey] ?: true }
    val imeFlagNoPersonalizedLearning: Flow<Boolean> =
        dataStore.data.map { it[imeFlagNoPersonalizedLearningKey] ?: true }

    suspend fun setImeFlagNoSuggestions(v: Boolean) {
        dataStore.edit { it[imeFlagNoSuggestionsKey] = v }
    }

    suspend fun setImeFlagVisiblePassword(v: Boolean) {
        dataStore.edit { it[imeFlagVisiblePasswordKey] = v }
    }

    suspend fun setImeFlagAutoCorrect(v: Boolean) {
        dataStore.edit { it[imeFlagAutoCorrectKey] = v }
    }

    suspend fun setImeFlagFullEditor(v: Boolean) {
        dataStore.edit { it[imeFlagFullEditorKey] = v }
    }

    suspend fun setImeFlagNoExtractUi(v: Boolean) {
        dataStore.edit { it[imeFlagNoExtractUiKey] = v }
    }

    suspend fun setImeFlagNoPersonalizedLearning(v: Boolean) {
        dataStore.edit { it[imeFlagNoPersonalizedLearningKey] = v }
    }

    /**
     * Intercept Ctrl+Shift+V from a hardware keyboard as "paste from Android
     * clipboard". When off, the key combo is forwarded to the remote shell
     * unchanged — useful if a remote app binds Ctrl+Shift+V itself.
     */
    val interceptCtrlShiftV: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[interceptCtrlShiftVKey] ?: true
    }

    suspend fun setInterceptCtrlShiftV(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[interceptCtrlShiftVKey] = enabled
        }
    }

    /**
     * Resize the terminal (reflow / SIGWINCH) to fit above the soft keyboard
     * when it opens, instead of keeping the row count and render-shifting the
     * view up (#206). Off by default — the render-shift keeps a plain shell
     * stable when the keyboard toggles. Turn it on for a full-screen TUI that
     * draws a status/header line at the *top*: without the resize, the
     * render-shift pushes that top row off-screen (#242). The alternate screen
     * and mouse-tracking apps reflow regardless; this extends reflow to plain
     * primary-buffer TUIs the heuristics can't detect.
     */
    val reflowTerminalOnKeyboard: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[reflowTerminalOnKeyboardKey] ?: false
    }

    suspend fun setReflowTerminalOnKeyboard(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[reflowTerminalOnKeyboardKey] = enabled
        }
    }

    /**
     * When on, a tab attached to a session manager (tmux/zellij/screen/…)
     * keeps the session's own name in the tab strip instead of titles set by
     * running programs (OSC 0/2, e.g. a shell integration publishing the
     * cwd). Tabs without a multiplexer name are unaffected. On by default:
     * the preference only ever bites on multiplexer tabs, where the session
     * name is the label the user chose.
     */
    val terminalTabTitlesFollowSession: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[terminalTabTitlesFollowSessionKey] ?: true
    }

    suspend fun setTerminalTabTitlesFollowSession(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[terminalTabTitlesFollowSessionKey] = enabled
        }
    }

    /** Opt-in launch-time update check (#578). Default off. */
    val updateCheckEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[updateCheckEnabledKey] ?: false
    }

    suspend fun setUpdateCheckEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[updateCheckEnabledKey] = enabled
        }
    }

    /** Wall-clock ms of the last launch-time check; 0 = never. Throttle input. */
    val updateCheckLastRunMs: Flow<Long> = dataStore.data.map { prefs ->
        prefs[updateCheckLastRunKey] ?: 0L
    }

    suspend fun setUpdateCheckLastRunMs(atMs: Long) {
        dataStore.edit { prefs ->
            prefs[updateCheckLastRunKey] = atMs
        }
    }

    /**
     * Version already notified about, so a user who chose not to update now
     * is not told again on every launch. Empty = nothing notified yet.
     */
    val updateCheckLastNotifiedVersion: Flow<String> = dataStore.data.map { prefs ->
        prefs[updateCheckLastNotifiedKey] ?: ""
    }

    suspend fun setUpdateCheckLastNotifiedVersion(version: String) {
        dataStore.edit { prefs ->
            prefs[updateCheckLastNotifiedKey] = version
        }
    }

    /** Whether the terminal session tab bar is shown above the terminal. */
    val showTerminalTabBar: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showTerminalTabBarKey] ?: true
    }

    suspend fun setShowTerminalTabBar(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showTerminalTabBarKey] = enabled
        }
    }

    val reorderHintShown: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[reorderHintShownKey] ?: false
    }

    suspend fun setReorderHintShown() {
        dataStore.edit { prefs ->
            prefs[reorderHintShownKey] = true
        }
    }

    /** Comma-separated route names defining bottom navigation tab order. */
    val screenOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[screenOrderKey]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun setScreenOrder(routes: List<String>) {
        dataStore.edit { prefs ->
            prefs[screenOrderKey] = routes.joinToString(",")
        }
    }

    /**
     * Commit the Navigation Tabs dialog's draft — screen order **and** the
     * per-tab visibility map — in a single DataStore edit. One edit means one
     * [dataStore.data] emission, so the nav host recomputes its screens and the
     * pager re-renders exactly once on Save (two separate writes would make the
     * whole screen jump twice).
     */
    suspend fun setNavigationTabs(order: List<String>, visibility: Map<String, TabVisibility>) {
        dataStore.edit { prefs ->
            prefs[screenOrderKey] = order.joinToString(",")
            val current = visibility.filterValues { it != TabVisibility.AUTO }
            prefs[tabVisibilityKey] = current.entries.joinToString(",") { "${it.key}=${it.value.name}" }
        }
    }

    /** User-defined SSH-key display order as a list of key IDs. (#238) */
    val sshKeyOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[sshKeyOrderKey]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun setSshKeyOrder(ids: List<String>) {
        dataStore.edit { prefs ->
            prefs[sshKeyOrderKey] = ids.joinToString(",")
        }
    }

    /**
     * How the Keys tab sorts the SSH-key list — a `KeySort` enum name, with
     * the interpretation (including the fallback for an unknown value) owned
     * by `feature:keys`. Stored as the bare name so this module needs no
     * dependency on that enum. (#460)
     */
    val keysSortMode: Flow<String?> = dataStore.data.map { prefs ->
        prefs[keysSortModeKey]
    }

    suspend fun setKeysSortMode(name: String) {
        dataStore.edit { prefs ->
            prefs[keysSortModeKey] = name
        }
    }

    /**
     * Section ids the user has collapsed on the Keys tab (#460). Persisted
     * because the whole point is reclaiming vertical space on a small phone,
     * and a collapse that resets on every visit to the tab does not.
     */
    /**
     * Which Keys-screen sections are collapsed, or **null when the user has never touched
     * one** — which is not the same as "none collapsed" and must not be conflated with it.
     * The caller supplies the first-run default; once anything is toggled, an empty set here
     * means the user deliberately expanded everything and that choice has to survive.
     */
    val keysCollapsedSections: Flow<Set<String>?> = dataStore.data.map { prefs ->
        prefs[keysCollapsedSectionsKey]?.split(",")?.filter { it.isNotBlank() }?.toSet()
    }

    suspend fun setKeysCollapsedSections(ids: Set<String>) {
        dataStore.edit { prefs ->
            prefs[keysCollapsedSectionsKey] = ids.joinToString(",")
        }
    }

    /**
     * Whether the user has dismissed the battery optimization prompt.
     * EPISODIC (#494): cleared again when a granted exemption is detected as
     * dropped (ROMs reset it on app update), so the offer can return with an
     * explanation. [batteryPromptNeverAsk] is the permanent opt-out.
     */
    val batteryPromptDismissed: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[batteryPromptDismissedKey] ?: false
    }

    suspend fun setBatteryPromptDismissed(dismissed: Boolean = true) {
        dataStore.edit { prefs ->
            prefs[batteryPromptDismissedKey] = dismissed
        }
    }

    /** Permanent "don't ask again" for the battery prompt — survives drops (#494). */
    val batteryPromptNeverAsk: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[batteryPromptNeverAskKey] ?: false
    }

    suspend fun setBatteryPromptNeverAsk() {
        dataStore.edit { prefs ->
            prefs[batteryPromptNeverAskKey] = true
        }
    }

    /**
     * Last observed `isIgnoringBatteryOptimizations` value; null until first
     * observed. A stored true against a current false = the exemption was
     * dropped out from under us (#494).
     */
    val batteryLastKnownExempt: Flow<Boolean?> = dataStore.data.map { prefs ->
        prefs[batteryLastKnownExemptKey]
    }

    suspend fun setBatteryLastKnownExempt(exempt: Boolean) {
        dataStore.edit { prefs ->
            prefs[batteryLastKnownExemptKey] = exempt
        }
    }

    /** Space-separated file extensions to treat as streamable media (e.g. "mp3 mp4 flac"). */
    /** Last port used by the media streaming server (for reconnection after restart). */

    val terminalFontSize: Flow<Int> = dataStore.data.map { prefs ->
        prefs[terminalFontSizeKey] ?: DEFAULT_FONT_SIZE
    }

    val terminalHorizontalPadding: Flow<Int> = dataStore.data.map { prefs ->
        prefs[terminalHorizontalPaddingKey] ?: DEFAULT_HORIZONTAL_PADDING_DP
    }

    suspend fun setTerminalHorizontalPadding(paddingDp: Int) {
        dataStore.edit { prefs ->
            prefs[terminalHorizontalPaddingKey] =
                paddingDp.coerceIn(MIN_HORIZONTAL_PADDING_DP, MAX_HORIZONTAL_PADDING_DP)
        }
    }

    val terminalAmbiwidthMode: Flow<TerminalAmbiwidthMode> = dataStore.data.map { prefs ->
        val raw = prefs[terminalAmbiwidthModeKey]
        if (raw != null) {
            TerminalAmbiwidthMode.fromString(raw)
        } else {
            val legacy = prefs[ambiwidthKey]
            if (legacy == true) TerminalAmbiwidthMode.WIDE else TerminalAmbiwidthMode.NARROW
        }
    }

    suspend fun setTerminalAmbiwidthMode(mode: TerminalAmbiwidthMode) {
        dataStore.edit { prefs ->
            prefs[terminalAmbiwidthModeKey] = mode.id
            prefs[ambiwidthKey] = mode == TerminalAmbiwidthMode.WIDE || mode == TerminalAmbiwidthMode.AUTO_WIDE
        }
    }

    val ambiwidth: Flow<Boolean> = terminalAmbiwidthMode.map {
        it == TerminalAmbiwidthMode.WIDE || it == TerminalAmbiwidthMode.AUTO_WIDE
    }

    suspend fun setAmbiwidth(enabled: Boolean) {
        setTerminalAmbiwidthMode(if (enabled) TerminalAmbiwidthMode.WIDE else TerminalAmbiwidthMode.NARROW)
    }

    /**
     * Minimum width (dp) every terminal-toolbar key is stretched to. 0 = each
     * key hugs its content; larger values give bigger, more tappable keys at
     * the cost of fitting fewer per row. Applied uniformly by the toolbar's
     * shared key primitive.
     */
    val toolbarMinButtonWidth: Flow<Int> = dataStore.data.map { prefs ->
        prefs[toolbarMinButtonWidthKey] ?: DEFAULT_TOOLBAR_MIN_BUTTON_WIDTH
    }

    /**
     * Multiplier on the terminal toolbar's size (#641). 1.0 is the un-scaled
     * toolbar; above that the toolbar's own density is scaled, so key
     * heights, icons, spacing and glyph text all grow together. Composes
     * with [toolbarMinButtonWidth], which keeps operating in scaled dp.
     */
    val toolbarKeyScale: Flow<Float> = dataStore.data.map { prefs ->
        prefs[toolbarKeyScaleKey] ?: DEFAULT_TOOLBAR_KEY_SCALE
    }

    /**
     * Maximum number of lines retained in each tab's scrollback ring (#151).
     * The emulator reads this once at construction; changing it affects
     * newly created tabs, not existing ones. Larger values cost roughly
     * 2 KB per line at typical column widths, multiplied by the number of
     * open tabs.
     */
    val terminalScrollbackRows: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[terminalScrollbackRowsKey] ?: DEFAULT_SCROLLBACK_ROWS)
            .coerceIn(MIN_SCROLLBACK_ROWS, MAX_SCROLLBACK_ROWS)
    }

    /**
     * Desktop size Haven asks an RDP server for.
     *
     * This was hardcoded to 1920x1080 with no way to change it, which is fine
     * until the server draws something else. A VirtualBox VM defaults to
     * 2560x1600 and simply paints at that size without announcing a resize, so
     * every update outside 1920x1080 was silently discarded and the screen went
     * stale (#422). Matching the two is the workaround; letting the client
     * follow the server is the better fix and is a separate piece of work.
     */
    val rdpDesktopWidth: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[rdpDesktopWidthKey] ?: DEFAULT_RDP_WIDTH)
            .coerceIn(MIN_RDP_DIMENSION, MAX_RDP_DIMENSION)
    }

    val rdpDesktopHeight: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[rdpDesktopHeightKey] ?: DEFAULT_RDP_HEIGHT)
            .coerceIn(MIN_RDP_DIMENSION, MAX_RDP_DIMENSION)
    }

    suspend fun setRdpDesktopSize(width: Int, height: Int) {
        dataStore.edit { prefs ->
            prefs[rdpDesktopWidthKey] = width.coerceIn(MIN_RDP_DIMENSION, MAX_RDP_DIMENSION)
            prefs[rdpDesktopHeightKey] = height.coerceIn(MIN_RDP_DIMENSION, MAX_RDP_DIMENSION)
        }
    }

    suspend fun setTerminalScrollbackRows(rows: Int) {
        dataStore.edit { prefs ->
            prefs[terminalScrollbackRowsKey] = rows.coerceIn(MIN_SCROLLBACK_ROWS, MAX_SCROLLBACK_ROWS)
        }
    }

    /**
     * Tap on a shell prompt's input line moves the readline cursor to the
     * tapped column by synthesising arrow-key dispatches. Requires the
     * shell to emit OSC 133 prompt markers (starship, fish 3.6+, recent
     * bash/zsh shell-integration setups). Default off — surfaces in
     * Settings → Terminal so users opt in only when their shell supports
     * it.
     */
    val terminalTapToPositionCursor: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[terminalTapToPositionCursorKey] ?: false
    }

    suspend fun setTerminalTapToPositionCursor(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[terminalTapToPositionCursorKey] = enabled
        }
    }

    /**
     * #753: when a terminal program rings BEL (0x07) and Haven is in the
     * background, post a system notification naming the tab. Foreground BEL
     * stays silent either way — shells ring BEL for ordinary things
     * (backspace at an empty prompt, ambiguous completion) and a toast per
     * bell would be noise. Default on.
     */
    val terminalBellNotification: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[terminalBellNotificationKey] ?: true
    }

    suspend fun setTerminalBellNotification(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[terminalBellNotificationKey] = enabled
        }
    }

    /**
     * Absolute path to a user-chosen TTF/OTF font that the terminal
     * should use in place of the bundled Hack font. Empty/null means
     * "use the default". The picker copies the chosen font into the
     * app's private files dir so we own the lifecycle and survive a
     * source-document permission revocation.
     */
    val terminalFontPath: Flow<String?> = dataStore.data.map { prefs ->
        prefs[terminalFontPathKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setTerminalFontPath(path: String?) {
        dataStore.edit { prefs ->
            if (path.isNullOrBlank()) prefs.remove(terminalFontPathKey)
            else prefs[terminalFontPathKey] = path
        }
    }

    /**
     * Extra characters the user treats as a shell-prompt terminator, added to
     * the built-in `$ # % > ❯` set used to detect when a queued command may be
     * sent on (re)attach (#280). Whitespace is ignored; e.g. "»尺". Empty
     * (default) keeps only the built-ins.
     */
    val terminalPromptChars: Flow<String> = dataStore.data.map { prefs ->
        prefs[terminalPromptCharsKey] ?: ""
    }

    suspend fun setTerminalPromptChars(chars: String) {
        dataStore.edit { prefs ->
            prefs[terminalPromptCharsKey] = chars
        }
    }

    /**
     * Locale exported as `LANG` into the local Linux (proot) shell and the
     * Android fallback shell (#282). Default `en_US.UTF-8`. `C.UTF-8` always
     * works without locale-gen; other locales may need generating in the guest
     * (`locale-gen`) before programs honour them. Blank falls back to the
     * default.
     */
    val terminalLocale: Flow<String> = dataStore.data.map { prefs ->
        prefs[terminalLocaleKey]?.takeIf { it.isNotBlank() } ?: DEFAULT_TERMINAL_LOCALE
    }

    suspend fun setTerminalLocale(locale: String) {
        dataStore.edit { prefs ->
            prefs[terminalLocaleKey] = locale.trim()
        }
    }

    val sessionManager: Flow<SessionManager> = dataStore.data.map { prefs ->
        SessionManager.fromString(prefs[sessionManagerKey])
    }

    suspend fun setSessionManager(manager: SessionManager) {
        dataStore.edit { prefs ->
            prefs[sessionManagerKey] = manager.name
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[biometricEnabledKey] = enabled
        }
    }

    val theme: Flow<ThemeMode> = dataStore.data.map { prefs ->
        ThemeMode.fromString(prefs[themeKey])
    }

    suspend fun setTerminalFontSize(sizeSp: Int) {
        dataStore.edit { prefs ->
            prefs[terminalFontSizeKey] = sizeSp.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        }
    }

    /**
     * Read a Float pref that a pre-v5.68.27 backup restore may have corrupted to
     * a non-Float type. That restore stored Float prefs under an Int key (#323),
     * so `prefs[floatKey]` throws ClassCastException and crash-loops the app on
     * launch. A wrong stored type returns null here (→ caller's default) instead
     * of throwing, so an already-corrupted store self-heals; the fix also
     * corrects the import so it can't recur.
     */
    private fun Preferences.floatSafe(key: Preferences.Key<Float>): Float? =
        runCatching { this[key] }.getOrNull()

    /** Mail message-list pinch-zoom factor (× the terminal font size). 1.0 = match terminal. */
    val mailFontScale: Flow<Float> = dataStore.data.map { prefs ->
        (prefs.floatSafe(mailFontScaleKey) ?: DEFAULT_MAIL_FONT_SCALE)
            .coerceIn(MIN_MAIL_FONT_SCALE, MAX_MAIL_FONT_SCALE)
    }

    suspend fun setMailFontScale(scale: Float) {
        dataStore.edit { prefs ->
            prefs[mailFontScaleKey] = scale.coerceIn(MIN_MAIL_FONT_SCALE, MAX_MAIL_FONT_SCALE)
        }
    }

    suspend fun setToolbarMinButtonWidth(dp: Int) {
        dataStore.edit { prefs ->
            prefs[toolbarMinButtonWidthKey] =
                dp.coerceIn(MIN_TOOLBAR_MIN_BUTTON_WIDTH, MAX_TOOLBAR_MIN_BUTTON_WIDTH)
        }
    }

    suspend fun setToolbarKeyScale(scale: Float) {
        dataStore.edit { prefs ->
            prefs[toolbarKeyScaleKey] =
                scale.coerceIn(MIN_TOOLBAR_KEY_SCALE, MAX_TOOLBAR_KEY_SCALE)
        }
    }

    suspend fun setTheme(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[themeKey] = mode.name
        }
    }

    val reticulumRpcKey: Flow<String?> = dataStore.data.map { prefs ->
        prefs[reticulumRpcKeyKey]
    }

    val reticulumHost: Flow<String> = dataStore.data.map { prefs ->
        prefs[reticulumHostKey] ?: DEFAULT_RETICULUM_HOST
    }

    val reticulumPort: Flow<Int> = dataStore.data.map { prefs ->
        prefs[reticulumPortKey] ?: DEFAULT_RETICULUM_PORT
    }

    val reticulumConfigured: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[reticulumRpcKeyKey] != null
    }

    suspend fun setReticulumConfig(rpcKey: String, host: String, port: Int) {
        dataStore.edit { prefs ->
            prefs[reticulumRpcKeyKey] = rpcKey
            prefs[reticulumHostKey] = host
            prefs[reticulumPortKey] = port
        }
    }

    suspend fun clearReticulumConfig() {
        dataStore.edit { prefs ->
            prefs.remove(reticulumRpcKeyKey)
        }
    }

    /**
     * Toolbar layout as a [ToolbarLayout]. Migrates from legacy row1/row2
     * comma-separated format on first read if needed.
     */
    val toolbarLayout: Flow<ToolbarLayout> = dataStore.data.map { prefs ->
        val json = prefs[toolbarLayoutKey]
        if (json != null) {
            ToolbarLayout.fromJson(json)
        } else {
            // Migrate from legacy formats
            val row1 = prefs[toolbarRow1Key]
            val row2 = prefs[toolbarRow2Key]
            if (row1 != null || row2 != null) {
                ToolbarLayout.fromLegacy(
                    row1 ?: DEFAULT_TOOLBAR_ROW1,
                    row2 ?: DEFAULT_TOOLBAR_ROW2,
                )
            } else {
                ToolbarLayout.DEFAULT
            }
        }
    }

    val toolbarLayoutJson: Flow<String> = dataStore.data.map { prefs ->
        prefs[toolbarLayoutKey] ?: ToolbarLayout.DEFAULT.toJson()
    }

    suspend fun setToolbarLayout(layout: ToolbarLayout) {
        dataStore.edit { prefs ->
            prefs[toolbarLayoutKey] = layout.toJson()
            // Clear legacy keys
            prefs.remove(toolbarRow1Key)
            prefs.remove(toolbarRow2Key)
            prefs.remove(toolbarRowsKey)
        }
    }

    suspend fun setToolbarLayoutJson(json: String) {
        dataStore.edit { prefs ->
            prefs[toolbarLayoutKey] = json
            prefs.remove(toolbarRow1Key)
            prefs.remove(toolbarRow2Key)
            prefs.remove(toolbarRowsKey)
        }
    }

    /**
     * Off-toolbar snippet library (#244): custom send-key macros that exist
     * and show in the scissors sheet but have no dedicated toolbar button.
     * Separate from [toolbarLayout] so toggling a snippet "Off" in settings
     * keeps it here instead of discarding it.
     */
    val snippetLibrary: Flow<List<ToolbarItem.Custom>> = dataStore.data.map { prefs ->
        SnippetOps.libraryFromJson(prefs[snippetLibraryKey] ?: "")
    }

    suspend fun setSnippetLibrary(items: List<ToolbarItem.Custom>) {
        dataStore.edit { prefs ->
            prefs[snippetLibraryKey] = SnippetOps.libraryToJson(items)
        }
    }

    val navBlockMode: Flow<NavBlockMode> = dataStore.data.map { prefs ->
        prefs[navBlockModeKey]?.let { NavBlockMode.fromId(it) } ?: NavBlockMode.ALIGNED
    }

    suspend fun setNavBlockMode(mode: NavBlockMode) {
        dataStore.edit { prefs ->
            prefs[navBlockModeKey] = mode.id
        }
    }

    /**
     * Termux-style uniform key grid (#372): every toolbar key occupies an
     * equal-width cell of the screen (no horizontal scroll); long labels wrap
     * inside their cell. Off = the classic natural-width scrolling rows.
     */
    val toolbarUniformGrid: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[toolbarUniformGridKey] ?: false
    }

    suspend fun setToolbarUniformGrid(enabled: Boolean) {
        dataStore.edit { it[toolbarUniformGridKey] = enabled }
    }

    /** Where the fixed (non-draggable) controls sit in toolbar edit mode. (#224) */
    val editModeControlsPlacement: Flow<EditModeControlsPlacement> = dataStore.data.map { prefs ->
        prefs[editModeControlsPlacementKey]?.let { EditModeControlsPlacement.fromId(it) }
            ?: EditModeControlsPlacement.LEFT
    }

    suspend fun setEditModeControlsPlacement(placement: EditModeControlsPlacement) {
        dataStore.edit { prefs ->
            prefs[editModeControlsPlacementKey] = placement.id
        }
    }

    /** Where the auto-shown desktop (VNC/RDP) key sits, or whether it's hidden. (#245) */
    val desktopKeyPlacement: Flow<DesktopKeyPlacement> = dataStore.data.map { prefs ->
        prefs[desktopKeyPlacementKey]?.let { DesktopKeyPlacement.fromId(it) }
            ?: DesktopKeyPlacement.LEFT
    }

    suspend fun setDesktopKeyPlacement(placement: DesktopKeyPlacement) {
        dataStore.edit { prefs ->
            prefs[desktopKeyPlacementKey] = placement.id
        }
    }

    /** Corner of the terminal view holding the fullscreen toggle (#445). */
    val fullscreenButtonCorner: Flow<FullscreenButtonCorner> = dataStore.data.map { prefs ->
        prefs[fullscreenButtonCornerKey]?.let { FullscreenButtonCorner.fromId(it) }
            ?: FullscreenButtonCorner.DEFAULT
    }

    suspend fun setFullscreenButtonCorner(corner: FullscreenButtonCorner) {
        dataStore.edit { prefs ->
            prefs[fullscreenButtonCornerKey] = corner.id
        }
    }

    /**
     * Anchor of the fullscreen session-menu chip in remote-desktop sessions
     * (#528 follow-up) — hold-and-drag to move it, same idiom as the terminal
     * fullscreen button above, persisted across sessions.
     */
    val rdpChipAnchor: Flow<RdpChipAnchor> = dataStore.data.map { prefs ->
        prefs[rdpChipAnchorKey]?.let { RdpChipAnchor.fromId(it) } ?: RdpChipAnchor.DEFAULT
    }

    suspend fun setRdpChipAnchor(anchor: RdpChipAnchor) {
        dataStore.edit { prefs ->
            prefs[rdpChipAnchorKey] = anchor.id
        }
    }

    /**
     * User override for the session manager command template.
     * If non-null, replaces the built-in command. Use {name} for session name.
     */
    val sessionCommandOverride: Flow<String?> = dataStore.data.map { prefs ->
        prefs[sessionCommandOverrideKey]
    }

    suspend fun setSessionCommandOverride(command: String?) {
        dataStore.edit { prefs ->
            if (command.isNullOrBlank()) {
                prefs.remove(sessionCommandOverrideKey)
            } else {
                prefs[sessionCommandOverrideKey] = command
            }
        }
    }

    val sftpSortMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[sftpSortModeKey] ?: "NAME_ASC"
    }

    suspend fun setSftpSortMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[sftpSortModeKey] = mode
        }
    }

    val terminalColorScheme: Flow<TerminalColorScheme> = dataStore.data.map { prefs ->
        TerminalColorScheme.fromString(prefs[terminalColorSchemeKey])
    }

    suspend fun setTerminalColorScheme(scheme: TerminalColorScheme) {
        dataStore.edit { prefs ->
            prefs[terminalColorSchemeKey] = scheme.name
        }
    }

    /** Inline-image consent policy (#583): OFF / ASK (default) / ALWAYS. */
    val terminalInlineImages: Flow<TerminalInlineImages> = dataStore.data.map { prefs ->
        TerminalInlineImages.fromString(prefs[terminalInlineImagesKey])
    }

    suspend fun setTerminalInlineImages(mode: TerminalInlineImages) {
        dataStore.edit { prefs ->
            prefs[terminalInlineImagesKey] = mode.name
        }
    }

    /**
     * When true, the active terminal scheme follows the system light/dark
     * mode — [terminalLightColorScheme] in light, [terminalDarkColorScheme]
     * in dark. The plain [terminalColorScheme] pref is used otherwise.
     */
    val terminalAutoSwitchScheme: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[terminalAutoSwitchSchemeKey] ?: false
    }

    suspend fun setTerminalAutoSwitchScheme(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[terminalAutoSwitchSchemeKey] = enabled
        }
    }

    val terminalLightColorScheme: Flow<TerminalColorScheme> = dataStore.data.map { prefs ->
        prefs[terminalLightColorSchemeKey]?.let { name ->
            TerminalColorScheme.entries.find { it.name == name }
        } ?: TerminalColorScheme.LIGHT
    }

    suspend fun setTerminalLightColorScheme(scheme: TerminalColorScheme) {
        dataStore.edit { prefs ->
            prefs[terminalLightColorSchemeKey] = scheme.name
        }
    }

    val terminalDarkColorScheme: Flow<TerminalColorScheme> = dataStore.data.map { prefs ->
        prefs[terminalDarkColorSchemeKey]?.let { name ->
            TerminalColorScheme.entries.find { it.name == name }
        } ?: TerminalColorScheme.HAVEN
    }

    suspend fun setTerminalDarkColorScheme(scheme: TerminalColorScheme) {
        dataStore.edit { prefs ->
            prefs[terminalDarkColorSchemeKey] = scheme.name
        }
    }

    /**
     * When true, the active scheme's 16-entry [TerminalColorScheme.ansi]
     * palette is pushed to the emulator, so SGR-coloured text tracks the
     * theme. Off by default (#407): overriding the stock ANSI palette
     * remaps the colours full-screen TUIs like mutt rely on (e.g. ANSI
     * white → a scheme "cream"), which reads as a regression. Off keeps
     * libvterm's canonical palette; only the default fg/bg are themed.
     */
    val terminalApplySchemePalette: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[terminalApplySchemePaletteKey] ?: false
    }

    suspend fun setTerminalApplySchemePalette(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[terminalApplySchemePaletteKey] = enabled
        }
    }

    /**
     * Global terminal background opacity (0.0–1.0). 1.0 = opaque (default).
     * Below 1.0 the terminal renders semi-transparently over the device
     * wallpaper. A per-profile override may supersede this (see
     * `ConnectionProfile.terminalBackgroundOpacity`).
     */
    val terminalBackgroundOpacity: Flow<Float> = dataStore.data.map { prefs ->
        (prefs.floatSafe(terminalBackgroundOpacityKey) ?: 1f).coerceIn(0f, 1f)
    }

    suspend fun setTerminalBackgroundOpacity(opacity: Float) {
        dataStore.edit { prefs ->
            prefs[terminalBackgroundOpacityKey] = opacity.coerceIn(0f, 1f)
        }
    }

    enum class TerminalColorScheme(
        val label: String,
        val background: Long,
        val foreground: Long,
        /**
         * 16-entry ANSI palette in libvterm order:
         *   0..7  = normal  black, red, green, yellow, blue, magenta, cyan, white
         *   8..15 = bright  black, red, green, yellow, blue, magenta, cyan, white
         * Passed to the emulator via `applyColorScheme` so SGR-coloured text
         * (most prompts) tracks the scheme, not libvterm's stock palette.
         */
        val ansi: LongArray,
    ) {
        /**
         * Sentinel scheme — actual fg/bg come from the live
         * `MaterialTheme.colorScheme` (surface / onSurface). The fixed
         * longs here are sane fallbacks for code paths that can't reach
         * MaterialTheme (e.g. emulator construction in
         * `TerminalViewModel`); the Compose layer overrides immediately
         * via `setDefaultColors`. Call sites that *can* reach the
         * theme should check [isDynamic] and use the live colours.
         *
         * The palette is a static neutral fallback for callers that can't
         * reach Compose; call sites with the live theme derive the palette
         * instead (see `materialYouAnsiPalette` in feature/terminal, #139).
         */
        MATERIAL_YOU(
            "Material You", 0xFF1A1A1A, 0xFFE0E0E0,
            longArrayOf(
                0xFF45475A, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF,
                0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFBAC2DE,
                0xFF585B70, 0xFFEBA0AC, 0xFFB1E3AB, 0xFFFCEAB5,
                0xFF96BDFD, 0xFFF7CEEC, 0xFF9EE9DC, 0xFFA6ADC8,
            ),
        ),
        HAVEN(
            "Haven", 0xFF1A1A2E, 0xFF00E676,
            longArrayOf(
                0xFF1A1A2E, 0xFFFF5C8A, 0xFF00E676, 0xFFFFD54F,
                0xFF4FC3F7, 0xFFCE93D8, 0xFF80DEEA, 0xFFB0BEC5,
                0xFF424242, 0xFFFF8A80, 0xFF69F0AE, 0xFFFFE082,
                0xFF82B1FF, 0xFFE1BEE7, 0xFFA7FFEB, 0xFFECEFF1,
            ),
        ),
        CLASSIC_GREEN(
            "Classic Green", 0xFF000000, 0xFF00FF00,
            longArrayOf(
                0xFF000000, 0xFF008800, 0xFF00BB00, 0xFF00CC00,
                0xFF005500, 0xFF007733, 0xFF009966, 0xFF00BB00,
                0xFF004400, 0xFF00CC00, 0xFF00FF00, 0xFF55FF55,
                0xFF007722, 0xFF00CC66, 0xFF00FFAA, 0xFF66FF66,
            ),
        ),
        LIGHT(
            "Light", 0xFFFFFFFF, 0xFF1A1A1A,
            longArrayOf(
                0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900,
                0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
                0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
                0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
            ),
        ),
        SOLARIZED_DARK(
            "Solarized Dark", 0xFF002B36, 0xFF839496,
            longArrayOf(
                0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900,
                0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
                0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
                0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
            ),
        ),
        SOLARIZED_LIGHT(
            "Solarized Light", 0xFFFDF6E3, 0xFF586E75,
            longArrayOf(
                0xFFFDF6E3, 0xFFDC322F, 0xFF859900, 0xFFB58900,
                0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFF073642,
                0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
                0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFF073642,
            ),
        ),
        CATPPUCCIN_MOCHA(
            "Catppuccin Mocha", 0xFF1E1E2E, 0xFFCDD6F4,
            longArrayOf(
                0xFF45475A, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF,
                0xFF89B4FA, 0xFFCBA6F7, 0xFF94E2D5, 0xFFBAC2DE,
                0xFF585B70, 0xFFF5C2E7, 0xFF94E2D5, 0xFFFAB387,
                0xFFB4BEFE, 0xFFF5C2E7, 0xFF94E2D5, 0xFFA6ADC8,
            ),
        ),
        CATPPUCCIN_LATTE(
            "Catppuccin Latte", 0xFFEFF1F5, 0xFF4C4F69,
            longArrayOf(
                0xFF5C5F77, 0xFFD20F39, 0xFF40A02B, 0xFFDF8E1D,
                0xFF1E66F5, 0xFF8839EF, 0xFF179299, 0xFF6C6F85,
                0xFF8C8FA1, 0xFFD20F39, 0xFF40A02B, 0xFFDF8E1D,
                0xFF1E66F5, 0xFF8839EF, 0xFF179299, 0xFF4C4F69,
            ),
        ),
        ONE_DARK(
            "One Dark", 0xFF282C34, 0xFFABB2BF,
            longArrayOf(
                0xFF3F4451, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
                0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFABB2BF,
                0xFF4F5666, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
                0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFD7DEE8,
            ),
        ),
        ONE_LIGHT(
            "One Light", 0xFFF8F8F8, 0xFF383A42,
            longArrayOf(
                0xFF383A42, 0xFFE45649, 0xFF50A14F, 0xFFC18401,
                0xFF0184BC, 0xFFA626A4, 0xFF0997B3, 0xFF737983,
                0xFF5B626F, 0xFFE45649, 0xFF50A14F, 0xFFC18401,
                0xFF0184BC, 0xFFA626A4, 0xFF0997B3, 0xFF383A42,
            ),
        ),
        MATERIAL_DARK(
            "Material Dark", 0xFF202124, 0xFFE8EAED,
            longArrayOf(
                0xFF3C4043, 0xFFFF6E6E, 0xFF81C995, 0xFFE9DBCA,
                0xFF75A5C9, 0xFFDDB4DB, 0xFF6ECCB8, 0xFFC4C7C5,
                0xFF5F6368, 0xFFFF6E6E, 0xFF81C995, 0xFFE9DBCA,
                0xFF75A5C9, 0xFFDDB4DB, 0xFF6ECCB8, 0xFFC4C7C5,
            ),
        ),
        DRACULA(
            "Dracula", 0xFF282A36, 0xFFF8F8F2,
            longArrayOf(
                0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C,
                0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
                0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5,
                0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF,
            ),
        ),
        MONOKAI(
            "Monokai", 0xFF272822, 0xFFF8F8F2,
            longArrayOf(
                0xFF272822, 0xFFF92672, 0xFFA6E22E, 0xFFF4BF75,
                0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF8F8F2,
                0xFF75715E, 0xFFF92672, 0xFFA6E22E, 0xFFE6DB74,
                0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF9F8F5,
            ),
        ),
        NORD(
            "Nord", 0xFF2E3440, 0xFFD8DEE9,
            longArrayOf(
                0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
                0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
                0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
                0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4,
            ),
        ),
        GRUVBOX(
            "Gruvbox", 0xFF282828, 0xFFEBDBB2,
            longArrayOf(
                0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921,
                0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
                0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F,
                0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2,
            ),
        ),
        TOKYO_NIGHT(
            "Tokyo Night", 0xFF1A1B26, 0xFFA9B1D6,
            longArrayOf(
                0xFF15161E, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
                0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFA9B1D6,
                0xFF414868, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
                0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFC0CAF5,
            ),
        ),
        QBASIC(
            "QBasic", 0xFF0000AA, 0xFFAAAAAA,
            longArrayOf(
                0xFF000000, 0xFFAA0000, 0xFF00AA00, 0xFFAA5500,
                0xFF0000AA, 0xFFAA00AA, 0xFF00AAAA, 0xFFAAAAAA,
                0xFF555555, 0xFFFF5555, 0xFF55FF55, 0xFFFFFF55,
                0xFF5555FF, 0xFFFF55FF, 0xFF55FFFF, 0xFFFFFFFF,
            ),
        ),
        AMBER(
            "Amber", 0xFF1A1000, 0xFFFFB000,
            longArrayOf(
                0xFF1A1000, 0xFF884400, 0xFFAA6600, 0xFFCC7700,
                0xFF553300, 0xFF884422, 0xFFAA5511, 0xFFCC8833,
                0xFF443300, 0xFFCC6600, 0xFFDD8822, 0xFFFFB000,
                0xFF775500, 0xFFCC7733, 0xFFDD9944, 0xFFFFCC66,
            ),
        ),
        PINK(
            "Pink", 0xFF2D001E, 0xFFFF9EC6,
            longArrayOf(
                0xFF2D001E, 0xFFFF4081, 0xFFFFAB91, 0xFFFFD180,
                0xFFCE93D8, 0xFFFF80AB, 0xFFFFB2DD, 0xFFFFCCDD,
                0xFF550022, 0xFFFF80AB, 0xFFFFCCBC, 0xFFFFE0B2,
                0xFFE1BEE7, 0xFFFFB6C1, 0xFFFFD8E8, 0xFFFFE6F0,
            ),
        ),
        LAVENDER(
            "Lavender", 0xFF1E1629, 0xFFCDB4DB,
            longArrayOf(
                0xFF1E1629, 0xFFE57373, 0xFFB39DDB, 0xFFFFCC80,
                0xFF9575CD, 0xFFCE93D8, 0xFFB0BEC5, 0xFFCDB4DB,
                0xFF4A3F61, 0xFFFF8A80, 0xFFD1C4E9, 0xFFFFE082,
                0xFFB39DDB, 0xFFE1BEE7, 0xFFCFD8DC, 0xFFEDE7F6,
            ),
        ),
        OCEAN(
            "Ocean", 0xFF0A192F, 0xFF64FFDA,
            longArrayOf(
                0xFF0A192F, 0xFFFF6E6E, 0xFF64FFDA, 0xFFFFD180,
                0xFF82B1FF, 0xFF80D8FF, 0xFF18FFFF, 0xFFCFD8DC,
                0xFF1F3A5F, 0xFFFF8A80, 0xFFA7FFEB, 0xFFFFE082,
                0xFF8C9EFF, 0xFFB388FF, 0xFF84FFFF, 0xFFECEFF1,
            ),
        ),

        // #516: three schemes whose default foreground is a plain grey rather
        // than a tinted one. Palettes transcribed from upstream, not eyeballed:
        // Campbell from microsoft/terminal's TerminalSettingsModel/defaults.json,
        // the two Modern ones from microsoft/vscode's terminalColorRegistry.ts
        // (`ansiColorMap` light/dark defaults) with fg/bg from the matching
        // light_modern.json / dark_modern.json.
        CAMPBELL(
            "Campbell", 0xFF0C0C0C, 0xFFCCCCCC,
            longArrayOf(
                0xFF0C0C0C, 0xFFC50F1F, 0xFF13A10E, 0xFFC19C00,
                0xFF0037DA, 0xFF881798, 0xFF3A96DD, 0xFFCCCCCC,
                0xFF767676, 0xFFE74856, 0xFF16C60C, 0xFFF9F1A5,
                0xFF3B78FF, 0xFFB4009E, 0xFF61D6D6, 0xFFF2F2F2,
            ),
        ),
        MODERN_DARK(
            "Modern Dark", 0xFF1F1F1F, 0xFFCCCCCC,
            longArrayOf(
                0xFF000000, 0xFFCD3131, 0xFF0DBC79, 0xFFE5E510,
                0xFF2472C8, 0xFFBC3FBC, 0xFF11A8CD, 0xFFE5E5E5,
                0xFF666666, 0xFFF14C4C, 0xFF23D18B, 0xFFF5F543,
                0xFF3B8EEA, 0xFFD670D6, 0xFF29B8DB, 0xFFE5E5E5,
            ),
        ),
        MODERN_LIGHT(
            "Modern Light", 0xFFFFFFFF, 0xFF3B3B3B,
            longArrayOf(
                0xFF000000, 0xFFCD3131, 0xFF107C10, 0xFF949800,
                0xFF0451A5, 0xFFBC05BC, 0xFF0598BC, 0xFF555555,
                0xFF666666, 0xFFCD3131, 0xFF14CE14, 0xFFB5BA00,
                0xFF0451A5, 0xFFBC05BC, 0xFF0598BC, 0xFFA5A5A5,
            ),
        );

        /** True when fg/bg should be sourced from the live system theme rather than the enum's static longs. */
        val isDynamic: Boolean get() = this == MATERIAL_YOU

        /** Snapshot of [ansi] as a 16-entry ARGB IntArray, ready for `applyColorScheme`. */
        fun ansiPaletteArgb(): IntArray = IntArray(16) { ansi[it].toInt() }

        companion object {
            fun fromString(value: String?): TerminalColorScheme =
                entries.find { it.name == value } ?: HAVEN
        }
    }

    val lockTimeout: Flow<LockTimeout> = dataStore.data.map { prefs ->
        LockTimeout.fromString(prefs[lockTimeoutKey])
    }

    suspend fun setLockTimeout(timeout: LockTimeout) {
        dataStore.edit { prefs ->
            prefs[lockTimeoutKey] = timeout.name
        }
    }

    enum class LockTimeout(val label: String, val seconds: Long) {
        IMMEDIATE("Immediately", 0),
        THIRTY_SECONDS("30 seconds", 30),
        ONE_MINUTE("1 minute", 60),
        FIVE_MINUTES("5 minutes", 300),
        NEVER("Never", Long.MAX_VALUE);

        companion object {
            fun fromString(value: String?): LockTimeout =
                entries.find { it.name == value } ?: IMMEDIATE
        }
    }

    enum class ThemeMode(val label: String) {
        SYSTEM("System default"),
        LIGHT("Light"),
        DARK("Dark");

        companion object {
            fun fromString(value: String?): ThemeMode =
                entries.find { it.name == value } ?: SYSTEM
        }
    }

    enum class SessionManager(
        val label: String,
        val url: String?,
        val command: ((String) -> String)?,
        val supportsScrollback: Boolean = true,
    ) {
        NONE("None", null, null, supportsScrollback = false),
        TMUX(
            "tmux",
            "https://github.com/tmux/tmux/wiki",
            { name -> "tmux new-session -A -s $name \\; set -gq allow-passthrough on \\; set -gq mouse on" }),
        ZELLIJ("zellij", "https://zellij.dev", { name -> "zellij attach $name --create" }),
        SCREEN(
            "screen",
            "https://www.gnu.org/software/screen/",
            { name -> "screen -dRR $name" },
            supportsScrollback = false
        ),
        BYOBU("byobu", "https://www.byobu.org", { name -> "byobu new-session -A -s $name \\; set -gq mouse on" }),
        HERDR("herdr", "https://herdr.dev", { name -> "herdr --session $name" }),
        PSMUX("psmux", "https://github.com/psmux/psmux", { name -> "psmux new-session -A -s $name" });

        companion object {
            fun fromString(value: String?): SessionManager =
                entries.find { it.name == value } ?: NONE
        }
    }

    /** Inline-image consent policy for the terminal (#583). */
    enum class TerminalInlineImages {
        OFF,
        ASK,
        ALWAYS;

        companion object {
            fun fromString(value: String?): TerminalInlineImages =
                entries.find { it.name == value } ?: ASK
        }
    }

    /** East Asian Ambiguous width rendering policy (#719). */
    enum class TerminalAmbiwidthMode(val id: String) {
        NARROW("narrow"),
        AUTO_NARROW("auto_narrow"),
        WIDE("wide"),
        AUTO_WIDE("auto_wide");

        companion object {
            fun fromString(value: String?): TerminalAmbiwidthMode =
                entries.find { it.id.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                    ?: NARROW
        }
    }

    companion object {
        const val DEFAULT_FONT_SIZE = 14
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 32

        // #745: even steps so the slider has discrete detents; 0 keeps the
        // terminal edge-to-edge, the previous behaviour.
        const val DEFAULT_HORIZONTAL_PADDING_DP = 0
        const val MIN_HORIZONTAL_PADDING_DP = 0
        const val MAX_HORIZONTAL_PADDING_DP = 24
        const val HORIZONTAL_PADDING_STEP_DP = 2

        // Mail list pinch-zoom: 75% of the terminal font size up to 300%.
        const val DEFAULT_MAIL_FONT_SCALE = 1.0f
        const val MIN_MAIL_FONT_SCALE = 0.75f
        const val MAX_MAIL_FONT_SCALE = 3.0f

        // Terminal toolbar minimum key width (dp). Default 0 = keys hug content.
        const val DEFAULT_TOOLBAR_MIN_BUTTON_WIDTH = 0
        const val MIN_TOOLBAR_MIN_BUTTON_WIDTH = 0
        const val MAX_TOOLBAR_MIN_BUTTON_WIDTH = 64

        // Terminal toolbar key scale (#641). 1.0 = the un-scaled toolbar.
        const val DEFAULT_TOOLBAR_KEY_SCALE = 1.0f
        const val MIN_TOOLBAR_KEY_SCALE = 1.0f
        const val MAX_TOOLBAR_KEY_SCALE = 2.0f

        /** What Haven asked RDP servers for before it was configurable (#422). */
        const val DEFAULT_RDP_WIDTH = 1920
        const val DEFAULT_RDP_HEIGHT = 1080

        /**
         * Floor for a requested RDP desktop dimension.
         *
         * RDP carries the desktop size as a u16, so the protocol's own limit is
         * far below anything usable and this exists only to reject a degenerate
         * request. It applies to WIDTH AND HEIGHT, which is why it used to be
         * wrong: at 640 it refused every mode shorter than that, including
         * 640x480 and 800x600 — 800x600 silently became 800x640, reported by a
         * user driving a guest that only runs at 800x600 (#572).
         *
         * 200 keeps the "not degenerate" guard the original comment describes
         * without ruling out standard modes.
         */
        const val MIN_RDP_DIMENSION = 200
        const val MAX_RDP_DIMENSION = 8192

        const val DEFAULT_SCROLLBACK_ROWS = 1000
        const val MIN_SCROLLBACK_ROWS = 100
        const val MAX_SCROLLBACK_ROWS = 25000

        /** Suggested presets surfaced by the Settings UI. */
        val SCROLLBACK_ROWS_PRESETS = listOf(1000, 5000, 10000, 25000)
        const val DEFAULT_TOOLBAR_ROWS = 2 // legacy
        const val DEFAULT_RETICULUM_HOST = "127.0.0.1"
        const val DEFAULT_RETICULUM_PORT = 37428
        const val DEFAULT_TOOLBAR_ROW1 = "keyboard,esc,tab,shift,ctrl,alt" // legacy
        const val DEFAULT_TOOLBAR_ROW2 =
            "arrow_left,arrow_up,arrow_down,arrow_right,sym_pipe,sym_tilde,sym_slash,sym_backslash,sym_backtick" // legacy
        const val DEFAULT_TERMINAL_LOCALE = "en_US.UTF-8"
    }
}
