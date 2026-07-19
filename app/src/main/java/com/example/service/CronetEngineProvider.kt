package com.example.service

import android.content.Context
import android.util.Log
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import java.io.File

object CronetEngineProvider {
    private const val TAG = "CronetEngineProvider"

    // Standard Cronet Provider name constants as per org.chromium.net specification
    private const val PROVIDER_NAME_PLAY_SERVICES = "Google Play Services"
    private const val PROVIDER_NAME_APP_PACKAGED = "App-Packaged-Cronet-Provider"

    fun createCronetEngine(context: Context): CronetEngine? {
        Log.i(TAG, "Initializing dynamic dual-provider Cronet Engine loader...")

        try {
            // Install Google Play Services Cronet provider proactively if possible
            try {
                com.google.android.gms.net.CronetProviderInstaller.installProvider(context)
                Log.i(TAG, "Google Play Services Cronet provider installer executed.")
            } catch (e: Exception) {
                Log.w(TAG, "Play Services Cronet installer not available or failed: ${e.message}")
            }

            // Query all available providers
            val providers = CronetProvider.getAllProviders(context)
            Log.i(TAG, "Discovered Cronet providers: ${providers.map { "${it.name} (enabled=${it.isEnabled})" }}")

            // 1. Attempt to load PROVIDER_NAME_PLAY_SERVICES first
            val playServicesProvider = providers.find {
                it.name == PROVIDER_NAME_PLAY_SERVICES && it.isEnabled
            }

            // 2. If Play Services missing/disabled, fallback to PROVIDER_NAME_APP_PACKAGED
            val appPackagedProvider = providers.find {
                it.name == PROVIDER_NAME_APP_PACKAGED && it.isEnabled
            }

            // 3. Last-resort fallback to any enabled provider
            val selectedProvider = playServicesProvider ?: appPackagedProvider ?: providers.find { it.isEnabled }

            if (selectedProvider != null) {
                Log.i(TAG, "Loading selected Cronet provider: ${selectedProvider.name} (Version: ${selectedProvider.version})")
                val builder = selectedProvider.createBuilder()

                // Enforce both HTTP/3 (QUIC) and HTTP/2 (TCP) flags for soft fallback compatibility
                builder.enableHttp2(true)
                builder.enableQuic(true)
                builder.enableBrotli(true)

                val cacheDir = File(context.cacheDir, "cronet_cache")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 10 * 1024 * 1024) // 10MB cache
                builder.setStoragePath(cacheDir.absolutePath)

                // Add QUIC hints to preemptively establish QUIC connections to direct IPs and domains
                val commonEndpoints = listOf(
                    "dns.google", "dns.adguard-dns.com", "cloudflare-dns.com", "dns.quad9.net", "dns.controld.com",
                    "8.8.8.8", "8.8.4.4", "1.1.1.3", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112",
                    "94.140.14.14", "94.140.15.15", "76.76.2.0", "76.76.10.0"
                )
                for (endpoint in commonEndpoints) {
                    builder.addQuicHint(endpoint, 443, 443)
                }

                val engine = builder.build()
                Log.i(TAG, "Successfully constructed CronetEngine using ${selectedProvider.name}.")
                return engine
            } else {
                Log.w(TAG, "No specific enabled Cronet provider was resolved. Executing standard fallback.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dynamic Cronet loader encountered an exception: ${e.message}", e)
        }

        // Standard direct CronetEngine initialization fallback
        return try {
            Log.i(TAG, "Executing fallback initialization with default CronetEngine.Builder...")
            val cacheDir = File(context.cacheDir, "cronet_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val builder = CronetEngine.Builder(context)
                .enableHttp2(true)
                .enableQuic(true)
                .enableBrotli(true)
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 10 * 1024 * 1024)
                .setStoragePath(cacheDir.absolutePath)

            val commonEndpoints = listOf(
                "dns.google", "dns.adguard-dns.com", "cloudflare-dns.com", "dns.quad9.net", "dns.controld.com",
                "8.8.8.8", "8.8.4.4", "1.1.1.3", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112",
                "94.140.14.14", "94.140.15.15", "76.76.2.0", "76.76.10.0"
            )
            for (endpoint in commonEndpoints) {
                builder.addQuicHint(endpoint, 443, 443)
            }

            val engine = builder.build()
            Log.i(TAG, "Successfully initialized fallback default CronetEngine.")
            engine
        } catch (e: Exception) {
            Log.e(TAG, "Fallback default CronetEngine initialization failed: ${e.message}", e)
            null
        }
    }
}
