package com.example.service

import android.util.Log

data class ResolverConfig(
    val name: String,
    val ip: String,
    val transport: String // "UDP", "TCP", "DoH", "DoT"
)

data class BenchmarkResult(
    val minMs: Double,
    val avgMs: Double,
    val medianMs: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val successRate: Double,
    val timeoutRate: Double,
    val stabilityScore: Double
)

data class BenchmarkReport(
    val resolverStats: Map<String, BenchmarkResult>
)

data class NetworkProfile(
    val type: String, // "WIFI", "MOBILE", etc.
    val active: Boolean
)

data class ResolverDecision(
    val primaryResolver: String,
    val secondaryResolver: String,
    val score: Int,
    val confidence: String,
    val reason: String
)

data class DnsQuery(
    val domain: String,
    val recordType: Int
)

data class DnsResponse(
    val ips: List<String>,
    val latencyMs: Long,
    val ttl: Long
)

data class EngineConfig(
    val tunFd: Int
)

data class NetworkStatistics(
    val totalQueries: Long,
    val cacheHitRate: Double,
    val averageLatency: Double
)

data class SmartRoutingStatus(
    val enabled: Boolean,
    val active_edge: String,
    val tunneled_connections: Long,
    val direct_connections: Long,
    val health_status: String
)

object NeonDnsNative {

    init {
        try {
            System.loadLibrary("neon_dns_core")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("NeonDnsNative", "Could not load libneon_dns_core.so", e)
        }
    }

    // --- High-level Kotlin interface mapping to JNI below ---

    fun startEngine(config: EngineConfig) {
        startEngineNative(config.tunFd)
    }

    fun stopEngine() {
        stopEngineNative()
    }
    
    fun setSmartRouting(enable: Boolean, edgeIp: String) {
        setSmartRoutingNative(enable, edgeIp)
    }
    
    fun getSmartRoutingStatus(): SmartRoutingStatus {
        val jsonStr = getSmartRoutingStatusNative()
        return parseSmartRoutingStatus(jsonStr)
    }

    suspend fun benchmarkResolvers(resolvers: List<ResolverConfig>): BenchmarkReport {
        // Implement suspension over async JNI or wrap blocking JNI in withContext(Dispatchers.IO)
        // For simplicity in this structure, we'll do blocking in IO.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val ips = resolvers.map { it.ip }.toTypedArray()
            val transports = resolvers.map { it.transport }.toTypedArray()
            // In a real app we'd get a complex JSON string back or complex objects.
            val jsonResult = benchmarkResolversNative(ips, transports)
            // Parse JSON into BenchmarkReport
            parseBenchmarkReport(jsonResult)
        }
    }

    suspend fun selectBestResolver(candidates: List<ResolverConfig>, networkProfile: NetworkProfile): ResolverDecision {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val jsonResult = selectBestResolverNative(candidates.map { it.ip }.toTypedArray(), networkProfile.type)
            parseResolverDecision(jsonResult)
        }
    }

    fun getStatistics(): NetworkStatistics {
        val statsStr = getStatisticsNative()
        return parseStatistics(statsStr)
    }
    
    // --- JNI Methods ---
    
    @JvmStatic
    private external fun startEngineNative(tunFd: Int)
    
    @JvmStatic
    private external fun stopEngineNative()
    
    @JvmStatic
    private external fun setSmartRoutingNative(enable: Boolean, edgeIp: String)
    
    @JvmStatic
    private external fun getSmartRoutingStatusNative(): String
    
    @JvmStatic
    private external fun benchmarkResolversNative(ips: Array<String>, transports: Array<String>): String
    
    @JvmStatic
    private external fun selectBestResolverNative(candidates: Array<String>, networkType: String): String
    
    @JvmStatic
    private external fun getStatisticsNative(): String
    
    // Simple JSON Parsers for JNI Strings
    private fun parseBenchmarkReport(json: String): BenchmarkReport {
        // Dummy implementation for structure
        val map = mapOf("194.146.68.68" to BenchmarkResult(8.7, 9.1, 8.9, 12.0, 15.0, 100.0, 0.0, 99.8))
        return BenchmarkReport(map)
    }

    private fun parseResolverDecision(json: String): ResolverDecision {
        return ResolverDecision("194.146.68.68", "1.1.1.1", 97, "High", "Low DNS latency")
    }

    private fun parseStatistics(json: String): NetworkStatistics {
        return NetworkStatistics(1024, 72.0, 8.7)
    }
    
    private fun parseSmartRoutingStatus(json: String): SmartRoutingStatus {
        return try {
            val jsonObject = org.json.JSONObject(json)
            SmartRoutingStatus(
                enabled = jsonObject.optBoolean("enabled", false),
                active_edge = jsonObject.optString("active_edge", ""),
                tunneled_connections = jsonObject.optLong("tunneled_connections", 0),
                direct_connections = jsonObject.optLong("direct_connections", 0),
                health_status = jsonObject.optString("health_status", "UNKNOWN")
            )
        } catch (e: Exception) {
            SmartRoutingStatus(false, "", 0, 0, "ERROR")
        }
    }
}
