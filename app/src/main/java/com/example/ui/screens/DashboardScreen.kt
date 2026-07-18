@file:Suppress("DEPRECATION")
package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DnsProfile
import com.example.service.VpnState
import com.example.ui.components.ParticlesBg
import kotlin.math.sin

@Composable
fun DashboardScreen(
    vpnState: VpnState,
    activeProfileName: String,
    activePrimaryDns: String,
    activeSecondaryDns: String,
    selectedProfile: DnsProfile?,
    pingMs: Int?,
    isPinging: Boolean,
    totalQueriesResolved: Int,
    connectionUptime: String,
    isTurboEnabled: Boolean,
    onToggleTurbo: (Boolean) -> Unit,
    onToggleVpn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = vpnState == VpnState.CONNECTED
    val isConnecting = vpnState == VpnState.CONNECTING

    // Infinite transitions for neon pulsing and glowing elements
    val infiniteTransition = rememberInfiniteTransition(label = "core_pulse")
    val breathingGlowScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_scale"
    )

    val breathingOpacity by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_opacity"
    )

    // Animated dynamic grid lines phase for the real-time grid background
    val gridOffsetPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "grid_flow"
    )

    // Holographic Orbit Rotations
    val orbitRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnected) 4000 else 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_rotation_forward"
    )

    val innerOrbitRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnected) 6000 else 24000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_rotation_reverse"
    )

    // --- Dynamic Latency Wave System ---
    // Smooth transitions for wave physical properties mapping to live ping responsiveness
    val targetAmplitude = when {
        !isConnected -> 2f
        pingMs == null -> 4f
        pingMs < 60 -> 7f       // Calm wave for low latency
        pingMs < 150 -> 14f    // Moderate wave
        else -> 22f            // Heavy turbulent wave for high latency
    }

    val targetFrequency = when {
        !isConnected -> 1f
        pingMs == null -> 1.5f
        pingMs < 60 -> 4.5f     // Clean high density cycles
        pingMs < 150 -> 2.5f
        else -> 1.4f           // Slow massive surges
    }

    val targetSpeedMs = when {
        !isConnected -> 6000
        pingMs == null -> 4000
        pingMs < 60 -> 1200     // Faster motion rate
        pingMs < 150 -> 2200
        else -> 3500
    }

    val waveAmplitude by animateFloatAsState(
        targetValue = targetAmplitude,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "wave_amp"
    )

    val waveFrequency by animateFloatAsState(
        targetValue = targetFrequency,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "wave_freq"
    )

    val waveDuration by animateIntAsState(
        targetValue = targetSpeedMs,
        animationSpec = tween(500),
        label = "wave_speed"
    )

    val wavePhaseSpec = rememberInfiniteTransition(label = "wave_oscillator")
    val wavePhase by wavePhaseSpec.animateFloat(
        initialValue = 0f,
        targetValue = 2 * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(waveDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    // Dynamic wave gradient based on connection status and latency
    val waveColors = when {
        !isConnected -> listOf(Color(0x224C5D7E), Color(0x054C5D7E))
        pingMs == null -> listOf(Color(0xAA8A2BE2), Color(0x228A2BE2))
        pingMs < 60 -> listOf(Color(0xFF00FF88), Color(0xFF00F0FF))  // Cyber Teal-Green
        pingMs < 150 -> listOf(Color(0xFF00F0FF), Color(0xFF8A2BE2)) // Neon Blue-Purple
        else -> listOf(Color(0xFFFF3355), Color(0xFFFF9900))         // Warning Red-Orange
    }

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
                val gridColor = Color(0xFF00F0FF).copy(alpha = 0.025f)
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
        ParticlesBg(isActive = true)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            
            // --- HEADER PANEL ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "MNX DNS CORES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00F0FF).copy(alpha = 0.85f),
                    letterSpacing = 6.sp,
                    fontFamily = FontFamily.SansSerif
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .background(Color(0x44080C14), RoundedCornerShape(20.dp))
                        .border(1.dp, Color(0x11FFFFFF), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    val statusDotColor by animateColorAsState(
                        targetValue = if (isConnected) Color(0xFF00FF88) else if (isConnecting) Color(0xFFFFCC00) else Color(0xFF4C5D7E),
                        animationSpec = tween(500), label = "status_dot"
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(statusDotColor, CircleShape)
                            .graphicsLayer {
                                if (isConnected || isConnecting) {
                                    scaleX = breathingGlowScale
                                    scaleY = breathingGlowScale
                                }
                            }
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = if (isConnected) "MNX SHIELD SECURED" else if (isConnecting) "CONFIGURING TUNNEL..." else "DISCONNECTED",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isConnected) Color(0xFF00FF88) else if (isConnecting) Color(0xFFFFCC00) else Color.White.copy(alpha = 0.7f),
                        letterSpacing = 1.sp
                    )
                }
            }

            // --- CENTRAL MULTIDIMENSIONAL POWER CORE CONTROLLER ---
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(vertical = 24.dp)
                    .size(240.dp)
            ) {
                // Orbit Layer 1: Forward Orbit Ring
                Canvas(
                    modifier = Modifier
                        .size(210.dp)
                        .graphicsLayer { rotationZ = orbitRotation }
                ) {
                    val baseOrbitColor = if (isConnected) Color(0xFF00F0FF).copy(alpha = 0.2f) else Color(0x0AFFFFFF)
                    val arcHighlightColor = if (isConnected) Color(0xFF00FF88).copy(alpha = 0.85f) else Color(0x1A00F0FF)

                    // Draw fine dotted background circle
                    drawCircle(
                        color = baseOrbitColor,
                        radius = size.width / 2f,
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 12f), 0f)
                        )
                    )

                    // Highlight arcs
                    drawArc(
                        color = arcHighlightColor,
                        startAngle = 0f,
                        sweepAngle = 50f,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = arcHighlightColor,
                        startAngle = 180f,
                        sweepAngle = 40f,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Orbit Layer 2: Reverse Orbit Ring
                Canvas(
                    modifier = Modifier
                        .size(175.dp)
                        .graphicsLayer { rotationZ = innerOrbitRotation }
                ) {
                    val baseOrbitColor = if (isConnected) Color(0xFF8A2BE2).copy(alpha = 0.15f) else Color(0x05FFFFFF)
                    val arcHighlightColor = if (isConnected) Color(0xFF8A2BE2).copy(alpha = 0.75f) else Color(0x0D8A2BE2)

                    drawCircle(
                        color = baseOrbitColor,
                        radius = size.width / 2f,
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f), 0f)
                        )
                    )

                    drawArc(
                        color = arcHighlightColor,
                        startAngle = 90f,
                        sweepAngle = 35f,
                        useCenter = false,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = arcHighlightColor,
                        startAngle = 270f,
                        sweepAngle = 35f,
                        useCenter = false,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Breathing Glow Background Aura
                val auraGlowColor by animateColorAsState(
                    targetValue = when {
                        isConnected -> Color(0xFF00F0FF)
                        isConnecting -> Color(0xFFFFCC00)
                        else -> Color(0x05FFFFFF)
                    },
                    animationSpec = tween(800), label = "aura_color"
                )

                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .graphicsLayer {
                            scaleX = breathingGlowScale
                            scaleY = breathingGlowScale
                        }
                        .shadow(
                            elevation = if (isConnected) 44.dp else 0.dp,
                            shape = CircleShape,
                            clip = false,
                            ambientColor = auraGlowColor,
                            spotColor = auraGlowColor
                        )
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    auraGlowColor.copy(alpha = if (isConnected) breathingOpacity * 0.35f else 0.05f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )

                // Central Interactive Tactile Reactor Switch
                val switchCoreColor by animateColorAsState(
                    targetValue = when {
                        isConnected -> Color(0xFF04060A)
                        isConnecting -> Color(0xFF0F0F05)
                        else -> Color(0xFF0A0C14)
                    },
                    animationSpec = tween(600), label = "switch_core"
                )

                val switchRingColor by animateColorAsState(
                    targetValue = when {
                        isConnected -> Color(0xFF00FF88)
                        isConnecting -> Color(0xFFFFCC00)
                        else -> Color(0xFF1E283F)
                    },
                    animationSpec = tween(600), label = "switch_ring"
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(130.dp)
                        .border(
                            BorderStroke(
                                2.dp,
                                Brush.linearGradient(
                                    colors = listOf(switchRingColor, switchRingColor.copy(alpha = 0.3f))
                                )
                            ),
                            CircleShape
                        )
                        .background(switchCoreColor, CircleShape)
                        .clip(CircleShape)
                        .clickable { onToggleVpn() }
                ) {
                    val pressScale by animateFloatAsState(
                        targetValue = if (isConnecting) 0.92f else if (isConnected) 1.05f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "press_scale"
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = pressScale
                                scaleY = pressScale
                            }
                            .testTag("vpn_toggle_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "VPN Switch Key",
                            tint = switchRingColor,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isConnected) "ACTIVE" else if (isConnecting) "LINKING" else "TAP CORE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = switchRingColor.copy(alpha = 0.95f),
                            letterSpacing = 1.5.sp
                        )
                    }
                }
            }

            // --- LATENCY OSCILLATION WAVE PATH ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Color(0x33070A14), RoundedCornerShape(18.dp))
                    .border(1.dp, Color(0x0DFFFFFF), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val midY = height / 2f
                    val pathPrimary = Path()
                    val pathSecondary = Path()

                    pathPrimary.moveTo(0f, midY)
                    pathSecondary.moveTo(0f, midY)

                    val waveAmplitudePx = waveAmplitude.dp.toPx()
                    for (x in 0..width.toInt() step 6) {
                        val relX = x.toFloat() / width
                        // Primary Oscillating Sine Path
                        val y1 = midY + waveAmplitudePx * sin(wavePhase + (relX * waveFrequency * 2 * Math.PI.toFloat()))
                        // Out-of-phase secondary trail for a cybernetic overlap look
                        val y2 = midY + (waveAmplitudePx * 0.5f) * sin(wavePhase + Math.PI.toFloat() + (relX * (waveFrequency * 1.4f) * 2 * Math.PI.toFloat()))

                        pathPrimary.lineTo(relX * width, y1)
                        pathSecondary.lineTo(relX * width, y2)
                    }

                    // Draw primary gradient path
                    drawPath(
                        path = pathPrimary,
                        brush = Brush.horizontalGradient(
                            colors = waveColors,
                            startX = 0f,
                            endX = width
                        ),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Draw complementary path if connected
                    if (isConnected) {
                        drawPath(
                            path = pathSecondary,
                            color = Color(0xFF8A2BE2).copy(alpha = 0.35f),
                            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }

                Text(
                    text = if (isConnected) "MNX WAVE ACTIVE" else "RESOLVER STANDBY",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isConnected) Color(0xFF00F0FF).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.2f),
                    letterSpacing = 2.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- MODERN WIDGETS STATS CONTROLS PANEL ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Row 1: Session Stats (Queries Resolved & Live Uptime Duration)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Total secure queries resolved
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x55070B14)),
                        border = BorderStroke(1.dp, Color(0x0A00F0FF))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(Color(0x1F00FF88), RoundedCornerShape(5.dp))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = "Shield Guard",
                                        tint = Color(0xFF00FF88),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "SHIELD SECURED",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.4f),
                                    letterSpacing = 0.5.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = String.format("%,d", totalQueriesResolved),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "DNS queries filtered",
                                fontSize = 9.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }

                    // Secured Tunnel session uptime
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x55070B14)),
                        border = BorderStroke(1.dp, Color(0x09FFFFFF))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(Color(0x1FA862FF), RoundedCornerShape(5.dp))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timeline,
                                        contentDescription = "Uptime Clock",
                                        tint = Color(0xFFA862FF),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "TUNNEL TIME",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.4f),
                                    letterSpacing = 0.5.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = connectionUptime,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isConnected) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.8f),
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Active connection session",
                                fontSize = 9.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                // Row 2: Unified Active Resolver Status + Live Ping Module
                val pingBarColor by animateColorAsState(
                    targetValue = when {
                        pingMs == null -> Color(0xFFFF5252)
                        pingMs < 60 -> Color(0xFF00FF88)
                        pingMs < 150 -> Color(0xFF00F0FF)
                        else -> Color(0xFFFF9900)
                    },
                    animationSpec = tween(500), label = "ping_color_anim"
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x88080C16)),
                    border = BorderStroke(1.dp, Color(0x1EFFFFFF))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(Color(0xFF141A2D), RoundedCornerShape(8.dp))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SettingsInputComponent,
                                        contentDescription = "Active DNS Settings",
                                        tint = Color(0xFF00F0FF),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "RESOLVER CONSOLE",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White.copy(alpha = 0.4f),
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        text = if (isConnected) activeProfileName else selectedProfile?.name ?: "No Profile Selected",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }

                            // High-tech latency badge
                            Box(
                                modifier = Modifier
                                    .background(pingBarColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                    .border(1.dp, pingBarColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .background(pingBarColor, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (pingMs == null) "OFFLINE" else "$pingMs ms",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = pingBarColor,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // IPs Display Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0x4404060B), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "IP ROUTE",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.35f),
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isConnected) "$activePrimaryDns | $activeSecondaryDns" else "${selectedProfile?.primaryDns ?: "0.0.0.0"} | ${selectedProfile?.secondaryDns ?: "0.0.0.0"}",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.65f),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Row 3: Turbo Flow Switch Panel
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x66080C16)),
                    border = BorderStroke(1.dp, Color(0x0AFFFFFF))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .clickable { onToggleTurbo(!isTurboEnabled) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        if (isTurboEnabled) Color(0x2200F0FF) else Color(0xFF131724),
                                        RoundedCornerShape(8.dp)
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "Turbo Flow speed",
                                    tint = if (isTurboEnabled) Color(0xFF00F0FF) else Color(0xFF4C5D7E),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "MNX TURBO FLOW",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.4f),
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = if (isTurboEnabled) "Optimized Dual-stack IPv4/IPv6" else "Standard DNS Mode",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isTurboEnabled) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }

                        Switch(
                            checked = isTurboEnabled,
                            onCheckedChange = { onToggleTurbo(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF04060A),
                                checkedTrackColor = Color(0xFF00F0FF),
                                uncheckedThumbColor = Color(0xFF4C5D7E),
                                uncheckedTrackColor = Color(0xFF131724)
                            ),
                            modifier = Modifier.testTag("turbo_switch")
                        )
                    }
                }
            }
        }
    }
}
