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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DnsProfile
import com.example.ui.components.AnimatedCharacterText
import com.example.ui.components.shake
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    // 0: Idle, 1: Loading (circular spinner), 2: Success Checkmark
    var buttonMorphState by remember { mutableStateOf(0) }

    // Form visibility
    var isFormExpanded by remember { mutableStateOf(false) }

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
        // Reset errors
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

        // Start Morphing Animation
        coroutineScope.launch {
            buttonMorphState = 1 // Shrink and spin
            delay(1000) // Simulate database persistence delay
            buttonMorphState = 2 // Transition to Green Success Checkmark
            delay(800)

            // Save actual data to DB
            onSaveProfile(
                editId,
                nameInput.trim(),
                primaryInput.trim(),
                secondaryInput.trim(),
                enableIpv6Input,
                if (enableIpv6Input) primaryIpv6Input.trim() else "",
                if (enableIpv6Input) secondaryIpv6Input.trim() else "",
                isDefaultInput,
                true // isCustom
            ) {
                coroutineScope.launch {
                    // Reset fields and collapse form
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

                // Vertical grid lines
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

                // Horizontal grid lines
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
        // Render background particles
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
                            text = "PROFILES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00F0FF).copy(alpha = 0.8f),
                            letterSpacing = 4.sp
                        )
                        Text(
                            text = "DNS SERVERS",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }

                    // Add Profile FAB-like button
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
                                colors = listOf(Color(0xFF00F0FF).copy(alpha = 0.3f), Color(0x22101524))
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
                                text = if (editId == 0) "CREATE NEW PROFILE" else "EDIT DNS PROFILE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00F0FF).copy(alpha = 0.9f),
                                letterSpacing = 2.sp
                            )

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
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                        }

                        // Character typing visualization on header name
                        if (nameInput.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Preview: ",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                                AnimatedCharacterText(
                                    text = nameInput,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = Color(0xFF00F0FF),
                                        fontWeight = FontWeight.Bold
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
                            label = { Text("Profile Name") },
                            placeholder = { Text("e.g. Cyber Shield DNS") },
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
                                        text = "IPv4 ENDPOINTS",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF00F0FF),
                                        letterSpacing = 1.sp
                                    )
                                }

                                // Primary IPv4
                                OutlinedTextField(
                                    value = primaryInput,
                                    onValueChange = {
                                        primaryInput = it
                                        primaryError = false
                                    },
                                    label = { Text("Primary IPv4 Address") },
                                    placeholder = { Text("e.g. 1.1.1.1") },
                                    isError = primaryError,
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    supportingText = {
                                        if (primaryError) {
                                            Text("Valid IPv4 required (e.g. 1.1.1.1)", color = Color(0xFFFF3355))
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF00F0FF),
                                        unfocusedBorderColor = Color(0x33FFFFFF),
                                        errorBorderColor = Color(0xFFFF3355),
                                        focusedLabelColor = Color(0xFF00F0FF),
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

                                // Secondary IPv4
                                OutlinedTextField(
                                    value = secondaryInput,
                                    onValueChange = {
                                        secondaryInput = it
                                        secondaryError = false
                                    },
                                    label = { Text("Secondary IPv4 Address (Optional)") },
                                    placeholder = { Text("e.g. 1.0.0.1") },
                                    isError = secondaryError,
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    supportingText = {
                                        if (secondaryError) {
                                            Text("Invalid IPv4 address format", color = Color(0xFFFF3355))
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF00F0FF),
                                        unfocusedBorderColor = Color(0x33FFFFFF),
                                        errorBorderColor = Color(0xFFFF3355),
                                        focusedLabelColor = Color(0xFF00F0FF),
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
                                            text = "ENABLE IPV6 DUAL-STACK",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (enableIpv6Input) Color(0xFF00FF88) else Color.White,
                                            letterSpacing = 0.5.sp
                                        )
                                        Text(
                                            text = "Activates IPv6 primary and secondary routes",
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
                                            text = "IPv6 ENDPOINTS",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF00FF88),
                                            letterSpacing = 1.sp
                                        )
                                    }

                                    // Primary IPv6
                                    OutlinedTextField(
                                        value = primaryIpv6Input,
                                        onValueChange = {
                                            primaryIpv6Input = it
                                            primaryIpv6Error = false
                                        },
                                        label = { Text("Primary IPv6 Address") },
                                        placeholder = { Text("e.g. 2001:4860:4860::8888") },
                                        isError = primaryIpv6Error,
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        supportingText = {
                                            if (primaryIpv6Error) {
                                                Text("Valid IPv6 required (e.g. 2606:4700:4700::1111)", color = Color(0xFFFF3355))
                                            }
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF00FF88),
                                            unfocusedBorderColor = Color(0x33FFFFFF),
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

                                    // Secondary IPv6
                                    OutlinedTextField(
                                        value = secondaryIpv6Input,
                                        onValueChange = {
                                            secondaryIpv6Input = it
                                            secondaryIpv6Error = false
                                        },
                                        label = { Text("Secondary IPv6 Address (Optional)") },
                                        placeholder = { Text("e.g. 2001:4860:4860::8884") },
                                        isError = secondaryIpv6Error,
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        supportingText = {
                                            if (secondaryIpv6Error) {
                                                Text("Invalid IPv6 address format", color = Color(0xFFFF3355))
                                            }
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF00FF88),
                                            unfocusedBorderColor = Color(0x33FFFFFF),
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
                                }
                            }
                        }

                        // Morphing "Save Profile" Button
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val buttonWidth by animateDpAsState(
                                targetValue = if (buttonMorphState == 0) 260.dp else 56.dp,
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                label = "btn_width"
                            )

                            val buttonColor by animateColorAsState(
                                targetValue = when (buttonMorphState) {
                                    2 -> Color(0xFF00FF88) // Success (Green)
                                    else -> Color(0xFF0072FF) // Idle (Blue)
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

        // Section Header Item
            item(key = "section_title_item") {
                Text(
                    text = "AVAILABLE DNS PROFILES (${profiles.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f),
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
                                // Radio button look
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
                                                    text = "IPv4/IPv6",
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF00FF88)
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
                                                    color = Color(0xFF00F0FF)
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
                                                    color = Color.White.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "IPv4: ${profile.primaryDns}  |  ${profile.secondaryDns.ifEmpty { "None" }}",
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (profile.enableIpv6 && profile.primaryIpv6.isNotEmpty()) {
                                        Text(
                                            text = "IPv6: ${profile.primaryIpv6}  |  ${profile.secondaryIpv6.ifEmpty { "None" }}",
                                            fontSize = 10.sp,
                                            color = Color(0xFF00FF88).copy(alpha = 0.8f),
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }

                            // Operations (Edit/Delete)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Edit Action
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

                                // Delete Action (only if custom)
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
