package com.keal.nexus.agent

import android.content.Context

/** User-owned identity and least-privilege policy for workspace tools. */
class NexusPreferences(context: Context) {
    private val store = context.getSharedPreferences("nexus_preferences", Context.MODE_PRIVATE)
    var assistantName: String
        get() = store.getString("assistant_name", "NEXUS") ?: "NEXUS"
        set(value) { store.edit().putString("assistant_name", value.trim().take(32).ifBlank { "NEXUS" }).apply() }
    fun isToolAllowed(tool: String): Boolean = store.getStringSet("allowed_tools", DEFAULT_READ_ONLY_TOOLS)?.contains(tool) == true
    var androidControlEnabled: Boolean
        get() = store.getBoolean("android_control_enabled", false)
        set(value) { store.edit().putBoolean("android_control_enabled", value).apply() }

    fun logAndroidAction(tool: String, result: String) {
        val entries = runCatching { org.json.JSONArray(store.getString("android_action_log", "[]")) }
            .getOrDefault(org.json.JSONArray())
        entries.put(org.json.JSONObject()
            .put("time", System.currentTimeMillis())
            .put("tool", tool.take(80))
            .put("result", result.take(240)))
        val trimmed = org.json.JSONArray()
        for (index in (entries.length() - 50).coerceAtLeast(0) until entries.length()) {
            trimmed.put(entries.getJSONObject(index))
        }
        store.edit().putString("android_action_log", trimmed.toString()).apply()
    }

    fun androidActionLog(): String = store.getString("android_action_log", "[]") ?: "[]"
    fun setToolAllowed(tool: String, allowed: Boolean) {
        val updated = store.getStringSet("allowed_tools", DEFAULT_READ_ONLY_TOOLS).orEmpty().toMutableSet()
        if (allowed) updated.add(tool) else updated.remove(tool)
        store.edit().putStringSet("allowed_tools", updated).apply()
    }
    fun allowAll() { store.edit().putStringSet("allowed_tools", WORKSPACE_TOOLS.toSet()).apply() }
    fun denyAll() { store.edit().putStringSet("allowed_tools", emptySet()).apply() }
    companion object {
        val WORKSPACE_TOOLS = listOf("workspace.list_files", "workspace.read_file", "workspace.write_file", "workspace.delete_file")
        private val DEFAULT_READ_ONLY_TOOLS = setOf("workspace.list_files", "workspace.read_file")
    }
}
