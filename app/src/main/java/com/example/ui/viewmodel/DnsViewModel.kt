package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.DnsDatabase
import com.example.data.DnsProfile
import com.example.data.DnsRepository
import com.example.service.DnsVpnService
import com.example.service.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class DnsViewModel(
    application: Application,
    private val repository: DnsRepository
) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    val allProfiles: StateFlow<List<DnsProfile>> = repository.allProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allGamingApps: StateFlow<List<com.example.data.GamingApp>> = repository.allGamingApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleGamingAppSelection(packageName: String, isSelected: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setGamingAppSelected(packageName, isSelected)
        }
    }

    fun toggleGamingAppMultiPath(packageName: String, isMultiPathEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setGamingAppMultiPathEnabled(packageName, isMultiPathEnabled)
        }
    }

    fun addCustomGamingApp(name: String, packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertGamingApp(com.example.data.GamingApp(packageName = packageName, name = name, isSelected = true))
        }
    }

    fun addMultipleGamingApps(apps: List<com.example.data.GamingApp>) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertGamingApps(apps)
        }
    }

    fun deleteGamingApp(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteGamingApp(packageName)
        }
    }

    val vpnState: StateFlow<VpnState> = DnsVpnService.state
    val activeProfileName: StateFlow<String> = DnsVpnService.activeProfileName
    val activePrimaryDns: StateFlow<String> = DnsVpnService.activePrimaryDns
    val activeSecondaryDns: StateFlow<String> = DnsVpnService.activeSecondaryDns
    val totalQueriesResolved: StateFlow<Int> = DnsVpnService.totalQueriesResolved
    val smartRoutingStatus: StateFlow<com.example.service.SmartRoutingStatus?> = DnsVpnService.smartRoutingStatus
//     val totalQueriesFiltered: StateFlow<Int> = DnsVpnService.totalQueriesFiltered
    val logs: StateFlow<List<com.example.service.DnsLogEntry>> = DnsVpnService.logs

    fun clearLogs() {
        DnsVpnService.clearLogs()
    }

    private val _pendingCrashLog = MutableStateFlow<String?>(null)
    val pendingCrashLog: StateFlow<String?> = _pendingCrashLog.asStateFlow()

    fun clearPendingCrashLog() {
        _pendingCrashLog.value = null
        com.example.util.CrashHandler.clearCrashLog(context)
    }

    private val _connectionUptime = MutableStateFlow("00:00:00")
    val connectionUptime: StateFlow<String> = _connectionUptime.asStateFlow()

    private val _pingResult = MutableStateFlow<Int?>(null)
    val pingResult: StateFlow<Int?> = _pingResult.asStateFlow()

    private val _jitterMs = MutableStateFlow<Int>(0)
    val jitterMs: StateFlow<Int> = _jitterMs.asStateFlow()

    private val _packetLossPercent = MutableStateFlow<Float>(0f)
    val packetLossPercent: StateFlow<Float> = _packetLossPercent.asStateFlow()

    private val _pingHistory = MutableStateFlow<List<Int>>(emptyList())
    val pingHistory: StateFlow<List<Int>> = _pingHistory.asStateFlow()

    private val pingHistoryBuffer = java.util.concurrent.CopyOnWriteArrayList<Int?>()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    private val _selectedProfile = MutableStateFlow<DnsProfile?>(null)
    val selectedProfile: StateFlow<DnsProfile?> = _selectedProfile.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    private val _isGamingShieldEnabled = MutableStateFlow(true)
    val isGamingShieldEnabled: StateFlow<Boolean> = _isGamingShieldEnabled.asStateFlow()

    private val _isMultiPathGlobalEnabled = MutableStateFlow(true)
    val isMultiPathGlobalEnabled: StateFlow<Boolean> = _isMultiPathGlobalEnabled.asStateFlow()
    
    private val _isSmartRoutingEnabled = MutableStateFlow(false)
    val isSmartRoutingEnabled: StateFlow<Boolean> = _isSmartRoutingEnabled.asStateFlow()

    private val _fixedEgressIp = MutableStateFlow("45.79.112.20")
    val fixedEgressIp: StateFlow<String> = _fixedEgressIp.asStateFlow()

    fun setMultiPathGlobalEnabled(enabled: Boolean) {
        _isMultiPathGlobalEnabled.value = enabled
        context.getSharedPreferences("dns_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("multipath_global_enabled", enabled)
            .apply()
    }
    
    fun setFixedEgressIp(ip: String) {
        _fixedEgressIp.value = ip
        context.getSharedPreferences("dns_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("fixed_egress_ip", ip)
            .apply()
            
        // If VPN is connected and smart routing is enabled, restart VPN to apply new IP
        if (vpnState.value == VpnState.CONNECTED && _isSmartRoutingEnabled.value) {
            reloadVpnWithCurrentSettings()
        }
    }
    
    fun setSmartRoutingEnabled(enabled: Boolean) {
        _isSmartRoutingEnabled.value = enabled
        context.getSharedPreferences("dns_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("smart_routing_enabled", enabled)
            .apply()
            
        // If VPN is connected, dynamically reload
        if (vpnState.value == VpnState.CONNECTED) {
            reloadVpnWithCurrentSettings()
        }
    }
    
    private fun reloadVpnWithCurrentSettings() {
        val currentSelected = _selectedProfile.value ?: return
        val intent = Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_START
            putExtra(DnsVpnService.EXTRA_PRIMARY_DNS, currentSelected.primaryDns)
            putExtra(DnsVpnService.EXTRA_SECONDARY_DNS, currentSelected.secondaryDns)
            putExtra(DnsVpnService.EXTRA_PROFILE_NAME, currentSelected.name)
            putExtra(DnsVpnService.EXTRA_PROTOCOL, "UDP")
            putExtra(DnsVpnService.EXTRA_SMART_ROUTING_ENABLED, _isSmartRoutingEnabled.value)
            putExtra(DnsVpnService.EXTRA_FIXED_EGRESS_IP, _fixedEgressIp.value)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun setAppInForeground(foreground: Boolean) {
        _isAppInForeground.value = foreground
        if (!foreground) {
            Log.d("DnsViewModel", "ZIBE: ON_STOP caught. Triggering manual GC and halting UI flows.")
            // Trigger a lightweight manual garbage collection exactly once to flush the JVM heap before entering background stasis
            System.gc()
        } else {
            Log.d("DnsViewModel", "ZIBE: ON_START caught. Resuming all UI flows and active monitoring.")
        }
    }

    fun setGamingShieldEnabled(enabled: Boolean) {
        _isGamingShieldEnabled.value = enabled
        context.getSharedPreferences("dns_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("gaming_shield_enabled", enabled)
            .apply()

        // If VPN is connected, dynamically reload to apply split tunneling config instantly!
        if (vpnState.value == VpnState.CONNECTED) {
            val currentSelected = _selectedProfile.value ?: return
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_START
                putExtra(DnsVpnService.EXTRA_PRIMARY_DNS, currentSelected.primaryDns)
                putExtra(DnsVpnService.EXTRA_SECONDARY_DNS, currentSelected.secondaryDns)
                putExtra(DnsVpnService.EXTRA_PROFILE_NAME, currentSelected.name)
                putExtra(DnsVpnService.EXTRA_PROTOCOL, "UDP")
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    init {
        // Ensure default profiles (Google, Cloudflare, Radar Game, Shecan, Electro, etc.) exist in the database
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureDefaultProfilesExist()
        }

        // Check for saved crash logs from previous sessions
        val savedCrash = com.example.util.CrashHandler.getSavedCrashLog(context)
        if (savedCrash != null) {
            _pendingCrashLog.value = savedCrash
            // Propagate it immediately to the local logs list so it displays in the LOGS tab
            DnsVpnService.log(com.example.service.LogType.ERROR, "CRASH", "Previous session terminated by unhandled exception:\n$savedCrash")
        }

        // Load initial Gaming Shield setting & MultiPath settings
        val prefs = context.getSharedPreferences("dns_settings", Context.MODE_PRIVATE)
        _isGamingShieldEnabled.value = prefs.getBoolean("gaming_shield_enabled", true)
        _isMultiPathGlobalEnabled.value = prefs.getBoolean("multipath_global_enabled", true)
        _isSmartRoutingEnabled.value = prefs.getBoolean("smart_routing_enabled", false)
        _fixedEgressIp.value = prefs.getString("fixed_egress_ip", "45.79.112.20") ?: "45.79.112.20"

        // Automatically fetch default/first profile and start periodic ping tests
        viewModelScope.launch {
            repository.allProfiles.collectLatest { profiles ->
                if (_selectedProfile.value == null && profiles.isNotEmpty()) {
                    val defaultProfile = profiles.firstOrNull { it.isDefault } ?: profiles.first()
                    _selectedProfile.value = defaultProfile
                }
            }
        }

        // Ticking connection uptime timer
        viewModelScope.launch {
            var startTime = 0L
            vpnState.collectLatest { state ->
                if (state == VpnState.CONNECTED) {
                    startTime = System.currentTimeMillis()
                    while (state == VpnState.CONNECTED) {
                        _isAppInForeground.first { it }
                        val elapsedMs = System.currentTimeMillis() - startTime
                        val seconds = (elapsedMs / 1000) % 60
                        val minutes = (elapsedMs / (1000 * 60)) % 60
                        val hours = (elapsedMs / (1000 * 60 * 60)) % 24
                        _connectionUptime.value = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                        delay(1000)
                    }
                } else {
                    _connectionUptime.value = "00:00:00"
                }
            }
        }

        // Start periodic ping measurement
        viewModelScope.launch {
            while (true) {
                _isAppInForeground.first { it }
                measurePing()
                delay(3000) // update ping every 3 seconds
            }
        }
    }

    fun selectProfile(profile: DnsProfile) {
        _selectedProfile.value = profile
        viewModelScope.launch {
            repository.setDefaultProfile(profile.id)
            
            // If VPN is connected, dynamically switch to the new profile instantly!
            if (vpnState.value == VpnState.CONNECTED) {
                reloadVpnWithCurrentSettings()
            }
        }
    }

    fun toggleVpn() {
        if (vpnState.value == VpnState.DISCONNECTED) {
            reloadVpnWithCurrentSettings()
        } else {
            stopVpnInternal()
        }
    }

    fun forceStopVpn() {
        stopVpnInternal()
    }

    private fun stopVpnInternal() {
        val serviceInstance = DnsVpnService.instance
        if (serviceInstance != null) {
            try {
                serviceInstance.stopVpnDirectly()
            } catch (e: Exception) {
                Log.e("DnsViewModel", "Failed to stop VPN service directly", e)
            }
        } else {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e("DnsViewModel", "Failed to startService with ACTION_STOP", e)
            }
        }
    }

    fun deleteProfile(profile: DnsProfile) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.deleteProfile(profile)
            }
            if (_selectedProfile.value?.id == profile.id) {
                val nextProfile = allProfiles.value.firstOrNull { it.id != profile.id }
                _selectedProfile.value = nextProfile
                
                // If VPN is active, switch or stop
                if (vpnState.value == VpnState.CONNECTED) {
                    if (nextProfile != null) {
                        reloadVpnWithCurrentSettings()
                    } else {
                        forceStopVpn()
                    }
                }
            }
        }
    }

    fun saveProfile(
        id: Int,
        name: String,
        primary: String,
        secondary: String,
        enableIpv6: Boolean,
        primaryIpv6: String,
        secondaryIpv6: String,
        isDefault: Boolean,
        isCustom: Boolean,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            val profile = DnsProfile(
                id = id,
                name = name,
                primaryDns = primary,
                secondaryDns = secondary,
                enableIpv6 = enableIpv6,
                primaryIpv6 = primaryIpv6,
                secondaryIpv6 = secondaryIpv6,
                isDefault = isDefault,
                isCustom = isCustom
            )
            withContext(Dispatchers.IO) {
                if (id == 0) {
                    val newId = repository.insertProfile(profile)
                    if (isDefault) {
                        repository.setDefaultProfile(newId.toInt())
                    }
                } else {
                    repository.updateProfile(profile)
                    if (isDefault) {
                        repository.setDefaultProfile(id)
                    }
                }
            }
            
            // If editing the active profile, hot-reload VPN immediately!
            if (id != 0 && _selectedProfile.value?.id == id) {
                _selectedProfile.value = profile
                if (vpnState.value == VpnState.CONNECTED) {
                    reloadVpnWithCurrentSettings()
                }
            }
            
            onComplete()
        }
    }

    private suspend fun measurePing() {
        if (_isPinging.value) return
        _isPinging.value = true

        val currentProfile = _selectedProfile.value
        val targetServer = if (vpnState.value == VpnState.CONNECTED) {
            activePrimaryDns.value.ifEmpty { "8.8.8.8" }
        } else {
            currentProfile?.primaryDns?.ifEmpty { "8.8.8.8" } ?: "8.8.8.8"
        }

        withContext(Dispatchers.IO) {
            val dnsQueryBytes = byteArrayOf(
                0x12.toByte(), 0x34.toByte(), // Transaction ID
                0x01.toByte(), 0x00.toByte(), // Flags (Standard Query, Recursion Desired)
                0x00.toByte(), 0x01.toByte(), // Questions: 1
                0x00.toByte(), 0x00.toByte(), // Answers: 0
                0x00.toByte(), 0x00.toByte(), // Authority: 0
                0x00.toByte(), 0x00.toByte(), // Additional: 0
                
                // Name: google.com
                6.toByte(), 'g'.toByte(), 'o'.toByte(), 'o'.toByte(), 'g'.toByte(), 'l'.toByte(), 'e'.toByte(),
                3.toByte(), 'c'.toByte(), 'o'.toByte(), 'm'.toByte(),
                0.toByte(), // Zero terminator of name
                
                0x00.toByte(), 0x01.toByte(), // Type A
                0x00.toByte(), 0x01.toByte()  // Class IN
            )

            var socket: DatagramSocket? = null
            try {
                val startTime = System.currentTimeMillis()
                val address = InetAddress.getByName(targetServer)
                
                // Create an unbound socket to protect it before binding
                socket = DatagramSocket(null)
                
                // Protect socket to bypass VPN if VPN is currently active
                DnsVpnService.instance?.let { vpnService ->
                    val protected = vpnService.protect(socket)
                    Log.d("DnsViewModel", "Ping socket bypass protected: $protected")
                } ?: run {
                    Log.d("DnsViewModel", "VPN Service instance not running; skipping socket protection")
                }
                
                // Bind to ephemeral local port and apply configuration
                socket.bind(null)
                socket.soTimeout = 1500 // 1.5 seconds timeout

                val requestPacket = DatagramPacket(dnsQueryBytes, dnsQueryBytes.size, address, 53)
                socket.send(requestPacket)

                val responseBuffer = ByteArray(512)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(responsePacket)

                val endTime = System.currentTimeMillis()
                val rtt = (endTime - startTime).toInt()
                _pingResult.value = rtt
                recordPingSample(rtt)
            } catch (e: Exception) {
                Log.w("DnsViewModel", "Failed to ping DNS server: $targetServer", e)
                _pingResult.value = null // represent Timeout/Offline
                recordPingSample(null)
            } finally {
                socket?.close()
                _isPinging.value = false
            }
        }
    }

    private fun recordPingSample(sample: Int?) {
        pingHistoryBuffer.add(sample)
        if (pingHistoryBuffer.size > 20) {
            pingHistoryBuffer.removeAt(0)
        }
        
        val validPings = pingHistoryBuffer.filterNotNull()
        _pingHistory.value = validPings
        
        // Calculate Jitter = Mean Absolute Difference between consecutive valid ping samples
        if (validPings.size >= 2) {
            var diffSum = 0
            for (i in 1 until validPings.size) {
                diffSum += kotlin.math.abs(validPings[i] - validPings[i - 1])
            }
            _jitterMs.value = diffSum / (validPings.size - 1)
        } else {
            _jitterMs.value = 0
        }

        // Calculate Packet Loss %
        val totalProbes = pingHistoryBuffer.size
        if (totalProbes > 0) {
            val lostProbes = pingHistoryBuffer.count { it == null }
            _packetLossPercent.value = (lostProbes.toFloat() / totalProbes.toFloat()) * 100f
        } else {
            _packetLossPercent.value = 0f
        }
    }
}

class DnsViewModelFactory(
    private val application: Application,
    private val repository: DnsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DnsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DnsViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

