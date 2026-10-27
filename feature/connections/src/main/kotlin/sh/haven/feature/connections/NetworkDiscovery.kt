package sh.haven.feature.connections

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import sh.haven.core.redact.LogRedact

private const val TAG = "NetworkDiscovery"

data class DiscoveredHost(
    val address: String,
    val hostname: String?,
    val port: Int = 22,
    val source: String, // "mDNS", "scan", "jump", "tailscale", or "localhost"
)

/**
 * Discovers SSH hosts on the local network via:
 * 1. mDNS/DNS-SD: listens for _ssh._tcp services (Avahi/Bonjour)
 * 2. ARP scan: reads /proc/net/arp and probes port 22
 */
class NetworkDiscovery(private val context: Context) {

    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()


    /**
     * Master switch for the Tailscale LocalAPI probe (#654). Set from the
     * user preference by ConnectionsViewModel; when false, discoverTailscale
     * returns before touching 100.100.100.100 and any previously discovered
     * peers are dropped.
     */
    @Volatile
    var tailscaleDiscoveryEnabled: Boolean = true

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val mdnsHosts = mutableSetOf<DiscoveredHost>()
    private val arpHosts = mutableSetOf<DiscoveredHost>()
    private val tailscaleHosts = mutableSetOf<DiscoveredHost>()

    private val jumpHosts = mutableSetOf<DiscoveredHost>()

    fun start() {
        startMdns()
    }

    fun stop() {
        stopMdns()
        mdnsHosts.clear()
        arpHosts.clear()
        synchronized(jumpHosts) { jumpHosts.clear() }
        tailscaleHosts.clear()
        _hosts.value = emptyList()
    }

    private fun isTailscaleAppInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.tailscale.ipn", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Discover Tailscale peers via the local API (100.100.100.100).
     * Silently returns if Tailscale is not running. The probe is skipped
     * entirely when the official Tailscale app is not installed: the
     * quad-100 LocalAPI only exists on the app's own TUN interface, so
     * without it the attempt is a doomed connect that firewalls see
     * (#654).
     */
    suspend fun discoverTailscale() {
        withContext(Dispatchers.IO) {
            if (!tailscaleDiscoveryEnabled) {
                // User turned the probe off (#654): drop any peers a previous
                // probe found so the list reflects the switch immediately.
                val had = synchronized(tailscaleHosts) { tailscaleHosts.isNotEmpty() }
                if (had) {
                    synchronized(tailscaleHosts) { tailscaleHosts.clear() }
                    mergeAndEmit()
                }
                return@withContext
            }
            if (!isTailscaleAppInstalled()) {
                return@withContext
            }
            try {
                val url = URL("http://100.100.100.100/localapi/v0/status")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 2_000
                conn.readTimeout = 5_000
                conn.requestMethod = "GET"

                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@withContext
                }

                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(body)
                val peers = json.optJSONObject("Peer") ?: return@withContext

                val discovered = mutableListOf<DiscoveredHost>()
                for (key in peers.keys()) {
                    val peer = peers.getJSONObject(key)
                    if (!peer.optBoolean("Online", false)) continue

                    val hostname = peer.optString("HostName", "").ifEmpty { null }
                    val ips = peer.optJSONArray("TailscaleIPs")
                    if (ips == null || ips.length() == 0) continue

                    // Prefer IPv4 tailscale address
                    val ip = (0 until ips.length())
                        .map { ips.getString(it) }
                        .firstOrNull { !it.contains(":") }
                        ?: ips.getString(0)

                    discovered.add(DiscoveredHost(
                        address = ip,
                        hostname = hostname,
                        port = 22,
                        source = "tailscale",
                    ))
                }

                if (discovered.isNotEmpty()) {
                    Log.d(TAG, "Tailscale: found ${discovered.size} online peers")
                    synchronized(tailscaleHosts) {
                        tailscaleHosts.clear()
                        tailscaleHosts.addAll(discovered)
                    }
                    mergeAndEmit()
                }
            } catch (_: Exception) {
                // Tailscale not running or API not accessible — silently ignore
            }
        }
    }

    /**
     * Scan the local /24 subnet for hosts with SSH on port 22.
     * Uses ConnectivityManager to determine the local IP, then probes
     * all 254 addresses in parallel with a short timeout.
     */
    suspend fun scanSubnet() {
        withContext(Dispatchers.IO) {
            try {
                val baseIp = getLocalSubnetBase() ?: run {
                    Log.d(TAG, "Could not determine local subnet")
                    return@withContext
                }
                Log.d(TAG, "Scanning subnet $baseIp.1-254 for SSH")

                coroutineScope {
                    val jobs = (1..254).map { i ->
                        async(Dispatchers.IO) {
                            val ip = "$baseIp.$i"
                            if (probePort(ip, 22, timeoutMs = 400)) {
                                val hostname = resolveHostname(ip)
                                val host = DiscoveredHost(
                                    address = ip,
                                    hostname = hostname,
                                    port = 22,
                                    source = "scan",
                                )
                                synchronized(arpHosts) {
                                    arpHosts.add(host)
                                }
                                mergeAndEmit()
                                Log.d(TAG, "SSH found: ${LogRedact.of(ip)} (${LogRedact.of(hostname)})")
                            }
                        }
                    }
                    jobs.awaitAll()
                }
                Log.d(TAG, "Subnet scan complete: ${arpHosts.size} SSH hosts found")
            } catch (e: Exception) {
                Log.e(TAG, "Subnet scan failed", e)
            }
        }
    }

    /**
     * Scan a /24 for SSH through a SOCKS5 proxy, so the probes originate at the
     * proxy rather than at this device. Haven points that proxy at a jump
     * host's own dynamic forward, which is how "scan the network behind my jump
     * host" works with nothing installed on the far side.
     *
     * Deliberately unlike [scanSubnet] in two ways:
     *  - **Concurrency is bounded.** The local scan fires all 254 probes at
     *    once because they are bare sockets. Every probe here costs an SSH
     *    direct-tcpip channel and two relay threads inside the SOCKS server,
     *    so the same fan-out would be several hundred threads on the phone.
     *  - **No reverse DNS.** [resolveHostname] would ask *this* device's
     *    resolver about an address on a network it cannot see, and would
     *    either fail or answer about the wrong host. A confidently wrong name
     *    is worse than no name, so these results carry addresses only.
     *
     * Returns the number of hosts found.
     */
    suspend fun scanSubnetVia(
        proxy: Proxy,
        subnetBase: String,
        timeoutMs: Int = JUMP_PROBE_TIMEOUT_MS,
        concurrency: Int = JUMP_SCAN_CONCURRENCY,
    ): Int = withContext(Dispatchers.IO) {
        val gate = Semaphore(concurrency)
        val found = AtomicInteger(0)
        Log.d(TAG, "Scanning $subnetBase.1-254 for SSH via jump host")
        try {
            coroutineScope {
                (1..254).map { i ->
                    async(Dispatchers.IO) {
                        val ip = "$subnetBase.$i"
                        gate.withPermit {
                            if (probePort(ip, 22, timeoutMs, proxy)) {
                                val host = DiscoveredHost(
                                    address = ip,
                                    hostname = null,
                                    port = 22,
                                    source = "jump",
                                )
                                synchronized(jumpHosts) { jumpHosts.add(host) }
                                mergeAndEmit()
                                found.incrementAndGet()
                                Log.d(TAG, "SSH via jump: ${LogRedact.of(ip)}")
                            }
                        }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Jump-host scan failed", e)
        }
        Log.d(TAG, "Jump scan complete: ${found.get()} host(s) on $subnetBase.0/24")
        found.get()
    }

    /** Drop results from a previous jump-host scan. */
    fun clearJumpHosts() {
        synchronized(jumpHosts) { jumpHosts.clear() }
        mergeAndEmit()
    }


    @SuppressLint("MissingPermission") // ACCESS_NETWORK_STATE declared in app manifest
    private fun getLocalSubnetBase(): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val props: LinkProperties = cm.getLinkProperties(network) ?: return null
            val ipv4 = props.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?: return null
            val parts = ipv4.hostAddress?.split(".") ?: return null
            if (parts.size != 4) return null
            return "${parts[0]}.${parts[1]}.${parts[2]}"
        } catch (e: Exception) {
            Log.e(TAG, "getLocalSubnetBase failed", e)
            return null
        }
    }

    private fun startMdns() {
        try {
            val mgr = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager = mgr

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "mDNS discovery started for $serviceType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "mDNS found: ${serviceInfo.serviceName}")
                    mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.d(TAG, "mDNS resolve failed: ${info.serviceName} error=$errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val addr = info.host?.hostAddress ?: return
                            val host = DiscoveredHost(
                                address = addr,
                                hostname = info.serviceName,
                                port = info.port,
                                source = "mDNS",
                            )
                            Log.d(TAG, "mDNS resolved: $addr (${info.serviceName}:${info.port})")
                            mdnsHosts.add(host)
                            mergeAndEmit()
                        }
                    })
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    mdnsHosts.removeAll { it.hostname == serviceInfo.serviceName }
                    mergeAndEmit()
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "mDNS discovery stopped")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "mDNS start failed: error=$errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "mDNS stop failed: error=$errorCode")
                }
            }

            discoveryListener = listener
            mgr.discoverServices("_ssh._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "mDNS init failed", e)
        }
    }

    private fun stopMdns() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {
            // Already stopped
        }
        discoveryListener = null
        nsdManager = null
    }

    private fun mergeAndEmit() {
        // Snapshot under the same lock the scan writes with: the jump scan
        // adds from 16 concurrent probes, and iterating a LinkedHashSet
        // mid-insert throws rather than merely reading stale data.
        val jumpSnapshot = synchronized(jumpHosts) { jumpHosts.toList() }
        val all = (mdnsHosts + arpHosts + tailscaleHosts + jumpSnapshot)
            .distinctBy { it.address }
            .sortedWith(compareBy(
                { it.port != 22 },                                    // port 22 first
                { it.source != "tailscale" && it.source != "mDNS" }, // tailscale/mDNS before scan
                { it.hostname == null },                              // named hosts before bare IPs
                { it.address },
            ))
        _hosts.value = all
    }

    private fun probePort(host: String, port: Int, timeoutMs: Int, proxy: Proxy? = null): Boolean {
        return try {
            // A SOCKS-proxied Socket makes the connect happen at the proxy's
            // end, which is the whole trick behind scanning via a jump host:
            // the probe is unchanged, only where it originates moves.
            val socket = if (proxy != null) Socket(proxy) else Socket()
            socket.use {
                it.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveHostname(ip: String): String? {
        return try {
            val addr = java.net.InetAddress.getByName(ip)
            val name = addr.canonicalHostName
            if (name != ip) name else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /**
         * Longer than the 400ms local probe: every probe now pays the round
         * trip to the jump host plus the direct-tcpip channel open.
         */
        const val JUMP_PROBE_TIMEOUT_MS = 1_500

        /**
         * Each in-flight probe holds an SSH channel and two relay threads in
         * the SOCKS server, so this is a thread budget as much as a politeness
         * limit. A dead /24 takes roughly 254 / 16 * timeout to sweep.
         */
        const val JUMP_SCAN_CONCURRENCY = 16

        private val INET_RE =
            Regex("^\\s*\\d+:\\s+(\\S+)\\s+inet\\s+(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})")
        private val DEFAULT_DEV_RE = Regex("\\bdev\\s+(\\S+)")

        /**
         * Pull the /24 bases out of `ip -o -4 addr` output.
         *
         * Always the /24 containing the interface address, never the declared
         * prefix: a /16 would be 65,536 probes through one SSH connection,
         * which is not a scan anyone wants started by accident.
         *
         * When [defaultRouteOutput] (`ip -o -4 route show default`) names an
         * interface, only that interface is swept. A developer machine is
         * routinely on four networks — `docker0`, `virbr0` and a compose bridge
         * alongside the real LAN — and sweeping all of them turned one 254-host
         * scan into 1016 probes across three networks nobody wanted, none of
         * which is the network the user meant. The default route is the one
         * that answers "which network is this host actually on".
         *
         * Loopback and link-local are dropped — neither is reachable network
         * worth sweeping.
         */
        fun parseSubnetBases(
            ipAddrOutput: String,
            defaultRouteOutput: String? = null,
        ): List<String> {
            val preferredDev = defaultRouteOutput
                ?.lineSequence()
                ?.firstNotNullOfOrNull { DEFAULT_DEV_RE.find(it)?.groupValues?.get(1) }

            val all = mutableListOf<Pair<String, String>>() // iface to base
            for (line in ipAddrOutput.lineSequence()) {
                val m = INET_RE.find(line) ?: continue
                val iface = m.groupValues[1]
                val o1 = m.groupValues[2].toIntOrNull() ?: continue
                val o2 = m.groupValues[3].toIntOrNull() ?: continue
                val o3 = m.groupValues[4].toIntOrNull() ?: continue
                if (o1 > 255 || o2 > 255 || o3 > 255) continue
                if (o1 == 127) continue
                if (o1 == 169 && o2 == 254) continue
                all += iface to "$o1.$o2.$o3"
            }

            val chosen = if (preferredDev != null && all.any { it.first == preferredDev }) {
                all.filter { it.first == preferredDev }
            } else {
                all
            }
            return chosen.map { it.second }.distinct()
        }
    }
}

