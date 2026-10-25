package com.waseemasif.aiagent

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Represents the initialization and operational state of the local LLM.
 */
sealed class LLMStatus {
    object Idle : LLMStatus()
    data class Loading(val progressMessage: String) : LLMStatus()
    data class Ready(val modelName: String, val isMediaPipeActive: Boolean) : LLMStatus()
    data class Error(val errorMessage: String) : LLMStatus()
}

/**
 * LLMHelper: Coordinates on-device LLM inference using MediaPipe Tasks GenAI.
 * Formulates system prompts to constrain responses to structured JSON commands
 * for phone automation.
 */
class LLMHelper(private val context: Context) {

    companion object {
        private const val TAG = "LLMHelper"
        const val DEFAULT_MODEL_NAME = "gemma2-2b-it-gpu-int4.bin"

        private val SYSTEM_PROMPT = """
You are an offline Android phone control assistant.
Translate user voice/text commands into precise JSON actions.
Output ONLY valid JSON without markdown formatting or code fences.

Schema:
{
  "action": "SYSTEM_TOGGLE" | "LAUNCH_APP" | "UI_CLICK" | "UI_TYPE" | "SYSTEM_NAV" | "SET_VOLUME" | "SET_BRIGHTNESS" | "SPEAK",
  "target": "WIFI" | "BLUETOOTH" | "DATA" | "AIRPLANE" | "<App Name>" | "<Button or UI Text>",
  "state": "ON" | "OFF" | "TOGGLE" | null,
  "text": "<text to type, or spoken feedback>",
  "value": <numeric value for volume 0-15 or brightness 0-255 if applicable>,
  "explanation": "<short confirmation>"
}
""".trimIndent()
    }

    private var llmInference: LlmInference? = null
    private val _status = MutableStateFlow<LLMStatus>(LLMStatus.Idle)
    val status: StateFlow<LLMStatus> = _status.asStateFlow()

    private var currentModelPath: String = ""

    init {
        // Look for model in app files directory
        val defaultModelFile = File(context.filesDir, DEFAULT_MODEL_NAME)
        if (defaultModelFile.exists()) {
            currentModelPath = defaultModelFile.absolutePath
        }
    }

    /**
     * Initializes the MediaPipe LlmInference engine with the given model file path.
     */
    suspend fun initializeModel(customPath: String? = null): Boolean = withContext(Dispatchers.IO) {
        val path = customPath ?: currentModelPath
        val file = File(path)

        if (!file.exists() || file.length() == 0L) {
            val errorMsg = "Model file not found at: $path (Place $DEFAULT_MODEL_NAME in app filesDir or internal storage)"
            Log.w(TAG, errorMsg)
            _status.value = LLMStatus.Ready(
                modelName = "Built-in Rule Agent (Model file not placed)",
                isMediaPipeActive = false
            )
            return@withContext false
        }

        try {
            _status.value = LLMStatus.Loading("Loading MediaPipe LLM weights...")
            Log.d(TAG, "Initializing MediaPipe LlmInference with model: ${file.absolutePath} (${file.length() / 1024 / 1024} MB)")

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(512)
                .setTemperature(0.2f) // Lower temperature for deterministic JSON output
                .setTopK(40)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            currentModelPath = file.absolutePath
            _status.value = LLMStatus.Ready(
                modelName = file.name,
                isMediaPipeActive = true
            )
            Log.d(TAG, "MediaPipe LlmInference initialized successfully!")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize MediaPipe LlmInference", e)
            _status.value = LLMStatus.Ready(
                modelName = "Built-in Local Agent (MediaPipe init fallback: ${e.localizedMessage})",
                isMediaPipeActive = false
            )
            false
        }
    }

    /**
     * Generates a structured JSON action plan from user input.
     * Uses MediaPipe when active; otherwise uses an intelligent local deterministic parser
     * so commands work reliably offline.
     */
    suspend fun generateActionJson(userInput: String): String = withContext(Dispatchers.IO) {
        val trimmed = userInput.trim()
        if (trimmed.isEmpty()) {
            return@withContext """{"action": "SPEAK", "text": "I didn't hear any command."}"""
        }

        val inference = llmInference
        if (inference != null) {
            try {
                val fullPrompt = "$SYSTEM_PROMPT\n\nUser command: \"$trimmed\"\nJSON Output:"
                Log.d(TAG, "Sending prompt to MediaPipe LLM...")
                val rawResponse = inference.generateResponse(fullPrompt)
                Log.d(TAG, "MediaPipe LLM Response: $rawResponse")

                val cleaned = cleanJsonOutput(rawResponse)
                if (isValidJson(cleaned)) {
                    return@withContext cleaned
                }
            } catch (e: Throwable) {
                Log.e(TAG, "MediaPipe generation error, using fallback engine", e)
            }
        }

        // Offline deterministic intent classification fallback
        return@withContext generateFallbackJson(trimmed)
    }

    /**
     * Strips Markdown formatting (e.g. ```json ... ```) from LLM output.
     */
    private fun cleanJsonOutput(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```json")) {
            text = text.removePrefix("```json")
        } else if (text.startsWith("```")) {
            text = text.removePrefix("```")
        }
        if (text.endsWith("```")) {
            text = text.removeSuffix("```")
        }
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            text = text.substring(firstBrace, lastBrace + 1)
        }
        return text.trim()
    }

    private fun isValidJson(text: String): Boolean {
        return text.startsWith("{") && text.endsWith("}") && text.contains("\"action\"")
    }

    /**
     * Deterministic offline intent recognizer that converts spoken/typed natural language
     * commands directly to the expected JSON schema.
     */
    fun generateFallbackJson(command: String): String {
        val lower = command.lowercase(Locale.ROOT)

        // Wi-Fi commands
        if (lower.contains("wifi") || lower.contains("wi-fi")) {
            val state = if (lower.contains("off") || lower.contains("disable")) "OFF" else "ON"
            return """{"action": "SYSTEM_TOGGLE", "target": "WIFI", "state": "$state", "explanation": "Turning Wi-Fi $state"}"""
        }

        // Bluetooth commands
        if (lower.contains("bluetooth")) {
            val state = if (lower.contains("off") || lower.contains("disable")) "OFF" else "ON"
            return """{"action": "SYSTEM_TOGGLE", "target": "BLUETOOTH", "state": "$state", "explanation": "Turning Bluetooth $state"}"""
        }

        // Mobile Data
        if (lower.contains("mobile data") || lower.contains("cellular data") || lower.contains("data network")) {
            val state = if (lower.contains("off") || lower.contains("disable")) "OFF" else "ON"
            return """{"action": "SYSTEM_TOGGLE", "target": "DATA", "state": "$state", "explanation": "Turning Mobile Data $state"}"""
        }

        // Airplane mode
        if (lower.contains("airplane") || lower.contains("flight mode")) {
            val state = if (lower.contains("off") || lower.contains("disable")) "OFF" else "ON"
            return """{"action": "SYSTEM_TOGGLE", "target": "AIRPLANE", "state": "$state", "explanation": "Turning Airplane Mode $state"}"""
        }

        // Navigation
        if (lower == "home" || lower.contains("go home") || lower.contains("home screen")) {
            return """{"action": "SYSTEM_NAV", "target": "HOME", "explanation": "Navigating to home screen"}"""
        }
        if (lower == "back" || lower.contains("go back")) {
            return """{"action": "SYSTEM_NAV", "target": "BACK", "explanation": "Going back"}"""
        }
        if (lower.contains("lock screen") || lower.contains("lock phone") || lower.contains("turn off screen")) {
            return """{"action": "SYSTEM_NAV", "target": "LOCK", "explanation": "Locking screen"}"""
        }
        if (lower.contains("screenshot") || lower.contains("capture screen")) {
            return """{"action": "SYSTEM_NAV", "target": "SCREENSHOT", "explanation": "Taking a screenshot"}"""
        }

        // Volume controls
        if (lower.contains("mute") || lower.contains("silence")) {
            return """{"action": "SET_VOLUME", "target": "MEDIA", "value": 0, "explanation": "Muting media volume"}"""
        }
        if (lower.contains("volume up") || lower.contains("increase volume")) {
            return """{"action": "SET_VOLUME", "target": "MEDIA", "value": 12, "explanation": "Setting volume to 12"}"""
        }
        if (lower.contains("volume down") || lower.contains("lower volume")) {
            return """{"action": "SET_VOLUME", "target": "MEDIA", "value": 4, "explanation": "Setting volume to 4"}"""
        }

        // Launch Apps
        val appMatches = listOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "chrome" to "com.android.chrome",
            "settings" to "com.android.settings",
            "camera" to "com.google.android.GoogleCamera",
            "calculator" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock",
            "maps" to "com.google.android.apps.maps",
            "messages" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer",
            "spotify" to "com.spotify.music"
        )
        for ((name, pkg) in appMatches) {
            if (lower.contains("open $name") || lower.contains("launch $name") || lower.contains("start $name")) {
                return """{"action": "LAUNCH_APP", "target": "$pkg", "explanation": "Opening $name"}"""
            }
        }
        if (lower.startsWith("open ") || lower.startsWith("launch ")) {
            val appTarget = command.substring(5).trim()
            return """{"action": "LAUNCH_APP", "target": "$appTarget", "explanation": "Opening $appTarget"}"""
        }

        // Typing into UI
        if (lower.startsWith("type ") || lower.startsWith("enter ") || lower.startsWith("write ")) {
            val words = command.split(" ", limit = 2)
            val textToType = if (words.size > 1) words[1] else ""
            return """{"action": "UI_TYPE", "target": "ACTIVE_INPUT", "text": "$textToType", "explanation": "Typing: $textToType"}"""
        }

        // Clicking UI element
        if (lower.startsWith("click ") || lower.startsWith("tap ") || lower.startsWith("press ")) {
            val targetText = command.substring(command.indexOf(' ') + 1).trim()
            return """{"action": "UI_CLICK", "target": "$targetText", "explanation": "Clicking element: $targetText"}"""
        }

        // Default: Spoken feedback / general response
        return """{"action": "SPEAK", "target": "USER", "text": "Understood: $command", "explanation": "Awaiting next directive."}"""
    }

    fun close() {
        try {
            llmInference?.close()
            llmInference = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing LlmInference", e)
        }
    }
}
