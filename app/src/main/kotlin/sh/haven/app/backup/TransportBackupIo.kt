package sh.haven.app.backup

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import sh.haven.core.data.backup.RemoteBackupIo
import sh.haven.feature.sftp.transport.TransportSelector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-layer [RemoteBackupIo] over `feature/sftp`'s [TransportSelector] — the
 * same seam `MailToolProvider` uses to write attachments to any connected
 * filesystem (#323). Lives in the app module because that's where the
 * `feature/sftp` dependency belongs; `core/data` and `feature/settings` see
 * only the interface.
 *
 * Resolution requires the destination profile to be connected: the SSH
 * session is opened on demand. A null resolution throws so the caller can
 * tell the user to connect the destination first.
 */
@Singleton
class TransportBackupIo @Inject constructor(
    private val transportSelector: TransportSelector,
) : RemoteBackupIo {

    override suspend fun writeBackup(profileId: String, remotePath: String, data: ByteArray) {
        backend(profileId).writeBytes(remotePath, data)
    }

    override suspend fun readBackup(profileId: String, remotePath: String): ByteArray =
        backend(profileId).readBytes(remotePath)

    private suspend fun backend(profileId: String) =
        transportSelector.resolveFileBackend(profileId)?.backend
            ?: throw IllegalStateException(
                "Backup destination '$profileId' isn't connected — open it first, then retry.",
            )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupIoModule {
    @Binds
    abstract fun bindRemoteBackupIo(impl: TransportBackupIo): RemoteBackupIo
}
