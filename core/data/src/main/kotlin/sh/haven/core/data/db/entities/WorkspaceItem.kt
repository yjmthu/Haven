package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One launchable element of a [WorkspaceProfile]. The [kind] dictates
 * how the launcher dispatches it; [connectionProfileId] is required and
 * is `SET_NULL`-ed when its profile is deleted (the launcher reports
 * such items as failed rather than silently dropping them, so the
 * workspace stays whole).
 */
@androidx.room.TypeConverters(WorkspaceKindConverter::class)
@Entity(
    tableName = "workspace_item",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceProfile::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ConnectionProfile::class,
            parentColumns = ["id"],
            childColumns = ["connectionProfileId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("workspaceId"),
        Index("connectionProfileId"),
    ],
)
data class WorkspaceItem(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val workspaceId: String,
    val kind: Kind,
    val connectionProfileId: String? = null,
    /** Starting path for [Kind.FILE_BROWSER]; ignored otherwise. */
    val path: String? = null,
    /**
     * For [Kind.TERMINAL]: the session-manager (tmux/zellij/screen) session
     * this tab was attached to when the workspace was saved. On restore the
     * launcher reattaches to it by name so the tab comes back to the same
     * session without the interactive picker. Null = plain shell / no manager.
     */
    val sessionName: String? = null,
    val sortOrder: Int = 0,
) {
    /**
     * The launchable surfaces a workspace can compose. The
     * underlying transport (SSH vs. Mosh vs. ET, SFTP vs. SMB vs.
     * Rclone, VNC vs. RDP) derives from the referenced
     * [ConnectionProfile.connectionType], so adding a new transport in
     * an existing kind doesn't expand this enum.
     */
    enum class Kind {
        TERMINAL,
        FILE_BROWSER,
        DESKTOP,
    }
}

/**
 * Stores [WorkspaceItem.Kind] by name and degrades unknown legacy values
 * (a kind a build no longer ships) to [WorkspaceItem.Kind.TERMINAL] instead
 * of throwing while the repository reads the row.
 */
object WorkspaceKindConverter {
    @androidx.room.TypeConverter
    @JvmStatic
    fun fromKind(kind: WorkspaceItem.Kind): String = kind.name

    @androidx.room.TypeConverter
    @JvmStatic
    fun toKind(value: String): WorkspaceItem.Kind =
        WorkspaceItem.Kind.entries.firstOrNull { it.name == value }
            ?: WorkspaceItem.Kind.TERMINAL
}
