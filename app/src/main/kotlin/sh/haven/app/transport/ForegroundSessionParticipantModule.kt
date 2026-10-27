package sh.haven.app.transport

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.map
import sh.haven.core.ssh.ForegroundSessionParticipant
import sh.haven.core.ssh.SshSessionManager

/**
 * Which transports count as "active connections" in the foreground
 * notification.
 *
 * Moved here from `:core:ssh` with the registry adapters (#510) — it named
 * `RdpSessionManager` directly, which was one of the two reasons `:core:ssh`
 * had a compile dependency on `:core:rdp`. RDP's participant now lives in
 * [DesktopTransportModule] so both of its registrations drop out of a
 * terminal-only build together.
 */
@Module
@InstallIn(SingletonComponent::class)
object ForegroundSessionParticipantModule {

    @Provides @IntoSet
    fun ssh(m: SshSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override val changes get() = m.sessions.map { }
        override fun disconnectAll() = m.disconnectAll()
    }

}
