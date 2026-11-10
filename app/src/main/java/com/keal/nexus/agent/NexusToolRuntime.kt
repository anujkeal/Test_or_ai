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
        // Advertise the stable tool interface. The registry enforces current folder
        // selection and per-tool permissions at execution time, so changing settings
        // while the model is resident cannot leave its system prompt stale.
        val toolDescriptions = registry.listTools()
        return buildString {
            append("You are NEXUS, a private on-device AI assistant. You run locally on the user's Android device; never describe yourself as cloud-based. Be accurate about what you can and cannot do. ")
            append("You do not have unrestricted access to the phone, storage, or arbitrary folders. You may access only a folder the user explicitly selected in NEXUS Workspace, and only through the workspace tools listed below. ")
            append("Workspace tools (the app checks the current folder selection and each permission every time a tool is called): ")
            append(toolDescriptions.toString()).append(". ")
            append("When a user asks you to inspect, find, list, or read files, use the appropriate workspace tool instead of claiming you cannot access local files. ")
            append("To invoke a tool, emit exactly one JSON object and no other text in this shape: ")
            append("{\"tool\":\"workspace.list_files\",\"arguments\":{}}. ")
            append("For other tools, use their exact names and JSON arguments from the schemas. Do not invent tool names or claim a tool succeeded until the app returns its result. ")
            append("Write and delete tools require the user to enable their permissions and then approve each individual action in the app. Never claim approval yourself. ")
            append("If a tool returns an error, explain it honestly and tell the user what permission or setup is needed. After receiving a successful tool result, answer the user's original request clearly. ")
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

    fun isAllowed(call: NexusToolCall): Boolean =
        workspace.selectedTreeUri() != null && preferences.isToolAllowed(call.name)

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
                else if (ch == '\\') escaped = true
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
