package sh.haven.core.data.agent

/**
 * UI-level commands the agent transport can post to drive the user's
 * existing surfaces. Distinct from [ConsentRequest] — these are
 * tap-equivalent navigation / state-promotion actions that reveal the
 * same UI the user already has access to. The agent layer publishes,
 * the UI layer collects (`HavenNavHost`, [sh.haven.feature.sftp.SftpViewModel]).
 *
 * Sealed so the collector exhaustively handles every variant. Adding a
 * new verb is intentionally a multi-site change: a new variant here, a
 * matching MCP tool, and a collector branch in whichever screen owns
 * the surface.
 */
sealed class AgentUiCommand {
    /**
     * Open the SFTP file browser for [profileId] at [path]. The
     * collector switches the pager to the SFTP page; the
     * [sh.haven.feature.sftp.SftpViewModel] reacts in parallel by
     * selecting the profile (if not already active) and calling
     * `navigateTo(path)`.
     *
     * Tap-equivalent: same effect as the user tapping the SFTP tab and
     * entering the path manually. [path] is interpreted by whichever
     * backend the profile resolves to — POSIX absolute for SSH and
     * Local, share-relative for SMB, remote-relative for rclone.
     */
    data class NavigateToSftpPath(
        val profileId: String,
        val path: String,
    ) : AgentUiCommand()

    /**
     * Bring the terminal tab for [sessionId] to the front. The
     * collector switches the pager to the Terminal page; the
     * [sh.haven.feature.terminal.TerminalViewModel] finds the matching
     * tab by sessionId and calls `selectTab(index)`.
     *
     * Tap-equivalent: same effect as the user tapping the Terminal tab
     * and tapping the right session header. No effect when no tab
     * matches — agents discover live sessionIds via `list_sessions`,
     * so a stale ID drops silently.
     */
    data class FocusTerminalSession(
        val sessionId: String,
    ) : AgentUiCommand()

    /**
     * Open a new terminal tab for [profileId]. The transport (SSH /
     * Mosh / ET / Reticulum / Local) derives from the profile's
     * `connectionType` and flags — this verb is profile-shaped, not
     * transport-shaped, mirroring how the user opens a terminal by
     * tapping the profile row.
     *
     * Used by the workspace launcher to materialise terminal items.
     * Same constraint as [NavigateToSftpPath]: collector must be a live
     * `TerminalViewModel`. Drops silently otherwise.
     */
    data class OpenTerminalSession(
        val profileId: String,
        /**
         * When non-null, the tab attaches directly to this session-manager
         * (tmux/zellij/screen) session and skips the interactive picker — used
         * by the workspace launcher to restore each tab to its saved session.
         */
        val sessionName: String? = null,
    ) : AgentUiCommand()

    /**
     * Open a new remote-desktop tab for [profileId]. The kind (VNC /
     * RDP) derives from the profile's `connectionType`.
     *
     * Collector is `DesktopViewModel`, which is nav-scoped, so emissions
     * always land regardless of which tab the user is currently viewing.
     */
    data class OpenRemoteDesktop(
        val profileId: String,
    ) : AgentUiCommand()

    /**
     * Re-mint a step-ca-signed SSH cert for [keyId]. Posted by
     * `MainActivity` when the user taps the "cert expiring soon"
     * notification (#133 phase 2b). HavenNavHost switches to the Keys
     * tab; KeysViewModel collects the same bus, finds the key by id,
     * and runs the OIDC + sign flow against the same CA the original
     * cert was minted from.
     *
     * Tap-equivalent: same effect as the user opening the key row's
     * overflow menu and choosing Regenerate.
     */
    data class RegenerateStepCaCert(
        val keyId: String,
    ) : AgentUiCommand()

    /**
     * Initiate a connection for a saved profile. Picked up by
     * [ConnectionsViewModel], which dispatches to the right transport
     * (SSH / Mosh / ET / VNC / RDP / SMB / rclone / Reticulum / local)
     * using the same code path a UI tap would, so route-through,
     * stored passwords, and key auth all apply identically.
     *
     * Used by the MCP `connect_profile` tool — the only sanctioned
     * way for the agent to drive a connect, since this re-uses the
     * existing UI password-prompt fallback when neither a stored
     * password nor a key is configured.
     *
     * [sessionName], when non-null, pre-selects a session-manager
     * (tmux/zellij/screen) session by name instead of surfacing the
     * interactive session picker — the agent can't tap that picker, so a
     * connect to a profile with existing sessions would otherwise stall.
     * The name is attached if it already exists or created otherwise
     * (`new-session -A -s <name>`). Ignored for transports / profiles with
     * no session manager.
     */
    data class ConnectProfile(
        val profileId: String,
        val sessionName: String? = null,
    ) : AgentUiCommand()

    /**
     * Connect from a `haven://connect` deep link that matched exactly one
     * saved profile (#305). Unlike [ConnectProfile] (the MCP path, no
     * prompt), this is routed through a confirmation step in
     * [ConnectionsViewModel] before connecting: a BROWSABLE deep link can be
     * fired by an arbitrary web page, and the confirm blocks a drive-by
     * connect that would otherwise use the profile's stored credentials. On
     * confirm it runs the same connect path as [ConnectProfile], attaching
     * [sessionName] (a tmux/zellij/screen session to create-or-attach).
     */
    data class ConnectFromDeepLink(
        val profileId: String,
        val sessionName: String? = null,
        /** Optional remote command (e.g. tmux attach) — mosh path uses mosh-server `-- COMMAND`. */
        val startupCommand: String? = null,
    ) : AgentUiCommand()

    /**
     * Open the New-Connection editor pre-filled from a `haven://connect` deep
     * link whose host matched no saved profile (#305). Deep links can't carry
     * credentials, so rather than silently create a half-configured (or
     * attacker-named) profile, the editor is surfaced seeded with these
     * fields for the user to review, add auth, and save. [transport] is one
     * of `ssh` / `mosh` / `et`.
     */
    data class PrefillNewConnection(
        val host: String,
        val username: String?,
        val port: Int?,
        val transport: String,
        val session: String?,
    ) : AgentUiCommand()

    /**
     * Answer the password / key-passphrase prompt currently shown by
     * ConnectionsViewModel (its `_passwordFallback` dialog): supply the secret
     * a human would type, then re-drive the stalled connect through the same
     * path. Used by the MCP `answer_auth_prompt` verb. No-op when no prompt is
     * pending.
     */
    data class AnswerAuthPrompt(
        val password: String,
        val username: String? = null,
        val rememberPassword: Boolean = false,
    ) : AgentUiCommand()

    /**
     * Answer the session-manager picker currently shown by
     * ConnectionsViewModel (its `_sessionSelection` dialog): pick an existing
     * session by name, or pass [sessionName] = null to create a new one — then
     * re-drive the stalled attach through `onSessionSelected`, the same path a
     * human tap uses. Used by the MCP `answer_session_picker` verb. No-op when
     * no picker is pending.
     */
    data class AnswerSessionSelection(
        val sessionId: String,
        val sessionName: String? = null,
    ) : AgentUiCommand()

    /**
     * Encrypt the file at [path] on [profileId] to [recipients] (age
     * `age1…` strings), producing `<name>.age` in the same folder. The
     * collector ([sh.haven.feature.sftp.SftpViewModel]) selects the
     * profile if needed and runs `encryptFile` — the same code path the
     * file browser's Encrypt (age) action drives, so the user sees the
     * progress and the result appear. Used by the MCP `encrypt_file`
     * verb (VISION §2). Non-destructive: the original is kept.
     */
    data class EncryptFile(
        val profileId: String,
        val path: String,
        val recipients: List<String>,
    ) : AgentUiCommand()

    /**
     * Decrypt the `.age` file at [path] on [profileId] in place (strips
     * `.age`) using any stored age identity. Collector runs `decryptFile`
     * — same path as the file browser's Decrypt (age) action. Used by
     * the MCP `decrypt_file` verb (VISION §2).
     */
    data class DecryptFile(
        val profileId: String,
        val path: String,
    ) : AgentUiCommand()

    data object OpenBackupPasswordDialog : AgentUiCommand()

    /**
     * Open the Mail Rules pane (its pending-approval queue). Posted by
     * `MainActivity` when the user taps the "N mail actions awaiting
     * approval" notification (`MailRuleNotifier`). HavenNavHost switches to
     * the Mail tab and asks MailScreen to open its rules overlay. The Mail
     * tab only exists while an email session is connected — with none open,
     * the selection is remembered and lands there once the user reconnects
     * (the same reactive semantics as OpenRemoteDesktop on a hidden tab),
     * which matches reality: approving a queued IMAP action needs the
     * session anyway.
     *
     * Tap-equivalent: same effect as opening Mail → overflow → Rules.
     */
    data object OpenMailRules : AgentUiCommand()
}
