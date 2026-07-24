package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DnsProfile
import com.example.ui.components.AnimatedCharacterText
import com.example.ui.components.shake
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Ipv4SyntaxResult(
    val rawText: String,
    val octetStrings: List<String>,
    val octetValues: List<Int?>,
    val isFullyValid: Boolean,
    val hasError: Boolean,
    val errorMessage: String,
    val statusText: String,
    val statusColor: Color
)

fun parseIpv4Syntax(input: String): Ipv4SyntaxResult {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return Ipv4SyntaxResult(
            rawText = "",
            octetStrings = emptyList(),
            octetValues = emptyList(),
            isFullyValid = false,
            hasError = false,
            errorMessage = "",
            statusText = "0/4 OCTETS (AWAITING INPUT)",
            statusColor = Color.White.copy(alpha = 0.4f)
        )
    }

    val parts = trimmed.split(".")
    val octetStrings = mutableListOf<String>()
    val octetValues = mutableListOf<Int?>()
    var hasRangeError = false
    var errorMsg = ""

    for ((index, part) in parts.withIndex()) {
        if (part.isEmpty()) {
            if (index < parts.size - 1) {
                hasRangeError = true
                errorMsg = "Empty octet value"
                octetStrings.add("")
                octetValues.add(null)
            }
            continue
        }
        val num = part.toIntOrNull()
        if (num == null) {
            hasRangeError = true
            errorMsg = "'$part' is non-numeric"
            octetStrings.add(part)
            octetValues.add(null)
        } else if (num < 0 || num > 255) {
            hasRangeError = true
            errorMsg = "Octet $num exceeds 255 limit"
            octetStrings.add(part)
            octetValues.add(null)
        } else {
            octetStrings.add(part)
            octetValues.add(num)
        }
    }

    if (parts.size > 4) {
        hasRangeError = true
        errorMsg = "Too many octets (max 4)"
    }

    val isComplete = parts.size == 4 && octetValues.size == 4 && octetValues.all { it != null } && !hasRangeError && !trimmed.endsWith(".")
    val isProgress = parts.size in 1..4 && !hasRangeError

    val (statusText, statusColor) = when {
        hasRangeError -> "SYNTAX ERROR: $errorMsg" to Color(0xFFFF3355)
        isComplete -> "PATTERN MATCHED (4/4 VALID OCTETS ✓)" to Color(0xFF00FF88)
        trimmed.endsWith(".") && parts.size < 4 -> "OCTET ${parts.size - 1} LOCKED - ENTERING NEXT" to Color(0xFFFFB703)
        isProgress -> "BUILDING IPv4 PATTERN (${octetStrings.size}/4 OCTETS)" to Color(0xFF00F0FF)
        else -> "INVALID IPv4 SYNTAX" to Color(0xFFFF3355)
    }

    return Ipv4SyntaxResult(
        rawText = trimmed,
        octetStrings = octetStrings,
        octetValues = octetValues,
        isFullyValid = isComplete,
        hasError = hasRangeError,
        errorMessage = errorMsg,
        statusText = statusText,
        statusColor = statusColor
    )
}

data class Ipv6SyntaxResult(
    val rawText: String,
    val hexGroups: List<String>,
    val isFullyValid: Boolean,
    val hasError: Boolean,
    val errorMessage: String,
    val statusText: String,
    val statusColor: Color
)

fun parseIpv6Syntax(input: String): Ipv6SyntaxResult {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return Ipv6SyntaxResult(
            rawText = "",
            hexGroups = emptyList(),
            isFullyValid = false,
            hasError = false,
            errorMessage = "",
            statusText = "AWAITING IPv6 INPUT",
            statusColor = Color.White.copy(alpha = 0.4f)
        )
    }

    val ipv6Regex = """^(?:[0-9a-fA-F]{1,4}:){1,7}[0-9a-fA-F]{1,4}$|^(?:[0-9a-fA-F]{1,4}:){1,7}:$|^::(?:[0-9a-fA-F]{1,4}:){0,5}[0-9a-fA-F]{1,4}$""".toRegex()
    val isValid = ipv6Regex.matches(trimmed)
    val groups = trimmed.split(":").filter { it.isNotEmpty() }

    val (statusText, statusColor) = when {
        isValid -> "IPv6 PATTERN MATCHED ✓" to Color(0xFF00FF88)
        groups.isNotEmpty() -> "IPv6 IN PROGRESS (${groups.size} HEX BLOCKS)" to Color(0xFF00F0FF)
        else -> "INVALID IPv6 FORMAT" to Color(0xFFFF3355)
    }

    return Ipv6SyntaxResult(
        rawText = trimmed,
        hexGroups = groups,
        isFullyValid = isValid,
        hasError = !isValid && trimmed.length > 6,
        errorMessage = if (!isValid && trimmed.length > 6) "Invalid IPv6 address structure" else "",
        statusText = statusText,
        statusColor = statusColor
    )
}

@Composable
fun OctetMatrixChips(
    syntaxResult: Ipv4SyntaxResult,
    titleTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x330B0F1A), RoundedCornerShape(12.dp))
            .border(1.dp, syntaxResult.statusColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        // Top Header Row with Markdown Tag & Live Status Message
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "###",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF00F0FF),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = titleTag,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }

            // Status Badge
            Box(
                modifier = Modifier
                    .background(syntaxResult.statusColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .border(1.dp, syntaxResult.statusColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = syntaxResult.statusText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = syntaxResult.statusColor,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 4 Octets Visual Grid Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0..3) {
                val octetVal = syntaxResult.octetStrings.getOrNull(i)
                val isPresent = octetVal != null
                val isValidVal = syntaxResult.octetValues.getOrNull(i) != null

                val chipBg = when {
                    !isPresent -> Color(0x11FFFFFF)
                    !isValidVal -> Color(0x33FF3355)
                    syntaxResult.isFullyValid -> Color(0x2200FF88)
                    else -> Color(0x2200F0FF)
                }

                val chipBorder = when {
                    !isPresent -> Color(0x22FFFFFF)
                    !isValidVal -> Color(0xFFFF3355)
                    syntaxResult.isFullyValid -> Color(0xFF00FF88)
                    else -> Color(0xFF00F0FF)
                }

                val chipTextColor = when {
                    !isPresent -> Color.White.copy(alpha = 0.3f)
                    !isValidVal -> Color(0xFFFF3355)
                    syntaxResult.isFullyValid -> Color(0xFF00FF88)
                    else -> Color(0xFF00F0FF)
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(chipBg)
                        .border(1.dp, chipBorder, RoundedCornerShape(8.dp))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "OCTET ${i + 1}",
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Bold,
                            color = chipTextColor.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (isPresent && octetVal!!.isNotEmpty()) octetVal else "0",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = chipTextColor,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (i < 3) {
                    Text(
                        text = ".",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF00F0FF),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Animated Character Text Live Output
        if (syntaxResult.rawText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x22000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "> SYNTAX_STREAM: ",
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace
                )
                AnimatedCharacterText(
                    text = syntaxResult.rawText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = syntaxResult.statusColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                )
            }
        }
    }
}

@Composable
fun Ipv6HexBlockChips(
    syntaxResult: Ipv6SyntaxResult,
    titleTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x330B0F1A), RoundedCornerShape(12.dp))
            .border(1.dp, syntaxResult.statusColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "::",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF00FF88),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = titleTag,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }

            Box(
                modifier = Modifier
                    .background(syntaxResult.statusColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .border(1.dp, syntaxResult.statusColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = syntaxResult.statusText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = syntaxResult.statusColor,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (syntaxResult.hexGroups.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                syntaxResult.hexGroups.take(6).forEach { group ->
                    Box(
                        modifier = Modifier
                            .background(Color(0x2200FF88), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF00FF88).copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = group,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FF88),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        if (syntaxResult.rawText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x22000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "> IPv6_STREAM: ",
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace
                )
                AnimatedCharacterText(
                    text = syntaxResult.rawText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = syntaxResult.statusColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profiles: List<DnsProfile>,
    currentSelectedId: Int?,
    onSelectProfile: (DnsProfile) -> Unit,
    onDeleteProfile: (DnsProfile) -> Unit,
    onSaveProfile: (id: Int, name: String, primary: String, secondary: String, enableIpv6: Boolean, primaryIpv6: String, secondaryIpv6: String, isDefault: Boolean, isCustom: Boolean, onComplete: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()

    // Infinite transitions for grid and background particles
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val gridOffsetPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "grid_flow"
    )

    // Form inputs state
    var editId by remember { mutableStateOf(0) }
    var nameInput by remember { mutableStateOf("") }
    var primaryInput by remember { mutableStateOf("") }
    var secondaryInput by remember { mutableStateOf("") }
    var enableIpv6Input by remember { mutableStateOf(false) }
    var primaryIpv6Input by remember { mutableStateOf("") }
    var secondaryIpv6Input by remember { mutableStateOf("") }
    var isDefaultInput by remember { mutableStateOf(false) }

    // Validation state
    var nameError by remember { mutableStateOf(false) }
    var primaryError by remember { mutableStateOf(false) }
    var secondaryError by remember { mutableStateOf(false) }
    var primaryIpv6Error by remember { mutableStateOf(false) }
    var secondaryIpv6Error by remember { mutableStateOf(false) }

    // Shake animation trigger counters
    var nameShakeTrigger by remember { mutableStateOf(0) }
    var primaryShakeTrigger by remember { mutableStateOf(0) }
    var secondaryShakeTrigger by remember { mutableStateOf(0) }
    var primaryIpv6ShakeTrigger by remember { mutableStateOf(0) }
    var secondaryIpv6ShakeTrigger by remember { mutableStateOf(0) }

    // Morphing button states
    var buttonMorphState by remember { mutableStateOf(0) }

    // Form visibility
    var isFormExpanded by remember { mutableStateOf(false) }

    // Live Syntax Evaluators
    val primarySyntax = remember(primaryInput) { parseIpv4Syntax(primaryInput) }
    val secondarySyntax = remember(secondaryInput) { parseIpv4Syntax(secondaryInput) }
    val primaryIpv6Syntax = remember(primaryIpv6Input) { parseIpv6Syntax(primaryIpv6Input) }
    val secondaryIpv6Syntax = remember(secondaryIpv6Input) { parseIpv6Syntax(secondaryIpv6Input) }

    // Helper functions for IP validation
    fun isValidIpv4(ip: String): Boolean {
        if (ip.trim().isEmpty()) return false
        val ipv4Regex = """^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)$""".toRegex()
        return ipv4Regex.matches(ip.trim())
    }

    fun isValidIpv6(ip: String): Boolean {
        if (ip.trim().isEmpty()) return false
        val ipv6Regex = """^(?:[0-9a-fA-F]{1,4}:){1,7}[0-9a-fA-F]{1,4}$|^(?:[0-9a-fA-F]{1,4}:){1,7}:$|^::(?:[0-9a-fA-F]{1,4}:){0,5}[0-9a-fA-F]{1,4}$""".toRegex()
        return ipv6Regex.matches(ip.trim())
    }

    fun handleSave() {
        nameError = nameInput.trim().isEmpty()
        primaryError = !isValidIpv4(primaryInput)
        secondaryError = secondaryInput.trim().isNotEmpty() && !isValidIpv4(secondaryInput)
        primaryIpv6Error = enableIpv6Input && !isValidIpv6(primaryIpv6Input)
        secondaryIpv6Error = enableIpv6Input && secondaryIpv6Input.trim().isNotEmpty() && !isValidIpv6(secondaryIpv6Input)

        if (nameError) nameShakeTrigger++
        if (primaryError) primaryShakeTrigger++
        if (secondaryError) secondaryShakeTrigger++
        if (primaryIpv6Error) primaryIpv6ShakeTrigger++
        if (secondaryIpv6Error) secondaryIpv6ShakeTrigger++

        if (nameError || primaryError || secondaryError || primaryIpv6Error || secondaryIpv6Error) {
            return
        }

        coroutineScope.launch {
            buttonMorphState = 1
            delay(1000)
            buttonMorphState = 2
            delay(800)

            onSaveProfile(
                editId,
                nameInput.trim(),
                primaryInput.trim(),
                secondaryInput.trim(),
                enableIpv6Input,
                if (enableIpv6Input) primaryIpv6Input.trim() else "",
                if (enableIpv6Input) secondaryIpv6Input.trim() else "",
                isDefaultInput,
                true
            ) {
                coroutineScope.launch {
                    editId = 0
                    nameInput = ""
                    primaryInput = ""
                    secondaryInput = ""
                    enableIpv6Input = false
                    primaryIpv6Input = ""
                    secondaryIpv6Input = ""
                    isDefaultInput = false
                    buttonMorphState = 0
                    isFormExpanded = false
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF04060A),
                        Color(0xFF0D111D),
                        Color(0xFF05070B)
                    )
                )
            )
            .drawBehind {
                val lineSpacing = 60.dp.toPx()
                val gridColor = Color(0xFF00F0FF).copy(alpha = 0.03f)
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
        com.example.ui.components.ParticlesBg(isActive = true)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
        ) {
            // Header Item
            item(key = "header_item") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "# PROFILES_CONFIG",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00F0FF).copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "DNS SYNTAX MANAGER",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            if (isFormExpanded && editId != 0) {
                                editId = 0
                                nameInput = ""
                                primaryInput = ""
                                secondaryInput = ""
                                enableIpv6Input = false
                                primaryIpv6Input = ""
                                secondaryIpv6Input = ""
                                isDefaultInput = false
                            } else {
                                isFormExpanded = !isFormExpanded
                            }
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF161B29))
                            .testTag("add_profile_fab")
                    ) {
                        Icon(
                            imageVector = if (isFormExpanded && editId == 0) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = "Add New Profile",
                            tint = Color(0xFF00F0FF)
                        )
                    }
                }
            }

            // Expanded Form Area Item
            item(key = "form_item") {
                AnimatedVisibility(
                    visible = isFormExpanded,
                    enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                    exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111625)),
                        border = BorderStroke(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFF00F0FF).copy(alpha = 0.4f), Color(0x22101524))
                            )
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (editId == 0) "# CREATE_NEW_PROFILE" else "# EDIT_DNS_PROFILE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00F0FF).copy(alpha = 0.9f),
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (enableIpv6Input) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0x2200FF88), RoundedCornerShape(8.dp))
                                                .border(1.dp, Color(0xFF00FF88).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "DUAL-STACK ACTIVE",
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Black,
                                                color = Color(0xFF00FF88),
                                                fontFamily = FontFamily.Monospace,
                                                letterSpacing = 1.sp
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            editId = 0
                                            nameInput = ""
                                            primaryInput = ""
                                            secondaryInput = ""
                                            enableIpv6Input = false
                                            primaryIpv6Input = ""
                                            secondaryIpv6Input = ""
                                            isDefaultInput = false
                                            isFormExpanded = false
                                            nameError = false
                                            primaryError = false
                                            secondaryError = false
                                            primaryIpv6Error = false
                                            secondaryIpv6Error = false
                                        },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(Color(0x22FF3355))
                                            .border(1.dp, Color(0xFFFF3355).copy(alpha = 0.6f), CircleShape)
                                            .testTag("close_edit_form_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cancel Editing",
                                            tint = Color(0xFFFF3355),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // Live Character Typing Stream Preview
                            if (nameInput.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0x2200F0FF), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "> NAME_PREVIEW: ",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                    AnimatedCharacterText(
                                        text = nameInput,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = Color(0xFF00F0FF),
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }
                            }

                            // Profile Name input
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = {
                                    nameInput = it
                                    nameError = false
                                },
                                label = { Text("Profile Name", fontFamily = FontFamily.Monospace) },
                                placeholder = { Text("e.g. Cyber Shield DNS", fontFamily = FontFamily.Monospace) },
                                isError = nameError,
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00F0FF),
                                    unfocusedBorderColor = Color(0x33FFFFFF),
                                    errorBorderColor = Color(0xFFFF3355),
                                    focusedLabelColor = Color(0xFF00F0FF),
                                    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                                    errorLabelColor = Color(0xFFFF3355),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = Color(0xFF00F0FF)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .shake(nameShakeTrigger)
                                    .testTag("input_profile_name")
                            )

                            // --- IPv4 SECTION CARD ---
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0x660B0F1A)),
                                border = BorderStroke(1.dp, Color(0x1A00F0FF))
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(22.dp)
                                                .background(Color(0x2200F0FF), CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Dns,
                                                contentDescription = "IPv4 Icon",
                                                tint = Color(0xFF00F0FF),
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "## IPv4 ENDPOINTS & SYNTAX INSPECTOR",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF00F0FF),
                                            fontFamily = FontFamily.Monospace,
                                            letterSpacing = 1.sp
                                        )
                                    }

                                    // Primary IPv4
                                    val primaryBorderColor by animateColorAsState(
                                        targetValue = when {
                                            primaryError -> Color(0xFFFF3355)
                                            primarySyntax.isFullyValid -> Color(0xFF00FF88)
                                            primaryInput.isNotEmpty() -> Color(0xFF00F0FF)
                                            else -> Color(0x33FFFFFF)
                                        },
                                        animationSpec = tween(300),
                                        label = "primary_border_color"
                                    )

                                    OutlinedTextField(
                                        value = primaryInput,
                                        onValueChange = {
                                            primaryInput = it
                                            primaryError = false
                                        },
                                        label = { Text("Primary IPv4 Address", fontFamily = FontFamily.Monospace) },
                                        placeholder = { Text("e.g. 1.1.1.1 or 34.2.43.2", fontFamily = FontFamily.Monospace) },
                                        isError = primaryError,
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = primaryBorderColor,
                                            unfocusedBorderColor = primaryBorderColor.copy(alpha = 0.5f),
                                            errorBorderColor = Color(0xFFFF3355),
                                            focusedLabelColor = primaryBorderColor,
                                            unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            cursorColor = Color(0xFF00F0FF)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .shake(primaryShakeTrigger)
                                            .testTag("input_primary_dns")
                                    )

                                    // Live Octet Matrix Inspector Component for Primary
                                    OctetMatrixChips(
                                        syntaxResult = primarySyntax,
                                        titleTag = "PRIMARY_IPV4_SYNTAX"
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Secondary IPv4
                                    val secondaryBorderColor by animateColorAsState(
                                        targetValue = when {
                                            secondaryError -> Color(0xFFFF3355)
                                            secondarySyntax.isFullyValid -> Color(0xFF00FF88)
                                            secondaryInput.isNotEmpty() -> Color(0xFF00F0FF)
                                            else -> Color(0x33FFFFFF)
                                        },
                                        animationSpec = tween(300),
                                        label = "secondary_border_color"
                                    )

                                    OutlinedTextField(
                                        value = secondaryInput,
                                        onValueChange = {
                                            secondaryInput = it
                                            secondaryError = false
                                        },
                                        label = { Text("Secondary IPv4 Address (Optional)", fontFamily = FontFamily.Monospace) },
                                        placeholder = { Text("e.g. 1.0.0.1", fontFamily = FontFamily.Monospace) },
                                        isError = secondaryError,
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = secondaryBorderColor,
                                            unfocusedBorderColor = secondaryBorderColor.copy(alpha = 0.5f),
                                            errorBorderColor = Color(0xFFFF3355),
                                            focusedLabelColor = secondaryBorderColor,
                                            unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            cursorColor = Color(0xFF00F0FF)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .shake(secondaryShakeTrigger)
                                            .testTag("input_secondary_dns")
                                    )

                                    if (secondaryInput.isNotEmpty()) {
                                        OctetMatrixChips(
                                            syntaxResult = secondarySyntax,
                                            titleTag = "SECONDARY_IPV4_SYNTAX"
                                        )
                                    }
                                }
                            }

                            // --- ENABLE IPV6 TOGGLE SWITCH CARD ---
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { enableIpv6Input = !enableIpv6Input },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (enableIpv6Input) Color(0x2200FF88) else Color(0x440B0F1A)
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (enableIpv6Input) Color(0xFF00FF88).copy(alpha = 0.6f) else Color(0x1EFFFFFF)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(
                                                    if (enableIpv6Input) Color(0x3300FF88) else Color(0x11FFFFFF),
                                                    CircleShape
                                                )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Router,
                                                contentDescription = "IPv6 Router",
                                                tint = if (enableIpv6Input) Color(0xFF00FF88) else Color.White.copy(alpha = 0.6f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "## ENABLE IPV6 DUAL-STACK",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (enableIpv6Input) Color(0xFF00FF88) else Color.White,
                                                fontFamily = FontFamily.Monospace,
                                                letterSpacing = 0.5.sp
                                            )
                                            Text(
                                                text = "Activates IPv6 primary & secondary routes",
                                                fontSize = 10.sp,
                                                color = Color.White.copy(alpha = 0.5f)
                                            )
                                        }
                                    }

                                    Switch(
                                        checked = enableIpv6Input,
                                        onCheckedChange = { enableIpv6Input = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color(0xFF04060A),
                                            checkedTrackColor = Color(0xFF00FF88),
                                            uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                            uncheckedTrackColor = Color(0x33FFFFFF)
                                        ),
                                        modifier = Modifier.testTag("ipv6_toggle_switch")
                                    )
                                }
                            }

                            // --- IPv6 DYNAMIC EXPANDABLE CARDS ---
                            AnimatedVisibility(
                                visible = enableIpv6Input,
                                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                            ) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0x880E1322)),
                                    border = BorderStroke(1.dp, Color(0xFF00FF88).copy(alpha = 0.4f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .background(Color(0x3300FF88), CircleShape)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Lan,
                                                    contentDescription = "IPv6 Icon",
                                                    tint = Color(0xFF00FF88),
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "## IPv6 ENDPOINTS",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF00FF88),
                                                fontFamily = FontFamily.Monospace,
                                                letterSpacing = 1.sp
                                            )
                                        }

                                        // Primary IPv6
                                        val primaryIpv6BorderColor by animateColorAsState(
                                            targetValue = when {
                                                primaryIpv6Error -> Color(0xFFFF3355)
                                                primaryIpv6Syntax.isFullyValid -> Color(0xFF00FF88)
                                                primaryIpv6Input.isNotEmpty() -> Color(0xFF00FF88)
                                                else -> Color(0x33FFFFFF)
                                            },
                                            animationSpec = tween(300),
                                            label = "primary_ipv6_border_color"
                                        )

                                        OutlinedTextField(
                                            value = primaryIpv6Input,
                                            onValueChange = {
                                                primaryIpv6Input = it
                                                primaryIpv6Error = false
                                            },
                                            label = { Text("Primary IPv6 Address", fontFamily = FontFamily.Monospace) },
                                            placeholder = { Text("e.g. 2001:4860:4860::8888", fontFamily = FontFamily.Monospace) },
                                            isError = primaryIpv6Error,
                                            singleLine = true,
                                            shape = RoundedCornerShape(12.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = primaryIpv6BorderColor,
                                                unfocusedBorderColor = primaryIpv6BorderColor.copy(alpha = 0.5f),
                                                errorBorderColor = Color(0xFFFF3355),
                                                focusedLabelColor = Color(0xFF00FF88),
                                                unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White,
                                                cursorColor = Color(0xFF00FF88)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .shake(primaryIpv6ShakeTrigger)
                                                .testTag("input_primary_ipv6")
                                        )

                                        Ipv6HexBlockChips(
                                            syntaxResult = primaryIpv6Syntax,
                                            titleTag = "PRIMARY_IPV6_SYNTAX"
                                        )

                                        // Secondary IPv6
                                        val secondaryIpv6BorderColor by animateColorAsState(
                                            targetValue = when {
                                                secondaryIpv6Error -> Color(0xFFFF3355)
                                                secondaryIpv6Syntax.isFullyValid -> Color(0xFF00FF88)
                                                secondaryIpv6Input.isNotEmpty() -> Color(0xFF00FF88)
                                                else -> Color(0x33FFFFFF)
                                            },
                                            animationSpec = tween(300),
                                            label = "secondary_ipv6_border_color"
                                        )

                                        OutlinedTextField(
                                            value = secondaryIpv6Input,
                                            onValueChange = {
                                                secondaryIpv6Input = it
                                                secondaryIpv6Error = false
                                            },
                                            label = { Text("Secondary IPv6 Address (Optional)", fontFamily = FontFamily.Monospace) },
                                            placeholder = { Text("e.g. 2001:4860:4860::8884", fontFamily = FontFamily.Monospace) },
                                            isError = secondaryIpv6Error,
                                            singleLine = true,
                                            shape = RoundedCornerShape(12.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = secondaryIpv6BorderColor,
                                                unfocusedBorderColor = secondaryIpv6BorderColor.copy(alpha = 0.5f),
                                                errorBorderColor = Color(0xFFFF3355),
                                                focusedLabelColor = Color(0xFF00FF88),
                                                unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White,
                                                cursorColor = Color(0xFF00FF88)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .shake(secondaryIpv6ShakeTrigger)
                                                .testTag("input_secondary_ipv6")
                                        )

                                        if (secondaryIpv6Input.isNotEmpty()) {
                                            Ipv6HexBlockChips(
                                                syntaxResult = secondaryIpv6Syntax,
                                                titleTag = "SECONDARY_IPV6_SYNTAX"
                                            )
                                        }
                                    }
                                }
                            }

                            // Action Buttons: Cancel and Morphing Save Profile
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0x1AFF3355))
                                        .border(1.dp, Color(0xFFFF3355).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                        .clickable {
                                            editId = 0
                                            nameInput = ""
                                            primaryInput = ""
                                            secondaryInput = ""
                                            enableIpv6Input = false
                                            primaryIpv6Input = ""
                                            secondaryIpv6Input = ""
                                            isDefaultInput = false
                                            isFormExpanded = false
                                            nameError = false
                                            primaryError = false
                                            secondaryError = false
                                            primaryIpv6Error = false
                                            secondaryIpv6Error = false
                                        }
                                        .testTag("cancel_edit_bottom_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cancel",
                                            tint = Color(0xFFFF3355),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "CANCEL",
                                            color = Color(0xFFFF3355),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }

                                // Morphing "Save Profile" Button
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.weight(1.6f)
                                ) {
                                    val buttonWidth by animateDpAsState(
                                        targetValue = if (buttonMorphState == 0) 220.dp else 56.dp,
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        label = "btn_width"
                                    )

                                    val buttonColor by animateColorAsState(
                                        targetValue = when (buttonMorphState) {
                                            2 -> Color(0xFF00FF88)
                                            else -> Color(0xFF0072FF)
                                        },
                                        animationSpec = tween(500), label = "btn_color"
                                    )

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .width(buttonWidth)
                                            .height(50.dp)
                                            .clip(if (buttonMorphState == 0) RoundedCornerShape(14.dp) else CircleShape)
                                            .background(buttonColor)
                                            .clickable(enabled = buttonMorphState == 0, onClick = ::handleSave)
                                            .testTag("save_profile_button")
                                    ) {
                                        AnimatedContent(
                                            targetState = buttonMorphState,
                                            transitionSpec = {
                                                scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut()
                                            },
                                            label = "button_content"
                                        ) { state ->
                                            when (state) {
                                                0 -> {
                                                    Text(
                                                        text = "SAVE PROFILE",
                                                        color = Color.White,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Monospace,
                                                        letterSpacing = 1.sp
                                                    )
                                                }
                                                1 -> {
                                                    CircularProgressIndicator(
                                                        color = Color.White,
                                                        strokeWidth = 3.dp,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                                2 -> {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Success",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(26.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section Header Item
            item(key = "section_title_item") {
                Text(
                    text = "## AVAILABLE_PROFILES (${profiles.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            items(
                items = profiles,
                key = { it.id }
            ) { profile ->
                val isSelected = currentSelectedId == profile.id

                val activeGlowBorder by animateColorAsState(
                    targetValue = if (isSelected) Color(0xFF00F0FF) else Color(0x22FFFFFF),
                    animationSpec = tween(450), label = "item_border"
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItemPlacement()
                        .clickable { onSelectProfile(profile) }
                        .testTag("profile_card_${profile.id}"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Color(0xFF131825) else Color(0xFF0E121E)
                    ),
                    border = BorderStroke(1.dp, activeGlowBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(20.dp)
                                    .border(2.dp, if (isSelected) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.4f), CircleShape)
                                    .clip(CircleShape)
                            ) {
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(Color(0xFF00F0FF), CircleShape)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = profile.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (profile.enableIpv6) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0x2200FF88), RoundedCornerShape(4.dp))
                                                .border(0.5.dp, Color(0xFF00FF88).copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "DUAL-STACK",
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF00FF88),
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0x2200F0FF), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "IPv4 ONLY",
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF00F0FF),
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                    if (!profile.isCustom) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFF222F3E), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "SYSTEM",
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White.copy(alpha = 0.7f),
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))

                                // Markdown Code Badge for Profile IPs
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(Color(0x33000000), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "IPs: ",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00F0FF),
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "${profile.primaryDns}  |  ${profile.secondaryDns.ifEmpty { "None" }}",
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                if (profile.enableIpv6 && profile.primaryIpv6.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .background(Color(0x2200FF88), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "IPv6: ",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF00FF88),
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "${profile.primaryIpv6}  |  ${profile.secondaryIpv6.ifEmpty { "None" }}",
                                            fontSize = 10.sp,
                                            color = Color(0xFF00FF88).copy(alpha = 0.9f),
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }

                        // Operations (Edit/Delete)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    editId = profile.id
                                    nameInput = profile.name
                                    primaryInput = profile.primaryDns
                                    secondaryInput = profile.secondaryDns
                                    enableIpv6Input = profile.enableIpv6
                                    primaryIpv6Input = profile.primaryIpv6
                                    secondaryIpv6Input = profile.secondaryIpv6
                                    isDefaultInput = profile.isDefault
                                    isFormExpanded = true
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Profile",
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            if (profile.isCustom) {
                                IconButton(
                                    onClick = { onDeleteProfile(profile) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Profile",
                                        tint = Color(0xFFFF4D4D).copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
