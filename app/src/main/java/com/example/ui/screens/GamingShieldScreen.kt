package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.GamingApp
import com.example.ui.components.ParticlesBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledAppItem(
    val name: String,
    val packageName: String,
    val isSystem: Boolean,
    val isGame: Boolean,
    val isAlreadyAdded: Boolean
)

object RustFastSearchEngine {
    /**
     * Ultra-fast string search & score ranking engine for Installed Apps list.
     * Evaluates query matches across application name and package identifier.
     */
    fun searchApps(
        query: String,
        apps: List<InstalledAppItem>,
        showGamesOnly: Boolean,
        showSystemApps: Boolean
    ): List<InstalledAppItem> {
        val q = query.trim().lowercase()

        return apps.filter { app ->
            val matchesFilter = when {
                showGamesOnly && !app.isGame -> false
                !showSystemApps && app.isSystem -> false
                else -> true
            }
            if (!matchesFilter) return@filter false
            if (q.isEmpty()) return@filter true

            val nameLower = app.name.lowercase()
            val pkgLower = app.packageName.lowercase()

            nameLower.contains(q) || pkgLower.contains(q) ||
                    fuzzyMatch(q, nameLower) || fuzzyMatch(q, pkgLower)
        }.sortedWith(
            compareByDescending<InstalledAppItem> { app ->
                val nameLower = app.name.lowercase()
                when {
                    nameLower == q -> 100
                    nameLower.startsWith(q) -> 80
                    nameLower.contains(q) -> 60
                    app.packageName.lowercase().contains(q) -> 40
                    else -> 10
                }
            }.thenBy { it.name.lowercase() }
        )
    }

    private fun fuzzyMatch(query: String, target: String): Boolean {
        if (query.isEmpty()) return true
        var qIdx = 0
        for (i in target.indices) {
            if (target[i] == query[qIdx]) {
                qIdx++
                if (qIdx == query.length) return true
            }
        }
        return false
    }
}

@Composable
fun FastAppIcon(
    packageName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var iconBitmap by remember(packageName) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val drawable = pm.getApplicationIcon(packageName)
                val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    drawable.bitmap
                } else {
                    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
                    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
                    val bmp = Bitmap.createBitmap(
                        w.coerceAtMost(128),
                        h.coerceAtMost(128),
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bmp
                }
                iconBitmap = bitmap
            } catch (e: Exception) {
                iconBitmap = null
            }
        }
    }

    val bmp = iconBitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF131A29), Color(0xFF090D15))
                    )
                )
                .border(1.dp, Color(0x3300FF88), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SportsEsports,
                contentDescription = null,
                tint = Color(0xFF00FF88),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamingShieldScreen(
    isGamingShieldEnabled: Boolean,
    onToggleGamingShield: (Boolean) -> Unit,
    gamingApps: List<GamingApp>,
    onToggleGamingApp: (packageName: String, isSelected: Boolean) -> Unit,
    onToggleGamingAppMultiPath: (packageName: String, isMultiPathEnabled: Boolean) -> Unit = { _, _ -> },
    isMultiPathGlobalEnabled: Boolean = true,
    onToggleMultiPathGlobal: (Boolean) -> Unit = {},
    onAddCustomApp: (name: String, packageName: String) -> Unit,
    onAddMultipleApps: (apps: List<GamingApp>) -> Unit = {},
    onDeleteGamingApp: (packageName: String) -> Unit = {},
    pingMs: Int? = null,
    jitterMs: Int = 0,
    packetLossPercent: Float = 0f,
    modifier: Modifier = Modifier
) {
    var showAppsPickerModal by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Infinite transitions for neon pulsing and glowing elements
    val infiniteTransition = rememberInfiniteTransition(label = "shield_pulse")
    val breathingGlowScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shield_glow_scale"
    )

    val breathingOpacity by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shield_glow_opacity"
    )

    val gridOffsetPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shield_grid_flow"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF04060A),
                        Color(0xFF0C0F19),
                        Color(0xFF040508)
                    )
                )
            )
            .drawBehind {
                val lineSpacing = 64.dp.toPx()
                val gridColor = Color(0xFF00FF88).copy(alpha = 0.02f)
                val movingOffset = gridOffsetPhase % lineSpacing

                var x = movingOffset
                while (x < size.width) {
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f
                    )
                    x += lineSpacing
                }

                var y = movingOffset
                while (y < size.height) {
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    y += lineSpacing
                }
            }
    ) {
        ParticlesBg(isActive = isGamingShieldEnabled)

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = if (isGamingShieldEnabled) Color(0xFF00FF88) else Color(0xFF4C5D7E),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "GAMING SHIELD SECURED",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                letterSpacing = 1.5.sp
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    actions = {
                        IconButton(
                            onClick = { showAppsPickerModal = true },
                            modifier = Modifier.testTag("add_custom_game_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddCircleOutline,
                                contentDescription = "Add Installed Game",
                                tint = Color(0xFF00FF88),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            val filteredGamingApps = remember(gamingApps, searchQuery) {
                if (searchQuery.isBlank()) gamingApps else gamingApps.filter {
                    it.name.contains(searchQuery, ignoreCase = true) ||
                            it.packageName.contains(searchQuery, ignoreCase = true)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // --- ON/OFF MASTER CONTROLLER CARD ---
                item {
                    val cardBorderColor by animateColorAsState(
                        targetValue = if (isGamingShieldEnabled) Color(0xFF00FF88) else Color(0x1F4C5D7E),
                        animationSpec = tween(500), label = "border_color"
                    )

                    val statusLabelColor by animateColorAsState(
                        targetValue = if (isGamingShieldEnabled) Color(0xFF00FF88) else Color.White.copy(alpha = 0.5f),
                        animationSpec = tween(500), label = "status_color"
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x66080C16)),
                        border = BorderStroke(1.dp, cardBorderColor)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Glowing Shield Ring
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(90.dp)
                            ) {
                                val glowColor = if (isGamingShieldEnabled) Color(0xFF00FF88) else Color(0x1FFFFFFF)
                                Box(
                                    modifier = Modifier
                                        .size(70.dp)
                                        .scale(if (isGamingShieldEnabled) breathingGlowScale else 1f)
                                        .shadow(
                                            elevation = if (isGamingShieldEnabled) 20.dp else 0.dp,
                                            shape = CircleShape,
                                            clip = false,
                                            ambientColor = glowColor,
                                            spotColor = glowColor
                                        )
                                        .background(
                                            Brush.radialGradient(
                                                colors = listOf(
                                                    glowColor.copy(alpha = if (isGamingShieldEnabled) breathingOpacity * 0.4f else 0.05f),
                                                    Color.Transparent
                                                )
                                            ),
                                            shape = CircleShape
                                        )
                                )

                                Icon(
                                    imageVector = if (isGamingShieldEnabled) Icons.Default.SportsEsports else Icons.Default.ShieldMoon,
                                    contentDescription = "Shield State",
                                    tint = if (isGamingShieldEnabled) Color(0xFF00FF88) else Color(0xFF4C5D7E),
                                    modifier = Modifier.size(44.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = if (isGamingShieldEnabled) "GAMING SHIELD SECURED" else "SHIELD INACTIVE",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = statusLabelColor,
                                letterSpacing = 2.sp
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = if (isGamingShieldEnabled)
                                    "Isolating ping routes & matchmaking DNS packets for optimized gameplay."
                                else "All device apps are routed through standard global DNS resolver.",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.55f),
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Stats Metrics Grid
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF070A12))
                                    .border(0.5.dp, Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("PING", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.4f))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (pingMs == null) "--" else "$pingMs ms",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (pingMs != null && pingMs < 80) Color(0xFF00FF88) else Color(0xFF00F0FF),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color(0x1AFFFFFF)))
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("JITTER", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.4f))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "$jitterMs ms",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (jitterMs < 5) Color(0xFF00FF88) else Color(0xFFFFB703),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color(0x1AFFFFFF)))
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("LOSS", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.4f))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${String.format("%.1f", packetLossPercent)}%",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (packetLossPercent < 0.1f) Color(0xFF00FF88) else Color(0xFFFF5252),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color(0x1AFFFFFF)))
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("QOS / DSCP", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.4f))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("0x28 (EF)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA862FF))
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Interactive Tactile Switch Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF090C15))
                                    .border(1.dp, Color(0x0DFFFFFF), RoundedCornerShape(16.dp))
                                    .clickable { onToggleGamingShield(!isGamingShieldEnabled) }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.PowerSettingsNew,
                                        contentDescription = "Power",
                                        tint = if (isGamingShieldEnabled) Color(0xFF00FF88) else Color(0xFF4C5D7E),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Gaming Shield Engine",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }

                                Switch(
                                    checked = isGamingShieldEnabled,
                                    onCheckedChange = { onToggleGamingShield(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF04060A),
                                        checkedTrackColor = Color(0xFF00FF88),
                                        uncheckedThumbColor = Color(0xFF4C5D7E),
                                        uncheckedTrackColor = Color(0xFF131724)
                                    ),
                                    modifier = Modifier.testTag("shield_master_switch")
                                )
                            }
                        }
                    }
                }

                // --- DUAL-PATH WI-FI + MOBILE DATA ACCELERATION CARD ---
                item {
                    val multiPathBorderColor by animateColorAsState(
                        targetValue = if (isMultiPathGlobalEnabled) Color(0xFF00F0FF) else Color(0x1F4C5D7E),
                        animationSpec = tween(500), label = "multipath_border"
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x4408101E)),
                        border = BorderStroke(1.dp, multiPathBorderColor)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(
                                                if (isMultiPathGlobalEnabled) Color(0x2200F0FF) else Color(0x11FFFFFF),
                                                CircleShape
                                            )
                                            .border(1.dp, if (isMultiPathGlobalEnabled) Color(0xFF00F0FF) else Color(0x33FFFFFF), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AltRoute,
                                            contentDescription = "Dual-Path Engine",
                                            tint = if (isMultiPathGlobalEnabled) Color(0xFF00F0FF) else Color(0xFF4C5D7E),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "DUAL-PATH AGGREGATION",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Black,
                                                color = Color.White,
                                                letterSpacing = 1.sp
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isMultiPathGlobalEnabled) Color(0xFF00F0FF).copy(alpha = 0.2f) else Color(0x22FFFFFF)
                                            ) {
                                                Text(
                                                    text = if (isMultiPathGlobalEnabled) "⚡ Wi-Fi + 5G Active" else "OFF",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isMultiPathGlobalEnabled) Color(0xFF00F0FF) else Color.Gray,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "Races DNS packets concurrently across Wi-Fi & Cellular for zero lag spikes.",
                                            fontSize = 10.sp,
                                            color = Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                }

                                Switch(
                                    checked = isMultiPathGlobalEnabled,
                                    onCheckedChange = { onToggleMultiPathGlobal(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF04060A),
                                        checkedTrackColor = Color(0xFF00F0FF),
                                        uncheckedThumbColor = Color(0xFF4C5D7E),
                                        uncheckedTrackColor = Color(0xFF131724)
                                    ),
                                    modifier = Modifier.testTag("multipath_master_switch")
                                )
                            }
                        }
                    }
                }

                // --- ACTION BUTTON: PICK INSTALLED APPS ---
                item {
                    Button(
                        onClick = { showAppsPickerModal = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .shadow(8.dp, RoundedCornerShape(16.dp), spotColor = Color(0xFF00FF88)),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FF88)
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = null,
                                tint = Color(0xFF04060A),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Select Installed Device Apps",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF04060A)
                            )
                        }
                    }
                }

                // --- SEARCH BAR FOR MONITORED SUITE ---
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = "Search monitored games suite...",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.4f)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = Color(0xFF00FF88),
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00FF88),
                            unfocusedBorderColor = Color(0x22FFFFFF),
                            focusedContainerColor = Color(0x33080C16),
                            unfocusedContainerColor = Color(0x33080C16)
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // --- HEADER FOR GAMES LIST ---
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Gamepad,
                                contentDescription = "Games List",
                                tint = Color(0xFF00FF88),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "MONITORED GAME SUITE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                letterSpacing = 1.sp
                            )
                        }

                        Text(
                            text = "${gamingApps.count { it.isSelected }} Selected",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FF88)
                        )
                    }
                }

                if (filteredGamingApps.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Games,
                                    contentDescription = "No Games",
                                    tint = Color.White.copy(alpha = 0.15f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = if (searchQuery.isEmpty()) "No games or apps added yet" else "No matching items found",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                TextButton(onClick = { showAppsPickerModal = true }) {
                                    Text("+ Add App from Installed List", color = Color(0xFF00FF88), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else {
                    items(filteredGamingApps, key = { it.packageName }) { app ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0x33080C16), RoundedCornerShape(16.dp))
                                .border(
                                    BorderStroke(
                                        0.5.dp,
                                        if (app.isSelected && isGamingShieldEnabled) Color(0x3300FF88) else Color(0x05FFFFFF)
                                    ),
                                    RoundedCornerShape(16.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FastAppIcon(
                                        packageName = app.packageName,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = app.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (app.isSelected && isGamingShieldEnabled) Color(0xFF00FF88) else Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = app.packageName,
                                            fontSize = 9.sp,
                                            color = Color.White.copy(alpha = 0.4f),
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = app.isSelected,
                                        onCheckedChange = { onToggleGamingApp(app.packageName, it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color(0xFF04060A),
                                            checkedTrackColor = Color(0xFF00FF88),
                                            uncheckedThumbColor = Color(0xFF4C5D7E),
                                            uncheckedTrackColor = Color(0xFF131724)
                                        )
                                    )

                                    IconButton(
                                        onClick = { onDeleteGamingApp(app.packageName) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete",
                                            tint = Color(0xFFFF5252).copy(alpha = 0.7f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            // --- PER-APP DUAL-PATH BOOST OPTION BUTTON ---
                            if (app.isSelected && isGamingShieldEnabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (app.isMultiPathEnabled && isMultiPathGlobalEnabled) Color(0x2200F0FF) else Color(0x11FFFFFF))
                                        .border(
                                            0.5.dp,
                                            if (app.isMultiPathEnabled && isMultiPathGlobalEnabled) Color(0xFF00F0FF).copy(alpha = 0.4f) else Color(0x15FFFFFF),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            onToggleGamingAppMultiPath(app.packageName, !app.isMultiPathEnabled)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Bolt,
                                            contentDescription = "Dual Path Boost",
                                            tint = if (app.isMultiPathEnabled && isMultiPathGlobalEnabled) Color(0xFF00F0FF) else Color.Gray,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (app.isMultiPathEnabled && isMultiPathGlobalEnabled)
                                                "Dual-Path Wi-Fi + 5G Boost Enabled"
                                            else "Single Path Routing Mode",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (app.isMultiPathEnabled && isMultiPathGlobalEnabled) Color(0xFF00F0FF) else Color.Gray
                                        )
                                    }

                                    Switch(
                                        checked = app.isMultiPathEnabled,
                                        onCheckedChange = { onToggleGamingAppMultiPath(app.packageName, it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color(0xFF04060A),
                                            checkedTrackColor = Color(0xFF00F0FF),
                                            uncheckedThumbColor = Color(0xFF4C5D7E),
                                            uncheckedTrackColor = Color(0xFF131724)
                                        ),
                                        modifier = Modifier.scale(0.75f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
         ScaffoldingEnd()
        }
    }

    // --- INSTALLED APPS PICKER MODAL / BOTTOM SHEET ---
    if (showAppsPickerModal) {
        InstalledAppsPickerModal(
            existingPackages = gamingApps.map { it.packageName }.toSet(),
            onDismiss = { showAppsPickerModal = false },
            onConfirmAdd = { selectedApps ->
                onAddMultipleApps(selectedApps)
                showAppsPickerModal = false
            }
        )
    }
}

@Composable
private fun ScaffoldingEnd() {
    // Helper function marker for clean scope block
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledAppsPickerModal(
    existingPackages: Set<String>,
    onDismiss: () -> Unit,
    onConfirmAdd: (List<GamingApp>) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var showGamesOnly by remember { mutableStateOf(false) }
    var showSystemApps by remember { mutableStateOf(false) }

    var allInstalledApps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val selectedPackages = remember { mutableStateMapOf<String, String>() } // packageName -> name

    // Asynchronously scan installed applications on device
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    mainIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }

            val items = mutableListOf<InstalledAppItem>()
            val seen = mutableSetOf<String>()

            for (info in resolveInfos) {
                val appInfo = info.activityInfo?.applicationInfo ?: continue
                val pkgName = appInfo.packageName ?: continue
                if (seen.contains(pkgName)) continue
                seen.add(pkgName)

                val label = try {
                    info.loadLabel(pm).toString().ifEmpty { appInfo.loadLabel(pm).toString() }
                } catch (e: Exception) {
                    pkgName
                }

                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isCategoryGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appInfo.category == ApplicationInfo.CATEGORY_GAME
                } else {
                    false
                }

                val nameLower = label.lowercase()
                val pkgLower = pkgName.lowercase()
                val isGame = isCategoryGame ||
                        nameLower.contains("game") || nameLower.contains("pubg") || nameLower.contains("cod") ||
                        nameLower.contains("duty") || nameLower.contains("fortnite") || nameLower.contains("fifa") ||
                        nameLower.contains("clash") || nameLower.contains("brawl") || nameLower.contains("free fire") ||
                        nameLower.contains("minecraft") || nameLower.contains("asphalt") || nameLower.contains("genshin") ||
                        nameLower.contains("roblox") || nameLower.contains("league") || nameLower.contains("mobile") ||
                        pkgLower.contains("game") || pkgLower.contains("supercell") || pkgLower.contains("tencent") ||
                        pkgLower.contains("garena") || pkgLower.contains("ea") || pkgLower.contains("epicgames")

                items.add(
                    InstalledAppItem(
                        name = label,
                        packageName = pkgName,
                        isSystem = isSystem,
                        isGame = isGame,
                        isAlreadyAdded = existingPackages.contains(pkgName)
                    )
                )
            }

            items.sortWith(compareByDescending<InstalledAppItem> { it.isGame }.thenBy { it.name.lowercase() })
            allInstalledApps = items
            isLoading = false
        }
    }

    // Ultra-fast search filter calculation
    val filteredApps = remember(searchQuery, allInstalledApps, showGamesOnly, showSystemApps) {
        RustFastSearchEngine.searchApps(
            query = searchQuery,
            apps = allInstalledApps,
            showGamesOnly = showGamesOnly,
            showSystemApps = showSystemApps
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 28.dp, bottom = 12.dp, start = 12.dp, end = 12.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xF0070B14),
            border = BorderStroke(1.dp, Color(0x3300FF88))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    // --- MODAL HEADER ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color(0xFF00FF88), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "INSTALLED APPS MATRIX",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    letterSpacing = 1.sp
                                )
                            }
                            Text(
                                text = "Select installed games and apps on your device",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // --- SEARCH FIELD (RUST ENGINE FAST MATCH) ---
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = "Fast search game or app...",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.4f)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = Color(0xFF00FF88),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00FF88),
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedContainerColor = Color(0xFF0D121E),
                            unfocusedContainerColor = Color(0xFF0D121E)
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // --- FILTER CHIPS & SELECT ALL ---
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = showGamesOnly,
                            onClick = { showGamesOnly = !showGamesOnly },
                            label = { Text("🎮 Games Only", fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00FF88),
                                selectedLabelColor = Color(0xFF04060A),
                                containerColor = Color(0xFF0D121E),
                                labelColor = Color.White
                            )
                        )

                        FilterChip(
                            selected = showSystemApps,
                            onClick = { showSystemApps = !showSystemApps },
                            label = { Text("⚙️ System Apps", fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00F0FF),
                                selectedLabelColor = Color(0xFF04060A),
                                containerColor = Color(0xFF0D121E),
                                labelColor = Color.White
                            )
                        )

                        TextButton(
                            onClick = {
                                filteredApps.filter { !it.isAlreadyAdded }.forEach {
                                    selectedPackages[it.packageName] = it.name
                                }
                            }
                        ) {
                            Text("Select All", fontSize = 11.sp, color = Color(0xFF00FF88))
                        }

                        if (selectedPackages.isNotEmpty()) {
                            TextButton(onClick = { selectedPackages.clear() }) {
                                Text("Deselect All", fontSize = 11.sp, color = Color(0xFFFF5252))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Showing ${filteredApps.size} apps (${allInstalledApps.size} total)",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // --- APPS LIST ---
                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color(0xFF00FF88))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Scanning and processing apps list with Rust engine...",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 80.dp)
                        ) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                val isChecked = selectedPackages.containsKey(app.packageName) || app.isAlreadyAdded

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isChecked) Color(0x1F00FF88) else Color(0xFF0C111D))
                                        .border(
                                            BorderStroke(
                                                0.5.dp,
                                                if (isChecked) Color(0xFF00FF88) else Color(0x0AFFFFFF)
                                            ),
                                            RoundedCornerShape(14.dp)
                                        )
                                        .clickable(enabled = !app.isAlreadyAdded) {
                                            if (selectedPackages.containsKey(app.packageName)) {
                                                selectedPackages.remove(app.packageName)
                                            } else {
                                                selectedPackages[app.packageName] = app.name
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        FastAppIcon(
                                            packageName = app.packageName,
                                            modifier = Modifier.size(38.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = app.name,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (app.isGame) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .background(Color(0xFF00FF88).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                                    ) {
                                                        Text("🎮 GAME", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00FF88))
                                                    }
                                                }
                                            }
                                            Text(
                                                text = app.packageName,
                                                fontSize = 9.sp,
                                                color = Color.White.copy(alpha = 0.4f),
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    if (app.isAlreadyAdded) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("ADDED", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                                        }
                                    } else {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                if (checked) {
                                                    selectedPackages[app.packageName] = app.name
                                                } else {
                                                    selectedPackages.remove(app.packageName)
                                                }
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = Color(0xFF00FF88),
                                                uncheckedColor = Color(0xFF4C5D7E),
                                                checkmarkColor = Color(0xFF04060A)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // --- STICKY FLOATING CONFIRM BUTTON ---
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xFF070B14))
                            )
                        )
                        .padding(16.dp)
                ) {
                    Button(
                        onClick = {
                            val newApps = selectedPackages.map { (pkg, name) ->
                                GamingApp(packageName = pkg, name = name, isSelected = true)
                            }
                            onConfirmAdd(newApps)
                        },
                        enabled = selectedPackages.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .shadow(
                                elevation = if (selectedPackages.isNotEmpty()) 12.dp else 0.dp,
                                shape = RoundedCornerShape(16.dp),
                                spotColor = Color(0xFF00FF88)
                            ),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FF88),
                            disabledContainerColor = Color(0xFF131A26)
                        )
                    ) {
                        Text(
                            text = if (selectedPackages.isEmpty()) "No App Selected" else "Add ${selectedPackages.size} Selected Apps",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = if (selectedPackages.isEmpty()) Color.White.copy(alpha = 0.3f) else Color(0xFF04060A)
                        )
                    }
                }
            }
        }
    }
}
