package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun Modifier.shake(trigger: Int): Modifier = composed {
    var animOffset by remember { mutableStateOf(0f) }
    
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            val anim = TargetBasedAnimation(
                animationSpec = keyframes {
                    durationMillis = 400
                    0f at 0
                    -12f at 50
                    12f at 120
                    -10f at 200
                    10f at 280
                    -5f at 350
                    0f at 400
                },
                typeConverter = Float.VectorConverter,
                initialValue = 0f,
                targetValue = 0f
            )
            
            val startTime = withFrameNanos { it }
            do {
                val playTime = withFrameNanos { it } - startTime
                animOffset = anim.getValueFromNanos(playTime)
            } while (playTime < anim.durationNanos)
            animOffset = 0f
        }
    }
    
    this.offset(x = animOffset.dp)
}

@Composable
fun AnimatedCharacterText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle.Default
) {
    Row(modifier = modifier) {
        text.forEachIndexed { index, char ->
            key(index) {
                var hasEntered by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    hasEntered = true
                }
                
                val scale by animateFloatAsState(
                    targetValue = if (hasEntered) 1f else 0.3f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "char_scale"
                )
                
                val slideY by animateFloatAsState(
                    targetValue = if (hasEntered) 0f else 12f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "char_slide"
                )
                
                Text(
                    text = char.toString(),
                    style = style,
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationY = slideY
                    }
                )
            }
        }
    }
}
