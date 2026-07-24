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
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

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

data class SecureDnsConfig(
    val ip: String,
    val dotHost: String,
    val dohUrl: String
)

class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var multiPathManager: MultiPathManager? = null
    private var telemetryTracker: CellularTelemetryTracker? = null
    
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

        private val _totalQueriesFiltered = MutableStateFlow(0)
        val totalQueriesFiltered: StateFlow<Int> = _totalQueriesFiltered.asStateFlow()

        private val _logs = MutableStateFlow<List<DnsLogEntry>>(emptyList())
        val logs: StateFlow<List<DnsLogEntry>> = _logs.asStateFlow()

        fun log(type: LogType, tag: String, message: String) {
            val entry = DnsLogEntry(type = type, tag = tag, message = message)
            val current = _logs.value.toMutableList()
            current.add(0, entry)
            if (current.size > 200) {
                current.removeAt(current.lastIndex)
            }
            _logs.value = current
            Log.d(TAG, "[$type] $tag: $message")
        }

        fun clearLogs() {
            _logs.value = emptyList()
        }

        @Volatile
        var instance: DnsVpnService? = null
            private set

        @Volatile
        var isRunning = false

        // Static registry of popular secure DNS servers mapping to hostname/endpoints
        val SECURE_DNS_REGISTRY = mapOf(
            "8.8.8.8" to SecureDnsConfig("8.8.8.8", "dns.google", "https://dns.google/dns-query"),
            "8.8.4.4" to SecureDnsConfig("8.8.4.4", "dns.google", "https://dns.google/dns-query"),
            "1.1.1.1" to SecureDnsConfig("1.1.1.1", "one.one.one.one", "https://cloudflare-dns.com/dns-query"),
            "1.0.0.1" to SecureDnsConfig("1.0.0.1", "one.one.one.one", "https://cloudflare-dns.com/dns-query"),
            "9.9.9.9" to SecureDnsConfig("9.9.9.9", "dns.quad9.net", "https://dns.quad9.net/dns-query"),
            "149.112.112.112" to SecureDnsConfig("149.112.112.112", "dns.quad9.net", "https://dns.quad9.net/dns-query"),
            "94.140.14.14" to SecureDnsConfig("94.140.14.14", "dns.adguard-dns.com", "https://dns.adguard-dns.com/dns-query"),
            "94.140.15.15" to SecureDnsConfig("94.140.15.15", "dns.adguard-dns.com", "https://dns.adguard-dns.com/dns-query"),
            "76.76.2.0" to SecureDnsConfig("76.76.2.0", "dns.controld.com", "https://dns.controld.com/dns-query"),
            "76.76.10.0" to SecureDnsConfig("76.76.10.0", "dns.controld.com", "https://dns.controld.com/dns-query")
        )
    }

    // Custom SocketFactory to automatically call protect() on every Socket created by OkHttp
    private inner class ProtectedSocketFactory : SocketFactory() {
        override fun createSocket(): Socket {
            val socket = Socket()
            protect(socket)
            return socket
        }

        override fun createSocket(host: String?, port: Int): Socket {
            val socket = Socket()
            protect(socket)
            socket.connect(InetSocketAddress(host, port))
            return socket
        }

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
            val socket = Socket()
            protect(socket)
            if (localHost != null) {
                socket.bind(InetSocketAddress(localHost, 0))
            }
            socket.connect(InetSocketAddress(host, port))
            return socket
        }

        override fun createSocket(address: InetAddress?, port: Int): Socket {
            val socket = Socket()
            protect(socket)
            socket.connect(InetSocketAddress(address, port))
            return socket
        }

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
            val socket = Socket()
            protect(socket)
            if (localAddress != null) {
                socket.bind(InetSocketAddress(localAddress, 0))
            }
            socket.connect(InetSocketAddress(address, port))
            return socket
        }
    }

    // Permissive SSL context used for secure connections to Direct IP custom DNS servers
    private val permissiveSslContext by lazy {
        val trustAllCertificates = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            }
        )
        SSLContext.getInstance("TLS").apply {
            init(null, trustAllCertificates, SecureRandom())
        }
    }

    private val bootstrapDns by lazy {
        object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                for (config in SECURE_DNS_REGISTRY.values) {
                    if (config.dotHost.equals(hostname, ignoreCase = true)) {
                        return listOf(InetAddress.getByName(config.ip))
                    }
                    val dohUri = android.net.Uri.parse(config.dohUrl)
                    if (dohUri.host?.equals(hostname, ignoreCase = true) == true) {
                        return listOf(InetAddress.getByName(config.ip))
                    }
                }
                try {
                    if (hostname.matches(Regex("^[0-9.]+$"))) {
                        return listOf(InetAddress.getByName(hostname))
                    }
                } catch (e: Exception) {}
                return okhttp3.Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    // Highly optimized OkHttpClient with low-latency configuration and VPN bypass
    private val okHttpClient by lazy {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
        }
        OkHttpClient.Builder()
            .dns(bootstrapDns)
            .socketFactory(ProtectedSocketFactory())
            .sslSocketFactory(permissiveSslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true } // Trust any hostname for flexible IP-based custom DoH
            .connectTimeout(4000, TimeUnit.MILLISECONDS)
            .readTimeout(4000, TimeUnit.MILLISECONDS)
            .writeTimeout(4000, TimeUnit.MILLISECONDS)
            .build()
    }

    private val cronetDohResolver by lazy { CronetDohResolver(applicationContext) }
    private val resolverController by lazy {
        FluxResolverController(
            context = applicationContext,
            resolveViaDoH = { query, ip -> resolveViaDoH(query, ip) },
            resolveViaDoT = { query, ip -> resolveViaDoT(query, ip) },
            protectSocket = { socket -> protect(socket) }
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        } else if (action == ACTION_NEXT_PROFILE) {
            switchNextProfile()
            return START_STICKY
        } else if (action == ACTION_START) {
            val primary = intent.getStringExtra(EXTRA_PRIMARY_DNS) ?: "8.8.8.8"
            val secondary = intent.getStringExtra(EXTRA_SECONDARY_DNS) ?: "8.8.4.4"
            val enableIpv6 = intent.getBooleanExtra(EXTRA_ENABLE_IPV6, false)
            val primaryIpv6 = intent.getStringExtra(EXTRA_PRIMARY_IPV6) ?: ""
            val secondaryIpv6 = intent.getStringExtra(EXTRA_SECONDARY_IPV6) ?: ""
            val name = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "Custom"
            val protocol = intent.getStringExtra(EXTRA_PROTOCOL) ?: "UDP"
            startVpn(primary, secondary, enableIpv6, primaryIpv6, secondaryIpv6, name, protocol)
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
        if (isRunning) {
            stopVpn()
        }

        _state.value = VpnState.CONNECTING
        _activeProfileName.value = profileName
        _activePrimaryDns.value = primaryDns
        _activeSecondaryDns.value = secondaryDns
        _activeEnableIpv6.value = enableIpv6
        _activePrimaryIpv6.value = primaryIpv6
        _activeSecondaryIpv6.value = secondaryIpv6
        _totalQueriesResolved.value = 0

        // Initialize and register MultiPath interface manager
        multiPathManager = MultiPathManager.getInstance(this).apply {
            startMonitoring()
        }
        FluxDnsEngine.setMultiPathManager(multiPathManager)

        val ipv6LogStr = if (enableIpv6 && primaryIpv6.isNotEmpty()) ", IPv6: $primaryIpv6" else ""
        log(LogType.INFO, "ENGINE", "Initializing DNS Changer Engine...")
        log(LogType.INFO, "PROFILE", "Active Profile: $profileName (Primary: $primaryDns, Secondary: $secondaryDns$ipv6LogStr)")
        log(LogType.INFO, "PROTOCOL", "Selected transport protocol: $protocol")

        val notification = createNotification(profileName, "$primaryDns | $secondaryDns [$protocol]")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        telemetryTracker = CellularTelemetryTracker(applicationContext).apply {
            start()
        }
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        serviceScope.launch(Dispatchers.IO + CoroutineName("DNS-VPN-Worker")) {
            try {
                val builder = Builder()
                builder.setSession("Vibrant DNS Changer")
                
                // Low-latency gaming MTU configuration to prevent UDP packet fragmentation
                builder.setMtu(1360)
                
                builder.addAddress("10.0.0.2", 32)
                
                builder.addDnsServer(primaryDns)
                if (secondaryDns.isNotEmpty() && secondaryDns != primaryDns) {
                    builder.addDnsServer(secondaryDns)
                }

                if (enableIpv6) {
                    try {
                        builder.addAddress("fd00:1::2", 128)
                        if (primaryIpv6.isNotEmpty()) {
                            builder.addDnsServer(primaryIpv6)
                        }
                        if (secondaryIpv6.isNotEmpty() && secondaryIpv6 != primaryIpv6) {
                            builder.addDnsServer(secondaryIpv6)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to add IPv6 interface address and DNS servers", e)
                    }
                }

                // Split Tunneling Configuration
                try {
                    val prefs = getSharedPreferences("dns_settings", android.content.Context.MODE_PRIVATE)
                    val isGamingShieldEnabled = prefs.getBoolean("gaming_shield_enabled", true)

                    var splitTunnelConfigured = false
                    if (isGamingShieldEnabled) {
                        val db = com.example.data.DnsDatabase.getDatabase(this@DnsVpnService)
                        val selectedApps = db.gamingAppDao().getSelectedApps()
                        
                        if (selectedApps.isNotEmpty()) {
                            for (app in selectedApps) {
                                try {
                                    builder.addAllowedApplication(app.packageName)
                                    Log.i(TAG, "Split Tunneling: Added allowed app: ${app.packageName}")
                                    splitTunnelConfigured = true
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to add allowed application: ${app.packageName}", e)
                                }
                            }
                        }
                    }
                    
                    if (splitTunnelConfigured) {
                        val db = com.example.data.DnsDatabase.getDatabase(this@DnsVpnService)
                        val selectedApps = db.gamingAppDao().getSelectedApps()
                        log(LogType.SUCCESS, "TUNNEL", "App-Split Tunneling active! Intercepting DNS only for ${selectedApps.size} game(s).")
                    } else if (isGamingShieldEnabled) {
                        log(LogType.INFO, "TUNNEL", "Gaming Shield is ON but no apps are selected. Operating in full system DNS routing mode.")
                    } else {
                        log(LogType.INFO, "TUNNEL", "Gaming Shield is OFF. Operating in full system DNS routing mode.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to configure App-Split Tunneling", e)
                }

                try {
                    builder.addRoute(primaryDns, 32)
                    if (secondaryDns.isNotEmpty() && secondaryDns != primaryDns) {
                        builder.addRoute(secondaryDns, 32)
                    }
                    if (enableIpv6 && primaryIpv6.isNotEmpty()) {
                        builder.addRoute(primaryIpv6, 128)
                        if (secondaryIpv6.isNotEmpty() && secondaryIpv6 != primaryIpv6) {
                            builder.addRoute(secondaryIpv6, 128)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add specific DNS routes, falling back to routing DNS subnet", e)
                    try {
                        builder.addRoute("8.8.8.8", 32)
                        builder.addRoute("8.8.4.4", 32)
                        builder.addRoute("1.1.1.1", 32)
                        builder.addRoute("1.0.0.1", 32)
                    } catch (ex: Exception) {
                        Log.e(TAG, "Failed to establish routes", ex)
                    }
                }

                val vpnInterfaceLocal = builder.establish()
                if (vpnInterfaceLocal == null) {
                    Log.e(TAG, "Failed to establish VPN interface (null)")
                    _state.value = VpnState.DISCONNECTED
                    return@launch
                }
                vpnInterface = vpnInterfaceLocal

                _state.value = VpnState.CONNECTED
                Log.i(TAG, "VPN tunnel established successfully. Native active: ${FluxDnsEngine.isNativeAvailable}")
                
                // Flush both standard JVM and system browser/webview caches
                DnsCacheFlusher.flushAll(applicationContext)
                log(LogType.SUCCESS, "CACHE", "System and browser/webview DNS caches successfully flushed.")

                if (FluxDnsEngine.isNativeAvailable) {
                    log(LogType.SUCCESS, "ENGINE", "Native JNI engine successfully bound to TUN interface.")
                    val activePrimaryNative = if (enableIpv6 && primaryIpv6.isNotEmpty()) primaryIpv6 else primaryDns
                    val activeSecondaryNative = if (enableIpv6 && secondaryIpv6.isNotEmpty()) secondaryIpv6 else secondaryDns
                    val started = FluxDnsEngine.start(vpnInterfaceLocal, activePrimaryNative, activeSecondaryNative, protocol)
                    if (started) {
                        log(LogType.SUCCESS, "ENGINE", "ZIBE: Finalizing VPN handshake. Applying CPU affinity and thread priority optimizations...")
                        FluxDnsEngine.applyZibeOptimization()
                        // Start polling native statistics
                        serviceScope.launch {
                            while (isRunning) {
                                delay(1000)
                                _totalQueriesResolved.value = FluxDnsEngine.getResolvedCount()
                            }
                        }
                        // Keep worker thread alive while VPN is running
                        while (isRunning) {
                            try {
                                delay(1000)
                            } catch (e: InterruptedException) {
                                break
                            }
                        }
                    } else {
                        log(LogType.ERROR, "ENGINE", "Failed to start native engine. Invoking JVM fallback...")
                        runVpnTunnelLoop(vpnInterfaceLocal, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, protocol)
                    }
                } else {
                    log(LogType.INFO, "ENGINE", "Running standard JVM-based packet routing engine.")
                    runVpnTunnelLoop(vpnInterfaceLocal, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, protocol)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in VPN tunnel thread", e)
                log(LogType.ERROR, "ENGINE", "Critical error in worker thread: ${e.message}")
                _state.value = VpnState.DISCONNECTED
            } finally {
                stopSelf()
            }
        }
    }

    private fun switchNextProfile() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val db = com.example.data.DnsDatabase.getDatabase(this@DnsVpnService)
                val profiles = db.dnsProfileDao().getAllProfiles().first()
                if (profiles.isNotEmpty()) {
                    val currentName = _activeProfileName.value
                    val currentIndex = profiles.indexOfFirst { it.name == currentName }
                    val nextIndex = if (currentIndex == -1 || currentIndex == profiles.lastIndex) 0 else currentIndex + 1
                    val nextProfile = profiles[nextIndex]
                    
                    log(LogType.INFO, "HOTSWAP", "Hot-swapping profile dynamically: ${nextProfile.name}")
                    
                    withContext(Dispatchers.Main) {
                        val intent = Intent(this@DnsVpnService, DnsVpnService::class.java).apply {
                            action = ACTION_START
                            putExtra(EXTRA_PRIMARY_DNS, nextProfile.primaryDns)
                            putExtra(EXTRA_SECONDARY_DNS, nextProfile.secondaryDns)
                            putExtra(EXTRA_PROFILE_NAME, nextProfile.name)
                            putExtra(EXTRA_PROTOCOL, "UDP") // Defaulting to standard UDP for notifications-driven swap
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in dynamic profile hot-swap", e)
            }
        }
    }

    private fun stopVpn() {
        val wasRunning = isRunning
        isRunning = false

        _state.value = VpnState.DISCONNECTED
        _activeProfileName.value = "None"
        _activePrimaryDns.value = ""
        _activeSecondaryDns.value = ""
        _totalQueriesResolved.value = 0

        log(LogType.WARNING, "ENGINE", "Stopping DNS Changer Engine...")

        if (FluxDnsEngine.isNativeAvailable) {
            try {
                FluxDnsEngine.stop()
                FluxDnsEngine.resetZibeOptimization()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop native engine", e)
            }
        }

        try {
            telemetryTracker?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop telemetry tracker", e)
        }
        telemetryTracker = null

        // Teardown and release MultiPath interface manager
        try {
            multiPathManager?.stopMonitoring()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop MultiPath monitoring", e)
        }
        FluxDnsEngine.setMultiPathManager(null)
        multiPathManager = null

        // Explicitly close the active TUN interface descriptor
        val pfd = vpnInterface
        if (pfd != null) {
            try {
                pfd.close()
                log(LogType.WARNING, "TUNNEL", "Explicitly closed active TUN interface file descriptor.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close VPN interface", e)
                log(LogType.ERROR, "TUNNEL", "Failed to close VPN interface file descriptor: ${e.message}")
            }
            vpnInterface = null
        }

        // Cancel all running Kotlin coroutines in the VPN scope
        try {
            serviceScope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel service scope", e)
        }

        // Shut down any active thread executor and reset socket connections
        try {
            okHttpClient.dispatcher.executorService.shutdown()
            okHttpClient.connectionPool.evictAll()
            log(LogType.WARNING, "SOCKET", "Shut down active HTTP client thread pool and evicted all cached sockets.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean up OkHttpClient", e)
        }

        // Flush standard JVM, webview, and process network routing descriptor caches
        try {
            DnsCacheFlusher.flushAll(applicationContext)
            log(LogType.SUCCESS, "CACHE", "System DNS resolution cache programmatically flushed.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush DNS cache", e)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground", e)
        }

        log(LogType.SUCCESS, "ENGINE", "Engine stopped successfully. Default system DNS restored.")
        try {
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop service self", e)
        }
    }

    private suspend fun runVpnTunnelLoop(
        vpnInterface: ParcelFileDescriptor,
        primaryDns: String,
        secondaryDns: String,
        enableIpv6: Boolean = false,
        primaryIpv6: String = "",
        secondaryIpv6: String = "",
        protocol: String = "UDP"
    ) {
        val fileDescriptor = vpnInterface.fileDescriptor
        val input = FileInputStream(fileDescriptor)
        val output = FileOutputStream(fileDescriptor)

        val packetBuffer = ByteBuffer.allocate(32767)

        while (isRunning) {
            try {
                packetBuffer.clear()
                val length = input.read(packetBuffer.array())
                if (length <= 0) {
                    delay(5) // Reduced sleep for faster cycle responsiveness
                    continue
                }

                packetBuffer.limit(length)

                // Parse IP header
                val versionAndIHL = packetBuffer.get(0).toInt() and 0xFF
                val version = versionAndIHL shr 4

                if (version == 4) {
                    val ihl = versionAndIHL and 0x0F
                    val ipProtocol = packetBuffer.get(9).toInt() and 0xFF

                    // Check if UDP (17)
                    if (ipProtocol == 17) {
                        val ipHeaderLength = ihl * 4

                        // Get Source and Destination IP
                        val srcIpBytes = ByteArray(4)
                        val dstIpBytes = ByteArray(4)

                        packetBuffer.position(12)
                        packetBuffer.get(srcIpBytes)
                        packetBuffer.get(dstIpBytes)

                        // Get UDP Source and Destination Ports
                        packetBuffer.position(ipHeaderLength)
                        val srcPort = packetBuffer.getShort().toInt() and 0xFFFF
                        val dstPort = packetBuffer.getShort().toInt() and 0xFFFF
                        val udpLength = packetBuffer.getShort().toInt() and 0xFFFF
                        packetBuffer.getShort() // Checksum

                        // Check if DNS Query (Destination Port is 53)
                        if (dstPort == 53) {
                            val dnsPayloadLength = udpLength - 8
                            if (dnsPayloadLength > 0) {
                                val dnsQuery = ByteArray(dnsPayloadLength)
                                packetBuffer.position(ipHeaderLength + 8)
                                packetBuffer.get(dnsQuery)

                                // Clone header fields to prevent concurrency conflicts
                                val srcIpBytesCopy = srcIpBytes.clone()
                                val dstIpBytesCopy = dstIpBytes.clone()

                                // Hand off resolution to high-performance non-blocking coroutine
                                serviceScope.launch {
                                    resolveDnsQueryAndReply(
                                        dnsQuery,
                                        srcIpBytesCopy,
                                        dstIpBytesCopy,
                                        srcPort,
                                        dstPort,
                                        output,
                                        primaryDns,
                                        secondaryDns,
                                        enableIpv6,
                                        primaryIpv6,
                                        secondaryIpv6,
                                        protocol,
                                        isIpv6 = false
                                    )
                                }
                            }
                        }
                    }
                } else if (version == 6) {
                    // IPv6 standard header has Next Header at byte index 6
                    val ipProtocol = packetBuffer.get(6).toInt() and 0xFF

                    // Check if UDP (17)
                    if (ipProtocol == 17) {
                        val ipHeaderLength = 40

                        // Source and Destination IP (16 bytes each)
                        val srcIpBytes = ByteArray(16)
                        val dstIpBytes = ByteArray(16)

                        packetBuffer.position(8)
                        packetBuffer.get(srcIpBytes)
                        packetBuffer.get(dstIpBytes)

                        // Get UDP Source and Destination Ports
                        packetBuffer.position(ipHeaderLength)
                        val srcPort = packetBuffer.getShort().toInt() and 0xFFFF
                        val dstPort = packetBuffer.getShort().toInt() and 0xFFFF
                        val udpLength = packetBuffer.getShort().toInt() and 0xFFFF
                        packetBuffer.getShort() // Checksum

                        // Check if DNS Query (Destination Port is 53)
                        if (dstPort == 53) {
                            val dnsPayloadLength = udpLength - 8
                            if (dnsPayloadLength > 0) {
                                val dnsQuery = ByteArray(dnsPayloadLength)
                                packetBuffer.position(ipHeaderLength + 8)
                                packetBuffer.get(dnsQuery)

                                // Clone header fields to prevent concurrency conflicts
                                val srcIpBytesCopy = srcIpBytes.clone()
                                val dstIpBytesCopy = dstIpBytes.clone()

                                // Hand off resolution to high-performance non-blocking coroutine
                                serviceScope.launch {
                                    resolveDnsQueryAndReply(
                                        dnsQuery,
                                        srcIpBytesCopy,
                                        dstIpBytesCopy,
                                        srcPort,
                                        dstPort,
                                        output,
                                        primaryDns,
                                        secondaryDns,
                                        enableIpv6,
                                        primaryIpv6,
                                        secondaryIpv6,
                                        protocol,
                                        isIpv6 = true
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "VPN loop interrupted")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error processing VPN packet", e)
            }
        }
    }

    private fun parseDnsQueryName(dnsQuery: ByteArray): String {
        if (dnsQuery.size < 12) return "unknown"
        val sb = StringBuilder()
        var pos = 12
        try {
            while (pos < dnsQuery.size) {
                val len = dnsQuery[pos].toInt() and 0xFF
                if (len == 0) break
                if (pos + 1 + len > dnsQuery.size) return "invalid"
                if (sb.isNotEmpty()) {
                    sb.append(".")
                }
                for (i in 0 until len) {
                    sb.append((dnsQuery[pos + 1 + i].toInt() and 0xFF).toChar())
                }
                pos += 1 + len
            }
            return if (sb.isEmpty()) "unknown" else sb.toString()
        } catch (e: Exception) {
            return "parse_error"
        }
    }

    private fun isDomainFiltered(domain: String, profileName: String): Boolean {
        if (domain == "unknown" || domain.isEmpty() || domain == "parse_error" || domain == "invalid") return false
        val lower = domain.lowercase()

        val adKeywords = listOf(
            "doubleclick.net", "adservice.google", "admob.com", "applovin.com",
            "unityads.unity3d.com", "adjust.com", "appsflyer.com", "bugsnag.com",
            "googlesyndication.com", "google-analytics.com", "popads.net",
            "scorecardresearch.com", "taboola.com", "outbrain.com", "moatads.com",
            "criteo.com", "adform.net", "rubiconproject.com", "flurry.com",
            "quantserve.com", "advertising.com", "adnxs.com", "telemetry",
            "adservice", "analytics.facebook.com"
        )

        if (adKeywords.any { lower.contains(it) }) return true

        if (profileName.contains("AdGuard", ignoreCase = true) || profileName.contains("Blocks Ads", ignoreCase = true)) {
            if (lower.startsWith("ad.") || lower.contains(".ads.") || lower.contains("tracker") || lower.contains("analytics")) {
                return true
            }
        }

        return false
    }

    private fun createNxdomainResponse(query: ByteArray): ByteArray {
        if (query.size < 12) return query
        val resp = query.copyOf()
        resp[2] = 0x81.toByte() // Response, RD, RA
        resp[3] = 0x83.toByte() // RCODE = 3 (NXDOMAIN)
        resp[6] = 0.toByte()    // Answers = 0
        resp[7] = 0.toByte()
        return resp
    }

    private fun sendDnsResponsePacket(
        response: ByteArray,
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        srcPort: Int,
        dstPort: Int,
        output: FileOutputStream,
        isIpv6: Boolean
    ) {
        if (isIpv6) {
            val responsePacketSize = 40 + 8 + response.size
            val responseBufferFull = ByteBuffer.allocate(responsePacketSize)

            responseBufferFull.put(0, 0x60.toByte())
            responseBufferFull.put(1, 0.toByte())
            responseBufferFull.put(2, 0.toByte())
            responseBufferFull.put(3, 0.toByte())

            val payloadLen = 8 + response.size
            responseBufferFull.put(4, ((payloadLen shr 8) and 0xFF).toByte())
            responseBufferFull.put(5, (payloadLen and 0xFF).toByte())

            responseBufferFull.put(6, 17.toByte()) // UDP
            responseBufferFull.put(7, 64.toByte()) // Hop Limit

            responseBufferFull.position(8)
            responseBufferFull.put(dstIpBytes)
            responseBufferFull.put(srcIpBytes)

            responseBufferFull.position(40)
            responseBufferFull.putShort(dstPort.toShort())
            responseBufferFull.putShort(srcPort.toShort())
            responseBufferFull.putShort((8 + response.size).toShort())
            responseBufferFull.putShort(0.toShort())

            responseBufferFull.position(48)
            responseBufferFull.put(response)

            synchronized(output) {
                output.write(responseBufferFull.array(), 0, responsePacketSize)
            }
        } else {
            val responseIpHeaderLength = 20
            val responsePacketSize = responseIpHeaderLength + 8 + response.size
            val responseBufferFull = ByteBuffer.allocate(responsePacketSize)

            responseBufferFull.put(0, 0x45.toByte())
            responseBufferFull.put(1, 0.toByte())
            responseBufferFull.putShort(2, responsePacketSize.toShort())
            responseBufferFull.putShort(4, 0.toShort())
            responseBufferFull.putShort(6, 0x4000.toShort())
            responseBufferFull.put(8, 64.toByte())
            responseBufferFull.put(9, 17.toByte())
            responseBufferFull.putShort(10, 0.toShort())

            responseBufferFull.position(12)
            responseBufferFull.put(dstIpBytes)
            responseBufferFull.put(srcIpBytes)

            responseBufferFull.position(20)
            responseBufferFull.putShort(dstPort.toShort())
            responseBufferFull.putShort(srcPort.toShort())
            responseBufferFull.putShort((8 + response.size).toShort())
            responseBufferFull.putShort(0.toShort())

            responseBufferFull.position(28)
            responseBufferFull.put(response)

            val ipChecksum = calculateChecksum(responseBufferFull.array(), 0, 20)
            responseBufferFull.putShort(10, ipChecksum)

            synchronized(output) {
                output.write(responseBufferFull.array(), 0, responsePacketSize)
            }
        }
    }

    private suspend fun resolveDnsQueryAndReply(
        dnsQuery: ByteArray,
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        srcPort: Int,
        dstPort: Int,
        output: FileOutputStream,
        primaryDns: String,
        secondaryDns: String,
        enableIpv6: Boolean,
        primaryIpv6: String,
        secondaryIpv6: String,
        protocol: String,
        isIpv6: Boolean
    ) {
        val domain = parseDnsQueryName(dnsQuery)
        val ipVer = if (isIpv6) "IPv6" else "IPv4"
        val activeProfile = _activeProfileName.value

        // 1. Check Filter / Block Rule
        if (isDomainFiltered(domain, activeProfile)) {
            _totalQueriesFiltered.value++
            log(
                LogType.WARNING,
                "FILTERED",
                "🛡️ [DNS FILTERED] Domain '$domain' ($ipVer) blocked by Cyber Shield Policy ($activeProfile). Category: Ads & Telemetry -> Returned 0.0.0.0 (NXDOMAIN)"
            )
            val filteredResp = createNxdomainResponse(dnsQuery)
            try {
                sendDnsResponsePacket(filteredResp, srcIpBytes, dstIpBytes, srcPort, dstPort, output, isIpv6)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending filtered DNS response", e)
            }
            return
        }

        // 2. Query Log
        log(LogType.INFO, "QUERY", "Requested: $domain ($ipVer) via 3-Tier Engine ($activeProfile)")

        // 3. Resolve Query
        val response = resolverController.resolve(dnsQuery, primaryDns, secondaryDns, enableIpv6, primaryIpv6, secondaryIpv6, domain, protocol)

        if (response != null) {
            try {
                sendDnsResponsePacket(response, srcIpBytes, dstIpBytes, srcPort, dstPort, output, isIpv6)
                _totalQueriesResolved.value++
                log(LogType.SUCCESS, "RESOLVED", "✅ [RESOLVED] Domain '$domain' ($ipVer) resolved via $protocol protocol ($activeProfile)")
            } catch (e: Exception) {
                Log.e(TAG, "Error writing back DNS response packet", e)
                log(LogType.ERROR, "SYSTEM", "Error writing back DNS response packet: ${e.message}")
            }
        } else {
            log(LogType.ERROR, "RESOLVER", "❌ [FAILED] Unable to resolve '$domain' ($ipVer) on configured servers ($activeProfile)")
        }
    }

    private suspend fun raceUdpQueries(dnsQuery: ByteArray, primaryDns: String, secondaryDns: String): ByteArray? = coroutineScope {
        val ips = mutableSetOf("8.8.8.8", "1.1.1.1", "9.9.9.9")
        if (primaryDns.isNotEmpty()) ips.add(primaryDns)
        if (secondaryDns.isNotEmpty()) ips.add(secondaryDns)
        
        val targetIps = ips.take(3).toList()
        Log.i(TAG, "Initiating Parallel DNS Racing across Anycast IPs: $targetIps")
        
        val channel = kotlinx.coroutines.channels.Channel<ByteArray>(1)
        val jobs = targetIps.map { ip ->
            launch(Dispatchers.IO) {
                try {
                    val res = resolveViaUdp(dnsQuery, ip)
                    if (res != null) {
                        channel.trySend(res)
                    }
                } catch (e: Exception) {
                    // Ignore and let other racers complete
                }
            }
        }
        
        val result = withTimeoutOrNull(1500) {
            channel.receive()
        }
        jobs.forEach { it.cancel() }
        result
    }

    private suspend fun resolveViaUdp(dnsQuery: ByteArray, dnsIp: String): ByteArray? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket) // Bypasses the VPN loop
            socket.soTimeout = 1000 // Tight 1-second timeout for responsive gaming
            
            // Tuned buffers to prevent packet loss under high connection loads
            socket.sendBufferSize = 65536
            socket.receiveBufferSize = 65536

            val address = InetAddress.getByName(dnsIp)
            val forwardPacket = DatagramPacket(dnsQuery, dnsQuery.size, address, 53)
            socket.send(forwardPacket)

            val responseBuffer = ByteArray(4096)
            val receivePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(receivePacket)

            val responseLength = receivePacket.length
            val responseBytes = ByteArray(responseLength)
            System.arraycopy(responseBuffer, 0, responseBytes, 0, responseLength)
            responseBytes
        } catch (e: Exception) {
            Log.w(TAG, "UDP resolution query failed on $dnsIp: ${e.message}")
            null
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {}
        }
    }

    private suspend fun resolveViaDoT(dnsQuery: ByteArray, dnsIp: String): ByteArray? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var sslSocket: SSLSocket? = null
        try {
            val config = SECURE_DNS_REGISTRY[dnsIp]
            val host = config?.dotHost

            // 1. Establish raw connection on port 853
            socket = Socket()
            protect(socket) // Bypasses the VPN loop
            socket.soTimeout = 4000
            socket.connect(InetSocketAddress(dnsIp, 853), 4000)

            // 2. Wrap socket in SSL/TLS layer
            val sslFactory = if (host != null) {
                SSLSocketFactory.getDefault() as SSLSocketFactory
            } else {
                permissiveSslContext.socketFactory
            }

            sslSocket = sslFactory.createSocket(socket, host ?: dnsIp, 853, true) as SSLSocket
            sslSocket.useClientMode = true
            sslSocket.startHandshake()

            val outputStream = sslSocket.outputStream
            val inputStream = sslSocket.inputStream

            // RFC 7858: 2-byte length header prefix + payload
            val len = dnsQuery.size
            val header = byteArrayOf(((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte())
            outputStream.write(header)
            outputStream.write(dnsQuery)
            outputStream.flush()

            // Read response 2-byte length header
            val lenBuf = ByteArray(2)
            var bytesRead = 0
            while (bytesRead < 2) {
                val r = inputStream.read(lenBuf, bytesRead, 2 - bytesRead)
                if (r < 0) throw IOException("Socket closed while reading length header")
                bytesRead += r
            }
            val responseLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)

            // Read response payload
            val responseBuf = ByteArray(responseLen)
            var bodyBytesRead = 0
            while (bodyBytesRead < responseLen) {
                val r = inputStream.read(responseBuf, bodyBytesRead, responseLen - bodyBytesRead)
                if (r < 0) throw IOException("Socket closed while reading response payload")
                bodyBytesRead += r
            }

            responseBuf
        } catch (e: Exception) {
            Log.w(TAG, "DoT query failed on $dnsIp: ${e.message}", e)
            log(LogType.ERROR, "RESOLVER", "DoT query failed on $dnsIp: ${e.message ?: e.javaClass.simpleName}")
            null
        } finally {
            try {
                sslSocket?.close()
            } catch (e: Exception) {}
            try {
                socket?.close()
            } catch (e: Exception) {}
        }
    }

    private suspend fun resolveViaDoH(dnsQuery: ByteArray, dnsIp: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val config = SECURE_DNS_REGISTRY[dnsIp]
            val dohUrl = config?.dohUrl ?: "https://$dnsIp/dns-query"

            val mediaType = "application/dns-message".toMediaTypeOrNull()
            val requestBody = dnsQuery.toRequestBody(mediaType)

            val request = Request.Builder()
                .url(dohUrl)
                .post(requestBody)
                .header("Content-Type", "application/dns-message")
                .header("Accept", "application/dns-message")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body
                    body?.bytes()
                } else {
                    Log.w(TAG, "DoH query returned non-success code ${response.code} on $dnsIp")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoH query failed on $dnsIp: ${e.message}", e)
            log(LogType.ERROR, "RESOLVER", "DoH query failed on $dnsIp: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun resolveViaDoH3(dnsQuery: ByteArray, dnsIp: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val config = SECURE_DNS_REGISTRY[dnsIp]
            val dohUrl = config?.dohUrl ?: "https://$dnsIp/dns-query"
            cronetDohResolver.resolveQuery(dnsQuery, dohUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Cronet DoH3 query failed on $dnsIp: ${e.message}", e)
            log(LogType.ERROR, "RESOLVER", "Cronet DoH3 query failed on $dnsIp: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private fun calculateChecksum(buf: ByteArray, offset: Int, length: Int): Short {
        var sum = 0
        var i = offset
        var len = length
        while (len > 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
            len -= 2
        }
        if (len > 0) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv()).toShort()
    }

    private fun flushSystemDnsCache() {
        Log.i(TAG, "Flushing InetAddress system cache programmatically...")
        try {
            val addressCacheField = InetAddress::class.java.getDeclaredField("addressCache")
            addressCacheField.isAccessible = true
            val addressCache = addressCacheField.get(null)
            val clearMethod = addressCache.javaClass.getDeclaredMethod("clear")
            clearMethod.isAccessible = true
            clearMethod.invoke(addressCache)
            Log.i(TAG, "InetAddress addressCache cleared successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "Skipped flushing InetAddress JVM cache (not supported on this Android version)")
        }
    }

    private fun createNotification(profileName: String, dnsDetails: String): Notification {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val stopIntent = Intent(this, DnsVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val switchIntent = Intent(this, DnsVpnService::class.java).apply {
            action = ACTION_NEXT_PROFILE
        }
        val switchPendingIntent = PendingIntent.getService(
            this, 2, switchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        builder
            .setContentTitle("DNS Shield Connected")
            .setContentText("Profile: $profileName | $dnsDetails")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        // Add Disconnect Action
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Disconnect",
            stopPendingIntent
        )

        // Add Next Profile Action
        builder.addAction(
            android.R.drawable.ic_menu_compass,
            "Next Profile",
            switchPendingIntent
        )

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "DNS Changer VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
