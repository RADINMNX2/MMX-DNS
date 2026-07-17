package com.example.ui.screens

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.DnsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: DnsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(0) } // 0 = Dashboard, 1 = Profiles

    // Collect all states from ViewModel
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val activeProfileName by viewModel.activeProfileName.collectAsStateWithLifecycle()
    val activePrimaryDns by viewModel.activePrimaryDns.collectAsStateWithLifecycle()
    val activeSecondaryDns by viewModel.activeSecondaryDns.collectAsStateWithLifecycle()
    val selectedProfile by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val pingMs by viewModel.pingResult.collectAsStateWithLifecycle()
    val isPinging by viewModel.isPinging.collectAsStateWithLifecycle()
    val profiles by viewModel.allProfiles.collectAsStateWithLifecycle()
    val gamingApps by viewModel.allGamingApps.collectAsStateWithLifecycle()
    val totalQueriesResolved by viewModel.totalQueriesResolved.collectAsStateWithLifecycle()
    val connectionUptime by viewModel.connectionUptime.collectAsStateWithLifecycle()
    val isTurboEnabled by viewModel.isTurboEnabled.collectAsStateWithLifecycle()

    // VPN Permission Launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Permission granted, toggle VPN
            viewModel.toggleVpn()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            // High-fidelity Glassmorphic Navigation Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xE60E121E))
                    .border(BorderStroke(0.5.dp, Color(0x33FFFFFF)), RoundedCornerShape(24.dp))
                    .testTag("bottom_nav_bar")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Dashboard Tab button
                    val isDashboardActive = currentTab == 0
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { currentTab = 0 }
                            .padding(vertical = 10.dp)
                            .testTag("tab_dashboard")
                    ) {
                        Icon(
                            imageVector = if (isDashboardActive) Icons.Filled.Home else Icons.Outlined.Home,
                            contentDescription = "Home Dashboard",
                            tint = if (isDashboardActive) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Dashboard",
                            fontSize = 11.sp,
                            fontWeight = if (isDashboardActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isDashboardActive) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.5f)
                        )
                    }

                    // Profiles Tab button
                    val isProfilesActive = currentTab == 1
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { currentTab = 1 }
                            .padding(vertical = 10.dp)
                            .testTag("tab_profiles")
                    ) {
                        Icon(
                            imageVector = if (isProfilesActive) Icons.Filled.Dns else Icons.Outlined.Dns,
                            contentDescription = "DNS Profiles",
                            tint = if (isProfilesActive) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Profiles",
                            fontSize = 11.sp,
                            fontWeight = if (isProfilesActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isProfilesActive) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.5f)
                        )
                    }

                    // Logs Tab button
                    val isLogsActive = currentTab == 2
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { currentTab = 2 }
                            .padding(vertical = 10.dp)
                            .testTag("tab_logs")
                    ) {
                        Icon(
                            imageVector = if (isLogsActive) Icons.Filled.History else Icons.Outlined.History,
                            contentDescription = "Engine Logs",
                            tint = if (isLogsActive) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Logs",
                            fontSize = 11.sp,
                            fontWeight = if (isLogsActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isLogsActive) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        },
        containerColor = Color.Transparent // Allow background gradients to show
    ) { innerPadding ->
        // Render current screen with high-end sliding/fading transition
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = {
                if (targetState > initialState) {
                    // Slide left on forward nav
                    (slideInHorizontally(animationSpec = tween(400)) { width -> width / 3 } + fadeIn(animationSpec = tween(400))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(350)) { width -> -width / 3 } + fadeOut(animationSpec = tween(350)))
                } else {
                    // Slide right on backward nav
                    (slideInHorizontally(animationSpec = tween(400)) { width -> -width / 3 } + fadeIn(animationSpec = tween(400))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(350)) { width -> width / 3 } + fadeOut(animationSpec = tween(350)))
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()), // Custom bottom padding handling to avoid bottom bar overlap
            label = "tab_transitions"
        ) { tab ->
            when (tab) {
                0 -> {
                    DashboardScreen(
                        vpnState = vpnState,
                        activeProfileName = activeProfileName,
                        activePrimaryDns = activePrimaryDns,
                        activeSecondaryDns = activeSecondaryDns,
                        selectedProfile = selectedProfile,
                        pingMs = pingMs,
                        isPinging = isPinging,
                        totalQueriesResolved = totalQueriesResolved,
                        connectionUptime = connectionUptime,
                        isTurboEnabled = isTurboEnabled,
                        onToggleTurbo = { viewModel.setTurboEnabled(it) },
                        onToggleVpn = {
                            val vpnPrepareIntent = VpnService.prepare(context)
                            if (vpnPrepareIntent != null) {
                                vpnPermissionLauncher.launch(vpnPrepareIntent)
                            } else {
                                viewModel.toggleVpn()
                            }
                        },
                        gamingApps = gamingApps,
                        onToggleGamingApp = { packageName, isSelected ->
                            viewModel.toggleGamingAppSelection(packageName, isSelected)
                        }
                    )
                }
                1 -> {
                    ProfileScreen(
                        profiles = profiles,
                        currentSelectedId = selectedProfile?.id,
                        onSelectProfile = { viewModel.selectProfile(it) },
                        onDeleteProfile = { viewModel.deleteProfile(it) },
                        onSaveProfile = { id, name, primary, secondary, isDefault, isCustom, onComplete ->
                            viewModel.saveProfile(id, name, primary, secondary, isDefault, isCustom, onComplete)
                        }
                    )
                }
                2 -> {
                    val logs by viewModel.logs.collectAsStateWithLifecycle()
                    LogScreen(
                        logs = logs,
                        onClearLogs = { viewModel.clearLogs() }
                    )
                }
            }
        }
    }
}
