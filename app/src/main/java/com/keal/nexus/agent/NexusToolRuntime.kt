package com.keal.nexus.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class NexusToolCall(val name: String, val arguments: JSONObject)
data class WorkspaceIntent(val action: Action, val fileName: String? = null) {
    enum class Action { LIST, READ, PATH, WRITE, DELETE }
}

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

    fun detectWorkspaceIntent(prompt: String): WorkspaceIntent? {
        val text = prompt.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        val createVerbs = listOf("create", "make", "generate", "write", "save", "build")
        val wantsCreate = createVerbs.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(lower) }
        val explicitFileName = Regex("""([A-Za-z0-9_-]+\.[A-Za-z0-9]{1,12})""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
        val inferredExtension = when {
            Regex("""\bpython\b""").containsMatchIn(lower) -> "py"
            Regex("""\bjavascript\b""").containsMatchIn(lower) -> "js"
            Regex("""\btypescript\b""").containsMatchIn(lower) -> "ts"
            Regex("""\bkotlin\b""").containsMatchIn(lower) -> "kt"
            Regex("""\bjava\b""").containsMatchIn(lower) -> "java"
            Regex("""\bc\+\+\b""").containsMatchIn(lower) -> "cpp"
            Regex("""\bc#\b""").containsMatchIn(lower) -> "cs"
            Regex("""\bhtml\b""").containsMatchIn(lower) -> "html"
            Regex("""\bcss\b""").containsMatchIn(lower) -> "css"
            Regex("""\bjson\b""").containsMatchIn(lower) -> "json"
            Regex("""\bxml\b""").containsMatchIn(lower) -> "xml"
            Regex("""\byaml\b""").containsMatchIn(lower) -> "yml"
            Regex("""\bsql\b""").containsMatchIn(lower) -> "sql"
            Regex("""\b(csv|spreadsheet)\b""").containsMatchIn(lower) -> "csv"
            Regex("""\b(markdown|readme)\b""").containsMatchIn(lower) -> "md"
            Regex("""\b(shell|bash)\b""").containsMatchIn(lower) -> "sh"
            Regex("""\brust\b""").containsMatchIn(lower) -> "rs"
            Regex("""\bgolang\b""").containsMatchIn(lower) -> "go"
            Regex("""\bruby\b""").containsMatchIn(lower) -> "rb"
            Regex("""\bphp\b""").containsMatchIn(lower) -> "php"
            Regex("""\bswift\b""").containsMatchIn(lower) -> "swift"
            Regex("""\bdart\b""").containsMatchIn(lower) -> "dart"
            Regex("""\btext\b""").containsMatchIn(lower) -> "txt"
            else -> null
        }
        val fileName = explicitFileName ?: if (wantsCreate) {
            when (inferredExtension) {
                "html" -> "index.html"
                "java" -> "Main.java"
                "cpp" -> "main.cpp"
                "py" -> "main.py"
                "js" -> "main.js"
                "kt" -> "Main.kt"
                "ts" -> "main.ts"
                "cs" -> "Program.cs"
                "rs" -> "main.rs"
                "go" -> "main.go"
                "rb" -> "main.rb"
                "php" -> "index.php"
                "swift" -> "main.swift"
                "dart" -> "main.dart"
                "sh" -> "script.sh"
                "sql" -> "query.sql"
                "json" -> "data.json"
                "xml" -> "data.xml"
                "yml" -> "config.yml"
                "csv" -> "data.csv"
                "md" -> "README.md"
                "css" -> "style.css"
                "txt" -> "notes.txt"
                else -> Regex("""\b(?:extension|file type|format)\s*[:.]?\s*([a-z0-9]{1,12})\b""", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1)?.let { "new_file.$it" }
            }
        } else null
        val mentionsFiles = listOf("file", "files", "folder", "workspace", "document").any { it in lower }
        if ((( "path" in lower || "where is" in lower || "what is" in lower || "location of" in lower) && fileName != null)) {
            return WorkspaceIntent(WorkspaceIntent.Action.PATH, fileName)
        }
        if (fileName != null && listOf("read ", "open ", "contents of", "show contents", "what is in ", "what's in ").any { it in lower }) {
            return WorkspaceIntent(WorkspaceIntent.Action.READ, fileName)
        }
        if ((listOf("list", "show", "what", "which").any { lower.startsWith(it) } &&
                listOf("file", "files", "folder", "workspace").any { it in lower }) ||
            "list workspace" in lower || "list files" in lower
        ) return WorkspaceIntent(WorkspaceIntent.Action.LIST)
        if (fileName != null && listOf("create", "make", "generate", "write", "save", "build").any { it in lower }) {
            return WorkspaceIntent(WorkspaceIntent.Action.WRITE, fileName)
        }
        if (fileName != null && listOf("delete", "remove").any { it in lower }) {
            return WorkspaceIntent(WorkspaceIntent.Action.DELETE, fileName)
        }
        return if (mentionsFiles && fileName == null && ("list" in lower || "show" in lower)) {
            WorkspaceIntent(WorkspaceIntent.Action.LIST)
        } else null
    }

    fun contentGenerationPrompt(originalRequest: String, fileName: String): String =
        "Create the complete contents for the file $fileName requested by the user. " +
            "Return ONLY the file contents, with no explanation, no markdown code fences, no JSON tool call, " +
            "and no claim that the file has been saved. Preserve the user's requested purpose. " +
            "Generate content appropriate for the requested file extension and language. For source-code files, produce complete runnable or compilable source where practical, include necessary imports and a concise main entry point, and avoid placeholder-only code. For JSON, XML, YAML, CSV, SQL, and configuration files, produce syntactically valid content in that format. " +
            "If it is an HTML file, return a complete valid HTML document with doctype, html, head, title, body, and responsive viewport. " +
            "Original request: $originalRequest"

    fun cleanGeneratedFileContent(output: String): String {
        val trimmed = output.trim()
        val fenced = Regex("^\\x60{3}[^\\n]*\\n([\\s\\S]*?)\\n\\x60{3}$").find(trimmed)
        return (fenced?.groupValues?.get(1) ?: trimmed).trim()
    }

    fun callFor(action: WorkspaceIntent.Action, fileName: String? = null, content: String = ""): NexusToolCall? {
        val name = when (action) {
            WorkspaceIntent.Action.LIST -> "workspace.list_files"
            WorkspaceIntent.Action.READ -> "workspace.read_file"
            WorkspaceIntent.Action.WRITE -> "workspace.write_file"
            WorkspaceIntent.Action.DELETE -> "workspace.delete_file"
            WorkspaceIntent.Action.PATH -> return null
        }
        val args = JSONObject()
        if (fileName != null) args.put("name", fileName)
        if (action == WorkspaceIntent.Action.WRITE) args.put("content", content)
        return NexusToolCall(name, args)
    }

    fun describePath(fileName: String): String = workspace.describeFilePath(fileName)

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
