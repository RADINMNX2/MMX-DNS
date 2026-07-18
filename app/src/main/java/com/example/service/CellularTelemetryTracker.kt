package com.example.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AetherCell Hardware-Telemetry Collector:
 * A non-blocking telemetry service that reads real-time radio metrics and
 * pipes them to the Rust JNI interface.
 */
class CellularTelemetryTracker(private val context: Context) {

    companion object {
        private const val TAG = "CellularTelemetry"
        private const val POLL_INTERVAL_MS = 10000L // 10 seconds polling interval for background safety
        private const val DELTA_RSRP_THRESHOLD = 3   // Dispatch on > 3 dBm change
        private const val DELTA_SINR_THRESHOLD = 2   // Dispatch on > 2 dB change
        private const val UNAVAILABLE = 2147483647   // Standard representation of Integer.MAX_VALUE / CellSignalStrength.UNAVAILABLE
        private const val UNAVAILABLE_LONG = 9223372036854775807L // Standard representation of Long.MAX_VALUE / CellInfo.UNAVAILABLE_LONG
    }

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var trackerScope: CoroutineScope? = null
    private var backgroundExecutor: ExecutorService? = null

    // Thread-safe cached metrics to check for delta changes and prevent JNI overhead
    private val lastRsrp = AtomicInteger(-140)
    private val lastSinr = AtomicInteger(-20)
    private val lastCellId = AtomicLong(-1)

    // Listener and callback references for clean lifecycle unregistration
    private var legacyListener: LegacyPhoneStateListener? = null
    private var telemetryCallback: Any? = null // Type is TelephonyCallback on API 31+

    /**
     * Start monitoring cellular radio metrics and base station information.
     * Operates completely asynchronously to avoid blocking the main UI loop.
     */
    fun start() {
        if (trackerScope != null) {
            Log.w(TAG, "CellularTelemetryTracker is already running.")
            return
        }

        Log.i(TAG, "Starting CellularTelemetryTracker background monitoring...")
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        trackerScope = scope
        
        val executor = Executors.newSingleThreadExecutor()
        backgroundExecutor = executor

        // Register signal strength callbacks
        registerSignalStrengthListener(executor)

        // Launch periodic background polling loop to guarantee Cell ID capturing and delta evaluation
        scope.launch {
            while (isActive) {
                try {
                    pollCellMetrics()
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling cellular metrics in background: ${e.message}", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Terminate telemetry monitoring, unregister listener callbacks, and release resources.
     */
    fun stop() {
        Log.i(TAG, "Stopping CellularTelemetryTracker and cleaning up listeners...")
        
        trackerScope?.cancel()
        trackerScope = null

        // Unregister listeners on background thread to prevent blocking main thread
        backgroundExecutor?.execute {
            try {
                unregisterListeners()
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering telephony listeners: ${e.message}", e)
            }
        }
        backgroundExecutor?.shutdown()
        backgroundExecutor = null
    }

    private fun registerSignalStrengthListener(executor: ExecutorService) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Modern API 31+ TelephonyCallback implementation
                val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener, TelephonyCallback.CellInfoListener {
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        handleSignalStrengthUpdate(signalStrength)
                    }

                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                        handleCellInfoUpdate(cellInfo)
                    }
                }
                telemetryCallback = callback
                telephonyManager.registerTelephonyCallback(executor, callback)
                Log.i(TAG, "Successfully registered modern TelephonyCallback listener.")
            } else {
                // Legacy PhoneStateListener implementation for API < 31
                val listener = LegacyPhoneStateListener()
                legacyListener = listener
                @Suppress("DEPRECATION")
                telephonyManager.listen(
                    listener,
                    PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_CELL_INFO
                )
                Log.i(TAG, "Successfully registered legacy PhoneStateListener.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register telephony signal strength listener: ${e.message}", e)
        }
    }

    private fun unregisterListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = telemetryCallback as? TelephonyCallback
            if (callback != null) {
                telephonyManager.unregisterTelephonyCallback(callback)
                telemetryCallback = null
                Log.i(TAG, "Successfully unregistered modern TelephonyCallback.")
            }
        } else {
            val listener = legacyListener
            if (listener != null) {
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
                legacyListener = null
                Log.i(TAG, "Successfully unregistered legacy PhoneStateListener.")
            }
        }
    }

    /**
     * Poll cellular tower information and combine with latest signal measurements.
     */
    private fun pollCellMetrics() {
        // Query permissions
        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocation) {
            Log.w(TAG, "Cannot poll Cell ID: ACCESS_FINE_LOCATION permission not granted.")
            return
        }

        try {
            val cellInfoList = telephonyManager.allCellInfo
            handleCellInfoUpdate(cellInfoList)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException while polling cell info: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error while polling cell info: ${e.message}", e)
        }
    }

    private fun handleSignalStrengthUpdate(signalStrength: SignalStrength) {
        var rsrp = -140
        var sinr = -20

        val cellSignalStrengths = signalStrength.cellSignalStrengths
        for (css in cellSignalStrengths) {
            when (css) {
                is CellSignalStrengthLte -> {
                    val lteRsrp = css.rsrp
                    val lteSinr = css.rssnr
                    
                    if (lteRsrp != UNAVAILABLE) {
                        rsrp = lteRsrp
                    }
                    if (lteSinr != UNAVAILABLE) {
                        sinr = lteSinr
                    }
                }
                is CellSignalStrengthNr -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val nrRsrp = css.ssRsrp
                        val nrSinr = css.ssSinr
                        
                        if (nrRsrp != UNAVAILABLE) {
                            rsrp = nrRsrp
                        }
                        if (nrSinr != UNAVAILABLE) {
                            sinr = nrSinr
                        }
                    }
                }
                is CellSignalStrengthWcdma -> {
                    val wcdmaDbm = css.dbm
                    if (wcdmaDbm != UNAVAILABLE) {
                        rsrp = wcdmaDbm
                    }
                }
                is CellSignalStrengthGsm -> {
                    val gsmDbm = css.dbm
                    if (gsmDbm != UNAVAILABLE) {
                        rsrp = gsmDbm
                    }
                }
            }
        }

        evaluateAndDispatch(rsrp, sinr, lastCellId.get())
    }

    private fun handleCellInfoUpdate(cellInfoList: List<CellInfo>?) {
        if (cellInfoList.isNullOrEmpty()) return

        var cellId = -1L

        for (info in cellInfoList) {
            if (info.isRegistered) {
                when (info) {
                    is CellInfoLte -> {
                        val identity = info.cellIdentity as CellIdentityLte
                        val ci = identity.ci
                        if (ci != UNAVAILABLE && ci != -1) {
                            cellId = ci.toLong()
                            break
                        }
                    }
                    is CellInfoNr -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val identity = info.cellIdentity as CellIdentityNr
                            val nci = identity.nci
                            if (nci != UNAVAILABLE_LONG && nci != -1L) {
                                cellId = nci
                                break
                            }
                        }
                    }
                    is CellInfoWcdma -> {
                        val identity = info.cellIdentity as CellIdentityWcdma
                        val cid = identity.cid
                        if (cid != UNAVAILABLE && cid != -1) {
                            cellId = cid.toLong()
                            break
                        }
                    }
                    is CellInfoGsm -> {
                        val identity = info.cellIdentity as CellIdentityGsm
                        val cid = identity.cid
                        if (cid != UNAVAILABLE && cid != -1) {
                            cellId = cid.toLong()
                            break
                        }
                    }
                }
            }
        }

        if (cellId != -1L) {
            evaluateAndDispatch(lastRsrp.get(), lastSinr.get(), cellId)
        }
    }

    /**
     * Atomically compares updated measurements against cache.
     * Triggers JNI dispatcher if a meaningful change threshold is crossed.
     */
    private fun evaluateAndDispatch(rsrp: Int, sinr: Int, cellId: Long) {
        val currentRsrp = lastRsrp.get()
        val currentSinr = lastSinr.get()
        val currentCellId = lastCellId.get()

        val rsrpDelta = Math.abs(rsrp - currentRsrp)
        val sinrDelta = Math.abs(sinr - currentSinr)
        val isCellIdChanged = cellId != currentCellId

        // If delta exceeds threshold OR cell tower handoff is detected OR initial values were not set
        if (rsrpDelta >= DELTA_RSRP_THRESHOLD || 
            sinrDelta >= DELTA_SINR_THRESHOLD || 
            isCellIdChanged || 
            currentRsrp == -140) {
            
            lastRsrp.set(rsrp)
            lastSinr.set(sinr)
            lastCellId.set(cellId)

            // Dispatch to JNI shared library of the Rust core engine via FluxDnsEngine controller
            FluxDnsEngine.updateCellularMetrics(rsrp, sinr, cellId)
        }
    }

    /**
     * Fallback PhoneStateListener for older Android platforms.
     */
    @Suppress("DEPRECATION")
    private inner class LegacyPhoneStateListener : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            handleSignalStrengthUpdate(signalStrength)
        }

        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
            handleCellInfoUpdate(cellInfo)
        }
    }
}
