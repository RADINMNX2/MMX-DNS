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
 * Tier 2: Google Play / App-Packaged Cronet HTTP/3 (DoH3/HTTP2) with Direct-IP Bootstrap Bypass.
 * Tier 3: Parallel Plain UDP racing across Anycast servers for high-speed fallback.
 */
class FluxResolverController(
    private val context: Context,
    private val cronetDohResolver: CronetDohResolver,
    private val protectSocket: (DatagramSocket) -> Boolean
) {
    companion object {
        private const val TAG = "FluxResolverController"
        private const val TIER1_TIMEOUT_MS = 800L
        private const val TIER2_TIMEOUT_MS = 2500L
    }

    private val isInitializing = AtomicBoolean(false)

    init {
        Log.i(TAG, "FluxResolverController initialized and ready to orchestrate queries.")
    }

    /**
     * Executes the sequential 3-tier cascade resolution pipeline for an incoming DNS query packet.
     * Guaranteed to operate asynchronously on a background dispatcher (Dispatchers.IO) to prevent
     * blocking Android's main loop.
     *
     * @param dnsQuery The raw wire-format DNS query packet.
     * @param primaryDns The configured primary DNS server IP.
     * @param secondaryDns The configured secondary DNS server IP.
     * @param domain The parsed query domain name (used for logging and diagnostics).
     * @return The resolved DNS response packet, or null if all tiers failed.
     */
    suspend fun resolve(
        dnsQuery: ByteArray,
        primaryDns: String,
        secondaryDns: String,
        domain: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        var response: ByteArray? = null

        // =====================================================================
        // TIER 1: Native DNS-over-QUIC (DoQ) JNI Engine
        // =====================================================================
        if (FluxDnsEngine.isNativeAvailable) {
            DnsVpnService.log(LogType.INFO, "RESOLVER", "Tier 1: Querying Native Rust JNI Engine via DoQ for '$domain'...")
            try {
                response = withTimeoutOrNull(TIER1_TIMEOUT_MS) {
                    FluxDnsEngine.resolveQuery(dnsQuery, primaryDns, secondaryDns, "DoQ")
                }
                if (response != null && response.isNotEmpty()) {
                    DnsVpnService.log(LogType.SUCCESS, "RESOLVED", "Tier 1: Resolved '$domain' via Native Rust DoQ.")
                    return@withContext response
                } else {
                    DnsVpnService.log(LogType.WARNING, "RESOLVER", "Tier 1: Native JNI DoQ returned empty response or timed out. Cascading to Tier 2...")
                }
            } catch (e: TimeoutCancellationException) {
                DnsVpnService.log(LogType.WARNING, "RESOLVER", "Tier 1: JNI DoQ timed out after ${TIER1_TIMEOUT_MS}ms. Cascading to Tier 2...")
            } catch (e: Exception) {
                Log.e(TAG, "Tier 1 JNI DoQ failure: ${e.message}", e)
                DnsVpnService.log(LogType.WARNING, "RESOLVER", "Tier 1: JNI DoQ error (${e.message}). Cascading to Tier 2...")
            }
        } else {
            DnsVpnService.log(LogType.WARNING, "RESOLVER", "Tier 1: Native JNI Engine is unavailable. Initiating cascade to Tier 2...")
        }

        // =====================================================================
        // TIER 2: High-Performance Kotlin Cronet HTTP/3 (DoH3/HTTP2) with IP Bypass
        // =====================================================================
        DnsVpnService.log(LogType.INFO, "RESOLVER", "Tier 2: Invoking high-performance Kotlin Cronet DoH3 resolver for '$domain'...")
        try {
            response = withTimeoutOrNull(TIER2_TIMEOUT_MS) {
                resolveViaCronet(dnsQuery, primaryDns)
            }
            if (response == null && secondaryDns.isNotEmpty() && secondaryDns != primaryDns) {
                DnsVpnService.log(LogType.INFO, "RESOLVER", "Tier 2: Primary DNS failed. Querying secondary DNS ($secondaryDns) via Cronet...")
                response = withTimeoutOrNull(TIER2_TIMEOUT_MS) {
                    resolveViaCronet(dnsQuery, secondaryDns)
                }
            }

            if (response != null && response.isNotEmpty()) {
                DnsVpnService.log(LogType.SUCCESS, "RESOLVED", "Tier 2: Resolved '$domain' via Kotlin Cronet HTTP/3 (DoH3) resolver.")
                return@withContext response
            } else {
                DnsVpnService.log(LogType.WARNING, "RESOLVER", "Tier 2: Kotlin Cronet HTTP/3 resolver failed. Cascading to Tier 3...")
            }
        } catch (e: TimeoutCancellationException) {
            DnsVpnService.log(LogType.WARNING, "RESOLVER", "Tier 2: Cronet resolver timed out after ${TIER2_TIMEOUT_MS}ms. Cascading to Tier 3...")
        } catch (e: Exception) {
            Log.e(TAG, "Tier 2 Cronet failure: ${e.message}", e)
            DnsVpnService.log(LogType.WARNING, "RESOLVER", "Tier 2: Cronet error (${e.message}). Cascading to Tier 3...")
        }

        // =====================================================================
        // TIER 3: Parallel Anycast Plain UDP Racing
        // =====================================================================
        DnsVpnService.log(LogType.WARNING, "FALLBACK", "Tier 2 failed. Tier 3: Invoking Parallel Fast-UDP Racing across Anycast IPs...")
        try {
            response = raceUdpQueries(dnsQuery, primaryDns, secondaryDns)
            if (response != null && response.isNotEmpty()) {
                DnsVpnService.log(LogType.SUCCESS, "RESOLVED", "Tier 3: Resolved '$domain' via Parallel Fast-UDP Racing.")
                return@withContext response
            } else {
                DnsVpnService.log(LogType.ERROR, "RESOLVER", "Tier 3: Parallel Fast-UDP Racing failed to resolve query for '$domain'.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tier 3 Parallel Racing failure: ${e.message}", e)
            DnsVpnService.log(LogType.ERROR, "RESOLVER", "Tier 3: Parallel Fast-UDP Racing encountered an error: ${e.message}")
        }

        return@withContext null
    }

    /**
     * Resolves a DNS query using Cronet with Direct-IP mapping.
     */
    private suspend fun resolveViaCronet(dnsQuery: ByteArray, dnsIp: String): ByteArray? {
        return try {
            val config = DnsVpnService.SECURE_DNS_REGISTRY[dnsIp]
            val dohUrl = config?.dohUrl ?: "https://$dnsIp/dns-query"
            cronetDohResolver.resolveQuery(dnsQuery, dohUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Cronet DoH3 query failed on $dnsIp: ${e.message}")
            DnsVpnService.log(LogType.ERROR, "RESOLVER", "Cronet DoH3 query failed on $dnsIp: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Anycast Parallel UDP Racing logic. Resolves the DNS query concurrently on multiple fallback servers
     * to ensure the fastest successful response is accepted first.
     */
    private suspend fun raceUdpQueries(
        dnsQuery: ByteArray,
        primaryDns: String,
        secondaryDns: String
    ): ByteArray? = coroutineScope {
        val ips = mutableSetOf("8.8.8.8", "1.1.1.1", "9.9.9.9")
        if (primaryDns.isNotEmpty()) ips.add(primaryDns)
        if (secondaryDns.isNotEmpty()) ips.add(secondaryDns)

        val targetIps = ips.take(3).toList()
        Log.i(TAG, "Initiating Parallel DNS Racing across Anycast IPs: $targetIps")

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

        val result = withTimeoutOrNull(1500L) {
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
            Log.w(TAG, "UDP resolution query failed on $dnsIp: ${e.message}")
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
