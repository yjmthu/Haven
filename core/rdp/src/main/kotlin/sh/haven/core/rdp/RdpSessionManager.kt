package sh.haven.core.rdp

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RdpSessionManager"

/**
 * Manages active RDP sessions across the app.
 * Same pattern as the SSH session manager.
 */
@Singleton
class RdpSessionManager @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val tlsCertVerifier: sh.haven.core.data.agent.TlsCertVerifier,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val host: String = "",
        val port: Int = 3389,
        /** Stable server identity for the TLS cert pin (real remote for a
         *  tunnelled session, where [host] is 127.0.0.1). */
        val certHost: String = "",
        val certPort: Int = 0,
        val username: String = "",
        val password: String = "",
        val domain: String = "",
        val rdpSession: RdpSession? = null,
        /** SSH client kept alive for tunneled connections (opaque Closeable). */
        val sshClient: Closeable? = null,
        /** Local port for SSH tunnel, if tunneled. */
        val tunnelPort: Int? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rdp-session-io").apply { isDaemon = true }
    }

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
                it.status == SessionState.Status.CONNECTING
        }

    /**
     * Register a new session. Returns the generated sessionId.
     */
    fun registerSession(profileId: String, label: String): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
            ))
        }
        return sessionId
    }

    /**
     * Store connection parameters for a registered session.
     */
    fun connectSession(
        sessionId: String,
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String = "",
        sshClient: Closeable? = null,
        tunnelPort: Int? = null,
        // Real remote for the TLS cert pin; defaults to the socket target. For
        // an SSH-tunnelled session the caller passes the real remote so the pin
        // survives across sessions (the local tunnel port changes each time).
        certHost: String = host,
        certPort: Int = port,
    ) {
        _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")

        Log.d(TAG, "Connecting RDP session: ${redactHost(host, port)} user=${redactUsername(username)} (ssh tunnel: ${sshClient != null})")

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.CONNECTED,
                host = host,
                port = port,
                certHost = certHost,
                certPort = certPort,
                username = username,
                password = password,
                domain = domain,
                sshClient = sshClient,
                tunnelPort = tunnelPort,
            ))
        }
    }

    /**
     * Create an [RdpSession] for a connected session.
     * Returns the session instance ready to start.
     */
    fun createRdpSession(sessionId: String): RdpSession? {
        val session = _sessions.value[sessionId] ?: return null
        if (session.status != SessionState.Status.CONNECTED) return null
        if (session.rdpSession != null) return null

        val rdpSession = RdpSession(
            sessionId = sessionId,
            host = session.host,
            port = session.port,
            username = session.username,
            password = session.password,
            domain = session.domain,
            onDisconnected = {
                Log.d(TAG, "Session $sessionId disconnected")
                updateStatus(sessionId, SessionState.Status.DISCONNECTED)
            },
            tlsCertVerifier = tlsCertVerifier,
            certHost = session.certHost.ifEmpty { session.host },
            certPort = if (session.certPort != 0) session.certPort else session.port,
        )

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(rdpSession = rdpSession))
        }

        return rdpSession
    }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            // Clear password from memory when session is no longer active
            val clearPwd = status == SessionState.Status.DISCONNECTED ||
                status == SessionState.Status.ERROR
            map + (sessionId to existing.copy(
                status = status,
                password = if (clearPwd) "" else existing.password,
            ))
        }
    }

    fun removeSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        ioExecutor.execute {
            try {
                session.rdpSession?.close()
                session.sshClient?.close()
            } catch (e: Exception) {
                Log.e(TAG, "tearDown failed for $sessionId", e)
            }
        }
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        ioExecutor.execute {
            toRemove.forEach { session ->
                try {
                    session.rdpSession?.close()
                    session.sshClient?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    fun disconnectSession(sessionId: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.DISCONNECTED,
                password = "",
            ))
        }
    }

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        ioExecutor.execute {
            snapshot.forEach { session ->
                try {
                    session.rdpSession?.close()
                    session.sshClient?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }
}
