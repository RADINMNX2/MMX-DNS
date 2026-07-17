package com.example.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hardware-accelerated tactile haptics manager using native Android Vibrator APIs
 * and Spring timings to emulate deep physical power reactor clicks.
 */
class HapticFeedbackHelper(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun triggerReactorTap() {
        vibrator?.let {
            if (it.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Tactile high-frequency pre-strike followed by a resonant mechanical spring decay
                    val timings = longArrayOf(0, 10, 35, 18)
                    val amplitudes = intArrayOf(0, 255, 0, 110)
                    val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                    it.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(40)
                }
            }
        }
    }

    fun triggerSuccessPulse() {
        vibrator?.let {
            if (it.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Double resonance pulse indicating lock and sync
                    val timings = longArrayOf(0, 15, 60, 25, 45, 30)
                    val amplitudes = intArrayOf(0, 180, 0, 255, 0, 90)
                    val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                    it.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(longArrayOf(0, 30, 40, 60), -1)
                }
            }
        }
    }
}

/**
 * Cyberpunk circular Reactor Switch.
 * Displays holographic rotating segments, internal core lighting, and breathing animations.
 */
@Composable
fun ReactorSwitch(
    isConnected: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hapticHelper = remember { HapticFeedbackHelper(context) }

    val infiniteTransition = rememberInfiniteTransition(label = "reactor_rotations")

    // Forward slow orbit rotation
    val outerRingRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnected) 3000 else 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outer_rot"
    )

    // Reverse mid-orbit rotation
    val innerRingRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnected) 5000 else 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "inner_rot"
    )

    // Breathing pulse scale
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnected) 1200 else 2400, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_scale"
    )

    // Core Aura Colors
    val glowColor by animateColorAsState(
        targetValue = when {
            isConnected -> Color(0xFF00FF88) // Vivid Neon Green
            isConnecting -> Color(0xFFFFCC00) // Fusion Amber
            else -> Color(0xFF00F0FF).copy(alpha = 0.3f) // Cyan Standby
        },
        animationSpec = tween(600),
        label = "glow_color"
    )

    val coreFillColor by animateColorAsState(
        targetValue = when {
            isConnected -> Color(0xFF04070F)
            isConnecting -> Color(0xFF0B0904)
            else -> Color(0xFF05070A)
        },
        animationSpec = tween(600),
        label = "core_fill"
    )

    val ringBorderColor by animateColorAsState(
        targetValue = when {
            isConnected -> Color(0xFF00FF88)
            isConnecting -> Color(0xFFFFCC00)
            else -> Color(0xFF00F0FF).copy(alpha = 0.4f)
        },
        animationSpec = tween(600),
        label = "ring_border"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(240.dp)
            .graphicsLayer {
                if (isConnected || isConnecting) {
                    scaleX = breathingScale
                    scaleY = breathingScale
                }
            }
    ) {
        // Neon radial glow backing
        Box(
            modifier = Modifier
                .size(190.dp)
                .shadow(
                    elevation = if (isConnected) 40.dp else 10.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = glowColor,
                    spotColor = glowColor
                )
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = if (isConnected) 0.35f else 0.12f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Outer Rotating Ring: Arcs and Tick Marks
        Canvas(
            modifier = Modifier
                .size(210.dp)
                .graphicsLayer { rotationZ = outerRingRotation }
        ) {
            val strokeWidth = 2.dp.toPx()
            // Static fine guide ring
            drawCircle(
                color = glowColor.copy(alpha = 0.1f),
                style = Stroke(width = 0.8.dp.toPx())
            )
            // Heavy cybernetic arcs
            drawArc(
                color = glowColor.copy(alpha = 0.8f),
                startAngle = 0f,
                sweepAngle = 45f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = glowColor.copy(alpha = 0.8f),
                startAngle = 120f,
                sweepAngle = 45f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = glowColor.copy(alpha = 0.8f),
                startAngle = 240f,
                sweepAngle = 45f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        // Inner Reverse Rotating Ring
        Canvas(
            modifier = Modifier
                .size(175.dp)
                .graphicsLayer { rotationZ = innerRingRotation }
        ) {
            val strokeWidth = 1.5.dp.toPx()
            // Tech markings
            drawArc(
                color = ringBorderColor.copy(alpha = 0.6f),
                startAngle = 60f,
                sweepAngle = 30f,
                useCenter = false,
                style = Stroke(width = strokeWidth)
            )
            drawArc(
                color = ringBorderColor.copy(alpha = 0.6f),
                startAngle = 180f,
                sweepAngle = 30f,
                useCenter = false,
                style = Stroke(width = strokeWidth)
            )
            drawArc(
                color = ringBorderColor.copy(alpha = 0.6f),
                startAngle = 300f,
                sweepAngle = 30f,
                useCenter = false,
                style = Stroke(width = strokeWidth)
            )
        }

        // Tactile switch core
        var isPressed by remember { mutableStateOf(false) }
        val animatedPressScale by animateFloatAsState(
            targetValue = if (isPressed) 0.90f else 1.0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "press_spring"
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(135.dp)
                .graphicsLayer {
                    scaleX = animatedPressScale
                    scaleY = animatedPressScale
                }
                .border(
                    BorderStroke(
                        2.dp,
                        Brush.sweepGradient(
                            colors = listOf(
                                ringBorderColor,
                                ringBorderColor.copy(alpha = 0.2f),
                                ringBorderColor
                            )
                        )
                    ),
                    CircleShape
                )
                .background(coreFillColor, CircleShape)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        hapticHelper.triggerReactorTap()
                        onClick()
                        if (!isConnected && !isConnecting) {
                            hapticHelper.triggerSuccessPulse()
                        }
                    }
                )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.testTag("reactor_switch_toggle")
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Reactor Core Switch",
                    tint = ringBorderColor,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        isConnected -> "ONLINE"
                        isConnecting -> "IGNITING"
                        else -> "OFFLINE"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = ringBorderColor,
                    letterSpacing = 2.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "REACTOR CORE",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Medium,
                    color = ringBorderColor.copy(alpha = 0.5f),
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Live Jitter Stability Oscilloscope Waveform.
 * Renders smooth high-frequency harmonic green path under optimal jitter (< 15 ms).
 * Shatters into erratic, high-amplitude red warning spikes under high jitter (>= 15 ms).
 */
@Composable
fun LiveJitterWaveform(
    jitterMs: Int,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_phase")
    
    // Smooth infinite time variable for wave progression
    val phaseOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase_offset"
    )

    // Jitter scaling animation state to make transitions ultra smooth
    val animatedJitter by animateFloatAsState(
        targetValue = jitterMs.toFloat(),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "jitter_smooth"
    )

    val strokeColor = when {
        !isConnected -> Color(0x334C5D7E)
        animatedJitter < 10f -> Color(0xFF00FF88) // Pristine Green
        animatedJitter < 20f -> Color(0xFF00F0FF) // Cyber Blue
        animatedJitter < 35f -> Color(0xFFFF9900) // Warning Orange
        else -> Color(0xFFFF3355) // Critical Red Jitter
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0x2A080C16), RoundedCornerShape(16.dp))
            .border(1.dp, strokeColor.copy(alpha = 0.12f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val midY = height / 2f
            val path = Path()

            path.moveTo(0f, midY)

            val isStable = animatedJitter < 15f
            val baseAmplitude = if (isConnected) 12.dp.toPx() else 2.dp.toPx()
            
            // Generate waveform sampling points
            for (x in 0..width.toInt() step 4) {
                val relX = x.toFloat() / width
                
                // Normal sine-wave math
                val baseFreq = (4.0 * Math.PI).toFloat()
                val primaryWave = sin(phaseOffset + (relX * baseFreq))
                
                var finalY = midY + (primaryWave * baseAmplitude)
                
                if (isConnected) {
                    if (isStable) {
                        // Smooth clean sine with tiny rapid ripples for stable connection look
                        val microRipple = sin(phaseOffset * 3f + (relX * 24f * Math.PI.toFloat())) * 1.5f.dp.toPx()
                        finalY += microRipple
                    } else {
                        // Jagged noise components representing jitter packet arrival anomalies
                        val severityFactor = (animatedJitter - 10f).coerceIn(0f, 60f) / 15f
                        val randomSpikes = sin(x * 15.6f) * cos(x * 9.3f) * (0.8f + sin(phaseOffset * 5f))
                        finalY += randomSpikes * baseAmplitude * 0.75f * severityFactor
                    }
                }

                // Constrain wave boundaries inside canvas
                finalY = finalY.coerceIn(4f, height - 4f)
                path.lineTo(x.toFloat(), finalY)
            }

            // Outer thick primary path
            drawPath(
                path = path,
                color = strokeColor.copy(alpha = 0.9f),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Dynamic glow shadow backing
            drawPath(
                path = path,
                color = strokeColor.copy(alpha = 0.18f),
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Dashboard overlay texts
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isConnected) "LATENCY OSCILLOSCOPE" else "STANDBY TUNNEL",
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = strokeColor.copy(alpha = 0.6f),
                letterSpacing = 1.5.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = if (!isConnected) "OFFLINE" else if (animatedJitter < 15f) "STABILITY: OPTIMAL" else "STABILITY: JITTER FLUID",
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                color = strokeColor,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
