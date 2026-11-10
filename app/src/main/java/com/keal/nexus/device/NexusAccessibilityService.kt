package com.keal.nexus.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Optional service, explicitly enabled by the device owner in Android Settings.
 * It only exposes a narrow set of user-requested navigation actions to NEXUS.
 */
class NexusAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != "com.google.android.youtube") return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!pendingYouTubeAutoplay) return
        val root = rootInActiveWindow ?: return
        val target = findFirstYouTubeVideoTitle(root) ?: return
        pendingYouTubeAutoplay = false
        var clickable: android.view.accessibility.AccessibilityNodeInfo? = target
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        clickable?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findFirstYouTubeVideoTitle(
        node: android.view.accessibility.AccessibilityNodeInfo
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val id = node.viewIdResourceName.orEmpty()
        if (id.contains("video_title", ignoreCase = true) ||
            id.contains("video_item", ignoreCase = true)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findFirstYouTubeVideoTitle(child)
            if (match != null) return match
        }
        return null
    }

    private fun clickVisibleText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(label)
        val node = matches.firstOrNull() ?: return false
        var target: android.view.accessibility.AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun typeIntoFocusedField(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!field.isEditable) return false
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (direction == "down") android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        fun findScrollable(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if (node.isScrollable && node.isEnabled) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = findScrollable(child)
                if (found != null) return found
            }
            return null
        }
        return findScrollable(root)?.performAction(action) == true
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var active: NexusAccessibilityService? = null

        fun performGlobalAction(action: Int): Boolean =
            active?.performGlobalAction(action) ?: false

        fun tapVisibleText(label: String): Boolean = active?.clickVisibleText(label) ?: false

        fun typeIntoFocusedField(value: String): Boolean = active?.typeIntoFocusedField(value) ?: false

        fun scroll(direction: String): Boolean = active?.scroll(direction) ?: false

        @Volatile private var pendingYouTubeAutoplay = false

        fun armYouTubeAutoplay(): Boolean {
            val service = active ?: return false
            service.pendingYouTubeAutoplay = true
            return true
        }
    }
}