package com.waseemasif.aiagent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured schema representing an agent decision.
 */
data class AgentCommand(
    @SerializedName("action") val action: String = "",
    @SerializedName("target") val target: String? = null,
    @SerializedName("state") val state: String? = null,
    @SerializedName("text") val text: String? = null,
    @SerializedName("value") val value: Int? = null,
    @SerializedName("explanation") val explanation: String? = null
)

/**
 * Outcome of executing an agent command.
 */
data class ExecutionResult(
    val isSuccess: Boolean,
    val message: String,
    val command: AgentCommand? = null
)

/**
 * Audit log entry for UI display.
 */
data class ActionLogEntry(
    val timestamp: String,
    val commandDescription: String,
    val isSuccess: Boolean,
    val details: String
)

/**
 * ActionExecutor: Parses JSON from LLMHelper and dispatches operations
 * to ShizukuManager (system privileges) or PhoneControlService (UI automation).
 */
class ActionExecutor(private val context: Context) {

    companion object {
        private const val TAG = "ActionExecutor"
        private val gson = Gson()

        private val _history = MutableStateFlow<List<ActionLogEntry>>(emptyList())
        val history: StateFlow<List<ActionLogEntry>> = _history.asStateFlow()

        fun addLog(description: String, isSuccess: Boolean, details: String) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = ActionLogEntry(time, description, isSuccess, details)
            _history.value = listOf(entry) + _history.value.take(49)
        }
    }

    /**
     * Parses the JSON string and routes execution.
     */
    suspend fun executeJson(jsonString: String): ExecutionResult = withContext(Dispatchers.Default) {
        val command = try {
            gson.fromJson(jsonString, AgentCommand::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON: $jsonString", e)
            val result = ExecutionResult(false, "Invalid JSON command format: ${e.message}")
            addLog("Parse Error", false, jsonString)
            return@withContext result
        }

        if (command == null || command.action.isBlank()) {
            return@withContext ExecutionResult(false, "Empty action in parsed command")
        }

        Log.d(TAG, "Routing command: action=${command.action}, target=${command.target}, state=${command.state}")

        val result = when (command.action.uppercase(Locale.ROOT)) {
            "SYSTEM_TOGGLE" -> executeSystemToggle(command)
            "LAUNCH_APP" -> executeLaunchApp(command)
            "UI_CLICK" -> executeUiClick(command)
            "UI_TYPE" -> executeUiType(command)
            "SYSTEM_NAV" -> executeSystemNav(command)
            "SET_VOLUME" -> executeSetVolume(command)
            "SET_BRIGHTNESS" -> executeSetBrightness(command)
            "SPEAK" -> ExecutionResult(true, command.text ?: command.explanation ?: "OK", command)
            else -> ExecutionResult(false, "Unsupported action: ${command.action}", command)
        }

        addLog(
            description = "${command.action}: ${command.target ?: ""}",
            isSuccess = result.isSuccess,
            details = result.message
        )

        result
    }

    private suspend fun executeSystemToggle(command: AgentCommand): ExecutionResult {
        val target = command.target?.uppercase(Locale.ROOT) ?: ""
        val isEnable = command.state?.uppercase(Locale.ROOT) == "ON"

        return when (target) {
            "WIFI" -> {
                val shellResult = ShizukuManager.setWifi(isEnable)
                if (shellResult.isSuccess) {
                    ExecutionResult(true, "Wi-Fi ${if (isEnable) "enabled" else "disabled"}", command)
                } else {
                    ExecutionResult(false, "Failed to toggle Wi-Fi via Shizuku: ${shellResult.error.ifEmpty { "Permission needed" }}", command)
                }
            }
            "BLUETOOTH" -> {
                val shellResult = ShizukuManager.setBluetooth(isEnable)
                if (shellResult.isSuccess) {
                    ExecutionResult(true, "Bluetooth ${if (isEnable) "enabled" else "disabled"}", command)
                } else {
                    ExecutionResult(false, "Failed to toggle Bluetooth: ${shellResult.error}", command)
                }
            }
            "DATA", "MOBILEDATA" -> {
                val shellResult = ShizukuManager.setMobileData(isEnable)
                ExecutionResult(shellResult.isSuccess, "Mobile Data ${if (isEnable) "enabled" else "disabled"}", command)
            }
            "AIRPLANE" -> {
                val shellResult = ShizukuManager.setAirplaneMode(isEnable)
                ExecutionResult(shellResult.isSuccess, "Airplane Mode ${if (isEnable) "enabled" else "disabled"}", command)
            }
            else -> ExecutionResult(false, "Unknown system toggle target: $target", command)
        }
    }

    private suspend fun executeLaunchApp(command: AgentCommand): ExecutionResult {
        val target = command.target ?: return ExecutionResult(false, "Target app is required", command)

        // 1. Try launching via package manager
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(target)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            return ExecutionResult(true, "Launched app: $target", command)
        }

        // 2. Search installed packages by label
        try {
            val packages = pm.getInstalledApplications(0)
            for (appInfo in packages) {
                val label = pm.getApplicationLabel(appInfo).toString()
                if (label.contains(target, ignoreCase = true)) {
                    val intent = pm.getLaunchIntentForPackage(appInfo.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return ExecutionResult(true, "Launched $label (${appInfo.packageName})", command)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed searching installed apps", e)
        }

        // 3. Fallback to privileged monkey command via Shizuku
        val shellRes = ShizukuManager.launchApp(target)
        return if (shellRes.isSuccess) {
            ExecutionResult(true, "Launched app $target via Shizuku shell", command)
        } else {
            ExecutionResult(false, "Could not find or launch app: $target", command)
        }
    }

    private fun executeUiClick(command: AgentCommand): ExecutionResult {
        val target = command.target ?: return ExecutionResult(false, "Target UI text/ID is required", command)
        val service = PhoneControlService.instance
            ?: return ExecutionResult(false, "Accessibility Service is not enabled. Enable PhoneControlService in Settings.", command)

        val success = if (target.contains(":id/")) {
            service.clickById(target)
        } else {
            service.clickByText(target)
        }

        return if (success) {
            ExecutionResult(true, "Clicked UI element: $target", command)
        } else {
            ExecutionResult(false, "Could not find clickable element matching: '$target' on screen", command)
        }
    }

    private fun executeUiType(command: AgentCommand): ExecutionResult {
        val text = command.text ?: return ExecutionResult(false, "Text to type is required", command)
        val service = PhoneControlService.instance
            ?: return ExecutionResult(false, "Accessibility Service is not enabled. Enable PhoneControlService in Settings.", command)

        val success = service.setText(command.target, text)
        return if (success) {
            ExecutionResult(true, "Typed text: \"$text\"", command)
        } else {
            ExecutionResult(false, "No active editable input found on current screen", command)
        }
    }

    private suspend fun executeSystemNav(command: AgentCommand): ExecutionResult {
        val target = command.target?.uppercase(Locale.ROOT) ?: ""
        val service = PhoneControlService.instance

        when (target) {
            "HOME" -> {
                if (service != null && service.performNavigation(AccessibilityService.GLOBAL_ACTION_HOME)) {
                    return ExecutionResult(true, "Navigated Home", command)
                }
                ShizukuManager.sendKeyEvent(3)
                return ExecutionResult(true, "Sent HOME keyevent", command)
            }
            "BACK" -> {
                if (service != null && service.performNavigation(AccessibilityService.GLOBAL_ACTION_BACK)) {
                    return ExecutionResult(true, "Navigated Back", command)
                }
                ShizukuManager.sendKeyEvent(4)
                return ExecutionResult(true, "Sent BACK keyevent", command)
            }
            "LOCK" -> {
                ShizukuManager.sendKeyEvent(26)
                return ExecutionResult(true, "Screen locked", command)
            }
            "SCREENSHOT" -> {
                if (service != null && service.performNavigation(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)) {
                    return ExecutionResult(true, "Screenshot captured", command)
                }
                ShizukuManager.executeCommand("screencap -p /sdcard/ai_screenshot.png")
                return ExecutionResult(true, "Screenshot captured to /sdcard", command)
            }
            "NOTIFICATIONS" -> {
                service?.performNavigation(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                return ExecutionResult(true, "Opened notifications", command)
            }
            else -> return ExecutionResult(false, "Unknown navigation target: $target", command)
        }
    }

    private suspend fun executeSetVolume(command: AgentCommand): ExecutionResult {
        val level = command.value ?: 8
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val clamped = level.coerceIn(0, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, AudioManager.FLAG_SHOW_UI)
            return ExecutionResult(true, "Volume set to $clamped/$maxVol", command)
        } catch (e: Exception) {
            Log.e(TAG, "Failed setting volume via AudioManager, falling back to Shizuku", e)
        }
        val res = ShizukuManager.setVolume(3, level)
        return ExecutionResult(res.isSuccess, "Volume adjusted: ${res.output}", command)
    }

    private suspend fun executeSetBrightness(command: AgentCommand): ExecutionResult {
        val level = command.value ?: 128
        val res = ShizukuManager.setBrightness(level)
        return ExecutionResult(res.isSuccess, "Brightness set to $level", command)
    }
}
