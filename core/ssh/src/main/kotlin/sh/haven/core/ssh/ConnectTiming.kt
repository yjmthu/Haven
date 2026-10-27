package sh.haven.core.ssh

/**
 * Where the milliseconds go when opening an SSH session (#519).
 *
 * @frebib reported connects going from under 100 ms to reliably over a second
 * on a local network. "Connect took 1.2 s" is not actionable; "resolve 980 ms,
 * handshake 90 ms" names the culprit in one line. This is the same approach that
 * settled the RDP decode (#466) and the audio pipeline (#442), where a phase
 * breakdown ended arguments that reasoning could not.
 *
 * The phases are chosen to separate causes that look identical from outside:
 *
 *  - `resolve`   — turning a hostname into an address. A `.local` name that mDNS
 *                  does not answer costs 1.5 s here before system DNS is tried,
 *                  which alone would explain the report.
 *  - `setup`     — building the JSch session: auth methods, host-CA repository,
 *                  ssh_config options. Cheap unless loading a key hits the
 *                  Android keystore, a biometric gate, or another app.
 *  - `knock`     — the optional pre-connect hook, when one is supplied.
 *  - `handshake` — JSch's own connect: TCP, key exchange and authentication.
 *                  JSch does not expose those separately, so they share a bucket.
 *
 * Deliberately free of anything identifying: phase names and durations only, so
 * it is safe in a log a user attaches to an issue (#518).
 */
internal class ConnectTiming(private val nanos: () -> Long = System::nanoTime) {

    private val start = nanos()
    private var last = start
    private val phases = LinkedHashMap<String, Long>()

    /** Record the time since the previous mark (or since construction) as [phase]. */
    fun mark(phase: String) {
        val now = nanos()
        // += rather than =, so a phase entered twice (a retry, a resolve per
        // address family) reads as its total cost rather than only the last go.
        phases[phase] = (phases[phase] ?: 0L) + (now - last)
        last = now
    }

    /**
     * One line: `resolve=980ms setup=8ms handshake=90ms total=1078ms`.
     *
     * `total` is measured from construction rather than summed from the phases,
     * so any time that fell between marks shows up as a discrepancy instead of
     * being silently dropped — the same "unaccounted" column that located the
     * real cost in #466.
     */
    fun summary(): String {
        val total = (nanos() - start).toMillis()
        val parts = phases.entries.joinToString(" ") { "${it.key}=${it.value.toMillis()}ms" }
        val accounted = phases.values.sum().toMillis()
        val unaccounted = total - accounted
        return buildString {
            append(parts)
            if (unaccounted > UNACCOUNTED_REPORT_MS) append(" unaccounted=${unaccounted}ms")
            append(" total=${total}ms")
        }
    }

    private fun Long.toMillis(): Long = this / 1_000_000

    private companion object {
        /**
         * Only mention unaccounted time when it is more than rounding noise.
         * Each phase truncates to whole milliseconds, so a handful of marks can
         * lose a millisecond or two with nothing wrong.
         */
        const val UNACCOUNTED_REPORT_MS = 5L
    }
}
