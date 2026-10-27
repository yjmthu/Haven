package sh.haven.app

import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.db.entities.ConnectionProfile

/**
 * Pure parsing + saved-profile matching for the `haven://connect` deep link
 * (#305). Kept free of `android.net.Uri` (the caller adapts it via the
 * [parse] query getter) so the parameter handling and host matching are
 * unit-testable on the JVM.
 *
 * Behaviour: a link identifies a host (plus optional user/port/transport).
 * If exactly one saved profile matches, we connect it (via a confirm step);
 * otherwise — no match or ambiguous — we open the New-Connection editor
 * pre-filled, since a deep link can't carry credentials and shouldn't
 * silently create a profile.
 */
object ConnectDeepLink {

    data class Params(
        val host: String,
        val username: String?,
        val port: Int?,
        /** `ssh` / `rdp` / `vnc`, or null when the link didn't specify one. */
        val transport: String?,
        val session: String?,
        /** Optional remote command (Tin attach / clean tmux). `command` or `startupCommand` query. */
        val command: String? = null,
        val id: String? = null,
    )

    /**
     * Build [Params] from a query-parameter getter (e.g. `uri::getQueryParameter`).
     * Returns null when no `host` (and no `id`) is present — a connect link without
     * a host or id is a no-op rather than an error.
     */
    fun parse(query: (String) -> String?): Params? {
        val host = query("host")?.trim()?.takeIf { it.isNotEmpty() }
        val id = query("id")?.trim()?.takeIf { it.isNotEmpty() }
        if (host == null && id == null) return null
        return Params(
            host = host ?: "",
            username = query("user")?.trim()?.takeIf { it.isNotEmpty() },
            port = query("port")?.trim()?.toIntOrNull(),
            transport = query("transport")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            session = query("session")?.trim()?.takeIf { it.isNotEmpty() },
            command = (query("command") ?: query("startupCommand"))?.trim()?.takeIf { it.isNotEmpty() },
            id = id,
        )
    }

    /** Whether [profile] is the transport named by [transport]. */
    fun matchesTransport(profile: ConnectionProfile, transport: String): Boolean =
        when (transport) {
            // Plain "ssh" matches any SSH profile for the host rather than
            // excluding one on a technicality.
            "ssh" -> profile.connectionType == "SSH"
            // Standard-scheme links (#751) carry their own transport; match the
            // profile type exactly so an ssh:// link never dials an RDP profile
            // that happens to share the host.
            "rdp" -> profile.connectionType == "RDP"
            "vnc" -> profile.connectionType == "VNC"
            else -> true
        }

    /**
     * Build [Params] from a standard-scheme link (`ssh://[user@]host[:port]`,
     * `rdp://[user@]host[:port]`, `vnc://host[:port]`) — the form other apps,
     * mail clients and QR codes emit (#751). [user] is the userinfo before any
     * `@` (the caller strips it), [host]/[port] from the authority. Returns null
     * for an empty host or an unclaimed scheme, so the link is a no-op rather
     * than a half-parsed connect. Port stays null when the link omitted it —
     * inventing a default would over-narrow saved-profile matching.
     */
    fun parseStandardScheme(scheme: String, user: String?, host: String?, port: Int?): Params? {
        val h = host?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val transport = scheme.trim().lowercase().takeIf { it == "ssh" || it == "rdp" || it == "vnc" } ?: return null
        return Params(
            host = h,
            username = user?.trim()?.takeIf { it.isNotEmpty() },
            port = port,
            transport = transport,
            session = null,
        )
    }

    /**
     * Build an unsaved, [ConnectionProfile.ephemeral] profile from [p] — the
     * "connect without saving" target (#751 part 2). The id is a fresh random
     * UUID that never enters the database; the transport picks the profile
     * type and the default port when the link omitted one (a link must dial
     * the port its scheme implies, unlike saved-profile matching where a null
     * port stays permissive). Credentials are absent by construction — a deep
     * link can't carry them — so the connect path prompts as it would for any
     * password-only host.
     */
    fun ephemeralProfile(p: Params): ConnectionProfile {
        val type = when (p.transport) {
            "rdp" -> "RDP"
            "vnc" -> "VNC"
            else -> "SSH"
        }
        val defaultPort = when (type) {
            "RDP" -> 3389
            "VNC" -> 5900
            else -> 22
        }
        return ConnectionProfile(
            label = p.host,
            host = p.host,
            port = p.port ?: defaultPort,
            username = p.username ?: "",
            connectionType = type,
        ).also { it.ephemeral = true }
    }

    /** Saved profiles whose host (and any supplied user/port/transport) match [p]. */
    fun matches(profiles: List<ConnectionProfile>, p: Params): List<ConnectionProfile> {
        if (p.id != null) {
            val byId = profiles.filter { it.id == p.id }
            // An id that still exists is the exact answer and wins outright.
            // One that doesn't is a stale link — a profile deleted and recreated
            // keeps its host but gets a new id — so fall through to host matching
            // when the link carried a host too, rather than returning nothing and
            // opening a New-Connection editor with a blank host.
            if (byId.isNotEmpty() || p.host.isEmpty()) return byId
        }
        val candidates = profiles.filter { prof ->
            prof.host.equals(p.host, ignoreCase = true) &&
                (p.username == null || prof.username.equals(p.username, ignoreCase = true)) &&
                (p.port == null || prof.port == p.port) &&
                (p.transport == null || matchesTransport(prof, p.transport))
        }
        if (candidates.size <= 1) {
            return candidates
        }
        val narrowCandidates = candidates.filter { prof ->
            val cmd = prof.remoteCommand
            if (cmd == null) {
                false
            } else {
                val matchSession = p.session == null || cmd.contains(p.session, ignoreCase = true)
                val matchCommand = p.command == null || cmd.contains(p.command, ignoreCase = true)
                if (p.session == null && p.command == null) {
                    false
                } else {
                    matchSession && matchCommand
                }
            }
        }
        return if (narrowCandidates.size == 1) {
            narrowCandidates
        } else {
            candidates
        }
    }

    /**
     * The command to emit for [p]: connect a single matched profile, otherwise
     * (zero or ambiguous matches) open the pre-filled New-Connection editor.
     */
    fun resolve(profiles: List<ConnectionProfile>, p: Params): AgentUiCommand {
        val match = matches(profiles, p).singleOrNull()
        return if (match != null) {
            AgentUiCommand.ConnectFromDeepLink(match.id, p.session, p.command)
        } else {
            AgentUiCommand.PrefillNewConnection(p.host, p.username, p.port, p.transport ?: "ssh", p.session)
        }
    }
}
