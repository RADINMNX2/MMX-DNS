package com.example.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.example.data.DnsDatabase
import com.example.data.DnsProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

class DnsTileService : TileService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var listenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listenJob?.cancel()
        listenJob = serviceScope.launch {
            DnsVpnService.state.collectLatest { vpnState ->
                updateTileState(vpnState)
            }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateTileState(state: VpnState) {
        val tile = qsTile ?: return
        when (state) {
            VpnState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                val activeProfile = DnsVpnService.activeProfileName.value
                tile.label = if (activeProfile.isNotEmpty() && activeProfile != "None") activeProfile else "Active Shield"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Gaming Shield ON"
                }
            }
            VpnState.CONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Connecting..."
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Optimizing latency"
                }
            }
            VpnState.DISCONNECTED, VpnState.ERROR -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "DNS Shield"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Tap to secure"
                }
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val currentState = DnsVpnService.state.value
        if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
            // Stop VPN Service
            val intent = Intent(this, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_STOP
            }
            startService(intent)
        } else {
            // Start VPN Service with default profile
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val db = DnsDatabase.getDatabase(applicationContext)
                    val profiles = withTimeoutOrNull(2000) {
                        db.dnsProfileDao().getAllProfiles().first()
                    } ?: emptyList()
                    
                    val defaultProfile = db.dnsProfileDao().getDefaultProfile()
                        ?: profiles.firstOrNull { it.isDefault }
                        ?: profiles.firstOrNull()
                        ?: DnsProfile(name = "Cloudflare DNS", primaryDns = "1.1.1.1", secondaryDns = "1.0.0.1", isDefault = true)

                    withContext(Dispatchers.Main) {
                        val intent = Intent(this@DnsTileService, DnsVpnService::class.java).apply {
                            action = DnsVpnService.ACTION_START
                            putExtra(DnsVpnService.EXTRA_PRIMARY_DNS, defaultProfile.primaryDns)
                            putExtra(DnsVpnService.EXTRA_SECONDARY_DNS, defaultProfile.secondaryDns)
                            putExtra(DnsVpnService.EXTRA_PROFILE_NAME, defaultProfile.name)
                            putExtra(DnsVpnService.EXTRA_PROTOCOL, "UDP")
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DnsTileService", "Error resolving default profile from TileService", e)
                }
            }
        }
    }
}
