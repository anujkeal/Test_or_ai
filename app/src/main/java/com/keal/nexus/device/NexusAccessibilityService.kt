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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var active: NexusAccessibilityService? = null

        fun performGlobalAction(action: Int): Boolean =
            active?.performGlobalAction(action) ?: false
    }
}