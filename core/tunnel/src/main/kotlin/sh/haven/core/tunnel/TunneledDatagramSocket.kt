package sh.haven.core.tunnel

/**
 * A UDP socket routed through a [Tunnel]. Mirrors the subset of
 * [java.net.DatagramSocket] that unconnected-UDP callers actually use:
 * send-to / receive-from / close, with a per-call read timeout.
 *
 * Implementations are unconnected — every [send] carries an explicit
 * destination, and [receive] surfaces the source of each datagram. A
 * roaming client whose server is identified per datagram maps directly
 * onto this; if a future caller wants connected semantics, it can pin the
 * destination at the wrapper level rather than down here.
 *
 * Not thread-safe by contract. Callers that send and receive from
 * different threads must coordinate externally.
 */
interface TunneledDatagramSocket {
    /**
     * Send [data] to `host:port` through the tunnel. host must be a
     * literal IP — the bridge layer rejects hostnames so DNS lookups
     * can't happen on the per-packet path.
     */
    fun send(data: ByteArray, host: String, port: Int)

    /**
     * Block until a datagram arrives or [timeoutMs] expires.
     *
     * Returns `null` on timeout — the same convention
     * [java.net.DatagramSocket.receive] communicates via
     * [java.net.SocketTimeoutException], translated to null here so the
     * receive loop doesn't have to catch in two places. `timeoutMs`
     * `<= 0` blocks indefinitely.
     *
     * On a successful receive, the caller-supplied [buf] is populated
     * from index 0 with up to [ReceivedPacket.length] bytes. If the
     * datagram is larger than [buf] it is truncated — same as
     * [java.net.DatagramSocket] with an under-sized [java.net.DatagramPacket].
     *
     * Throws on transport failure (socket closed, tunnel torn down).
     */
    fun receive(buf: ByteArray, timeoutMs: Int): ReceivedPacket?

    /** Close the socket. Idempotent. */
    fun close()
}

/**
 * Result of a successful [TunneledDatagramSocket.receive]. Mirrors the
 * fields of [java.net.DatagramPacket] that callers actually read.
 */
data class ReceivedPacket(
    val length: Int,
    val srcHost: String,
    val srcPort: Int,
)
