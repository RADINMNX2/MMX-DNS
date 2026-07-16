package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

class Particle(
    var x: Float,
    var y: Float,
    val size: Float,
    val speedX: Float,
    val speedY: Float,
    val baseAlpha: Float,
    val color: Color
)

@Composable
fun ParticlesBg(isActive: Boolean) {
    if (!isActive) return

    val particles = remember {
        mutableStateListOf<Particle>().apply {
            val colors = listOf(
                Color(0xFF00F0FF), // Cyan
                Color(0xFF0072FF), // Blue
                Color(0xFF8A2BE2)  // Neon Purple
            )
            for (i in 0..30) {
                add(
                    Particle(
                        x = Random.nextFloat(),
                        y = Random.nextFloat(),
                        size = Random.nextFloat() * 10f + 4f,
                        speedX = (Random.nextFloat() - 0.5f) * 0.0015f,
                        speedY = (Random.nextFloat() - 0.5f) * 0.0015f,
                        baseAlpha = Random.nextFloat() * 0.5f + 0.1f,
                        color = colors.random()
                    )
                )
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "particles")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        particles.forEachIndexed { index, p ->
            // Update coordinates based on speeds
            p.x = (p.x + p.speedX + 1f) % 1f
            p.y = (p.y + p.speedY + 1f) % 1f

            // Animate alpha for organic breathing motion
            val sinAlpha = kotlin.math.sin(phase + index)
            val animatedAlpha = (p.baseAlpha * (0.5f + 0.5f * sinAlpha)).coerceIn(0f, 1f)

            drawCircle(
                color = p.color.copy(alpha = animatedAlpha),
                radius = p.size,
                center = Offset(p.x * width, p.y * height)
            )
        }
    }
}
