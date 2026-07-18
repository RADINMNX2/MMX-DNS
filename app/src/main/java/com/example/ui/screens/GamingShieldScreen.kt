package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.GamingApp
import com.example.ui.components.ParticlesBg

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamingShieldScreen(
    isGamingShieldEnabled: Boolean,
    onToggleGamingShield: (Boolean) -> Unit,
    gamingApps: List<GamingApp>,
    onToggleGamingApp: (packageName: String, isSelected: Boolean) -> Unit,
    onAddCustomApp: (name: String, packageName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }

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

    // Animated dynamic grid lines phase for the real-time grid background
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
                // Cyber Grid Render
                val lineSpacing = 64.dp.toPx()
                val gridColor = Color(0xFF00FF88).copy(alpha = 0.02f)
                val movingOffset = gridOffsetPhase % lineSpacing

                // Vertical Grid
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

                // Horizontal Grid
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
        // Star particle system background
        ParticlesBg(isActive = isGamingShieldEnabled)

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = "GAMING SHIELD",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 2.sp
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    actions = {
                        IconButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.testTag("add_custom_game_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Custom Game",
                                tint = Color(0xFF00FF88)
                            )
                        }
                    }
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
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
                            .padding(top = 10.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x66080C16)),
                        border = BorderStroke(1.dp, cardBorderColor)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Glowing Shield Icon
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(100.dp)
                            ) {
                                val glowColor = if (isGamingShieldEnabled) Color(0xFF00FF88) else Color(0x1FFFFFFF)
                                Box(
                                    modifier = Modifier
                                        .size(75.dp)
                                        .scale(if (isGamingShieldEnabled) breathingGlowScale else 1f)
                                        .shadow(
                                            elevation = if (isGamingShieldEnabled) 24.dp else 0.dp,
                                            shape = CircleShape,
                                            clip = false,
                                            ambientColor = glowColor,
                                            spotColor = glowColor
                                        )
                                        .background(
                                            Brush.radialGradient(
                                                colors = listOf(
                                                    glowColor.copy(alpha = if (isGamingShieldEnabled) breathingOpacity * 0.35f else 0.05f),
                                                    Color.Transparent
                                                )
                                            ),
                                            shape = CircleShape
                                        )
                                )

                                Icon(
                                    imageVector = if (isGamingShieldEnabled) Icons.Default.Shield else Icons.Default.ShieldMoon,
                                    contentDescription = "Shield State",
                                    tint = if (isGamingShieldEnabled) Color(0xFF00FF88) else Color(0xFF4C5D7E),
                                    modifier = Modifier.size(48.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = if (isGamingShieldEnabled) "GAMING SHIELD SECURED" else "SHIELD INACTIVE",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = statusLabelColor,
                                letterSpacing = 2.sp
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = if (isGamingShieldEnabled) 
                                    "Isolating ping routes & matchmaking DNS packets for optimized gameplay." 
                                    else "All device apps are routed through standard global DNS resolver.",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // Interactive Tactile Switch Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF090C15))
                                    .border(1.dp, Color(0x0DFFFFFF), RoundedCornerShape(16.dp))
                                    .clickable { onToggleGamingShield(!isGamingShieldEnabled) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
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
                                        text = "Toggle Gaming Shield",
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

                // --- SHIELD CAPABILITIES / EXPLANATION ---
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x33080C16)),
                        border = BorderStroke(1.dp, Color(0x0AFFFFFF))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Split Tunneling Info",
                                    tint = Color(0xFF00F0FF),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "WHAT IS APP-SPLIT TUNNELING?",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00F0FF),
                                    letterSpacing = 1.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "When enabled, the DNS changer only captures network requests made by selected games. All other applications (such as browsers, social media, and background downloads) bypass the DNS tunnel completely. This guarantees zero overhead, full physical speed, and native performance for non-gaming apps while keeping games secured with low-latency DNS routes.",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // --- HEADER FOR GAMES LIST ---
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
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

                if (gamingApps.isEmpty()) {
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
                                    text = "No Games Configured",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.35f),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                } else {
                    items(gamingApps, key = { it.packageName }) { app ->
                        Row(
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
                                .clickable { onToggleGamingApp(app.packageName, !app.isSelected) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (app.isSelected && isGamingShieldEnabled) Color(0xFF00FF88) else Color.White
                                )
                                Text(
                                    text = app.packageName,
                                    fontSize = 9.sp,
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Checkbox(
                                checked = app.isSelected,
                                onCheckedChange = { onToggleGamingApp(app.packageName, it) },
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

    // --- ADD CUSTOM GAME DIALOG ---
    if (showAddDialog) {
        var newName by remember { mutableStateOf("") }
        var newPkg by remember { mutableStateOf("") }
        var nameError by remember { mutableStateOf(false) }
        var pkgError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    text = "REGISTER NEW GAME",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = Color.White
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        text = "Add custom packages (e.g. com.epicgames.fortnite) to isolate their DNS paths.",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )

                    OutlinedTextField(
                        value = newName,
                        onValueChange = {
                            newName = it
                            nameError = false
                        },
                        label = { Text("Game Name") },
                        isError = nameError,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00FF88),
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedLabelColor = Color(0xFF00FF88),
                            unfocusedLabelColor = Color.White.copy(alpha = 0.4f)
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_game_name_input")
                    )

                    OutlinedTextField(
                        value = newPkg,
                        onValueChange = {
                            newPkg = it
                            pkgError = false
                        },
                        label = { Text("Package ID") },
                        isError = pkgError,
                        placeholder = { Text("com.company.game") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00FF88),
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedLabelColor = Color(0xFF00FF88),
                            unfocusedLabelColor = Color.White.copy(alpha = 0.4f)
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_game_package_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isBlank()) {
                            nameError = true
                        }
                        if (newPkg.isBlank() || !newPkg.contains(".")) {
                            pkgError = true
                        }

                        if (!nameError && !pkgError) {
                            onAddCustomApp(newName.trim(), newPkg.trim())
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF88)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("confirm_add_game_button")
                ) {
                    Text("ADD CORE", color = Color(0xFF04060A), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddDialog = false },
                    modifier = Modifier.testTag("cancel_add_game_button")
                ) {
                    Text("CANCEL", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF0D111A),
            shape = RoundedCornerShape(20.dp)
        )
    }
}
