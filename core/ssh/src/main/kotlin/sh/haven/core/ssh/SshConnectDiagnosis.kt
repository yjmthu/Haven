package sh.haven.core.ssh

import com.jcraft.jsch.JSchException
import java.net.SocketTimeoutException

/**
 * Name which half of an SSH connect failed, and against which address.
 *
 * JSch reports two completely different failures with messages that look alike
 * to anyone who isn't reading its source, and #557 was diagnosed wrong twice
 * because of it. Verified against com.github.mwiede:jsch 2.28.6:
 *
 * | what happened                              | message JSch produces                                  |
 * |--------------------------------------------|--------------------------------------------------------|
 * | TCP never completed (dropped / filtered)   | `timeout: socket is not established`                    |
 * | TCP completed, peer then said nothing      | `Session.connect: java.net.SocketTimeoutException: Read timed out` |
 * | nothing listening                          | `java.net.ConnectException: Connection refused`         |
 *
 * The first comes from `Util.createSocket`, which catches the connect-phase
 * `SocketTimeoutException` and replaces it with that fixed string. The second is
 * the *read* timeout: `Session.connect` sets `SO_TIMEOUT` on an established
 * socket and then blocks for the server's identification string. So
 * **"Read timed out" is proof the TCP handshake succeeded** — something accepted
 * the connection and then stayed silent. That rules out every "the port is
 * closed" theory (a firewall drop, a forward aimed nowhere) and points at a
 * middlebox, a forward aimed at the wrong backend, or a filter that accepts
 * and holds.
 *
 * None of that reached the user: the raw JSch string was surfaced verbatim, so a
 * reporter had to guess, and the address actually dialled — the resolved IP,
 * which is the one fact that separates "wrong DNS answer" from everything else —
 * appeared nowhere at all.
 *
 * Only the two timeout shapes are rewritten. `ConnectException` and
 * `UnknownHostException` already say what happened *and* carry the address on
 * Android, and #367 depends on their text; leave them be.
 *
 * The original exception is always kept as the cause, so [isSshNetworkError]'s
 * walk of the cause chain (and #376 host rediscovery with it) is unaffected.
 */
object SshConnectDiagnosis {

    /**
     * The literal JSch emits when the *connect* timed out — see the table above.
     * Matched as a substring because JSch wraps it in a second JSchException on
     * some paths.
     */
    const val CONNECT_TIMEOUT_MARKER = "timeout: socket is not established"

    /**
     * Rewrite [e] into a failure that names the phase and the address, or return
     * it untouched when it isn't one of the ambiguous timeouts.
     *
     * [serverVersion] is the peer's identification string if one arrived —
     * read from `Session.getServerVersion()` *before* disconnecting, and null
     * when the banner never came (JSch throws NPE from that getter rather than
     * returning null, so callers must guard it).
     */
    fun rewrite(
        e: JSchException,
        host: String,
        address: String,
        port: Int,
        timeoutMs: Int,
        serverVersion: String?,
    ): JSchException {
        val where = endpoint(host, address, port)
        val secs = formatSeconds(timeoutMs)

        if (e.message.orEmpty().contains(CONNECT_TIMEOUT_MARKER)) {
            return JSchException(
                "No TCP connection to $where after $secs. The connection attempt got no reply, " +
                    "so the port is filtered, the packet was dropped, or nothing is at that address.",
                e,
            )
        }

        if (!hasReadTimeout(e)) return e

        val banner = serverVersion?.takeIf { it.isNotBlank() }
        return if (banner != null) {
            JSchException(
                "$where identified itself as \"$banner\" then stopped responding for $secs " +
                    "during key exchange.",
                e,
            )
        } else {
            JSchException(
                "$where accepted the TCP connection then sent nothing for $secs. The port is " +
                    "open and something answered, but it never sent an SSH identification " +
                    "string, so it is not an SSH server.",
                e,
            )
        }
    }

    /**
     * A read timeout survives as a plain [SocketTimeoutException] in the cause
     * chain. The connect-phase one does not — JSch discards it for the marker
     * string — so [rewrite] must test the marker first.
     */
    private fun hasReadTimeout(e: JSchException): Boolean =
        generateSequence<Throwable>(e) { it.cause.takeIf { c -> c !== it } }
            .take(MAX_CAUSE_DEPTH)
            .any { it is SocketTimeoutException }

    /** `name [ip]:port`, collapsing to `ip:port` when no name was resolved. */
    private fun endpoint(host: String, address: String, port: Int): String =
        if (address.isBlank() || address == host) "$host:$port" else "$host [$address]:$port"

    /** "10s" / "1.5s" — whole seconds when it divides, so the common case is clean. */
    private fun formatSeconds(timeoutMs: Int): String =
        if (timeoutMs % 1000 == 0) "${timeoutMs / 1000}s" else "${timeoutMs / 1000.0}s"

    private const val MAX_CAUSE_DEPTH = 8
}
