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

    val vpnState: StateFlow<VpnState> = DnsVpnService.state
    val activeProfileName: StateFlow<String> = DnsVpnService.activeProfileName
    val activePrimaryDns: StateFlow<String> = DnsVpnService.activePrimaryDns
    val activeSecondaryDns: StateFlow<String> = DnsVpnService.activeSecondaryDns
    val totalQueriesResolved: StateFlow<Int> = DnsVpnService.totalQueriesResolved

    private val _connectionUptime = MutableStateFlow("00:00:00")
    val connectionUptime: StateFlow<String> = _connectionUptime.asStateFlow()

    private val _pingResult = MutableStateFlow<Int?>(null)
    val pingResult: StateFlow<Int?> = _pingResult.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    private val _selectedProfile = MutableStateFlow<DnsProfile?>(null)
    val selectedProfile: StateFlow<DnsProfile?> = _selectedProfile.asStateFlow()

    init {
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
                val intent = Intent(context, DnsVpnService::class.java).apply {
                    action = DnsVpnService.ACTION_START
                    putExtra(DnsVpnService.EXTRA_PRIMARY_DNS, profile.primaryDns)
                    putExtra(DnsVpnService.EXTRA_SECONDARY_DNS, profile.secondaryDns)
                    putExtra(DnsVpnService.EXTRA_PROFILE_NAME, profile.name)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    fun toggleVpn() {
        val currentSelected = _selectedProfile.value ?: return
        if (vpnState.value == VpnState.DISCONNECTED) {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_START
                putExtra(DnsVpnService.EXTRA_PRIMARY_DNS, currentSelected.primaryDns)
                putExtra(DnsVpnService.EXTRA_SECONDARY_DNS, currentSelected.secondaryDns)
                putExtra(DnsVpnService.EXTRA_PROFILE_NAME, currentSelected.name)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_STOP
            }
            context.startService(intent)
        }
    }

    fun forceStopVpn() {
        val intent = Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_STOP
        }
        context.startService(intent)
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
                        val intent = Intent(context, DnsVpnService::class.java).apply {
                            action = DnsVpnService.ACTION_START
                            putExtra(DnsVpnService.EXTRA_PRIMARY_DNS, nextProfile.primaryDns)
                            putExtra(DnsVpnService.EXTRA_SECONDARY_DNS, nextProfile.secondaryDns)
                            putExtra(DnsVpnService.EXTRA_PROFILE_NAME, nextProfile.name)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    } else {
                        forceStopVpn()
                    }
                }
            }
        }
    }

    fun saveProfile(id: Int, name: String, primary: String, secondary: String, isDefault: Boolean, isCustom: Boolean, onComplete: () -> Unit) {
        viewModelScope.launch {
            val profile = DnsProfile(
                id = id,
                name = name,
                primaryDns = primary,
                secondaryDns = secondary,
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
                    val intent = Intent(context, DnsVpnService::class.java).apply {
                        action = DnsVpnService.ACTION_START
                        putExtra(DnsVpnService.EXTRA_PRIMARY_DNS, primary)
                        putExtra(DnsVpnService.EXTRA_SECONDARY_DNS, secondary)
                        putExtra(DnsVpnService.EXTRA_PROFILE_NAME, name)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
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
                socket = DatagramSocket()
                socket.soTimeout = 1500 // 1.5 seconds timeout

                // Protect socket to bypass VPN if VPN is currently active
                if (vpnState.value == VpnState.CONNECTED) {
                    // Note: In normal JVM, we don't need to protect if we are just verifying external,
                    // but we protect to ensure standard routing. We can use standard socket protect.
                }

                val requestPacket = DatagramPacket(dnsQueryBytes, dnsQueryBytes.size, address, 53)
                socket.send(requestPacket)

                val responseBuffer = ByteArray(512)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(responsePacket)

                val endTime = System.currentTimeMillis()
                val rtt = (endTime - startTime).toInt()
                _pingResult.value = rtt
            } catch (e: Exception) {
                Log.w("DnsViewModel", "Failed to ping DNS server: $targetServer", e)
                _pingResult.value = null // represent Timeout/Offline
            } finally {
                socket?.close()
                _isPinging.value = false
            }
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

