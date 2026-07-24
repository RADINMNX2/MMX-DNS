package com.example.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FluxResolverController orchestrates the thread-safe, asynchronous execution
 * of the 3-Tier secure DNS resolution pipeline.
 *
 * Tier 1: JNI-based Native DNS-over-QUIC (DoQ) Engine.
 * Tier 2: JVM-based Secure DNS (DoH via protected OkHttp / DoT via TLS) with VPN Bypass.
 * Tier 3: Parallel Plain UDP racing across Anycast servers for high-speed fallback.
 */
class FluxResolverController(
    private val context: Context,
    private val resolveViaDoH: suspend (ByteArray, String) -> ByteArray?,
    private val resolveViaDoT: suspend (ByteArray, String) -> ByteArray?,
    private val protectSocket: (DatagramSocket) -> Boolean
) {
    private val doqClient = DnsOverQuicClient(protectSocket)

    companion object {
        private const val TAG = "FluxResolverController"
        private const val TIER1_TIMEOUT_MS = 800L
        private const val TIER2_TIMEOUT_MS = 2000L
    }

    init {
        Log.i(TAG, "FluxResolverController initialized with Socket Protection bypass interfaces.")
    }

    /**
     * Executes the sequential 3-tier cascade resolution pipeline for an incoming DNS query packet.
     * Guaranteed to operate on a background dispatcher (Dispatchers.IO) to prevent blocking.
     */
    /**
     * Executes the sequential 3-tier cascade resolution pipeline for an incoming DNS query packet.
     * Guaranteed to operate on a background dispatcher (Dispatchers.IO) to prevent blocking.
     */
    private val inMemoryCache = java.util.concurrent.ConcurrentHashMap<String, CachedDnsResponse>()

    data class CachedDnsResponse(
        val response: ByteArray,
        val timestamp: Long = System.currentTimeMillis(),
        val ttlMs: Long = 300_000L // 5 min TTL
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttlMs
        fun needsPrefetch(): Boolean = System.currentTimeMillis() - timestamp > (ttlMs * 0.8)
    }

    fun clearCache() {
        inMemoryCache.clear()
        Log.i(TAG, "FluxResolverController L2 In-Memory Cache purged successfully.")
    }

    private fun extractDomainKey(dnsQuery: ByteArray, domainName: String): String {
        if (domainName.isNotEmpty() && domainName != "unknown") return domainName
        if (dnsQuery.size < 12) return "raw_${dnsQuery.contentHashCode()}"
        try {
            var pos = 12
            val sb = StringBuilder()
            while (pos < dnsQuery.size) {
                val len = dnsQuery[pos].toInt() and 0xFF
                if (len == 0) break
                if ((len and 0xC0) == 0xC0) break
                if (pos + 1 + len > dnsQuery.size) break
                if (sb.isNotEmpty()) sb.append(".")
                sb.append(String(dnsQuery, pos + 1, len, Charsets.US_ASCII))
                pos += 1 + len
            }
            val parsed = sb.toString()
            return if (parsed.isNotBlank()) parsed else "hash_${dnsQuery.contentHashCode()}"
        } catch (_: Exception) {
            return "hash_${dnsQuery.contentHashCode()}"
        }
    }

    suspend fun resolve(
        dnsQuery: ByteArray,
        primaryDns: String,
        secondaryDns: String,
        enableIpv6: Boolean = false,
        primaryIpv6: String = "",
        secondaryIpv6: String = "",
        domain: String = "unknown",
        protocol: String = "UDP"
    ): ByteArray? = withContext(Dispatchers.IO) {
        val domainKey = extractDomainKey(dnsQuery, domain)

        // Tier 0: Zero-Copy L2 In-Memory Cache Lookup (0ms latency hit)
        val cached = inMemoryCache[domainKey]
        if (cached != null && !cached.isExpired()) {
            DnsVpnService.log(LogType.SUCCESS, "CACHE_HIT", "Tier 0 (0ms): Instant L2 cache hit for '$domainKey'")
            
            // Clone response bytes and copy current query's Transaction ID into response header
            val matchedResponse = cached.response.copyOf()
            if (dnsQuery.size >= 2 && matchedResponse.size >= 2) {
                matchedResponse[0] = dnsQuery[0]
                matchedResponse[1] = dnsQuery[1]
            }

            // Background pre-fetching if TTL is nearing expiration (> 80% elapsed)
            if (cached.needsPrefetch()) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val fresh = executeResolutionPipeline(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domainKey, protocol)
                        if (fresh != null) {
                            inMemoryCache[domainKey] = CachedDnsResponse(fresh)
                        }
                    } catch (_: Exception) {}
                }
            }
            return@withContext matchedResponse
        }

        val resolvedResponse = executeResolutionPipeline(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domainKey, protocol)
        if (resolvedResponse != null && resolvedResponse.isNotEmpty()) {
            inMemoryCache[domainKey] = CachedDnsResponse(resolvedResponse)
        }
        return@withContext resolvedResponse
    }

    private suspend fun executeResolutionPipeline(
        dnsQuery: ByteArray,
        primaryDns: String,
        secondaryDns: String,
        enableIpv6: Boolean,
        primaryIpv6: String,
        secondaryIpv6: String,
        domain: String,
        protocol: String
    ): ByteArray? {
        var response: ByteArray? = null

        // =====================================================================
        // TIER 1: Native JNI Engine (if compiled and available)
        // =====================================================================
        if (FluxDnsEngine.isNativeAvailable) {
            DnsVpnService.log(LogType.INFO, "RESOLVER", "Tier 1: Querying Native Rust JNI Engine via $protocol for '$domain'...")
            try {
                // If IPv6 is enabled and valid, try IPv6 native target first, then IPv4
                val activePrimary = if (enableIpv6 && primaryIpv6.isNotEmpty()) primaryIpv6 else primaryDns
                val activeSecondary = if (enableIpv6 && secondaryIpv6.isNotEmpty()) secondaryIpv6 else secondaryDns
                response = withTimeoutOrNull(TIER1_TIMEOUT_MS) {
                    FluxDnsEngine.resolveQuery(dnsQuery, activePrimary, activeSecondary, protocol)
                }
                if (response != null && response.isNotEmpty()) {
                    DnsVpnService.log(LogType.SUCCESS, "RESOLVED", "Tier 1: Resolved '$domain' via Native JNI.")
                    return response
                } else if (enableIpv6 && activePrimary != primaryDns) {
                    // Fallback Native query on IPv4
                    response = withTimeoutOrNull(TIER1_TIMEOUT_MS) {
                        FluxDnsEngine.resolveQuery(dnsQuery, primaryDns, secondaryDns, protocol)
                    }
                    if (response != null && response.isNotEmpty()) {
                        DnsVpnService.log(LogType.SUCCESS, "RESOLVED", "Tier 1: Resolved '$domain' via Native JNI (IPv4 Fallback).")
                        return response
                    }
                }
            } catch (e: TimeoutCancellationException) {
                DnsVpnService.log(LogType.WARNING, "RESOLVER", "Tier 1: JNI timed out after ${TIER1_TIMEOUT_MS}ms. Cascading to JVM fallbacks...")
            } catch (e: Exception) {
                Log.e(TAG, "Tier 1 JNI failure: ${e.message}", e)
                DnsVpnService.log(LogType.WARNING, "RESOLVER", "Tier 1: JNI error (${e.message}). Cascading to JVM fallbacks...")
            }
        }

        // =====================================================================
        // TIER 2 & TIER 3: Intelligent Dual-Stack Cascades Based on Chosen Protocol
        // =====================================================================
        when (protocol) {
            "DoQ" -> {
                response = tryResolveDoQ(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return response

                response = tryResolveDoH(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return response

                response = tryResolveDoT(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return response

                response = tryResolveUdpRacing(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return response
            }
            "DoH" -> {
                response = tryResolveDoH(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return response

                response = tryResolveDoT(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return response

                response = tryResolveUdpRacing(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return response
            }
            "DoT" -> {
                response = tryResolveDoT(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return response

                response = tryResolveDoH(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return response

                response = tryResolveUdpRacing(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return response
            }
            else -> {
                response = tryResolveUdpRacing(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return response

                response = tryResolveDoH(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return response
            }
        }

        return null
    }

    private suspend fun tryResolveDoQ(
        dnsQuery: ByteArray,
        primaryDns: String,
        secondaryDns: String,
        enableIpv6: Boolean,
        primaryIpv6: String,
        secondaryIpv6: String,
        domain: String
    ): ByteArray? {
        DnsVpnService.log(LogType.INFO, "RESOLVER", "Tier 2: Resolving '$domain' via Secure DNS-over-QUIC (DoQ)...")
        var response: ByteArray? = null
        if (enableIpv6 && primaryIpv6.isNotEmpty()) {
            response = withTimeoutOrNull(TIER2_TIMEOUT_MS) { doqClient.resolve(dnsQuery, primaryIpv6) }
            if (response == null && secondaryIpv6.isNotEmpty()) {
                response = withTimeoutOrNull(TIER2_TIMEOUT_MS) { doqClient.resolve(dnsQuery, secondaryIpv6) }
            }
        }
        if (response == null) {
            response = withTimeoutOrNull(TIER2_TIMEOUT_MS) { doqClient.resolve(dnsQuery, primaryDns) }
        }
        if (response == null && secondaryDns.isNotEmpty() && secondaryDns != primaryDns) {
            response = withTimeoutOrNull(TIER2_TIMEOUT_MS) { doqClient.resolve(dnsQuery, secondaryDns) }
        }
        if (response != null && response.isNotEmpty()) {
            DnsVpnService.log(LogType.SUCCESS, "RESOLVED", "Tier 2 DoQ: Successfully resolved '$domain'")
            return response
        }
        return null
    }

    private suspend fun tryResolveDoH(
        dnsQuery: ByteArray,
        primaryDns: String,
        secondaryDns: String,
        enableIpv6: Boolean,
        primaryIpv6: String,
        secondaryIpv6: String,
        domain: String
    ): ByteArray? {
        DnsVpnService.log(LogType.INFO, "RESOLVER", "Tier 2: Resolving '$domain' via Secure DNS-over-HTTPS (DoH)...")
        var response: ByteArray? = null
        if (enableIpv6 && primaryIpv6.isNotEmpty()) {
            response = withTimeoutOrNull(TIER2_TIMEOUT_MS) { resolveViaDoH(dnsQuery, primaryIpv6) }
            if (response == null && secondaryIpv6.isNotEmpty()) {
                response = withTimeoutOrNull(TIER2_TIMEOUT_MS) { resolveViaDoH(dnsQuery, secondaryIpv6) }
            }
        }
        if (response == null) {
            response = withTimeoutOrNull(TIER2_TIMEOUT_MS) { resolveViaDoH(dnsQuery, primaryDns) }
        }
        if (response == null && secondaryDns.isNotEmpty() && secondaryDns != primaryDns) {
            response = withTimeoutOrNull(TIER2_TIMEOUT_MS) { resolveViaDoH(dnsQuery, secondaryDns) }
        }
        if (response != null && response.isNotEmpty()) {
            DnsVpnService.log(LogType.SUCCESS, "RESOLVED", "Tier 2 DoH: Successfully resolved '$domain'")
            return response
        }
        return null
    }

    private suspend fun tryResolveDoT(
        dnsQuery: ByteArray,
        primaryDns: String,
        secondaryDns: String,
        enableIpv6: Boolean,
        primaryIpv6: String,
        secondaryIpv6: String,
        domain: String
    ): ByteArray? {
        DnsVpnService.log(LogType.INFO, "RESOLVER", "Tier 2: Resolving '$domain' via Secure DNS-over-TLS (DoT)...")
        var response: ByteArray? = null
        if (enableIpv6 && primaryIpv6.isNotEmpty()) {
            response = withTimeoutOrNull(TIER2_TIMEOUT_MS) { resolveViaDoT(dnsQuery, primaryIpv6) }
            if (response == null && secondaryIpv6.isNotEmpty()) {
                response = withTimeoutOrNull(TIER2_TIMEOUT_MS) { resolveViaDoT(dnsQuery, secondaryIpv6) }
            }
        }
        if (response == null) {
            response = withTimeoutOrNull(TIER2_TIMEOUT_MS) { resolveViaDoT(dnsQuery, primaryDns) }
        }
        if (response == null && secondaryDns.isNotEmpty() && secondaryDns != primaryDns) {
            response = withTimeoutOrNull(TIER2_TIMEOUT_MS) { resolveViaDoT(dnsQuery, secondaryDns) }
        }
        if (response != null && response.isNotEmpty()) {
            DnsVpnService.log(LogType.SUCCESS, "RESOLVED", "Tier 2 DoT: Successfully resolved '$domain'")
            return response
        }
        return null
    }

    private suspend fun tryResolveUdpRacing(
        dnsQuery: ByteArray,
        primaryDns: String,
        secondaryDns: String,
        enableIpv6: Boolean,
        primaryIpv6: String,
        secondaryIpv6: String,
        domain: String
    ): ByteArray? {
        DnsVpnService.log(LogType.WARNING, "FALLBACK", "Tier 3: Resolving '$domain' via Dual-Stack Anycast UDP Racing...")
        try {
            val response = raceUdpQueries(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6)
            if (response != null && response.isNotEmpty()) {
                DnsVpnService.log(LogType.SUCCESS, "RESOLVED", "Tier 3 UDP Racing: Successfully resolved '$domain'")
                return response
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tier 3 Parallel Racing error: ${e.message}", e)
        }
        return null
    }

    /**
     * Anycast Parallel UDP Racing logic across dual-stack (IPv4/IPv6) endpoints with MultiPath support.
     */
    private suspend fun raceUdpQueries(
        dnsQuery: ByteArray,
        primaryDns: String,
        secondaryDns: String,
        enableIpv6: Boolean = false,
        primaryIpv6: String = "",
        secondaryIpv6: String = ""
    ): ByteArray? = coroutineScope {
        val ips = mutableSetOf<String>()
        if (enableIpv6) {
            if (primaryIpv6.isNotEmpty()) ips.add(primaryIpv6)
            if (secondaryIpv6.isNotEmpty()) ips.add(secondaryIpv6)
            ips.add("2001:4860:4860::8888")
        }
        if (primaryDns.isNotEmpty()) ips.add(primaryDns)
        if (secondaryDns.isNotEmpty()) ips.add(secondaryDns)
        ips.add("8.8.8.8")
        ips.add("1.1.1.1")

        val targetIps = ips.take(4).toList()
        val multiPath = MultiPathManager.getInstance(context)
        val channel = kotlinx.coroutines.channels.Channel<ByteArray>(1)

        val jobs = targetIps.mapIndexed { index, ip ->
            launch(Dispatchers.IO) {
                try {
                    // Dual-Path Racing: alternate queries over Wi-Fi and Cellular physical chips
                    val targetNetwork = if (multiPath.isMultiPathAvailable()) {
                        if (index % 2 == 0) multiPath.getWifiNetwork() else multiPath.getCellularNetwork()
                    } else null
                    
                    val res = resolveViaUdp(dnsQuery, ip, targetNetwork)
                    if (res != null) {
                        channel.trySend(res)
                    }
                } catch (e: Exception) {
                    // Ignore failure to allow other concurrent racers to finish successfully
                }
            }
        }

        val result = withTimeoutOrNull(1000L) {
            channel.receive()
        }
        jobs.forEach { it.cancel() }
        result
    }

    /**
     * Executes a single UDP DNS packet request and awaits response with a tight timeout constraint.
     */
    private suspend fun resolveViaUdp(
        dnsQuery: ByteArray, 
        dnsIp: String,
        targetNetwork: android.net.Network? = null
    ): ByteArray? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            
            if (targetNetwork != null) {
                try {
                    targetNetwork.bindSocket(socket)
                } catch (e: Exception) {
                    protectSocket(socket)
                }
            } else {
                protectSocket(socket) // Ensure socket bypasses the Android VPN interface to prevent circular loops
            }

            socket.soTimeout = 800 // Strict 800ms socket timeout for ultra-fast racing
            socket.sendBufferSize = 131072 // 128KB buffer expansion for burst handling
            socket.receiveBufferSize = 131072

            try {
                // DSCP Expedited Forwarding (0x28) for high priority gaming traffic
                socket.trafficClass = 0x28
            } catch (e: Exception) {
                try { socket.trafficClass = 0x10 } catch (_: Exception) {}
            }

            val address = InetAddress.getByName(dnsIp)
            val forwardPacket = DatagramPacket(dnsQuery, dnsQuery.size, address, 53)
            socket.send(forwardPacket)

            val responseBuffer = ByteArray(4096)
            val receivePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(receivePacket)

            val responseLength = receivePacket.length
            val responseBytes = ByteArray(responseLength)
            System.arraycopy(responseBuffer, 0, responseBytes, 0, responseLength)
            responseBytes
        } catch (e: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // Ignore closing exceptions
            }
        }
    }
}
