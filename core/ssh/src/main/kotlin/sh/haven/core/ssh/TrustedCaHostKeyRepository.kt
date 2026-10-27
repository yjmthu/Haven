package sh.haven.core.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo

/**
 * A JSch [HostKeyRepository] that serves ONLY `@cert-authority` entries — a
 * supplied list of trusted SSH host-CA public keys (#133).
 *
 * Installing this on a session activates JSch's native OpenSSH host-certificate
 * verification (`OpenSshCertificateHostKeyVerifier`): when the server presents
 * a `*-cert-v01@openssh.com` host key signed by one of these CAs — signature
 * valid, within validity window, principal matches the hostname, not revoked —
 * `Session.checkHost` returns before ever consulting [check], and the session's
 * host key is left unset. [SshClient.extractHostKey] reads that state
 * (together with [checkConsulted]) as "verified by CA" and skips Haven's TOFU
 * flow. When cert validation fails, JSch falls back to plain key verification
 * (`host_certificate_to_key_fallback` default): [check] IS consulted, returns
 * NOT_INCLUDED, `StrictHostKeyChecking=no` accepts, and Haven's usual
 * post-connect TOFU handles the stripped underlying key — same UX as today.
 *
 * Read-only by design: [add]/[remove] are no-ops. Haven's known hosts live in
 * Room, not in this repository.
 */
class TrustedCaHostKeyRepository(caPublicKeys: List<String>) : HostKeyRepository {

    /**
     * True once JSch consulted [check] — i.e. the plain-key (TOFU) path ran
     * for this session, so the host was NOT accepted via a CA-signed cert.
     */
    @Volatile
    var checkConsulted: Boolean = false
        private set

    private val caEntries: List<HostKey> = caPublicKeys.mapNotNull(::parseCaLine)

    /** Number of CA keys that parsed successfully. */
    val caCount: Int get() = caEntries.size

    override fun check(host: String?, key: ByteArray?): Int {
        checkConsulted = true
        return HostKeyRepository.NOT_INCLUDED
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) { /* read-only */ }

    override fun remove(host: String?, type: String?) { /* read-only */ }

    override fun remove(host: String?, type: String?, key: ByteArray?) { /* read-only */ }

    override fun getKnownHostsRepositoryID(): String = "haven-trusted-host-cas"

    override fun getHostKey(): Array<HostKey> = caEntries.toTypedArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        caEntries.filter { type == null || it.type == type }.toTypedArray()

    companion object {
        /**
         * Parse one stored CA public key — an OpenSSH public-key line
         * ("ssh-ed25519 AAAA… comment") or a bare base64 blob — into an
         * `@cert-authority` [HostKey] matching every host. Returns null for
         * unparseable input (a malformed config entry must not break
         * connects; it just contributes no trust).
         */
        private fun parseCaLine(line: String): HostKey? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split(Regex("\\s+"))
            // "ssh-ed25519 AAAA… [comment]" vs a bare base64 blob
            val typed = parts.size >= 2 &&
                (parts[0].startsWith("ssh-") || parts[0].startsWith("ecdsa-") || parts[0].startsWith("sk-"))
            val b64 = if (typed) parts[1] else parts[0]
            val comment = (if (typed) parts.drop(2) else parts.drop(1)).joinToString(" ")
            val blob = try {
                java.util.Base64.getDecoder().decode(b64)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return try {
                HostKey("@cert-authority", "*", HostKey.GUESS, blob, comment.ifEmpty { null })
            } catch (_: Exception) {
                null
            }
        }
    }
}
