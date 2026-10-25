package com.waseemasif.aiagent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Result data class for shell command execution.
 */
data class ShellResult(
    val isSuccess: Boolean,
    val output: String,
    val error: String = "",
    val exitCode: Int = 0
)

/**
 * Observable state of Shizuku service and permissions.
 */
data class ShizukuState(
    val isInstalled: Boolean = false,
    val isRunning: Boolean = false,
    val isPermissionGranted: Boolean = false,
    val version: Int = 0,
    val lastCommand: String = "",
    val lastOutput: String = ""
)

/**
 * ShizukuManager: Handles Shizuku initialization, permission checking,
 * listeners, and privileged system shell command execution.
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        updateState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        updateState()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Shizuku permission request result: granted=$granted")
            updateState()
        }
    }

    private var isInitialized = false

    /**
     * Initializes Shizuku listeners and updates current state.
     */
    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            updateState()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to register Shizuku listeners", e)
            _state.value = _state.value.copy(
                isInstalled = false,
                isRunning = false,
                isPermissionGranted = false
            )
        }
    }

    /**
     * Check if Shizuku service is running and binder is alive.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Check if this app has permission to use Shizuku.
     */
    fun hasPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            if (Shizuku.getVersion() < 11) {
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking Shizuku permission", e)
            false
        }
    }

    /**
     * Request Shizuku permission from the user.
     */
    fun requestPermission() {
        try {
            if (isShizukuAvailable()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                }
            } else {
                Log.w(TAG, "Cannot request permission: Shizuku service is not running")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    /**
     * Updates the StateFlow representing Shizuku availability and status.
     */
    fun updateState() {
        val available = isShizukuAvailable()
        val granted = if (available) hasPermission() else false
        val version = try {
            if (available) Shizuku.getVersion() else 0
        } catch (e: Throwable) {
            0
        }

        _state.value = _state.value.copy(
            isInstalled = true,
            isRunning = available,
            isPermissionGranted = granted,
            version = version
        )
    }

    /**
     * Executes a shell command via Shizuku with elevated privileges.
     * Includes fallback to runtime exec if Shizuku is unavailable.
     */
    suspend fun executeCommand(command: String): ShellResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Executing shell command: $command")
        _state.value = _state.value.copy(lastCommand = command)

        if (hasPermission()) {
            try {
                // Execute using Shizuku newProcess API
                val cmdArray = arrayOf("sh", "-c", command)
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                val process = method.invoke(null, cmdArray, null, null) as ShizukuRemoteProcess

                val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                val error = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                val exitCode = process.waitFor()
                process.destroy()

                val result = ShellResult(
                    isSuccess = exitCode == 0,
                    output = output.trim(),
                    error = error.trim(),
                    exitCode = exitCode
                )

                _state.value = _state.value.copy(lastOutput = output.ifEmpty { "Exit code $exitCode" })
                return@withContext result
            } catch (e: Throwable) {
                Log.e(TAG, "Error executing via Shizuku reflection", e)
            }
        }

        // Fallback: standard process execution (for non-root commands or rooted devices)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val error = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            process.destroy()

            val result = ShellResult(
                isSuccess = exitCode == 0,
                output = output.trim(),
                error = error.trim(),
                exitCode = exitCode
            )
            _state.value = _state.value.copy(lastOutput = output.ifEmpty { "Exit code $exitCode" })
            result
        } catch (e: Exception) {
            Log.e(TAG, "Shell execution failed completely", e)
            ShellResult(
                isSuccess = false,
                output = "",
                error = e.localizedMessage ?: "Unknown shell error",
                exitCode = -1
            )
        }
    }

    // ==============================================================
    // High-level system control helper methods
    // ==============================================================

    /**
     * Toggles Wi-Fi on or off using privileged svc or cmd commands.
     */
    suspend fun setWifi(enabled: Boolean): ShellResult {
        val stateStr = if (enabled) "enable" else "disable"
        // Try svc first, cmd as alternative
        val cmd = "svc wifi $stateStr || cmd wifi set-wifi-enabled ${if (enabled) "enabled" else "disabled"}"
        return executeCommand(cmd)
    }

    /**
     * Toggles Bluetooth on or off.
     */
    suspend fun setBluetooth(enabled: Boolean): ShellResult {
        val stateStr = if (enabled) "enable" else "disable"
        val cmd = "svc bluetooth $stateStr || cmd bluetooth_manager $stateStr"
        return executeCommand(cmd)
    }

    /**
     * Toggles Mobile Data on or off.
     */
    suspend fun setMobileData(enabled: Boolean): ShellResult {
        val stateStr = if (enabled) "enable" else "disable"
        val cmd = "svc data $stateStr"
        return executeCommand(cmd)
    }

    /**
     * Toggles Airplane Mode.
     */
    suspend fun setAirplaneMode(enabled: Boolean): ShellResult {
        val stateStr = if (enabled) "enable" else "disable"
        val cmd = "cmd connectivity airplane-mode $stateStr"
        return executeCommand(cmd)
    }

    /**
     * Adjusts system volume stream (e.g. 3 for media, 2 for ring, 1 for system).
     */
    suspend fun setVolume(stream: Int, level: Int): ShellResult {
        val cmd = "media volume --stream $stream --set $level"
        return executeCommand(cmd)
    }

    /**
     * Sets screen brightness (0 - 255).
     */
    suspend fun setBrightness(level: Int): ShellResult {
        val clamped = level.coerceIn(0, 255)
        val cmd = "settings put system screen_brightness $clamped"
        return executeCommand(cmd)
    }

    /**
     * Simulates device hardware key (e.g., 3=Home, 4=Back, 26=Power/Lock).
     */
    suspend fun sendKeyEvent(keyCode: Int): ShellResult {
        return executeCommand("input keyevent $keyCode")
    }

    /**
     * Launches an application given its package name.
     */
    suspend fun launchApp(packageName: String): ShellResult {
        val cmd = "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        return executeCommand(cmd)
    }
}
