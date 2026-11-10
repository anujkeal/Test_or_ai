package com.keal.nexus.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class NexusToolCall(val name: String, val arguments: JSONObject)

class NexusToolRuntime(context: Context) {
    private val workspace = WorkspaceStore(context.applicationContext)
    private val preferences = NexusPreferences(context.applicationContext)
    private val registry = McpToolRegistry(workspace, preferences)

    fun systemPrompt(): String {
        val allowed = NexusPreferences.WORKSPACE_TOOLS.filter { preferences.isToolAllowed(it) }
        val workspaceSelected = workspace.selectedTreeUri() != null
        val toolDescriptions = JSONArray().apply {
            val all = registry.listTools()
            for (i in 0 until all.length()) {
                val tool = all.getJSONObject(i)
                if (allowed.contains(tool.optString("name"))) put(tool)
            }
        }
        return buildString {
            append("You are NEXUS, a private on-device AI assistant. You run locally on the user's Android device; never describe yourself as cloud-based or claim that you cannot access a user-selected workspace. Be honest about capabilities and limitations. ")
            append("You do not have unrestricted access to the phone, storage, or arbitrary folders. You may access only the folder the user explicitly selected in NEXUS Workspace, through the tools listed below and subject to permissions. ")
            append("Workspace folder selected: ").append(if (workspaceSelected) "yes" else "no").append(". ")
            if (allowed.isEmpty()) {
                append("No workspace tools are currently enabled. Explain that the user can select a folder in Workspace and enable specific tools in Tool Permissions. Do not pretend to have read any files. ")
            } else {
                append("Tools currently enabled by the user (JSON schemas): ").append(toolDescriptions.toString()).append(". ")
                append("When a task requires one of these tools, emit exactly one JSON object and no other text in this shape: ")
                append("{\"tool\":\"workspace.list_files\",\"arguments\":{}}. ")
                append("For other tools, use their exact names and JSON arguments from the schemas. Do not invent tool names or claim a tool succeeded until the app returns its result. ")
                append("The app will ask the user to approve write or delete actions. Never claim approval yourself. After receiving a tool result, answer the user's original request clearly and accurately. ")
            }
            append("If no tool is needed, answer normally. Do not invent file names, file contents, or access permissions.")
        }
    }

    fun parseToolCall(output: String): NexusToolCall? {
        val json = extractFirstJsonObject(output) ?: return null
        return runCatching {
            val root = JSONObject(json)
            val nested = root.optJSONObject("tool_call")
            val name = nested?.optString("name").orEmpty()
                .ifBlank { root.optString("tool").ifBlank { root.optString("name") } }
            val args = nested?.optJSONObject("arguments")
                ?: root.optJSONObject("arguments")
                ?: JSONObject()
            if (name !in NexusPreferences.WORKSPACE_TOOLS) null else NexusToolCall(name, args)
        }.getOrNull()
    }

    fun execute(call: NexusToolCall, userApproved: Boolean = false): String {
        val result = registry.callTool(call.name, call.arguments, userApproved)
        val content = result.optJSONArray("content")
        val message = content?.optJSONObject(0)?.optString("text").orEmpty()
        val isError = result.optBoolean("isError")
        return (if (isError) "Tool error: " else "Tool result: ") +
            message.take(MAX_TOOL_RESULT_CHARS)
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\\\') escaped = true
                else if (ch == '"') inString = false
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    companion object {
        private const val MAX_TOOL_RESULT_CHARS = 8_000
    }
}
