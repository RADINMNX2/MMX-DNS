package com.example.ui.components

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.ReleaseInfo
import com.example.util.UpdateManager
import com.example.util.UpdateState
import java.io.File
import java.util.Locale

@Composable
fun DownloadManagerCard(
    updateState: UpdateState,
    onCheckUpdate: () -> Unit,
    onTriggerDemo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isChangelogExpanded by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "download_pulse")
    val speedGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF0B0F19),
                        Color(0xFF131C30)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF00F0FF).copy(alpha = 0.6f),
                            Color(0xFF00FF88).copy(alpha = 0.4f),
                            Color(0x15FFFFFF)
                        )
                    )
                ),
                RoundedCornerShape(24.dp)
            )
            .padding(20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // CARD HEADER
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF00F0FF).copy(alpha = 0.3f), Color(0xFF00FF88).copy(alpha = 0.1f))
                            )
                        )
                        .border(1.dp, Color(0xFF00F0FF), RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download Manager Icon",
                        tint = Color(0xFF00F0FF),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "HIGH-SPEED UPDATE MANAGER",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "Direct GitHub Release Sync & Chunked Engine",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // STATE CONTENT
            when (updateState) {
                is UpdateState.Idle, is UpdateState.UpToDate -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00FF88))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (updateState is UpdateState.UpToDate) "App is fully up-to-date (v1.2.0)" else "Ready to check for GitHub updates",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onCheckUpdate,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F0FF)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("check_github_release_btn")
                            ) {
                                Text("Check GitHub", color = Color(0xFF04060A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = onTriggerDemo,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(0.5.dp, Color(0xFF00FF88).copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("test_download_btn")
                            ) {
                                Text("Test Downloader", color = Color(0xFF00FF88), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }

                is UpdateState.Checking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF00F0FF),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Querying GitHub API & verifying build checksums...",
                            fontSize = 12.sp,
                            color = Color(0xFF00F0FF)
                        )
                    }
                }

                is UpdateState.UpdateAvailable -> {
                    val info = updateState.info
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (info.isSameVersionRebuild) Color(0xFFFF9900).copy(alpha = 0.2f) else Color(0xFF00FF88).copy(alpha = 0.2f))
                                    .border(0.5.dp, if (info.isSameVersionRebuild) Color(0xFFFF9900) else Color(0xFF00FF88), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (info.isSameVersionRebuild) "REBUILD UPDATE (${info.version})" else "NEW RELEASE: ${info.version}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (info.isSameVersionRebuild) Color(0xFFFF9900) else Color(0xFF00FF88)
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            Text(
                                text = "${formatFileSize(info.apkSize)} • ${info.commitSha.take(7)}",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.5f),
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = info.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Expandable Changelog
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF080D1A))
                                .border(0.5.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                                .clickable { isChangelogExpanded = !isChangelogExpanded }
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ListAlt,
                                        contentDescription = "Changelog",
                                        tint = Color(0xFF00F0FF),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Release Notes & Patch Details",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00F0FF)
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Icon(
                                        imageVector = if (isChangelogExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Expand",
                                        tint = Color.White.copy(alpha = 0.6f)
                                    )
                                }

                                AnimatedVisibility(visible = isChangelogExpanded) {
                                    Column(modifier = Modifier.padding(top = 8.dp)) {
                                        Text(
                                            text = info.changelog,
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.8f),
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Action Download Button
                        Button(
                            onClick = { UpdateManager.startDownload(context, info) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F0FF)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("start_highspeed_download_btn")
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download",
                                    tint = Color(0xFF04060A),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "DOWNLOAD UPDATE NOW",
                                    color = Color(0xFF04060A),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }

                is UpdateState.Downloading -> {
                    val info = updateState.info
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (updateState.isPaused) Color(0xFFFF9900) else Color(0xFF00FF88).copy(alpha = speedGlowAlpha))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (updateState.isPaused) "PAUSED" else "DOWNLOADING (${String.format(Locale.US, "%.1f MB/s", updateState.speedMBps)})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (updateState.isPaused) Color(0xFFFF9900) else Color(0xFF00FF88)
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            Text(
                                text = "${String.format(Locale.US, "%.1f", updateState.downloadedBytes / (1024f * 1024f))} / ${String.format(Locale.US, "%.1f MB", updateState.totalBytes / (1024f * 1024f))}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Progress Bar & Percentage
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF080E1C))
                                    .border(0.5.dp, Color(0x3300F0FF), CircleShape)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(updateState.progress)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(Color(0xFF00F0FF), Color(0xFF00FF88))
                                            )
                                        )
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = "${(updateState.progress * 100).toInt()}%",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF00F0FF),
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (updateState.etaSeconds > 0) "ETA: ${updateState.etaSeconds}s remaining" else "Calculating time...",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )

                            Text(
                                text = info.version,
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Controls: Pause/Resume & Cancel
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (updateState.isPaused) {
                                        UpdateManager.resumeDownload(context, info)
                                    } else {
                                        UpdateManager.pauseDownload()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (updateState.isPaused) Color(0xFF00F0FF) else Color(0xFF1E283D)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (updateState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = "Pause/Resume",
                                    tint = if (updateState.isPaused) Color(0xFF04060A) else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (updateState.isPaused) "Resume" else "Pause",
                                    color = if (updateState.isPaused) Color(0xFF04060A) else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            OutlinedButton(
                                onClick = { UpdateManager.cancelDownload() },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(0.5.dp, Color(0xFFFF0055).copy(alpha = 0.5f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    tint = Color(0xFFFF0055),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Cancel",
                                    color = Color(0xFFFF0055),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                is UpdateState.Downloaded -> {
                    val info = updateState.info
                    val file = updateState.file
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Completed",
                                tint = Color(0xFF00FF88),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "DOWNLOAD COMPLETED!",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF00FF88)
                                )
                                Text(
                                    text = "${info.version} • Ready to Install",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = { UpdateManager.installApk(context, file) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF88)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("install_apk_button")
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = "Install",
                                    tint = Color(0xFF04060A),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "INSTALL APK NOW",
                                    color = Color(0xFF04060A),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }

                is UpdateState.Error -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = updateState.message,
                            fontSize = 12.sp,
                            color = Color(0xFFFF0055)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onCheckUpdate,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E283D)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Retry Check", color = Color.White, fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = onTriggerDemo,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Test Downloader", color = Color(0xFF00FF88), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "18.5 MB"
    val mb = size / (1024f * 1024f)
    return String.format(Locale.US, "%.1f MB", mb)
}
