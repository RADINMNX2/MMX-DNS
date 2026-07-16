package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.DnsLogEntry
import com.example.service.LogType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    logs: List<DnsLogEntry>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var searchQuery by remember { mutableStateOf("") }
    var selectedLevelFilter by remember { mutableStateOf<LogType?>(null) }

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    // Filtered logs
    val filteredLogs = remember(logs, searchQuery, selectedLevelFilter) {
        logs.filter { log ->
            val matchesSearch = searchQuery.isEmpty() ||
                    log.message.contains(searchQuery, ignoreCase = true) ||
                    log.tag.contains(searchQuery, ignoreCase = true)
            
            val matchesLevel = selectedLevelFilter == null || log.type == selectedLevelFilter
            matchesSearch && matchesLevel
        }
    }

    val lazyListState = rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Futuristic Monospace Title Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "ENGINE LOGS",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF00F0FF)
                )
                Text(
                    text = "Real-time DNS socket inspection",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Row {
                // Clear button with terminal style
                IconButton(
                    onClick = onClearLogs,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x1AFF0055))
                        .border(1.dp, Color(0x33FF0055), RoundedCornerShape(10.dp))
                        .testTag("clear_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear Terminal Logs",
                        tint = Color(0xFFFF0055)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Copy all logs button
                IconButton(
                    onClick = {
                        if (filteredLogs.isEmpty()) {
                            Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
                        } else {
                            val allLogsText = filteredLogs.joinToString("\n") { log ->
                                "[${dateFormat.format(Date(log.timestamp))}] [${log.type}] [${log.tag}] ${log.message}"
                            }
                            val clip = ClipData.newPlainText("DNS Changer Logs", allLogsText)
                            clipboardManager.setPrimaryClip(clip)
                            Toast.makeText(context, "All logs copied to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x1A00F0FF))
                        .border(1.dp, Color(0x3300F0FF), RoundedCornerShape(10.dp))
                        .testTag("copy_all_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy All Logs",
                        tint = Color(0xFF00F0FF)
                    )
                }
            }
        }

        // Real-time Search Box with terminal prompt styling
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    text = "filter logs... (e.g. google.com)",
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 13.sp
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Color(0xFF00F0FF),
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear Search",
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF070A14),
                unfocusedContainerColor = Color(0xFF070A14),
                disabledContainerColor = Color(0xFF070A14),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFF00F0FF),
                focusedIndicatorColor = Color(0xFF00F0FF),
                unfocusedIndicatorColor = Color(0x33FFFFFF)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .border(0.5.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                .testTag("logs_search_input"),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        )

        // Filter Pills for Log Levels (INFO, SUCCESS, WARNING, ERROR)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // All Pill
            val isAllSelected = selectedLevelFilter == null
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isAllSelected) Color(0xFF00F0FF) else Color(0xFF0F1424))
                    .clickable { selectedLevelFilter = null }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .border(
                        BorderStroke(0.5.dp, if (isAllSelected) Color.Transparent else Color(0x33FFFFFF)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ALL",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isAllSelected) Color(0xFF04060A) else Color.White.copy(alpha = 0.7f)
                )
            }

            LogType.values().forEach { type ->
                val isSelected = selectedLevelFilter == type
                val color = when (type) {
                    LogType.INFO -> Color(0xFF00F0FF)
                    LogType.SUCCESS -> Color(0xFF00FF66)
                    LogType.WARNING -> Color(0xFFFF9900)
                    LogType.ERROR -> Color(0xFFFF0055)
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSelected) color else Color(0xFF0F1424))
                        .clickable { selectedLevelFilter = type }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .border(
                            BorderStroke(0.5.dp, if (isSelected) Color.Transparent else color.copy(alpha = 0.3f)),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = type.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (isSelected) Color(0xFF04060A) else color.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Terminal Console Output Board
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xE60E121E))
                .border(BorderStroke(0.5.dp, Color(0x33FFFFFF)), RoundedCornerShape(16.dp))
        ) {
            if (filteredLogs.isEmpty()) {
                // Beautiful retro terminal empty state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Empty Console",
                        tint = Color(0xFF00F0FF).copy(alpha = 0.2f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "CONSOLE STACK EMPTY",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Connect DNS engine to inspect packets in real-time.",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { log ->
                        LogItemRow(
                            log = log,
                            formattedTime = dateFormat.format(Date(log.timestamp)),
                            onCopy = {
                                val logText = "[${dateFormat.format(Date(log.timestamp))}] [${log.type}] [${log.tag}] ${log.message}"
                                val clip = ClipData.newPlainText("DNS Log", logText)
                                clipboardManager.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied log entry!", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LogItemRow(
    log: DnsLogEntry,
    formattedTime: String,
    onCopy: () -> Unit
) {
    val levelColor = when (log.type) {
        LogType.INFO -> Color(0xFF00F0FF)
        LogType.SUCCESS -> Color(0xFF00FF66)
        LogType.WARNING -> Color(0xFFFF9900)
        LogType.ERROR -> Color(0xFFFF0055)
    }

    val levelBgColor = levelColor.copy(alpha = 0.08f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF07090F))
            .clickable { onCopy() }
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Mini Indicator Tag
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(42.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(levelColor)
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Level Tag & Module Tag
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(levelBgColor)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = log.type.name,
                            color = levelColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = log.tag,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Monospace Time
                Text(
                    text = formattedTime,
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Log message contents
            Text(
                text = log.message,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = "Copy log entry",
            tint = Color.White.copy(alpha = 0.15f),
            modifier = Modifier
                .size(14.dp)
                .align(Alignment.CenterVertically)
        )
    }
}
