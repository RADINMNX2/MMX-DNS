package com.example.service

import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Thread-safe JNI Memory Bridge and Service Controller for the FluxDNS Native Routing Engine.
 */
object FluxDnsEngine {
    private const val TAG = "FluxDnsEngine"

    @Volatile
    var isNativeAvailable: Boolean = false
        private set

    init {
        // Accessing NativeEngine triggers library loading with complete diagnostics
        isNativeAvailable = NativeEngine.isReady()
        if (isNativeAvailable) {
            Log.i(TAG, "FluxDNS Native Engine initialized successfully via NativeEngine loader bridge.")
        } else {
            Log.e(TAG, "FluxDNS Native Engine failed to load native libraries. Dynamic fallback to Kotlin is active.")
        }
    }

    /**
     * Start the native VPN TUN reading loop.
     * @param tunFd The file descriptor of the Android TUN interface.
     * @param primaryDns The primary DNS server IP to forward queries to.
     * @param secondaryDns The secondary DNS server IP to forward queries to.
     * @param protocol The selected protocol ("UDP", "DoH", "DoT").
     * @return true if successfully started, false otherwise.
     */
    fun start(
        tunFd: ParcelFileDescriptor,
        primaryDns: String,
        secondaryDns: String,
        protocol: String
    ): Boolean {
        if (!isNativeAvailable) {
            Log.w(TAG, "Native library not loaded. start() call ignored.")
            return false
        }
        return try {
            // Detach fd so native owns its lifecycle and safely processes async read/write
            startEngine(tunFd.detachFd(), primaryDns, secondaryDns, protocol)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting native engine: ${e.message}", e)
            false
        }
    }

    /**
     * Stop the native VPN TUN loop.
     */
    fun stop() {
        if (isNativeAvailable) {
            try {
                stopEngine()
            } catch (e: Exception) {
                Log.e(TAG, "Exception stopping native engine: ${e.message}", e)
            }
        }
    }

    /**
     * Set/update engine configurations dynamically.
     */
    fun configure(
        primaryDns: String,
        secondaryDns: String,
        protocol: String
    ): Boolean {
        if (!isNativeAvailable) return false
        return try {
            configureEngine(primaryDns, secondaryDns, protocol)
        } catch (e: Exception) {
            Log.e(TAG, "Exception configuring native engine: ${e.message}", e)
            false
        }
    }

    /**
     * Query the engine for basic stats (e.g., number of queries resolved natively).
     */
    fun getResolvedCount(): Int {
        if (!isNativeAvailable) return 0
        return try {
            getQueriesResolved()
        } catch (e: Exception) {
            0
        }
    }

    // JNI native method declarations
    private external fun startEngine(
        tunFd: Int,
        primaryDns: String,
        secondaryDns: String,
        protocol: String
    ): Boolean

    private external fun stopEngine()

    private external fun configureEngine(
        primaryDns: String,
        secondaryDns: String,
        protocol: String
    ): Boolean

    private external fun getQueriesResolved(): Int

    fun resolveQuery(query: ByteArray, primaryDns: String, secondaryDns: String, protocol: String): ByteArray? {
        if (!isNativeAvailable) return null
        return try {
            resolveQueryNative(query, primaryDns, secondaryDns, protocol)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in native resolveQuery: ${e.message}", e)
            null
        }
    }

    private external fun resolveQueryNative(
        query: ByteArray,
        primaryDns: String,
        secondaryDns: String,
        protocol: String
    ): ByteArray?
}
