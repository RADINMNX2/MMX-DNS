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
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Gamepad
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import com.example.ui.viewmodel.DnsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: DnsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(0) } // 0 = Dashboard, 1 = Gaming Shield, 2 = Profiles, 3 = Logs

    // Collect all states from ViewModel
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val isAppInForeground by viewModel.isAppInForeground.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.setAppInForeground(false)
            } else if (event == Lifecycle.Event.ON_START) {
                viewModel.setAppInForeground(true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
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
    val isGamingShieldEnabled by viewModel.isGamingShieldEnabled.collectAsStateWithLifecycle()

    // VPN Permission Launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Permission granted, toggle VPN
            viewModel.toggleVpn()
        }
    }

    if (!isAppInForeground) {
        // Complete background stasis: stop rendering UI tree, halt animations, clear state recomposition
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF070913))
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            // High-fidelity Glassmorphic Navigation Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xE60E121E))
                    .border(BorderStroke(0.5.dp, Color(0x33FFFFFF)), RoundedCornerShape(24.dp))
                    .testTag("bottom_nav_bar")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
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
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Core",
                            fontSize = 11.sp,
                            fontWeight = if (isDashboardActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isDashboardActive) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.5f)
                        )
                    }

                    // Gaming Shield Tab button
                    val isGamingShieldActive = currentTab == 1
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { currentTab = 1 }
                            .padding(vertical = 10.dp)
                            .testTag("tab_gaming_shield")
                    ) {
                        Icon(
                            imageVector = if (isGamingShieldActive) Icons.Filled.Gamepad else Icons.Outlined.Gamepad,
                            contentDescription = "Gaming Shield",
                            tint = if (isGamingShieldActive) Color(0xFF00FF88) else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Shield",
                            fontSize = 11.sp,
                            fontWeight = if (isGamingShieldActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isGamingShieldActive) Color(0xFF00FF88) else Color.White.copy(alpha = 0.5f)
                        )
                    }

                    // Profiles Tab button
                    val isProfilesActive = currentTab == 2
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { currentTab = 2 }
                            .padding(vertical = 10.dp)
                            .testTag("tab_profiles")
                    ) {
                        Icon(
                            imageVector = if (isProfilesActive) Icons.Filled.Dns else Icons.Outlined.Dns,
                            contentDescription = "DNS Profiles",
                            tint = if (isProfilesActive) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(22.dp)
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
                    val isLogsActive = currentTab == 3
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { currentTab = 3 }
                            .padding(vertical = 10.dp)
                            .testTag("tab_logs")
                    ) {
                        Icon(
                            imageVector = if (isLogsActive) Icons.Filled.History else Icons.Outlined.History,
                            contentDescription = "Engine Logs",
                            tint = if (isLogsActive) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(22.dp)
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
                        onToggleVpn = {
                            val vpnPrepareIntent = VpnService.prepare(context)
                            if (vpnPrepareIntent != null) {
                                vpnPermissionLauncher.launch(vpnPrepareIntent)
                            } else {
                                viewModel.toggleVpn()
                            }
                        }
                    )
                }
                1 -> {
                    GamingShieldScreen(
                        isGamingShieldEnabled = isGamingShieldEnabled,
                        onToggleGamingShield = { viewModel.setGamingShieldEnabled(it) },
                        gamingApps = gamingApps,
                        onToggleGamingApp = { packageName, isSelected ->
                            viewModel.toggleGamingAppSelection(packageName, isSelected)
                        },
                        onAddCustomApp = { name, packageName ->
                            viewModel.addCustomGamingApp(name, packageName)
                        }
                    )
                }
                2 -> {
                    ProfileScreen(
                        profiles = profiles,
                        currentSelectedId = selectedProfile?.id,
                        onSelectProfile = { viewModel.selectProfile(it) },
                        onDeleteProfile = { viewModel.deleteProfile(it) },
                        onSaveProfile = { id, name, primary, secondary, enableIpv6, primaryIpv6, secondaryIpv6, isDefault, isCustom, onComplete ->
                            viewModel.saveProfile(id, name, primary, secondary, enableIpv6, primaryIpv6, secondaryIpv6, isDefault, isCustom, onComplete)
                        }
                    )
                }
                3 -> {
                    val logs by viewModel.logs.collectAsStateWithLifecycle()
                    LogScreen(
                        logs = logs,
                        onClearLogs = { viewModel.clearLogs() }
                    )
                }
            }
        }
    }

    val pendingCrashLog by viewModel.pendingCrashLog.collectAsStateWithLifecycle()

    if (pendingCrashLog != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearPendingCrashLog() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning Icon",
                        tint = Color(0xFFFF0055),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "CRASH RECOVERY ACTIVE",
                        color = Color(0xFFFF0055),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "FluxDNS has intercepted an unexpected crash and safely recovered the process. The stack trace is recorded below:",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF07090F))
                            .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text(
                                    text = pendingCrashLog ?: "",
                                    color = Color(0xFFFF4D79),
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("FluxDNS Crash Report", pendingCrashLog)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "Crash report copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F0FF)),
                    modifier = Modifier.testTag("copy_crash_report_button")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Report Icon",
                            tint = Color(0xFF04060A),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Report", color = Color(0xFF04060A), fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.clearPendingCrashLog() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.6f)),
                    modifier = Modifier.testTag("clear_crash_report_button")
                ) {
                    Text("Clear & Close")
                }
            },
            containerColor = Color(0xFF0E121E),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.border(1.dp, Color(0x33FF0055), RoundedCornerShape(24.dp))
        )
    }
}
