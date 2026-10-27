package sh.haven.app.transport

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import sh.haven.core.et.EtSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.ssh.SessionManager
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.Transport
import sh.haven.core.ssh.TransportSessionManager

/**
 * Binds each transport into [sh.haven.core.ssh.SessionManagerRegistry].
 *
 * These adapters live in `:app` rather than `:core:ssh` because `:app` is the
 * module that already depends on every transport. Keeping them here is what
 * lets `:core:ssh` stop depending on `:core:rdp` — and so what lets a build
 * variant leave the remote-desktop transports out entirely (#510). RDP is
 * bound separately in the flavour-specific `DesktopTransportModule`.
 *
 * Each adapter answers only what its manager actually supports; the interface
 * defaults cover the rest.
 */
@Module
@InstallIn(SingletonComponent::class)
object TransportSessionManagerModule {

    @Provides @IntoSet
    fun ssh(m: SshSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.SSH
        override val inputName = "SSH"
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override fun sendInput(sessionId: String, text: String) = m.sendInput(sessionId, text)
        override val activeSessionCount get() = m.activeSessions.size
        // Only SSH carries a session-manager (tmux/zellij/screen) name; other
        // transports have no equivalent, so their sessionName stays null.
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(
                    it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.SSH,
                    it.chosenSessionName,
                    sessionManagerLabel = it.sessionManager.takeIf { sm -> sm != SessionManager.NONE }?.label,
                )
            }
    }

    @Provides @IntoSet
    fun mosh(m: MoshSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.MOSH
        override val inputName = "mosh"
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override fun sendInput(sessionId: String, text: String) = m.sendInput(sessionId, text)
        override val activeSessionCount get() = m.activeSessions.size
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.MOSH)
            }
    }

    @Provides @IntoSet
    fun et(m: EtSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.ET
        override val inputName = "ET"
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override fun sendInput(sessionId: String, text: String) = m.sendInput(sessionId, text)
        override val activeSessionCount get() = m.activeSessions.size
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.ET)
            }
    }

}
