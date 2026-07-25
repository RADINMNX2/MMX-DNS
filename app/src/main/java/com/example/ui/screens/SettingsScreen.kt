package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import com.example.ui.components.AnimatedCharacterText
import com.example.ui.components.DownloadManagerCard
import com.example.util.UpdateManager
import com.example.util.UpdateState

@Composable
fun SettingsScreen(
    currentVersion: String = "v1.2.0",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val updateState by UpdateManager.updateState.collectAsState()
    var showDonateDialog by remember { mutableStateOf(false) }

    // Background Cyber Grid Animation
    val infiniteTransition = rememberInfiniteTransition(label = "settings_grid")
    val gridOffsetPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "grid_flow"
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
                val gridColor = Color(0xFF00F0FF).copy(alpha = 0.025f)
                val movingOffset = gridOffsetPhase % lineSpacing

                var x = -lineSpacing + movingOffset
                while (x < size.width + lineSpacing) {
                    drawLine(
                        color = gridColor,
                        start = androidx.compose.ui.geometry.Offset(x, 0f),
                        end = androidx.compose.ui.geometry.Offset(x, size.height),
                        strokeWidth = 1f
                    )
                    x += lineSpacing
                }

                var y = -lineSpacing + movingOffset
                while (y < size.height + lineSpacing) {
                    drawLine(
                        color = gridColor,
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    y += lineSpacing
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // --- HEADER ---
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SYSTEM & ARCHITECT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00F0FF).copy(alpha = 0.85f),
                    letterSpacing = 6.sp,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))
                AnimatedCharacterText(
                    text = "SETTINGS & INFO",
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 2.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- DEVELOPER & BRAND CARD (ULTRA CLASSY) ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF101728),
                                Color(0xFF0B0E17),
                                Color(0xFF151D30)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF00F0FF).copy(alpha = 0.5f),
                                    Color(0xFF7000FF).copy(alpha = 0.3f),
                                    Color(0x11FFFFFF)
                                )
                            )
                        ),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(20.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Developer Avatar / Icon Badge
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(Color(0xFF00F0FF).copy(alpha = 0.3f), Color(0xFF7000FF).copy(alpha = 0.1f))
                                    )
                                )
                                .border(1.5.dp, Color(0xFF00F0FF), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = "Developer Code Icon",
                                tint = Color(0xFF00F0FF),
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Radin (RADINMNX2)",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF00F0FF).copy(alpha = 0.15f))
                                        .border(0.5.dp, Color(0xFF00F0FF).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "ARCHITECT",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00F0FF)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = "Lead Systems & Security Engineer",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(16.dp))

                    // App Metadata & Description
                    Text(
                        text = "FluxDNS is an open-source, ultra-low latency C++ native DNS tunnel and latency optimizer crafted for gaming and secure privacy.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Buttons: GitHub Link & Copy Link
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RADINMNX2"))
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E283D)),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(0.5.dp, Color(0xFF00F0FF).copy(alpha = 0.4f)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("github_button")
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Launch,
                                    contentDescription = "GitHub Icon",
                                    tint = Color(0xFF00F0FF),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "GitHub Profile",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("GitHub Link", "https://github.com/RADINMNX2")
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "GitHub URL copied!", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            modifier = Modifier.testTag("copy_github_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Link",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- HIGH SPEED DOWNLOAD & UPDATE MANAGER CARD ---
            DownloadManagerCard(
                updateState = updateState,
                onCheckUpdate = { UpdateManager.checkForUpdates(context, currentVersion) },
                onTriggerDemo = { UpdateManager.triggerDemoUpdate(context) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // --- DONATE & SUPPORT CARD (حمایت مالی و دونیت) ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF18122B),
                                Color(0xFF0E0B1A),
                                Color(0xFF1F1438)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFFF00A0).copy(alpha = 0.4f),
                                    Color(0xFF7000FF).copy(alpha = 0.4f),
                                    Color(0x11FFFFFF)
                                )
                            )
                        ),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(20.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF00A0).copy(alpha = 0.15f))
                                .border(1.dp, Color(0xFFFF00A0), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Donate Icon",
                                tint = Color(0xFFFF00A0),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Support Development (دونیت)",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Keep FluxDNS free & open-source",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }

                        TextButton(
                            onClick = { showDonateDialog = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF00A0)),
                            modifier = Modifier.testTag("open_donate_dialog_button")
                        ) {
                            Text("View Wallets", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Primary Daramet Donate Button
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://daramet.com/RADIN_MNX"))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF00A0)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("daramet_donate_button")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Daramet Donate",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "حمایت مالی آنلاین (Daramet)",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Instant Crypto Quick Copy Boxes
                    CryptoAddressRow(
                        context = context,
                        coinName = "USDT / TRX (TRC20)",
                        address = "T9xMNX8888FluxDNS2026CryptoDonate"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    CryptoAddressRow(
                        context = context,
                        coinName = "TON / Wallet",
                        address = "EQD8888FluxDNS2026TonDonateAddress"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- APP SYSTEM INFO & CREDITS ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF0E121E))
                    .border(BorderStroke(0.5.dp, Color(0x15FFFFFF)), RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "ENGINE INFORMATION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.4f),
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    InfoRow(label = "Native Engine", value = "C++17 Standalone eBPF/TUN")
                    InfoRow(label = "Architecture", value = "ARM64 / x86_64 High-Perf")
                    InfoRow(label = "License", value = "MIT Open Source")
                    InfoRow(label = "Developer", value = "Radin (RADINMNX2)")
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    // --- DONATE FULL WALLET DIALOG ---
    if (showDonateDialog) {
        AlertDialog(
            onDismissRequest = { showDonateDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Donate Wallet Icon",
                        tint = Color(0xFFFF00A0),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Donate & Support MNX",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Your support helps maintain high-speed DNS servers and open-source updates!",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://daramet.com/RADIN_MNX"))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF00A0)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Launch,
                                contentDescription = "Daramet Link",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("پرداخت از طریق دارامت (Daramet)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    CryptoAddressRow(
                        context = context,
                        coinName = "USDT / TRX (TRC20)",
                        address = "T9xMNX8888FluxDNS2026CryptoDonate"
                    )

                    CryptoAddressRow(
                        context = context,
                        coinName = "TON / Telegram Wallet",
                        address = "EQD8888FluxDNS2026TonDonateAddress"
                    )

                    CryptoAddressRow(
                        context = context,
                        coinName = "Bitcoin (BTC)",
                        address = "bc1qmnx8888fluxdns2026btcaddress"
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showDonateDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF00A0))
                ) {
                    Text("Done", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF0E121E),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.border(1.dp, Color(0x33FF00A0), RoundedCornerShape(20.dp))
        )
    }
}

@Composable
private fun CryptoAddressRow(
    context: Context,
    coinName: String,
    address: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF080B12))
            .border(0.5.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = coinName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00F0FF)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = address,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }

            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(coinName, address)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "$coinName address copied!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Wallet Address",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}
