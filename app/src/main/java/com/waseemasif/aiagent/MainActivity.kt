package com.waseemasif.aiagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waseemasif.aiagent.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

/**
 * MainActivity: Management Hub for Waseem AI Assistant.
 * Handles runtime permissions (Microphone, Overlay, Notifications),
 * verifies Accessibility Service & Shizuku connectivity,
 * configures the on-device MediaPipe LLM model, and launches the Floating Overlay.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Initialize Shizuku listeners
        ShizukuManager.init(this)

        setContent {
            WaseemAIAssistantTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = DarkBackground,
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    MainDashboardScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuManager.updateState()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Observable states
    val isOverlayActive by FloatingWindowService.isRunning.collectAsStateWithLifecycle()
    val isAccessibilityActive by PhoneControlService.isServiceConnected.collectAsStateWithLifecycle()
    val shizukuState by ShizukuManager.state.collectAsStateWithLifecycle()
    val executionLogs by ActionExecutor.history.collectAsStateWithLifecycle()

    // State for local LLM
    val llmHelper = remember { LLMHelper(context) }
    val llmStatus by llmHelper.status.collectAsStateWithLifecycle()
    val actionExecutor = remember { ActionExecutor(context) }

    // Runtime Permission States
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasOverlayPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (!granted) {
            Toast.makeText(context, "Microphone permission is required for voice commands", Toast.LENGTH_SHORT).show()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        llmHelper.initializeModel()
    }

    // Interactive Test States
    var testCommandText by remember { mutableStateOf("Turn on Wi-Fi") }
    var testResultOutput by remember { mutableStateOf("") }
    var isExecutingTest by remember { mutableStateOf(false) }
    var modelPathInput by remember { mutableStateOf(File(context.filesDir, LLMHelper.DEFAULT_MODEL_NAME).absolutePath) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header Banner
        item {
            Spacer(modifier = Modifier.height(8.dp))
            HeaderBanner()
        }

        // Master Floating Service Toggle
        item {
            FloatingServiceControlCard(
                isOverlayActive = isOverlayActive,
                canStart = hasOverlayPermission && hasMicPermission,
                onToggleService = {
                    if (isOverlayActive) {
                        FloatingWindowService.stop(context)
                    } else {
                        if (!hasOverlayPermission) {
                            Toast.makeText(context, "Please grant Overlay permission first", Toast.LENGTH_SHORT).show()
                            openOverlaySettings(context)
                        } else if (!hasMicPermission) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            FloatingWindowService.start(context)
                        }
                    }
                }
            )
        }

        // Subsystem Readiness Checklist
        item {
            Text(
                text = "System Integrations & Permissions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = DarkOnSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 1. Floating Window Overlay
                StatusItemCard(
                    title = "Display Over Other Apps",
                    subtitle = if (hasOverlayPermission) "Granted (Floating Bubble ready)" else "Missing: Needed for screen overlay",
                    isReady = hasOverlayPermission,
                    icon = Icons.Default.Layers,
                    actionLabel = if (!hasOverlayPermission) "Grant" else null,
                    onAction = { openOverlaySettings(context) }
                )

                // 2. Microphone Permission
                StatusItemCard(
                    title = "Microphone Access",
                    subtitle = if (hasMicPermission) "Granted (Offline voice input ready)" else "Missing: Needed for speech commands",
                    isReady = hasMicPermission,
                    icon = Icons.Default.Mic,
                    actionLabel = if (!hasMicPermission) "Allow" else null,
                    onAction = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )

                // 3. Accessibility Service
                StatusItemCard(
                    title = "Accessibility Automation",
                    subtitle = if (isAccessibilityActive) "Active (Can click & type into UI)" else "Disabled: Required for UI clicks & typing",
                    isReady = isAccessibilityActive,
                    icon = Icons.Default.AccessibilityNew,
                    actionLabel = if (!isAccessibilityActive) "Enable" else null,
                    onAction = { openAccessibilitySettings(context) }
                )

                // 4. Shizuku Privileged Shell
                StatusItemCard(
                    title = "Shizuku Shell Privileges",
                    subtitle = when {
                        shizukuState.isPermissionGranted -> "Authorized (v${shizukuState.version}) - Full system toggles"
                        shizukuState.isRunning -> "Running - Permission required"
                        else -> "Not Running - Standalone/Root fallback mode"
                    },
                    isReady = shizukuState.isPermissionGranted,
                    icon = Icons.Default.Terminal,
                    actionLabel = if (shizukuState.isRunning && !shizukuState.isPermissionGranted) "Authorize" else null,
                    onAction = { ShizukuManager.requestPermission() }
                )
            }
        }

        // On-Device LLM Model Configuration
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(DarkOutline, DarkSurfaceVariant)))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = CyberCyanPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "MediaPipe LLM Engine",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = DarkOnSurface
                            )
                        }

                        // Model Status Badge
                        val badgeColor = when (llmStatus) {
                            is LLMStatus.Ready -> if ((llmStatus as LLMStatus.Ready).isMediaPipeActive) NeonMintTertiary else ElectricBlueSecondary
                            is LLMStatus.Loading -> WarningAmber
                            else -> DarkOnSurfaceVariant
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = badgeColor.copy(alpha = 0.2f),
                            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(badgeColor, badgeColor)))
                        ) {
                            Text(
                                text = when (llmStatus) {
                                    is LLMStatus.Ready -> if ((llmStatus as LLMStatus.Ready).isMediaPipeActive) "MediaPipe GPU" else "Local Fast Agent"
                                    is LLMStatus.Loading -> "Loading..."
                                    else -> "Standby"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = modelPathInput,
                        onValueChange = { modelPathInput = it },
                        label = { Text("Model Path (.bin or .task)", fontSize = 12.sp) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyanPrimary,
                            unfocusedBorderColor = DarkOutline
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                llmHelper.initializeModel(modelPathInput)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyanContainer),
                        modifier = Modifier.fillMaxWidth().testTag("reload_model_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Initialize / Reload Model", color = CyberCyanOnContainer)
                    }
                }
            }
        }

        // Live Command Tester Console
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(DarkOutline, DarkSurfaceVariant)))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Interactive Command Terminal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DarkOnSurface
                    )
                    Text(
                        text = "Test voice/text intent parsing and direct system execution",
                        style = MaterialTheme.typography.bodySmall,
                        color = DarkOnSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = testCommandText,
                            onValueChange = { testCommandText = it },
                            placeholder = { Text("e.g. Turn on Wi-Fi, Click Settings", fontSize = 12.sp) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberCyanPrimary,
                                unfocusedBorderColor = DarkOutline
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (testCommandText.isNotBlank()) {
                                    scope.launch {
                                        isExecutingTest = true
                                        val json = llmHelper.generateActionJson(testCommandText)
                                        val result = actionExecutor.executeJson(json)
                                        testResultOutput = "JSON: $json\n\nResult: ${result.message}"
                                        isExecutingTest = false
                                    }
                                }
                            },
                            enabled = !isExecutingTest,
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyanPrimary),
                            modifier = Modifier.testTag("execute_test_button")
                        ) {
                            if (isExecutingTest) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = DarkSurface)
                            } else {
                                Text("Execute", color = CyberCyanOnPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (testResultOutput.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = testResultOutput,
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonMintTertiary,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // Live Audit Log History
        item {
            Text(
                text = "Execution Activity Log",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = DarkOnSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (executionLogs.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No actions executed yet. Tap 'Execute' or speak to the floating bubble.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DarkOnSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(executionLogs) { log ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DarkSurface,
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(DarkOutline, DarkSurfaceVariant))),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (log.isSuccess) SuccessGreen else ErrorRed)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = log.commandDescription,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = log.timestamp,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DarkOnSurfaceVariant
                                )
                            }
                            Text(
                                text = log.details,
                                style = MaterialTheme.typography.bodySmall,
                                color = DarkOnSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun HeaderBanner() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(listOf(CyberCyanPrimary, ElectricBlueSecondary))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(CyberCyanContainer)
                    .border(1.5.dp, CyberCyanPrimary, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "AI Agent",
                    tint = CyberCyanPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = "Waseem AI Assistant",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Developer: Waseem Asif (wasimasif761@gmail.com)",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberCyanPrimary
                )
                Text(
                    text = "Autonomous Offline Phone Controller",
                    style = MaterialTheme.typography.labelSmall,
                    color = DarkOnSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun FloatingServiceControlCard(
    isOverlayActive: Boolean,
    canStart: Boolean,
    onToggleService: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isOverlayActive) CyberCyanContainer.copy(alpha = 0.4f) else DarkSurface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                if (isOverlayActive) listOf(CyberCyanPrimary, NeonMintTertiary) else listOf(DarkOutline, DarkOutline)
            )
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isOverlayActive) "Overlay Service Running" else "Floating Overlay Stopped",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isOverlayActive) CyberCyanPrimary else Color.White
                )
                Text(
                    text = if (isOverlayActive) "Floating bubble is on your screen ready for voice/text commands" else "Tap below to launch the on-screen assistant bubble",
                    style = MaterialTheme.typography.bodySmall,
                    color = DarkOnSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = onToggleService,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isOverlayActive) ErrorRed else CyberCyanPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("toggle_floating_service_button")
            ) {
                Icon(
                    imageVector = if (isOverlayActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isOverlayActive) "Stop" else "Start Overlay",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun StatusItemCard(
    title: String,
    subtitle: String,
    isReady: Boolean,
    icon: ImageVector,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkSurface,
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(DarkOutline, DarkSurfaceVariant))),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isReady) SuccessGreen.copy(alpha = 0.15f) else WarningAmber.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isReady) SuccessGreen else WarningAmber,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = DarkOnSurfaceVariant,
                    fontSize = 11.sp
                )
            }

            if (actionLabel != null) {
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onAction,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyanPrimary),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.horizontalGradient(listOf(CyberCyanPrimary, CyberCyanPrimary))),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp).testTag("action_${title.replace(" ", "_").lowercase()}")
                ) {
                    Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else if (isReady) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Ready",
                    tint = SuccessGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// -------------------------------------------------------------
// Intent Helpers
// -------------------------------------------------------------

private fun openOverlaySettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
