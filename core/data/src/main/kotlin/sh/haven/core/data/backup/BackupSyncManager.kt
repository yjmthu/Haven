package sh.haven.core.data.backup

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads/writes the encrypted backup blob to a remote destination the user
 * already trusts — an existing connected SSH profile (#323).
 * The interface lives in core/data so [BackupSyncManager] and the settings
 * layer depend only on it; the transport-backed implementation lives in the
 * app module (it needs `feature/sftp`'s `TransportSelector`, the same seam
 * `MailToolProvider` uses to write attachments to any connected filesystem).
 */
interface RemoteBackupIo {
    /** Write [data] to [remotePath] on the backend for [profileId]. */
    suspend fun writeBackup(profileId: String, remotePath: String, data: ByteArray)

    /** Read [remotePath] from the backend for [profileId]. */
    suspend fun readBackup(profileId: String, remotePath: String): ByteArray
}

/**
 * Push/pull an encrypted config backup to an existing remote (#323).
 *
 * Deliberately thin: [BackupService] already produces and consumes the
 * AES-256-GCM blob, and [RemoteBackupIo] already moves bytes to any connected
 * profile. This just composes the two. No scheduler and no conflict merge in
 * v1 — the remote copy is last-write-wins, and sync is a manual action, per
 * the shape agreed on the issue. Periodic automation can layer on later.
 *
 * The destination profile must be **connected** first (same as browsing it in
 * the Files tab): [RemoteBackupIo] resolves the live backend, opening an SFTP
 * session on demand but requiring SMB/rclone to be already connected. A
 * failure to resolve surfaces as the exception the caller maps to "connect the
 * destination first".
 */
@Singleton
class BackupSyncManager @Inject constructor(
    private val backupService: BackupService,
    private val remoteBackupIo: RemoteBackupIo,
) {
    /** Encrypt the current config and write it to [remotePath] on [profileId]. */
    suspend fun push(profileId: String, remotePath: String, password: String) {
        val data = backupService.export(password)
        remoteBackupIo.writeBackup(profileId, remotePath, data)
    }

    /** Read [remotePath] from [profileId], decrypt, and restore it. */
    suspend fun pull(profileId: String, remotePath: String, password: String): BackupService.BackupResult {
        val data = remoteBackupIo.readBackup(profileId, remotePath)
        return backupService.import(data, password)
    }
}
