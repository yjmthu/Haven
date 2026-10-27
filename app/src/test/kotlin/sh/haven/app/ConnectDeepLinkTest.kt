package sh.haven.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.db.entities.ConnectionProfile

/** Unit coverage for the `haven://connect` deep-link parsing + matching (#305). */
class ConnectDeepLinkTest {

    private fun profile(
        id: String = "id",
        host: String = "example.com",
        username: String = "me",
        port: Int = 22,
        type: String = "SSH",
        remoteCommand: String? = null,
    ) = ConnectionProfile(
        id = id,
        label = host,
        host = host,
        username = username,
        port = port,
        connectionType = type,
        remoteCommand = remoteCommand,
    )

    /** A query getter backed by a map, standing in for `Uri::getQueryParameter`. */
    private fun query(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    // --- parse ---

    @Test
    fun `parse returns null when host is absent`() {
        assertNull(ConnectDeepLink.parse(query("user" to "me")))
    }

    @Test
    fun `parse returns null when host is blank`() {
        assertNull(ConnectDeepLink.parse(query("host" to "   ")))
    }

    @Test
    fun `parse extracts and normalises all params`() {
        val p = ConnectDeepLink.parse(
            query(
                "host" to "Example.com",
                "user" to "  me ",
                "port" to "2022",
                "transport" to "  SSH ",
                "session" to " work ",
            ),
        )!!
        assertEquals("Example.com", p.host)
        assertEquals("me", p.username)
        assertEquals(2022, p.port)
        assertEquals("ssh", p.transport)
        assertEquals("work", p.session)
    }

    @Test
    fun `parse leaves optional fields null when missing or blank`() {
        val p = ConnectDeepLink.parse(query("host" to "h", "user" to "", "session" to ""))!!
        assertNull(p.username)
        assertNull(p.port)
        assertNull(p.transport)
        assertNull(p.session)
    }

    @Test
    fun `parse treats a non-numeric port as unspecified`() {
        val p = ConnectDeepLink.parse(query("host" to "h", "port" to "abc"))!!
        assertNull(p.port)
    }

    // --- matchesTransport ---

    @Test
    fun `matchesTransport matches ssh by profile type`() {
        val ssh = profile()
        val rdp = profile(type = "RDP")
        assertTrue(ConnectDeepLink.matchesTransport(ssh, "ssh"))
        assertTrue(!ConnectDeepLink.matchesTransport(rdp, "ssh"))
        assertTrue(!ConnectDeepLink.matchesTransport(ssh, "rdp"))
    }

    // --- matches ---

    @Test
    fun `matches is case-insensitive on host and narrows by user, port, transport`() {
        val a = profile(id = "a", host = "Host.A", username = "me", port = 22)
        val b = profile(id = "b", host = "host.a", username = "other", port = 2222)
        val all = listOf(a, b)

        // Host only (case-insensitive) → both.
        assertEquals(
            setOf("a", "b"),
            ConnectDeepLink.matches(all, ConnectDeepLink.Params("HOST.A", null, null, null, null)).map { it.id }.toSet(),
        )
        // Narrow by user.
        assertEquals(
            listOf("a"),
            ConnectDeepLink.matches(all, ConnectDeepLink.Params("host.a", "me", null, null, null)).map { it.id },
        )
        // Narrow by port.
        assertEquals(
            listOf("b"),
            ConnectDeepLink.matches(all, ConnectDeepLink.Params("host.a", null, 2222, null, null)).map { it.id },
        )
    }

    // --- resolve ---

    @Test
    fun `resolve connects when exactly one profile matches`() {
        val match = profile(id = "p1", host = "h", username = "me")
        val cmd = ConnectDeepLink.resolve(
            listOf(match, profile(id = "other", host = "elsewhere")),
            ConnectDeepLink.Params("h", "me", null, null, "work"),
        )
        assertTrue(cmd is AgentUiCommand.ConnectFromDeepLink)
        cmd as AgentUiCommand.ConnectFromDeepLink
        assertEquals("p1", cmd.profileId)
        assertEquals("work", cmd.sessionName)
    }

    @Test
    fun `resolve prefills when no profile matches, defaulting transport to ssh`() {
        val cmd = ConnectDeepLink.resolve(
            emptyList(),
            ConnectDeepLink.Params("newhost", "me", 2022, null, "work"),
        )
        assertTrue(cmd is AgentUiCommand.PrefillNewConnection)
        cmd as AgentUiCommand.PrefillNewConnection
        assertEquals("newhost", cmd.host)
        assertEquals("me", cmd.username)
        assertEquals(2022, cmd.port)
        assertEquals("ssh", cmd.transport)
        assertEquals("work", cmd.session)
    }

    @Test
    fun `resolve prefills (not connects) when the match is ambiguous`() {
        val a = profile(id = "a", host = "h", username = "me")
        val b = profile(id = "b", host = "h", username = "me")
        val cmd = ConnectDeepLink.resolve(listOf(a, b), ConnectDeepLink.Params("h", "me", null, null, null))
        assertTrue(cmd is AgentUiCommand.PrefillNewConnection)
    }

    @Test
    fun `parse extracts id param`() {
        val p = ConnectDeepLink.parse(query("host" to "h", "id" to "uuid-123"))!!
        assertEquals("uuid-123", p.id)
    }

    @Test
    fun `parse allows host to be missing when id is present`() {
        val p = ConnectDeepLink.parse(query("id" to "uuid-123"))!!
        assertEquals("uuid-123", p.id)
        assertEquals("", p.host)
    }

    @Test
    fun `matches handles exact match by id parameter`() {
        val a = profile(id = "a", host = "h", username = "me")
        val b = profile(id = "b", host = "h", username = "me")
        val all = listOf(a, b)
        
        // matching with id="b" should return only "b", bypassing any ambiguity on host/user
        val p = ConnectDeepLink.Params(host = "h", username = "me", port = null, transport = null, session = null, id = "b")
        val result = ConnectDeepLink.matches(all, p)
        assertEquals(1, result.size)
        assertEquals("b", result[0].id)
    }

    @Test
    fun `matches narrows ambiguous candidates using remoteCommand session and command substring match`() {
        val a = profile(id = "a", host = "h", username = "me").copy(remoteCommand = "exec tmux new -s sess-a")
        val b = profile(id = "b", host = "h", username = "me").copy(remoteCommand = "exec tmux new -s sess-b")
        val all = listOf(a, b)

        // If session = "sess-b", it should narrow to "b"
        val p = ConnectDeepLink.Params(host = "h", username = "me", port = null, transport = null, session = "sess-b")
        val result = ConnectDeepLink.matches(all, p)
        assertEquals(1, result.size)
        assertEquals("b", result[0].id)
    }

    @Test
    fun `resolve connects exact match by id when candidates are ambiguous on host-user-port`() {
        val a = profile(id = "a", host = "h", username = "me")
        val b = profile(id = "b", host = "h", username = "me")
        val cmd = ConnectDeepLink.resolve(listOf(a, b), ConnectDeepLink.Params("h", "me", null, null, null, null, "b"))
        assertTrue(cmd is AgentUiCommand.ConnectFromDeepLink)
        cmd as AgentUiCommand.ConnectFromDeepLink
        assertEquals("b", cmd.profileId)
    }

    // --- id fallback and ambiguity (maintainer additions on #486) ---

    @Test
    fun `a stale id falls back to host matching when the link carries a host`() {
        // A profile deleted and recreated keeps its host but gets a new id, so a
        // bookmarked link's id stops resolving. Falling through to the host beats
        // opening an empty New-Connection editor.
        val prof = profile(id = "new-id", host = "h")
        val cmd = ConnectDeepLink.resolve(
            listOf(prof),
            ConnectDeepLink.parse(query("host" to "h", "id" to "old-id"))!!,
        )
        assertTrue(cmd is AgentUiCommand.ConnectFromDeepLink)
        assertEquals("new-id", (cmd as AgentUiCommand.ConnectFromDeepLink).profileId)
    }

    @Test
    fun `a stale id with no host still yields no match`() {
        val prof = profile(id = "new-id", host = "h")
        val matches = ConnectDeepLink.matches(
            listOf(prof),
            ConnectDeepLink.parse(query("id" to "old-id"))!!,
        )
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `a live id wins over a host that matches a different profile`() {
        val a = profile(id = "a", host = "h", username = "one")
        val b = profile(id = "b", host = "h", username = "two")
        val cmd = ConnectDeepLink.resolve(
            listOf(a, b),
            ConnectDeepLink.parse(query("host" to "h", "user" to "one", "id" to "b"))!!,
        )
        assertEquals("b", (cmd as AgentUiCommand.ConnectFromDeepLink).profileId)
    }

    @Test
    fun `session narrowing picks the one profile whose command contains it`() {
        val a = profile(id = "a", host = "h", remoteCommand = "tmux new -A -s work")
        val b = profile(id = "b", host = "h", remoteCommand = "tmux new -A -s play")
        val cmd = ConnectDeepLink.resolve(
            listOf(a, b),
            ConnectDeepLink.parse(query("host" to "h", "session" to "play"))!!,
        )
        assertEquals("b", (cmd as AgentUiCommand.ConnectFromDeepLink).profileId)
    }

    @Test
    fun `session narrowing that stays ambiguous prefills instead of guessing`() {
        // Both commands contain the session string. Connecting to the wrong host
        // is worse than asking, so this must NOT pick one.
        val a = profile(id = "a", host = "h", remoteCommand = "tmux new -A -s work")
        val b = profile(id = "b", host = "h", remoteCommand = "tmux new -A -s workshop")
        val cmd = ConnectDeepLink.resolve(
            listOf(a, b),
            ConnectDeepLink.parse(query("host" to "h", "session" to "work"))!!,
        )
        assertTrue(cmd is AgentUiCommand.PrefillNewConnection)
    }

    // --- standard URI schemes (#751) ---

    @Test
    fun `parseStandardScheme maps ssh user at host and port`() {
        val p = ConnectDeepLink.parseStandardScheme("ssh", "deploy", "server.example.com", 2222)!!
        assertEquals("server.example.com", p.host)
        assertEquals("deploy", p.username)
        assertEquals(2222, p.port)
        assertEquals("ssh", p.transport)
    }

    @Test
    fun `parseStandardScheme leaves port null when the link did not specify one`() {
        // Inventing a port here would over-narrow saved-profile matching: a
        // profile on a non-default port should still be found by a bare link.
        val p = ConnectDeepLink.parseStandardScheme("ssh", null, "h", null)!!
        assertNull(p.port)
        assertNull(p.username)
    }

    @Test
    fun `parseStandardScheme maps rdp and vnc to their transports`() {
        assertEquals("rdp", ConnectDeepLink.parseStandardScheme("rdp", null, "pc.lan", 3389)!!.transport)
        assertEquals("vnc", ConnectDeepLink.parseStandardScheme("vnc", null, "pc.lan", 5900)!!.transport)
    }

    @Test
    fun `parseStandardScheme rejects an empty host and an unknown scheme`() {
        assertNull(ConnectDeepLink.parseStandardScheme("ssh", null, "", null))
        assertNull(ConnectDeepLink.parseStandardScheme("ftp", null, "h", null))
    }

    @Test
    fun `matchesTransport recognises rdp and vnc profile types`() {
        assertTrue(ConnectDeepLink.matchesTransport(profile(type = "RDP"), "rdp"))
        assertTrue(!ConnectDeepLink.matchesTransport(profile(type = "SSH"), "rdp"))
        assertTrue(ConnectDeepLink.matchesTransport(profile(type = "VNC"), "vnc"))
        assertTrue(!ConnectDeepLink.matchesTransport(profile(type = "SSH"), "vnc"))
    }

    // --- ephemeralProfile (#751 part 2) ---

    @Test
    fun `ephemeralProfile maps an ssh link with an explicit port`() {
        val p = ConnectDeepLink.parseStandardScheme("ssh", "ops", "srv.example.com", 2222)!!
        val prof = ConnectDeepLink.ephemeralProfile(p)
        assertEquals("SSH", prof.connectionType)
        assertEquals(2222, prof.port)
        assertEquals("ops", prof.username)
        assertEquals("srv.example.com", prof.host)
        assertEquals("srv.example.com", prof.label)
        assertTrue(prof.ephemeral)
    }

    @Test
    fun `ephemeralProfile fills the transport default port when the link omitted one`() {
        val ssh = ConnectDeepLink.ephemeralProfile(ConnectDeepLink.parseStandardScheme("ssh", null, "h", null)!!)
        assertEquals(22, ssh.port)
        assertEquals("", ssh.username)
        val rdp = ConnectDeepLink.ephemeralProfile(ConnectDeepLink.parseStandardScheme("rdp", null, "h", null)!!)
        assertEquals("RDP", rdp.connectionType)
        assertEquals(3389, rdp.port)
        val vnc = ConnectDeepLink.ephemeralProfile(ConnectDeepLink.parseStandardScheme("vnc", null, "h", null)!!)
        assertEquals("VNC", vnc.connectionType)
        assertEquals(5900, vnc.port)
    }

    @Test
    fun `ephemeralProfile carries a haven connect link's session and transport`() {
        val p = ConnectDeepLink.parse(query("host" to "box", "user" to "u", "port" to "2022", "transport" to "ssh"))!!
        val prof = ConnectDeepLink.ephemeralProfile(p)
        assertEquals("SSH", prof.connectionType)
        assertEquals(2022, prof.port)
        assertEquals("u", prof.username)
        assertEquals("box", prof.host)
        assertTrue(prof.ephemeral)
    }

    @Test
    fun `resolve prefills an rdp draft for an unmatched rdp link`() {
        val p = ConnectDeepLink.parseStandardScheme("rdp", "admin", "newpc", 3390)!!
        val cmd = ConnectDeepLink.resolve(emptyList(), p)
        assertTrue(cmd is AgentUiCommand.PrefillNewConnection)
        cmd as AgentUiCommand.PrefillNewConnection
        assertEquals("newpc", cmd.host)
        assertEquals("admin", cmd.username)
        assertEquals(3390, cmd.port)
        assertEquals("rdp", cmd.transport)
    }
}
