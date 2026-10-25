package com.waseemasif.aiagent

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.waseemasif.aiagent.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * FloatingWindowService: Displays a floating interactive AI bubble overlay on the phone screen
 * using Jetpack Compose in a WindowManager view, equipped with SpeechRecognizer and text input.
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val NOTIFICATION_CHANNEL_ID = "waseem_ai_floating_service"
        private const val NOTIFICATION_ID = 2001

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    private lateinit var llmHelper: LLMHelper
    private lateinit var actionExecutor: ActionExecutor
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = mutableStateOf(false)
    private var agentStatusText = mutableStateOf("Ready for commands")
    private var isExpanded = mutableStateOf(false)
    private var lastResultText = mutableStateOf("")

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        llmHelper = LLMHelper(this)
        actionExecutor = ActionExecutor(this)

        serviceScope.launch {
            llmHelper.initializeModel()
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initSpeechRecognizer()
        initOverlayWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        serviceScope.cancel()
        destroySpeechRecognizer()
        removeOverlayWindow()
        llmHelper.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------
    // Window Management & Compose Lifecycle
    // -------------------------------------------------------------

    private fun initOverlayWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 240
        }
        windowLayoutParams = params

        val composeView = ComposeView(this).apply {
            // Set up lifecycle owners for ComposeView inside Service
            val lifecycleOwner = ServiceLifecycleOwner()
            lifecycleOwner.performRestore(null)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                WaseemAIAssistantTheme {
                    FloatingOverlayContent(
                        isExpanded = isExpanded.value,
                        isListening = isListening.value,
                        statusMessage = agentStatusText.value,
                        lastResult = lastResultText.value,
                        onToggleExpand = { toggleExpanded() },
                        onMicClick = { toggleListening() },
                        onSubmitCommand = { cmd -> processCommand(cmd) },
                        onClose = { stopSelf() },
                        onDrag = { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            try {
                                windowManager.updateViewLayout(this@apply, params)
                            } catch (e: Exception) {
                                Log.e(TAG, "Update layout error", e)
                            }
                        }
                    )
                }
            }
        }

        floatingView = composeView
        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating window view", e)
        }
    }

    private fun toggleExpanded() {
        isExpanded.value = !isExpanded.value
        val params = windowLayoutParams ?: return
        val view = floatingView ?: return

        if (isExpanded.value) {
            // Enable keyboard input and focus
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            // Return to non-focusable touch-through
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating focus flags", e)
        }
    }

    private fun removeOverlayWindow() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating view", e)
            }
            floatingView = null
        }
    }

    // -------------------------------------------------------------
    // Speech Recognition
    // -------------------------------------------------------------

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening.value = true
                    agentStatusText.value = "Listening..."
                }

                override fun onBeginningOfSpeech() {
                    agentStatusText.value = "Hearing voice..."
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening.value = false
                    agentStatusText.value = "Processing command..."
                }

                override fun onError(error: Int) {
                    isListening.value = false
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No voice command matched"
                        SpeechRecognizer.ERROR_NETWORK -> "Network required for cloud speech"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        else -> "Speech error ($error)"
                    }
                    agentStatusText.value = msg
                }

                override fun onResults(results: Bundle?) {
                    isListening.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull() ?: ""
                    if (spokenText.isNotBlank()) {
                        agentStatusText.value = "Voice: \"$spokenText\""
                        processCommand(spokenText)
                    } else {
                        agentStatusText.value = "No command detected"
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull() ?: ""
                    if (partial.isNotBlank()) {
                        agentStatusText.value = "Heard: $partial"
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun toggleListening() {
        if (isListening.value) {
            speechRecognizer?.stopListening()
            isListening.value = false
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Prefer offline recognition if model installed on device
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            try {
                speechRecognizer?.startListening(intent)
                isListening.value = true
                agentStatusText.value = "Listening..."
            } catch (e: Exception) {
                Log.e(TAG, "Error starting speech recognition", e)
                agentStatusText.value = "Failed to open mic"
            }
        }
    }

    private fun destroySpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying speech recognizer", e)
        }
    }

    // -------------------------------------------------------------
    // Command Pipeline
    // -------------------------------------------------------------

    private fun processCommand(userCommand: String) {
        serviceScope.launch {
            agentStatusText.value = "Thinking..."
            try {
                // 1. LLM parses command to JSON
                val json = llmHelper.generateActionJson(userCommand)
                Log.d(TAG, "Generated Action JSON: $json")

                // 2. ActionExecutor executes system/accessibility actions
                val execResult = actionExecutor.executeJson(json)

                // 3. Update UI feedback
                lastResultText.value = "${if (execResult.isSuccess) "✓" else "✗"} ${execResult.message}"
                agentStatusText.value = execResult.command?.explanation ?: "Done"
            } catch (e: Exception) {
                Log.e(TAG, "Command execution error", e)
                agentStatusText.value = "Error: ${e.message}"
                lastResultText.value = "Failed: ${e.localizedMessage}"
            }
        }
    }

    // -------------------------------------------------------------
    // Foreground Notification
    // -------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Waseem AI Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the active status of Waseem AI Assistant floating overlay"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_service_notification_title))
            .setContentText(getString(R.string.floating_service_notification_desc))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

// -------------------------------------------------------------
// Floating Window UI (Composable)
// -------------------------------------------------------------

@Composable
fun FloatingOverlayContent(
    isExpanded: Boolean,
    isListening: Boolean,
    statusMessage: String,
    lastResult: String,
    onToggleExpand: () -> Unit,
    onMicClick: () -> Unit,
    onSubmitCommand: (String) -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    var textInput by remember { mutableStateOf("") }

    val micScale by animateFloatAsState(
        targetValue = if (isListening) 1.25f else 1.0f,
        label = "micPulse"
    )

    Box(
        modifier = Modifier
            .wrapContentSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        if (!isExpanded) {
            // Collapsed Floating Bubble
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                CyberCyanPrimary.copy(alpha = 0.9f),
                                DarkSurface
                            )
                        )
                    )
                    .border(2.dp, CyberCyanPrimary, CircleShape)
                    .clickable { onToggleExpand() }
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Mic else Icons.Default.SmartToy,
                    contentDescription = "AI Bubble",
                    tint = Color.White,
                    modifier = Modifier
                        .size(32.dp)
                        .scale(micScale)
                )
            }
        } else {
            // Expanded Interactive Dashboard Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = DarkSurface.copy(alpha = 0.96f)
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(
                        listOf(CyberCyanPrimary, ElectricBlueSecondary)
                    )
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                modifier = Modifier
                    .width(320.dp)
                    .padding(4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    // Header Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isListening) NeonMintTertiary else CyberCyanPrimary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Waseem AI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = CyberCyanPrimary
                            )
                        }

                        Row {
                            IconButton(
                                onClick = onToggleExpand,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloseFullscreen,
                                    contentDescription = "Minimize",
                                    tint = DarkOnSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = ErrorRed,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Status / Speech Display Box
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                            if (lastResult.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = lastResult,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (lastResult.startsWith("✓")) SuccessGreen else WarningAmber
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick Command Chips
                    val quickCommands = listOf("Turn on Wi-Fi", "Turn off Wi-Fi", "Home", "Back", "Screenshot", "Open YouTube")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(quickCommands) { cmd ->
                            SuggestionChip(
                                onClick = { onSubmitCommand(cmd) },
                                label = { Text(cmd, fontSize = 11.sp, color = DarkOnSurface) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = DarkSurfaceVariant
                                ),
                                border = SuggestionChipDefaults.suggestionChipBorder(
                                    enabled = true,
                                    borderColor = DarkOutline
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Input & Mic Controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            placeholder = { Text("Command...", fontSize = 12.sp, color = DarkOnSurfaceVariant) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(25.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberCyanPrimary,
                                unfocusedBorderColor = DarkOutline,
                                focusedContainerColor = DarkSurfaceVariant,
                                unfocusedContainerColor = DarkSurfaceVariant
                            ),
                            trailingIcon = {
                                if (textInput.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            onSubmitCommand(textInput)
                                            textInput = ""
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Send,
                                            contentDescription = "Send",
                                            tint = CyberCyanPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Mic Button
                        FilledIconButton(
                            onClick = onMicClick,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (isListening) NeonMintTertiary else CyberCyanPrimary
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .scale(micScale)
                        ) {
                            Icon(
                                imageVector = if (isListening) Icons.Default.GraphicEq else Icons.Default.Mic,
                                contentDescription = "Voice Input",
                                tint = if (isListening) NeonMintOnTertiary else CyberCyanOnPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Service Lifecycle Owner for Jetpack Compose View in WindowManager
// -------------------------------------------------------------

internal class ServiceLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun performRestore(savedState: Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}
