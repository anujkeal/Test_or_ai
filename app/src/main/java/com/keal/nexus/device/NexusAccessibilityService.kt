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

    override fun onInterrupt() = Unit

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


    data class VisibleElement(
        val id: Int,
        val label: String,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean
    )

    private fun collectVisibleElements(): List<Pair<VisibleElement, android.view.accessibility.AccessibilityNodeInfo>> {
        val root = rootInActiveWindow ?: return emptyList()
        val found = mutableListOf<Pair<VisibleElement, android.view.accessibility.AccessibilityNodeInfo>>()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
            if (depth > 24 || found.size >= 80 || !node.isVisibleToUser) return
            val label = node.text?.toString()?.trim().orEmpty()
                .ifBlank { node.contentDescription?.toString()?.trim().orEmpty() }
            if ((node.isClickable || node.isEditable || node.isScrollable) && label.isNotBlank()) {
                val duplicate = found.any { it.first.label.equals(label, ignoreCase = true) }
                if (!duplicate) {
                    val id = found.size
                    found += VisibleElement(id, label.take(160), node.isClickable, node.isEditable, node.isScrollable) to node
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                visit(child, depth + 1)
            }
        }
        visit(root, 0)
        return found
    }

    private fun visibleScreenText(): String? {
        val root = rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString().orEmpty()
        val elements = collectVisibleElements()
        return buildString {
            append("App: ").append(packageName.ifBlank { "unknown" }).append('\n')
            if (elements.isEmpty()) append("(No accessible interactive elements found)")
            else elements.forEach { (element, _) ->
                append(element.id).append(": ").append(element.label)
                if (element.editable) append(" [text field]")
                if (element.clickable) append(" [clickable]")
                if (element.scrollable) append(" [scrollable]")
                append('\n')
            }
        }.take(9000)
    }

    private fun clickIndexed(id: Int): Boolean {
        val target = collectVisibleElements().firstOrNull { it.first.id == id }?.second ?: return false
        var clickable: android.view.accessibility.AccessibilityNodeInfo? = target
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        return clickable?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun typeIndexed(id: Int, value: String): Boolean {
        if (value.length > 2000 || value.any { it == '\u0000' }) return false
        val target = collectVisibleElements().firstOrNull { it.first.id == id } ?: return false
        if (!target.first.editable || !target.second.isEditable) return false
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return target.second.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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

        fun screenSnapshot(): String? = active?.visibleScreenText()

        fun clickScreenElement(id: Int): Boolean = active?.clickIndexed(id) ?: false

        fun typeScreenElement(id: Int, value: String): Boolean = active?.typeIndexed(id, value) ?: false

        fun isConnected(): Boolean = active != null

        fun performGlobalAction(action: Int): Boolean =
            active?.performGlobalAction(action) ?: false

        fun tapVisibleText(label: String): Boolean = active?.clickVisibleText(label) ?: false

        fun typeIntoFocusedField(value: String): Boolean = active?.typeIntoFocusedField(value) ?: false

        fun scroll(direction: String): Boolean = active?.scroll(direction) ?: false

        @Volatile private var pendingYouTubeAutoplay = false

        fun armYouTubeAutoplay(): Boolean {
            val service = active ?: return false
            pendingYouTubeAutoplay = true
            return true
        }
    }
}