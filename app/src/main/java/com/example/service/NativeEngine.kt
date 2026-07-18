package com.example.service

import android.os.Build
import android.util.Log

/**
 * Diagnostic Native Library Loader for the FluxDNS Engine.
 * Provides detailed, architecture-specific link debugging to prevent silent JVM fallbacks.
 */
class NativeEngine {

    companion object {
        private const val TAG = "NativeEngine"
        private const val LIBRARY_NAME = "flux_dns_engine"
        private const val FALLBACK_LIBRARY_NAME = "fluxdns" // Maintain compatibility with template naming

        @Volatile
        private var isLoaded = false

        @Volatile
        private var loadError: Throwable? = null

        init {
            loadNativeLibrary()
        }

        /**
         * Checks if the native library is loaded and ready for use.
         */
        fun isReady(): Boolean {
            return isLoaded
        }

        /**
         * Gets the specific loading error if one occurred.
         */
        fun getLoadError(): Throwable? {
            return loadError
        }

        /**
         * Attempts to load the native library with extensive diagnostic capturing.
         */
        @Synchronized
        fun loadNativeLibrary(): Boolean {
            if (isLoaded) return true

            Log.i(TAG, "Initiating secure native library load sequence for: '$LIBRARY_NAME'")
            
            val abis = Build.SUPPORTED_ABIS.joinToString(", ")
            Log.i(TAG, "Device configuration diagnostics - Supported ABIs: [$abis], OS Arch: ${System.getProperty("os.arch")}")

            try {
                // Primary load attempt using the specified library name
                System.loadLibrary(LIBRARY_NAME)
                isLoaded = true
                loadError = null
                Log.i(TAG, "Native library '$LIBRARY_NAME' successfully loaded into the JVM.")
                return true
            } catch (primaryError: UnsatisfiedLinkError) {
                Log.w(TAG, "Primary load failed for '$LIBRARY_NAME'. Attempting legacy fallback to '$FALLBACK_LIBRARY_NAME'...")
                
                try {
                    // Fallback load attempt for older installations/build configs
                    System.loadLibrary(FALLBACK_LIBRARY_NAME)
                    isLoaded = true
                    loadError = null
                    Log.i(TAG, "Native library '$FALLBACK_LIBRARY_NAME' (legacy fallback) successfully loaded into the JVM.")
                    return true
                } catch (fallbackError: UnsatisfiedLinkError) {
                    isLoaded = false
                    loadError = fallbackError
                    
                    // Comprehensive system-level diagnostics and linkage troubleshooting
                    diagnoseLinkError(primaryError, fallbackError)
                }
            } catch (e: SecurityException) {
                isLoaded = false
                loadError = e
                Log.e(TAG, "SecurityException: Loader lacked permissions to link native libraries: ${e.message}", e)
            } catch (e: Exception) {
                isLoaded = false
                loadError = e
                Log.e(TAG, "Unexpected error encountered during JNI linkage sequence: ${e.message}", e)
            }

            return false
        }

        /**
         * Dissects JNI linkage errors, analyzing JVM properties, system architecture,
         * and linker outputs to log extremely clear, actionable troubleshooting insights.
         */
        private fun diagnoseLinkError(primaryErr: UnsatisfiedLinkError, fallbackErr: UnsatisfiedLinkError) {
            val osArch = System.getProperty("os.arch") ?: "Unknown"
            val javaLibPath = System.getProperty("java.library.path") ?: "Unknown"
            val supportedAbis = Build.SUPPORTED_ABIS.joinToString(", ")
            
            val diagnosticLog = StringBuilder()
            diagnosticLog.append("\n================= FLUXDNS JNI LINK FAILURE DIAGNOSTICS =================\n")
            diagnosticLog.append("CRITICAL: Failed to load native library '$LIBRARY_NAME' and fallback '$FALLBACK_LIBRARY_NAME'.\n")
            diagnosticLog.append("System Information:\n")
            diagnosticLog.append("  - OS Architecture (os.arch): $osArch\n")
            diagnosticLog.append("  - Supported Hardware ABIs:  [$supportedAbis]\n")
            diagnosticLog.append("  - Java Library Path:         $javaLibPath\n\n")
            
            diagnosticLog.append("Primary Error Details:\n")
            diagnosticLog.append("  - Message: ${primaryErr.message}\n")
            diagnosticLog.append("  - StackTrace: ${Log.getStackTraceString(primaryErr)}\n\n")

            diagnosticLog.append("Fallback Error Details:\n")
            diagnosticLog.append("  - Message: ${fallbackErr.message}\n")
            
            // Perform precise root-cause heuristic analysis
            val errMsg = primaryErr.message ?: ""
            diagnosticLog.append("\nHeuristic Troubleshooting & Root-Cause Analysis:\n")
            when {
                errMsg.contains("not found") || errMsg.contains("couldn't find") -> {
                    diagnosticLog.append("  [DIAGNOSIS]: MISSED PATH OR UNCOMPILED LIBRARY.\n")
                    diagnosticLog.append("  [EXPLANATION]: The Android linker cannot locate lib$LIBRARY_NAME.so in any of the search paths.\n")
                    diagnosticLog.append("  [SOLUTION]: Verify that your rust compiler / cargo ndk build output has copied lib$LIBRARY_NAME.so into the 'src/main/jniLibs/<abi>/' directories of the Android module.\n")
                }
                errMsg.contains("is 32-bit instead of 64-bit") || errMsg.contains("is 64-bit instead of 32-bit") || errMsg.contains("mips") -> {
                    diagnosticLog.append("  [DIAGNOSIS]: ARCHITECTURE ABI MISMATCH.\n")
                    diagnosticLog.append("  [EXPLANATION]: A native library was found, but it was compiled for a different word size or instruction set architecture than the active target process.\n")
                    diagnosticLog.append("  [SOLUTION]: Check your build.gradle.kts 'ndk.abiFilters' configuration. Ensure your JNI builds generate the correct 32-bit or 64-bit targets matching the host CPU.\n")
                }
                errMsg.contains("already loaded in another classloader") -> {
                    diagnosticLog.append("  [DIAGNOSIS]: MULTIPLE CLASSLOADER CONFLICT.\n")
                    diagnosticLog.append("  [EXPLANATION]: The library was already loaded elsewhere; JNI prevents loading the same shared library on multiple classloaders.\n")
                }
                errMsg.contains("unresolved symbol") || errMsg.contains("cannot locate symbol") || errMsg.contains("undefined symbol") -> {
                    diagnosticLog.append("  [DIAGNOSIS]: LINKAGE MATCH ERROR (UNRESOLVED SYMBOLS).\n")
                    diagnosticLog.append("  [EXPLANATION]: The lib$LIBRARY_NAME.so loaded, but it has references to external symbols (or libc/NDK APIs) that are unavailable on this Android API Level.\n")
                    diagnosticLog.append("  [SOLUTION]: Ensure your Rust crate uses the correct minimum NDK target API levels and does not link to unsupported dynamic symbols.\n")
                }
                else -> {
                    diagnosticLog.append("  [DIAGNOSIS]: UNCLASSIFIED SYSTEM LINK ERROR.\n")
                    diagnosticLog.append("  [EXPLANATION]: The operating system linker rejected loading the shared library.\n")
                    diagnosticLog.append("  [SOLUTION]: Verify standard dynamic library dependencies using ldd or objdump on the .so file, and inspect NDK compiler logs.\n")
                }
            }
            diagnosticLog.append("\n=========================================================================")
            
            Log.w(TAG, "Native engine not available, using JVM fallback. Run cargo ndk to build the native library.")
        }
    }
}
