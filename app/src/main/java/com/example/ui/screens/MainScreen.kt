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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.RedPrimary
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.AccentPurple
import com.example.ui.viewmodel.DnsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: DnsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(0) } // 0 = Dashboard, 1 = Gaming Shield, 2 = Profiles, 3 = Logs, 4 = Settings

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
    val jitterMs by viewModel.jitterMs.collectAsStateWithLifecycle()
    val packetLossPercent by viewModel.packetLossPercent.collectAsStateWithLifecycle()
    val isPinging by viewModel.isPinging.collectAsStateWithLifecycle()
    val profiles by viewModel.allProfiles.collectAsStateWithLifecycle()
    val gamingApps by viewModel.allGamingApps.collectAsStateWithLifecycle()
    val totalQueriesResolved by viewModel.totalQueriesResolved.collectAsStateWithLifecycle()
    val connectionUptime by viewModel.connectionUptime.collectAsStateWithLifecycle()
    val isGamingShieldEnabled by viewModel.isGamingShieldEnabled.collectAsStateWithLifecycle()
    val isMultiPathGlobalEnabled by viewModel.isMultiPathGlobalEnabled.collectAsStateWithLifecycle()

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
            viewModel.toggleVpn()
        }
    }

    if (!isAppInForeground) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(DarkBg)
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(DarkSurface.copy(alpha = 0.95f))
                    .border(BorderStroke(1.dp, DarkBorder), RoundedCornerShape(24.dp))
                    .testTag("bottom_nav_bar")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Dashboard Tab
                    val isDashActive = currentTab == 0
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { currentTab = 0 }
                            .padding(vertical = 8.dp)
                            .testTag("tab_dashboard")
                    ) {
                        Icon(
                            imageVector = if (isDashActive) Icons.Filled.Dns else Icons.Outlined.Dns,
                            contentDescription = "Dashboard",
                            tint = if (isDashActive) RedPrimary else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Engine",
                            fontSize = 11.sp,
                            fontWeight = if (isDashActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isDashActive) RedPrimary else Color.White.copy(alpha = 0.5f)
                        )
                    }

                    // Gaming Shield Tab
                    val isShieldActive = currentTab == 1
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { currentTab = 1 }
                            .padding(vertical = 8.dp)
                            .testTag("tab_shield")
                    ) {
                        Icon(
                            imageVector = if (isShieldActive) Icons.Filled.Gamepad else Icons.Outlined.Gamepad,
                            contentDescription = "Gaming Shield",
                            tint = if (isShieldActive) AccentPurple else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Shield",
                            fontSize = 11.sp,
                            fontWeight = if (isShieldActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isShieldActive) AccentPurple else Color.White.copy(alpha = 0.5f)
                        )
                    }

                    // Profiles Tab
                    val isProfilesActive = currentTab == 2
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { currentTab = 2 }
                            .padding(vertical = 8.dp)
                            .testTag("tab_profiles")
                    ) {
                        Icon(
                            imageVector = if (isProfilesActive) Icons.Filled.ListAlt else Icons.Outlined.ListAlt,
                            contentDescription = "Profiles",
                            tint = if (isProfilesActive) RedPrimary else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Profiles",
                            fontSize = 11.sp,
                            fontWeight = if (isProfilesActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isProfilesActive) RedPrimary else Color.White.copy(alpha = 0.5f)
                        )
                    }

                    // Logs Tab
                    val isLogsActive = currentTab == 3
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { currentTab = 3 }
                            .padding(vertical = 8.dp)
                            .testTag("tab_logs")
                    ) {
                        Icon(
                            imageVector = if (isLogsActive) Icons.Filled.Article else Icons.Outlined.Article,
                            contentDescription = "Logs",
                            tint = if (isLogsActive) RedPrimary else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Logs",
                            fontSize = 11.sp,
                            fontWeight = if (isLogsActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isLogsActive) RedPrimary else Color.White.copy(alpha = 0.5f)
                        )
                    }

                    // Settings Tab
                    val isSettingsActive = currentTab == 4
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { currentTab = 4 }
                            .padding(vertical = 8.dp)
                            .testTag("tab_settings")
                    ) {
                        Icon(
                            imageVector = if (isSettingsActive) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = if (isSettingsActive) RedPrimary else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Settings",
                            fontSize = 11.sp,
                            fontWeight = if (isSettingsActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSettingsActive) RedPrimary else Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        },
        containerColor = DarkBg
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = {
                (fadeIn(animationSpec = tween(250)) + slideInHorizontally(animationSpec = tween(250)) { width -> width / 4 }) togetherWith
                        (fadeOut(animationSpec = tween(200)) + slideOutHorizontally(animationSpec = tween(200)) { width -> -width / 4 })
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
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
                        onToggleGamingAppMultiPath = { packageName, isMultiPathEnabled ->
                            viewModel.toggleGamingAppMultiPath(packageName, isMultiPathEnabled)
                        },
                        isMultiPathGlobalEnabled = isMultiPathGlobalEnabled,
                        onToggleMultiPathGlobal = { viewModel.setMultiPathGlobalEnabled(it) },
                        onAddCustomApp = { name, packageName ->
                            viewModel.addCustomGamingApp(name, packageName)
                        },
                        onAddMultipleApps = { apps ->
                            viewModel.addMultipleGamingApps(apps)
                        },
                        onDeleteGamingApp = { packageName ->
                            viewModel.deleteGamingApp(packageName)
                        },
                        pingMs = pingMs,
                        jitterMs = jitterMs,
                        packetLossPercent = packetLossPercent
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
                4 -> {
                    SettingsScreen()
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
                        tint = RedPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "CRASH RECOVERY ACTIVE",
                        color = RedPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "MNX TOOLS intercepted an unexpected crash and safely recovered the process. The stack trace is recorded below:",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkBg)
                            .border(0.5.dp, DarkBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text(
                                    text = pendingCrashLog ?: "",
                                    color = RedPrimary,
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
                        val clip = android.content.ClipData.newPlainText("MNX TOOLS Crash Report", pendingCrashLog)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "Crash report copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedPrimary),
                    modifier = Modifier.testTag("copy_crash_report_button")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Report Icon",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Report", color = Color.White, fontWeight = FontWeight.Bold)
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
            containerColor = DarkSurface,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.border(1.dp, DarkBorder, RoundedCornerShape(24.dp))
        )
    }
}

