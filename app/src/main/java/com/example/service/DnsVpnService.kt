package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.MainActivity
import com.example.util.DnsCacheFlusher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

enum class LogType {
    INFO, SUCCESS, WARNING, ERROR
}

data class DnsLogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: LogType,
    val tag: String,
    val message: String
)

class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "DnsVpnService"
        private const val NOTIFICATION_ID = 4004
        private const val CHANNEL_ID = "dns_vpn_channel"

        const val ACTION_START = "com.example.service.START"
        const val ACTION_STOP = "com.example.service.STOP"
        const val ACTION_NEXT_PROFILE = "com.example.service.NEXT_PROFILE"

        const val EXTRA_PRIMARY_DNS = "primary_dns"
        const val EXTRA_SECONDARY_DNS = "secondary_dns"
        const val EXTRA_ENABLE_IPV6 = "enable_ipv6"
        const val EXTRA_PRIMARY_IPV6 = "primary_ipv6"
        const val EXTRA_SECONDARY_IPV6 = "secondary_ipv6"
        const val EXTRA_PROFILE_NAME = "profile_name"
        const val EXTRA_PROTOCOL = "protocol" // Values: "DoH", "DoT", "UDP"

        private val _state = MutableStateFlow(VpnState.DISCONNECTED)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        private val _activeProfileName = MutableStateFlow("None")
        val activeProfileName: StateFlow<String> = _activeProfileName.asStateFlow()

        private val _activePrimaryDns = MutableStateFlow("")
        val activePrimaryDns: StateFlow<String> = _activePrimaryDns.asStateFlow()

        private val _activeSecondaryDns = MutableStateFlow("")
        val activeSecondaryDns: StateFlow<String> = _activeSecondaryDns.asStateFlow()

        private val _activeEnableIpv6 = MutableStateFlow(false)
        val activeEnableIpv6: StateFlow<Boolean> = _activeEnableIpv6.asStateFlow()

        private val _activePrimaryIpv6 = MutableStateFlow("")
        val activePrimaryIpv6: StateFlow<String> = _activePrimaryIpv6.asStateFlow()

        private val _activeSecondaryIpv6 = MutableStateFlow("")
        val activeSecondaryIpv6: StateFlow<String> = _activeSecondaryIpv6.asStateFlow()

        private val _totalQueriesResolved = MutableStateFlow(0)
        val totalQueriesResolved: StateFlow<Int> = _totalQueriesResolved.asStateFlow()

        private val logBuffer = java.util.ArrayDeque<DnsLogEntry>(300)
        private val _logs = MutableStateFlow<List<DnsLogEntry>>(emptyList())
        val logs: StateFlow<List<DnsLogEntry>> = _logs.asStateFlow()

        @Synchronized
        fun log(type: LogType, tag: String, message: String) {
            val entry = DnsLogEntry(type = type, tag = tag, message = message)
            logBuffer.addFirst(entry)
            if (logBuffer.size > 250) {
                logBuffer.removeLast()
            }
            _logs.value = logBuffer.toList()
            Log.d(TAG, "[$type] $tag: $message")
        }

        @Synchronized
        fun clearLogs() {
            logBuffer.clear()
            _logs.value = emptyList()
        }

        @Volatile
        var instance: DnsVpnService? = null
            private set

        @Volatile
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onRevoke() {
        Log.i(TAG, "onRevoke triggered by Android system VPN manager")
        stopVpn()
        super.onRevoke()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        } else if (action == ACTION_START) {
            val primary = intent.getStringExtra(EXTRA_PRIMARY_DNS) ?: "8.8.8.8"
            val secondary = intent.getStringExtra(EXTRA_SECONDARY_DNS) ?: "8.8.4.4"
            val enableIpv6 = intent.getBooleanExtra(EXTRA_ENABLE_IPV6, false)
            val primaryIpv6 = intent.getStringExtra(EXTRA_PRIMARY_IPV6) ?: ""
            val secondaryIpv6 = intent.getStringExtra(EXTRA_SECONDARY_IPV6) ?: ""
            val name = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "Custom"
            val protocol = intent.getStringExtra(EXTRA_PROTOCOL) ?: "UDP"
            startVpn(primary, secondary, enableIpv6, primaryIpv6, secondaryIpv6, name, protocol)
            return START_STICKY
        }
        if (!isRunning) {
            stopVpn()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun startVpn(
        primaryDns: String,
        secondaryDns: String,
        enableIpv6: Boolean = false,
        primaryIpv6: String = "",
        secondaryIpv6: String = "",
        profileName: String = "Custom",
        protocol: String = "UDP"
    ) {
        if (isRunning) stopVpn()

        _state.value = VpnState.CONNECTING
        _activeProfileName.value = profileName
        _activePrimaryDns.value = primaryDns
        _activeSecondaryDns.value = secondaryDns
        _activeEnableIpv6.value = enableIpv6
        _activePrimaryIpv6.value = primaryIpv6
        _activeSecondaryIpv6.value = secondaryIpv6
        _totalQueriesResolved.value = 0

        log(LogType.INFO, "ENGINE", "Initializing NEON DNS Engine...")
        log(LogType.INFO, "PROFILE", "Active Profile: $profileName (Primary: $primaryDns, Secondary: $secondaryDns)")

        val notification = createNotification(profileName, "$primaryDns | $secondaryDns [$protocol]")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground", e)
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        serviceScope.launch {
            try {
                val builder = Builder()
                builder.setSession("NEON DNS")
                builder.setMtu(1360) // Gaming MTU
                builder.addAddress("10.0.0.2", 32)
                
                builder.addDnsServer(primaryDns)
                if (secondaryDns.isNotEmpty() && secondaryDns != primaryDns) {
                    builder.addDnsServer(secondaryDns)
                }

                if (enableIpv6) {
                    try {
                        builder.addAddress("fd00:1::2", 128)
                        if (primaryIpv6.isNotEmpty()) builder.addDnsServer(primaryIpv6)
                        if (secondaryIpv6.isNotEmpty()) builder.addDnsServer(secondaryIpv6)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed IPv6 interface", e)
                    }
                }

                try {
                    builder.addRoute(primaryDns, 32)
                    if (secondaryDns.isNotEmpty() && secondaryDns != primaryDns) {
                        builder.addRoute(secondaryDns, 32)
                    }
                    if (enableIpv6 && primaryIpv6.isNotEmpty()) {
                        builder.addRoute(primaryIpv6, 128)
                        if (secondaryIpv6.isNotEmpty()) builder.addRoute(secondaryIpv6, 128)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add specific DNS routes", e)
                }

                // Gaming Mode App filtering logic (omitted for brevity, assume full VPN if no apps)
                // Just routing all traffic for DNS for now

                val vpnInterfaceLocal = builder.establish()
                if (vpnInterfaceLocal == null) {
                    Log.e(TAG, "Failed to establish VPN interface (null)")
                    _state.value = VpnState.DISCONNECTED
                    return@launch
                }
                vpnInterface = vpnInterfaceLocal

                _state.value = VpnState.CONNECTED
                Log.i(TAG, "VPN tunnel established successfully. Starting Rust Engine.")
                
                DnsCacheFlusher.flushAll(applicationContext)

                // Pass FD to Rust Core
                val fd = vpnInterfaceLocal.fd
                NeonDnsNative.startEngine(EngineConfig(tunFd = fd))
                log(LogType.SUCCESS, "ENGINE", "Rust packet processing engine started successfully.")

                // Keep alive & poll stats
                while (isRunning) {
                    delay(2000)
                    val stats = NeonDnsNative.getStatistics()
                    _totalQueriesResolved.value = stats.totalQueries.toInt()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in VPN tunnel thread", e)
                log(LogType.ERROR, "ENGINE", "Critical error in VPN thread: ${e.message}")
                _state.value = VpnState.DISCONNECTED
            } finally {
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        _state.value = VpnState.DISCONNECTED
        _activeProfileName.value = "None"
        _activePrimaryDns.value = ""
        _activeSecondaryDns.value = ""
        _totalQueriesResolved.value = 0

        if (instance === this) instance = null

        log(LogType.WARNING, "ENGINE", "Stopping NEON DNS Engine...")

        try {
            NeonDnsNative.stopEngine()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop native engine", e)
        }

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close VPN interface", e)
        }
        vpnInterface = null

        try {
            serviceScope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel scope", e)
        }

        try {
            DnsCacheFlusher.flushAll(applicationContext)
        } catch (e: Exception) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (e: Exception) {}

        log(LogType.SUCCESS, "ENGINE", "Engine stopped successfully.")
        stopSelf()
    }

    fun stopVpnDirectly() {
        stopVpn()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "NEON DNS Service"
            val descriptionText = "Keeps the DNS optimization running"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(profile: String, details: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("NEON DNS is active")
            .setContentText("Profile: $profile ($details)")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentIntent(pendingIntent)
            .build()
    }
}