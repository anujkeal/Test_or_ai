package com.keal.nexus.agent

import org.json.JSONArray
import org.json.JSONObject

/** In-process MCP-compatible workspace tools; not a network MCP server. */
class McpToolRegistry(private val workspace: WorkspaceStore) {
    fun listTools(): JSONArray = JSONArray().apply {
        put(tool("workspace.list_files", "List files in the user-selected workspace folder.", JSONObject()))
        put(tool("workspace.read_file", "Read a UTF-8 text file from the workspace. Maximum 1 MB.",
            objectSchema("name" to stringProperty("File name directly inside the workspace."))))
        put(tool("workspace.write_file", "Create or replace a UTF-8 text file in the workspace. Maximum 1 MB. Replacing an existing file requires host-side user approval.",
            objectSchema(
                "name" to stringProperty("File name directly inside the workspace."),
                "content" to stringProperty("Complete file contents.")
            )))
        put(tool("workspace.delete_file", "Delete a file from the workspace. Requires host-side user approval.",
            objectSchema("name" to stringProperty("File name directly inside the workspace."))))
    }

    /** userApproved must come from a trusted app UI confirmation, never from model arguments. */
    fun callTool(name: String, arguments: JSONObject, userApproved: Boolean = false): JSONObject {
        return try {
            val result = when (name) {
                "workspace.list_files" -> workspace.listFiles().joinToString("\n").ifBlank { "Workspace is empty." }
                "workspace.read_file" -> workspace.readFile(arguments.getString("name"))
                "workspace.write_file" -> {
                    val fileName = arguments.getString("name")
                    if (workspace.fileExists(fileName) && !userApproved) {
                        throw SecurityException("Approval required: ask the user before replacing this file.")
                    }
                    "Saved " + workspace.writeFile(fileName, arguments.getString("content"))
                }
                "workspace.delete_file" -> {
                    if (!userApproved) throw SecurityException("Approval required: ask the user before deleting this file.")
                    if (workspace.deleteFile(arguments.getString("name"))) "Deleted " + arguments.getString("name")
                    else "File not found."
                }
                else -> return toolError("Unknown tool: $name")
            }
            toolResult(result, false)
        } catch (error: Exception) {
            toolError(error.message ?: "Tool execution failed.")
        }
    }

    fun handleJsonRpc(request: JSONObject): JSONObject {
        val id = request.opt("id")
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()
        val result = when (method) {
            "initialize" -> JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", "nexus-workspace").put("version", "0.2.0"))
            "notifications/initialized" -> return JSONObject()
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", listTools())
            "tools/call" -> callTool(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject())
            else -> return JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", -32601).put("message", "Method not found: $method"))
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }

    private fun tool(name: String, description: String, inputSchema: JSONObject) =
        JSONObject().put("name", name).put("description", description).put("inputSchema", inputSchema)

    private fun objectSchema(vararg properties: Pair<String, JSONObject>) =
        JSONObject().put("type", "object")
            .put("properties", JSONObject().apply { properties.forEach { put(it.first, it.second) } })
            .put("required", JSONArray(properties.map { it.first }))

    private fun stringProperty(description: String) =
        JSONObject().put("type", "string").put("description", description)

    private fun toolResult(message: String, isError: Boolean) =
        JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
            .put("isError", isError)

    private fun toolError(message: String) = toolResult(message, true)
}
