package sh.haven.core.ssh

import sh.haven.core.data.db.KnownHostDao
import sh.haven.core.data.db.entities.KnownHost
import javax.inject.Inject
import javax.inject.Singleton

sealed class HostKeyResult {
    data object Trusted : HostKeyResult()
    data class NewHost(val entry: KnownHostEntry) : HostKeyResult()
    data class KeyChanged(
        val old: KnownHost,
        val new: KnownHostEntry,
    ) : HostKeyResult()
}

@Singleton
class HostKeyVerifier @Inject constructor(
    private val knownHostDao: KnownHostDao,
) {

    suspend fun verify(entry: KnownHostEntry): HostKeyResult {
        val stored = knownHostDao.findByHostPort(entry.hostname, entry.port)
            ?: return HostKeyResult.NewHost(entry)

        return if (stored.publicKeyBase64 == entry.publicKeyBase64) {
            HostKeyResult.Trusted
        } else {
            HostKeyResult.KeyChanged(old = stored, new = entry)
        }
    }

    suspend fun accept(entry: KnownHostEntry) {
        // Delete any existing entry for this host:port, then insert the new one
        knownHostDao.deleteByHostPort(entry.hostname, entry.port)
        knownHostDao.upsert(
            KnownHost(
                hostname = entry.hostname,
                port = entry.port,
                keyType = entry.keyType,
                publicKeyBase64 = entry.publicKeyBase64,
                fingerprint = entry.fingerprint(),
            ),
        )
    }
}
