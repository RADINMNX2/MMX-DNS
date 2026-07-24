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
                    return@withContext response
                } else if (enableIpv6 && activePrimary != primaryDns) {
                    // Fallback Native query on IPv4
                    response = withTimeoutOrNull(TIER1_TIMEOUT_MS) {
                        FluxDnsEngine.resolveQuery(dnsQuery, primaryDns, secondaryDns, protocol)
                    }
                    if (response != null && response.isNotEmpty()) {
                        DnsVpnService.log(LogType.SUCCESS, "RESOLVED", "Tier 1: Resolved '$domain' via Native JNI (IPv4 Fallback).")
                        return@withContext response
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
                if (response != null) return@withContext response

                response = tryResolveDoH(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return@withContext response

                response = tryResolveDoT(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return@withContext response

                response = tryResolveUdpRacing(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return@withContext response
            }
            "DoH" -> {
                response = tryResolveDoH(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return@withContext response

                response = tryResolveDoT(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return@withContext response

                response = tryResolveUdpRacing(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return@withContext response
            }
            "DoT" -> {
                response = tryResolveDoT(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return@withContext response

                response = tryResolveDoH(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return@withContext response

                response = tryResolveUdpRacing(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return@withContext response
            }
            else -> {
                response = tryResolveUdpRacing(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return@withContext response

                response = tryResolveDoH(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain)
                if (response != null) return@withContext response
            }
        }

        return@withContext null
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
     * Anycast Parallel UDP Racing logic across dual-stack (IPv4/IPv6) endpoints.
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
        val channel = kotlinx.coroutines.channels.Channel<ByteArray>(1)
        val jobs = targetIps.map { ip ->
            launch(Dispatchers.IO) {
                try {
                    val res = resolveViaUdp(dnsQuery, ip)
                    if (res != null) {
                        channel.trySend(res)
                    }
                } catch (e: Exception) {
                    // Ignore failure to allow other concurrent racers to finish successfully
                }
            }
        }

        val result = withTimeoutOrNull(1200L) {
            channel.receive()
        }
        jobs.forEach { it.cancel() }
        result
    }

    /**
     * Executes a single UDP DNS packet request and awaits response with a tight timeout constraint.
     */
    private suspend fun resolveViaUdp(dnsQuery: ByteArray, dnsIp: String): ByteArray? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protectSocket(socket) // Ensure socket bypasses the Android VPN interface to prevent circular loops
            socket.soTimeout = 1000 // Strict 1-second socket timeout for quick fallback
            
            // Explicitly tuned buffers to handle heavy loads under congestion
            socket.sendBufferSize = 65536
            socket.receiveBufferSize = 65536

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
