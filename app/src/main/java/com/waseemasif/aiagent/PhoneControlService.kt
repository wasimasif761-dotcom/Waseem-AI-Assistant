package com.waseemasif.aiagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PhoneControlService: Accessibility Service to automate screen interactions
 * such as finding nodes by text/ID, clicking buttons, typing text, and system navigation.
 */
class PhoneControlService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneControlService"

        private var _instance: PhoneControlService? = null
        val instance: PhoneControlService?
            get() = _instance

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _lastActionLog = MutableStateFlow("Accessibility Service idle")
        val lastActionLog: StateFlow<String> = _lastActionLog.asStateFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = this
        _isServiceConnected.value = true
        _lastActionLog.value = "PhoneControlService connected and ready."
        Log.d(TAG, "PhoneControlService connected successfully")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can be used to track current foreground window or package if needed
    }

    override fun onInterrupt() {
        Log.w(TAG, "PhoneControlService interrupted")
        _lastActionLog.value = "Accessibility Service interrupted"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (_instance == this) {
            _instance = null
            _isServiceConnected.value = false
        }
        Log.d(TAG, "PhoneControlService destroyed")
    }

    /**
     * Finds a UI element matching the given text (case-insensitive substring)
     * and performs an ACTION_CLICK on it or its nearest clickable parent.
     */
    fun clickByText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.e(TAG, "rootInActiveWindow is null, cannot click '$targetText'")
            _lastActionLog.value = "Failed: Screen not accessible"
            return false
        }

        Log.d(TAG, "Searching for node matching text: '$targetText'")
        val directMatches = root.findAccessibilityNodeInfosByText(targetText)

        if (!directMatches.isNullOrEmpty()) {
            for (node in directMatches) {
                if (performClickOnNodeOrParent(node)) {
                    _lastActionLog.value = "Clicked: '$targetText'"
                    return true
                }
            }
        }

        // Deep traversal fallback for partial or description matches
        val foundNode = findNodeRecursively(root) { node ->
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            text.contains(targetText, ignoreCase = true) || desc.contains(targetText, ignoreCase = true)
        }

        if (foundNode != null) {
            val clicked = performClickOnNodeOrParent(foundNode)
            if (clicked) {
                _lastActionLog.value = "Clicked: '$targetText'"
                return true
            }
        }

        _lastActionLog.value = "Target not found: '$targetText'"
        return false
    }

    /**
     * Finds a UI element by View ID (e.g. "com.example.app:id/submit_button") and clicks it.
     */
    fun clickById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (!nodes.isNullOrEmpty()) {
            for (node in nodes) {
                if (performClickOnNodeOrParent(node)) {
                    _lastActionLog.value = "Clicked ID: $viewId"
                    return true
                }
            }
        }
        _lastActionLog.value = "View ID not found: $viewId"
        return false
    }

    /**
     * Sets text into an editable node on the screen.
     * If targetText is specified, finds that input; otherwise targets the currently focused input.
     */
    fun setText(targetIdentifier: String?, textToSet: String): Boolean {
        val root = rootInActiveWindow ?: return false

        var targetNode: AccessibilityNodeInfo? = null

        if (!targetIdentifier.isNullOrBlank() && targetIdentifier != "ACTIVE_INPUT") {
            // Find specific input by label or hint
            val matches = root.findAccessibilityNodeInfosByText(targetIdentifier)
            if (!matches.isNullOrEmpty()) {
                targetNode = matches.firstOrNull { it.isEditable } ?: matches.first()
            }
        }

        if (targetNode == null) {
            // Find currently focused editable element
            targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (targetNode == null) {
            // Find any editable node on screen
            targetNode = findNodeRecursively(root) { it.isEditable }
        }

        if (targetNode != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToSet)
            }
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            _lastActionLog.value = if (success) "Set text: \"$textToSet\"" else "Failed to set text"
            return success
        }

        _lastActionLog.value = "No editable input found on screen"
        return false
    }

    /**
     * Traverses up the view hierarchy to find a clickable ancestor if the node itself isn't clickable.
     */
    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) return true
            }
            current = current.parent
        }
        // If not clickable, try direct click as fallback
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Depth-first search for an AccessibilityNodeInfo matching a predicate.
     */
    private fun findNodeRecursively(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeRecursively(child, predicate)
            if (found != null) return found
        }
        return null
    }

    /**
     * Performs standard Android global accessibility actions:
     * Home, Back, Recents, Notifications, QuickSettings, PowerDialog, Screenshot.
     */
    fun performNavigation(action: Int): Boolean {
        val success = performGlobalAction(action)
        _lastActionLog.value = "Performed navigation action: $action"
        return success
    }
}
