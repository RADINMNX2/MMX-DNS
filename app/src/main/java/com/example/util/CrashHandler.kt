package com.example.util

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * High-fidelity Global Uncaught Exception Handler and Process Recovery System.
 * Prevents silent crashes, records diagnostics to local memory, and forces graceful recovery.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    companion object {
        private const val PREFS_NAME = "dns_crash_prefs"
        private const val KEY_CRASH_LOG = "last_crash_log"

        /**
         * Checks SharedPreferences for any saved crash logs from a previous session.
         */
        fun getSavedCrashLog(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_CRASH_LOG, null)
        }

        /**
         * Clears any saved crash logs from memory.
         */
        fun clearCrashLog(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_CRASH_LOG).apply()
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // Convert stack trace to a readable, formatted string
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stackTraceString = sw.toString()

            Log.e("CrashHandler", "CRITICAL: Uncaught exception intercepted on thread '${thread.name}':\n$stackTraceString")

            // Synchronously write the crash details to storage to prevent process loss on immediate termination
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_CRASH_LOG, stackTraceString).commit()

            // Construct intent to relaunch MainActivity cleanly with the recover dialog flag
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("SHOW_CRASH_DIALOG", true)
            }
            if (intent != null) {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("CrashHandler", "Error during crash recovery loop: ${e.message}", e)
        } finally {
            // Cleanly kill the current process to prevent the OS from displaying a generic system "App Has Stopped" modal
            exitProcess(2)
        }
    }
}
