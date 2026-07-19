package com.example.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.io.FileDescriptor

/**
 * MultiPathManager handles active Wi-Fi and Cellular network discovery and binds raw 
 * socket file descriptors to these paths to enable high-fidelity parallel path UDP racing.
 */
class MultiPathManager(private val context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var wifiNetwork: Network? = null
    @Volatile
    private var cellularNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                wifiNetwork = network
                Log.i(TAG, "MultiPath: Wi-Fi network detected and registered successfully.")
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                cellularNetwork = network
                Log.i(TAG, "MultiPath: Cellular network detected and registered successfully.")
            }
        }

        override fun onLost(network: Network) {
            if (network == wifiNetwork) {
                wifiNetwork = null
                Log.i(TAG, "MultiPath: Wi-Fi network interface lost.")
            } else if (network == cellularNetwork) {
                cellularNetwork = null
                Log.i(TAG, "MultiPath: Cellular network interface lost.")
            }
        }
    }

    companion object {
        private const val TAG = "MultiPathManager"
        
        @Volatile
        private var instance: MultiPathManager? = null

        fun getInstance(context: Context): MultiPathManager {
            return instance ?: synchronized(this) {
                instance ?: MultiPathManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Registers network callbacks to monitor both Wi-Fi and Cellular connectivity states.
     */
    fun startMonitoring() {
        try {
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            val cellularRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()

            connectivityManager.registerNetworkCallback(wifiRequest, networkCallback)
            connectivityManager.registerNetworkCallback(cellularRequest, networkCallback)

            // Scan initially active networks
            val networks = connectivityManager.allNetworks
            for (network in networks) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiNetwork = network
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    cellularNetwork = network
                }
            }

            Log.i(TAG, "MultiPathManager active. Current state -> Wi-Fi available: ${wifiNetwork != null}, Cellular available: ${cellularNetwork != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring active networks: ${e.message}", e)
        }
    }

    /**
     * Unregisters the connectivity callbacks.
     */
    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            wifiNetwork = null
            cellularNetwork = null
            Log.i(TAG, "MultiPathManager stopped. Interface monitors released.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop monitoring networks: ${e.message}", e)
        }
    }

    /**
     * Checks if both Wi-Fi and Cellular interfaces are concurrently available.
     */
    fun isMultiPathAvailable(): Boolean {
        return wifiNetwork != null && cellularNetwork != null
    }

    /**
     * Binds a native socket file descriptor to the Wi-Fi network routing path.
     */
    fun bindSocketToWifi(fd: Int): Boolean {
        val net = wifiNetwork
        if (net == null) {
            Log.w(TAG, "Cannot bind socket $fd: Wi-Fi network interface is currently offline.")
            return false
        }
        return try {
            val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
            val fileDesc = pfd.fileDescriptor
            net.bindSocket(fileDesc)
            pfd.close() // Close the dup file descriptor to prevent resource leaks
            Log.i(TAG, "Successfully bound socket FD $fd directly to Wi-Fi interface.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind socket FD $fd to Wi-Fi interface: ${e.message}", e)
            false
        }
    }

    /**
     * Binds a native socket file descriptor to the Cellular network routing path.
     */
    fun bindSocketToCellular(fd: Int): Boolean {
        val net = cellularNetwork
        if (net == null) {
            Log.w(TAG, "Cannot bind socket $fd: Cellular network interface is currently offline.")
            return false
        }
        return try {
            val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
            val fileDesc = pfd.fileDescriptor
            net.bindSocket(fileDesc)
            pfd.close() // Close the dup file descriptor to prevent resource leaks
            Log.i(TAG, "Successfully bound socket FD $fd directly to Cellular interface.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind socket FD $fd to Cellular interface: ${e.message}", e)
            false
        }
    }
}
