package com.keal.nexus.agent

import android.content.Context

/** User-owned identity and least-privilege policy for workspace tools. */
class NexusPreferences(context: Context) {
    private val store = context.getSharedPreferences("nexus_preferences", Context.MODE_PRIVATE)
    var assistantName: String
        get() = store.getString("assistant_name", "NEXUS") ?: "NEXUS"
        set(value) { store.edit().putString("assistant_name", value.trim().take(32).ifBlank { "NEXUS" }).apply() }
    fun isToolAllowed(tool: String): Boolean = store.getStringSet("allowed_tools", emptySet())?.contains(tool) == true
    fun setToolAllowed(tool: String, allowed: Boolean) {
        val updated = store.getStringSet("allowed_tools", emptySet()).orEmpty().toMutableSet()
        if (allowed) updated.add(tool) else updated.remove(tool)
        store.edit().putStringSet("allowed_tools", updated).apply()
    }
    fun allowAll() { store.edit().putStringSet("allowed_tools", WORKSPACE_TOOLS.toSet()).apply() }
    fun denyAll() { store.edit().putStringSet("allowed_tools", emptySet()).apply() }
    companion object {
        val WORKSPACE_TOOLS = listOf("workspace.list_files", "workspace.read_file", "workspace.write_file", "workspace.delete_file")
    }
}
