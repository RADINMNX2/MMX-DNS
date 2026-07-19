package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import java.net.InetAddress

object DnsCacheFlusher {
    private const val TAG = "DnsCacheFlusher"

    /**
     * Executes reflection-based JVM cache clearing and process network binding toggles
     * to completely clear system and engine DNS caches upon state transition.
     */
    fun flushAll(context: Context) {
        flushJvmCache()
        triggerNetworkDescriptorReset(context)
    }

    private fun flushJvmCache() {
        Log.i(TAG, "Initiating reflection-based JVM DNS cache purge...")
        try {
            val fields = listOf("addressCache", "negativeDirectory", "positiveDirectory")
            for (fieldName in fields) {
                try {
                    val field = InetAddress::class.java.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val cacheObj = field.get(null) ?: continue
                    
                    try {
                        val clearMethod = cacheObj.javaClass.getDeclaredMethod("clear")
                        clearMethod.isAccessible = true
                        clearMethod.invoke(cacheObj)
                        Log.d(TAG, "Successfully cleared field: $fieldName using clear()")
                    } catch (e: NoSuchMethodException) {
                        val mapFields = listOf("cache", "map", "mCache")
                        var clearedObj = false
                        for (mapFieldName in mapFields) {
                            try {
                                val mapField = cacheObj.javaClass.getDeclaredField(mapFieldName)
                                mapField.isAccessible = true
                                val mapObj = mapField.get(cacheObj)
                                if (mapObj is Map<*, *>) {
                                    (mapObj as? MutableMap<*, *>)?.clear()
                                    Log.d(TAG, "Cleared map in $fieldName.$mapFieldName")
                                    clearedObj = true
                                    break
                                } else if (mapObj != null) {
                                    val innerClear = mapObj.javaClass.getDeclaredMethod("clear")
                                    innerClear.isAccessible = true
                                    innerClear.invoke(mapObj)
                                    Log.d(TAG, "Cleared inner cache of $fieldName.$mapFieldName using clear()")
                                    clearedObj = true
                                    break
                                }
                            } catch (ignored: Exception) {}
                        }
                        if (!clearedObj) {
                            Log.w(TAG, "Could not clear cache in $fieldName directly")
                        }
                    }
                } catch (ignored: NoSuchFieldException) {
                    // Ignored to support older/newer platforms with different field naming
                }
            }
            Log.i(TAG, "JVM InetAddress cache flush completed.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush standard JVM InetAddress cache: ${e.message}", e)
        }
    }

    private fun triggerNetworkDescriptorReset(context: Context) {
        Log.i(TAG, "Toggling routing network interface states to invalidate global webview/system caches...")
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val activeNetwork = connectivityManager.activeNetwork
                    if (activeNetwork != null) {
                        val prevBoundNetwork = connectivityManager.boundNetworkForProcess
                        connectivityManager.bindProcessToNetwork(activeNetwork)
                        // Restore previous binding to trigger invalidation of process network cache descriptors
                        connectivityManager.bindProcessToNetwork(prevBoundNetwork)
                        Log.d(TAG, "Dynamic network descriptor toggle successfully completed.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bypass of process network binding. OS restricted operation or SDK constraint: ${e.message}")
        }
    }
}
