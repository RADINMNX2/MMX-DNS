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
    onSaveProfile: (id: Int, name: String, primary: String, secondary: String, isDefault: Boolean, isCustom: Boolean, onComplete: () -> Unit) -> Unit,
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
    var isDefaultInput by remember { mutableStateOf(false) }

    // Validation state
    var nameError by remember { mutableStateOf(false) }
    var primaryError by remember { mutableStateOf(false) }
    var secondaryError by remember { mutableStateOf(false) }

    // Shake animation trigger counters
    var primaryShakeTrigger by remember { mutableStateOf(0) }
    var nameShakeTrigger by remember { mutableStateOf(0) }
    var secondaryShakeTrigger by remember { mutableStateOf(0) }

    // Morphing button states
    // 0: Idle, 1: Loading (circular spinner), 2: Success Checkmark
    var buttonMorphState by remember { mutableStateOf(0) }

    // Form visibility
    var isFormExpanded by remember { mutableStateOf(false) }

    // Helper functions for IP validation
    fun isValidIp(ip: String): Boolean {
        if (ip.trim().isEmpty()) return false
        val ipv4Regex = """^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)$""".toRegex()
        val ipv6Regex = """^(?:[0-9a-fA-F]{1,4}:){1,7}[0-9a-fA-F]{1,4}$|^(?:[0-9a-fA-F]{1,4}:){1,7}:$|^::(?:[0-9a-fA-F]{1,4}:){0,5}[0-9a-fA-F]{1,4}$""".toRegex()
        return ipv4Regex.matches(ip.trim()) || ipv6Regex.matches(ip.trim())
    }

    fun handleSave() {
        // Reset errors
        nameError = nameInput.trim().isEmpty()
        primaryError = !isValidIp(primaryInput)
        secondaryError = secondaryInput.trim().isNotEmpty() && !isValidIp(secondaryInput)

        if (nameError) nameShakeTrigger++
        if (primaryError) primaryShakeTrigger++
        if (secondaryError) secondaryShakeTrigger++

        if (nameError || primaryError || secondaryError) {
            return
        }

        // Start Morphing Animation
        coroutineScope.launch {
            buttonMorphState = 1 // Shrink and spin
            delay(1200) // Simulate database persistence delay
            buttonMorphState = 2 // Transition to Green Success Checkmark
            delay(1000)

            // Save actual data to DB
            onSaveProfile(
                editId,
                nameInput.trim(),
                primaryInput.trim(),
                secondaryInput.trim(),
                isDefaultInput,
                true // isCustom
            ) {
                coroutineScope.launch {
                    // Reset fields and collapse form
                    editId = 0
                    nameInput = ""
                    primaryInput = ""
                    secondaryInput = ""
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
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
                        // Toggle form expansion
                        if (isFormExpanded && editId != 0) {
                            // If editing, reset to create mode instead of collapsing
                            editId = 0
                            nameInput = ""
                            primaryInput = ""
                            secondaryInput = ""
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

            // Expanded Form Area
            AnimatedVisibility(
                visible = isFormExpanded,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131825)),
                    border = BorderStroke(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF202A3F), Color(0x22101524))
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = if (editId == 0) "CREATE NEW PROFILE" else "EDIT DNS PROFILE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00F0FF).copy(alpha = 0.8f),
                            letterSpacing = 2.sp
                        )

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
                            placeholder = { Text("e.g. My Private DNS") },
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

                        // Primary DNS input
                        OutlinedTextField(
                            value = primaryInput,
                            onValueChange = {
                                primaryInput = it
                                primaryError = false
                            },
                            label = { Text("Primary DNS (IPv4/IPv6)") },
                            placeholder = { Text("e.g. 1.1.1.1") },
                            isError = primaryError,
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            supportingText = {
                                if (primaryError) {
                                    Text("Invalid IPv4 or IPv6 Address format", color = Color(0xFFFF3355))
                                }
                            },
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
                                .shake(primaryShakeTrigger)
                                .testTag("input_primary_dns")
                        )

                        // Secondary DNS input
                        OutlinedTextField(
                            value = secondaryInput,
                            onValueChange = {
                                secondaryInput = it
                                secondaryError = false
                            },
                            label = { Text("Secondary DNS (Optional)") },
                            placeholder = { Text("e.g. 1.0.0.1") },
                            isError = secondaryError,
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            supportingText = {
                                if (secondaryError) {
                                    Text("Invalid IPv4 or IPv6 Address format", color = Color(0xFFFF3355))
                                }
                            },
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
                                .shake(secondaryShakeTrigger)
                                .testTag("input_secondary_dns")
                        )

                        // Morphing "Save" Button
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

            // Profiles list
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
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
                                        if (!profile.isCustom) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFF222F3E), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "SYSTEM",
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF00F0FF)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${profile.primaryDns}  |  ${profile.secondaryDns.ifEmpty { "None" }}",
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontFamily = FontFamily.Monospace
                                    )
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
}
