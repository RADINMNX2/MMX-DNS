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

    /**
     * Triggers the ZIBE native CPU affinity pinning and niceness daemon optimizations.
     * Should be called as soon as the VPN handshake is finalized.
     * @return true if successfully applied, false otherwise.
     */
    fun applyZibeOptimization(): Boolean {
        if (!isNativeAvailable) {
            Log.w(TAG, "Native library is unavailable; ignoring ZIBE optimization request.")
            return false
        }
        return try {
            val success = applyZibeOptimizationNative()
            if (success) {
                Log.i(TAG, "ZIBE: Successfully applied native CPU affinity pinning and niceness.")
            } else {
                Log.e(TAG, "ZIBE: Failed to apply native CPU affinity pinning and niceness.")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "ZIBE: Exception while applying ZIBE optimization: ${e.message}", e)
            false
        }
    }

    /**
     * Resets ZIBE CPU affinity and scheduling priority to default.
     * @return true if successfully reset, false otherwise.
     */
    fun resetZibeOptimization(): Boolean {
        if (!isNativeAvailable) return false
        return try {
            val success = resetZibeOptimizationNative()
            if (success) {
                Log.i(TAG, "ZIBE: Successfully reset native CPU affinity and scheduling priority.")
            } else {
                Log.e(TAG, "ZIBE: Failed to reset native CPU affinity and scheduling priority.")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "ZIBE: Exception while resetting ZIBE optimization: ${e.message}", e)
            false
        }
    }

    // JNI native method declarations
    private external fun applyZibeOptimizationNative(): Boolean
    private external fun resetZibeOptimizationNative(): Boolean

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

    /**
     * Set socket-level optimizations (QoS / DSCP Expedited Forwarding, socket buffer expansion)
     * utilizing low-level native OS system calls via the JNI.
     * @param fd The raw file descriptor of the socket.
     * @return An integer mapping to diagnostic/logging states:
     *          0 -> SUCCESS
     *         -1 -> EBADF: Invalid File Descriptor
     *         -2 -> EACCES: Permission Denied
     *         -3 -> ENOPROTOOPT: Protocol/Option not available
     *         -4 -> ENOTSOCK: File descriptor is not a socket
     *         -5 -> EINVAL: Invalid parameter structure
     *        -99 -> UNKNOWN/OTHER SYSTEM ERROR
     */
    fun tuneSocket(fd: Int): Int {
        if (!isNativeAvailable) {
            Log.w(TAG, "Native library is unavailable; ignoring socket tuning for FD $fd.")
            return -99
        }
        return try {
            val result = tuneSocketNative(fd)
            when (result) {
                0 -> Log.i(TAG, "AetherUDP Tuning SUCCESS: Successfully tuned socket FD $fd (IP_TOS, SO_RCVBUF, SO_SNDBUF configured).")
                -1 -> Log.e(TAG, "AetherUDP Tuning FAILURE on FD $fd: EBADF (Bad file descriptor).")
                -2 -> Log.e(TAG, "AetherUDP Tuning FAILURE on FD $fd: EACCES (Permission denied).")
                -3 -> Log.e(TAG, "AetherUDP Tuning FAILURE on FD $fd: ENOPROTOOPT (Option not supported on this protocol).")
                -4 -> Log.e(TAG, "AetherUDP Tuning FAILURE on FD $fd: ENOTSOCK (File descriptor is not a socket).")
                -5 -> Log.e(TAG, "AetherUDP Tuning FAILURE on FD $fd: EINVAL (Invalid parameter/state).")
                else -> Log.e(TAG, "AetherUDP Tuning FAILURE on FD $fd: Unknown system error (code $result).")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "AetherUDP JNI Exception while tuning socket FD $fd: ${e.message}", e)
            -99
        }
    }

    private external fun tuneSocketNative(fd: Int): Int

    /**
     * Enforce the Don't Fragment (DF) flag on a raw socket file descriptor.
     * Prevents intermediate routing fragmentation and enables high-fidelity Path MTU Discovery.
     */
    fun enforceDf(fd: Int): Int {
        if (!isNativeAvailable) {
            Log.w(TAG, "Native library is unavailable; ignoring DF flag enforcement.")
            return -99
        }
        return try {
            val result = enforceDfNative(fd)
            when (result) {
                0 -> Log.i(TAG, "AetherUDP DF Enforce SUCCESS: Enforced DF flag on socket FD $fd.")
                -1 -> Log.e(TAG, "AetherUDP DF Enforce FAILURE on FD $fd: EBADF (Bad file descriptor).")
                -2 -> Log.e(TAG, "AetherUDP DF Enforce FAILURE on FD $fd: EACCES (Permission denied).")
                -3 -> Log.e(TAG, "AetherUDP DF Enforce FAILURE on FD $fd: ENOPROTOOPT (Option not supported).")
                -4 -> Log.e(TAG, "AetherUDP DF Enforce FAILURE on FD $fd: ENOTSOCK (Not a socket).")
                -5 -> Log.e(TAG, "AetherUDP DF Enforce FAILURE on FD $fd: EINVAL (Invalid parameter/state).")
                else -> Log.e(TAG, "AetherUDP DF Enforce FAILURE on FD $fd: Unknown error $result.")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "AetherUDP JNI Exception while enforcing DF on FD $fd: ${e.message}", e)
            -99
        }
    }

    private external fun enforceDfNative(fd: Int): Int

    /**
     * Retrieves the globally tracked and clamped Path MTU size from the native engine.
     */
    fun getTrackedMtu(): Int {
        if (!isNativeAvailable) return 1500
        return try {
            getTrackedMtuNative()
        } catch (e: Exception) {
            Log.e(TAG, "JNI Exception while getting tracked MTU: ${e.message}")
            1500
        }
    }

    private external fun getTrackedMtuNative(): Int

    /**
     * Manually updates or overrides the tracked/clamped PMTU limit.
     */
    fun setTrackedMtu(mtu: Int) {
        if (!isNativeAvailable) return
        try {
            setTrackedMtuNative(mtu)
            Log.i(TAG, "Successfully updated JNI PMTU clamp override to $mtu bytes.")
        } catch (e: Exception) {
            Log.e(TAG, "JNI Exception while setting tracked MTU: ${e.message}")
        }
    }

    private external fun setTrackedMtuNative(mtu: Int)

    /**
     * Queries the kernel's routing cache to find the current physical path's MTU size.
     */
    fun queryKernelMtu(fd: Int): Int {
        if (!isNativeAvailable) return -1
        return try {
            queryKernelMtuNative(fd)
        } catch (e: Exception) {
            Log.e(TAG, "JNI Exception while querying kernel MTU: ${e.message}")
            -1
        }
    }

    private external fun queryKernelMtuNative(fd: Int): Int

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

    /**
     * Dispatch cellular metrics to the Rust core engine.
     * @param rsrp Reference Signal Received Power in dBm.
     * @param sinr Signal-to-Interference-plus-Noise Ratio in dB.
     * @param cellId Cell ID of the connected base station.
     */
    fun updateCellularMetrics(rsrp: Int, sinr: Int, cellId: Long) {
        if (!isNativeAvailable) return
        try {
            updateCellularMetricsNative(rsrp, sinr, cellId)
            Log.d(TAG, "Successfully dispatched cellular telemetry to native: rsrp=$rsrp, sinr=$sinr, cellId=$cellId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch cellular metrics via JNI: ${e.message}")
        }
    }

    private external fun updateCellularMetricsNative(rsrp: Int, sinr: Int, cellId: Long)

    @Volatile
    private var multiPathManagerRef: MultiPathManager? = null

    @JvmStatic
    fun setMultiPathManager(manager: MultiPathManager?) {
        multiPathManagerRef = manager
    }

    @JvmStatic
    fun bindSocketToWifi(fd: Int): Boolean {
        Log.i(TAG, "FluxDnsEngine JNI Callback: Request to bind socket FD $fd to Wi-Fi received.")
        return multiPathManagerRef?.bindSocketToWifi(fd) ?: false
    }

    @JvmStatic
    fun bindSocketToCellular(fd: Int): Boolean {
        Log.i(TAG, "FluxDnsEngine JNI Callback: Request to bind socket FD $fd to Cellular received.")
        return multiPathManagerRef?.bindSocketToCellular(fd) ?: false
    }
}
