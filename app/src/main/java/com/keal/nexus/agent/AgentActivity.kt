package com.keal.nexus.agent

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

class AgentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AgentWorkspaceScreen() } }
    }
}

@Composable
private fun AgentWorkspaceScreen() {
    val context = LocalContext.current
    val workspace = remember { WorkspaceStore(context) }
    val preferences = remember { NexusPreferences(context) }
    val tools = remember { McpToolRegistry(workspace, preferences) }
    var assistantName by remember { mutableStateOf(preferences.assistantName) }
    var permissionRefresh by remember { mutableStateOf(0) }
    var workspaceLabel by remember { mutableStateOf(workspace.selectedTreeUri()?.lastPathSegment ?: "No folder selected") }
    var fileName by remember { mutableStateOf("index.html") }
    var source by remember { mutableStateOf("""<!doctype html>
<html lang="en">
<head><meta name="viewport" content="width=device-width, initial-scale=1">
<title>NEXUS workspace</title>
<style>body{font:16px system-ui;background:#0b1118;color:#edf4f8;padding:32px}
main{max-width:640px;margin:8vh auto}h1{color:#b8f2d0}p{color:#aab7c5;line-height:1.6}</style></head>
<body><main><h1>Hello from NEXUS</h1><p>Your workspace preview is running locally on this device.</p></main></body>
</html>""") }
    var status by remember { mutableStateOf("Choose a folder to grant NEXUS access to that workspace.") }
    var previewHtml by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf(emptyList<String>()) }
    var showOverwriteDialog by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { workspace.setWorkspace(uri) }
                .onSuccess {
                    workspaceLabel = uri.lastPathSegment ?: "Workspace selected"
                    files = runCatching { workspace.listFiles() }.getOrDefault(emptyList())
                    status = "Workspace permission granted. MCP workspace tools are ready."
                }
                .onFailure { status = it.message ?: "Could not access that folder." }
        }
    }

    if (showOverwriteDialog) {
        AlertDialog(
            onDismissRequest = { showOverwriteDialog = false },
            title = { Text("Replace existing file?") },
            text = { Text("This will overwrite $fileName in the selected workspace folder.") },
            confirmButton = {
                Button(onClick = {
                    showOverwriteDialog = false
                    val result = tools.callTool("workspace.write_file",
                        JSONObject().put("name", fileName).put("content", source), userApproved = true)
                    status = result.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: "Tool call finished."
                    files = runCatching { workspace.listFiles() }.getOrDefault(files)
                }) { Text("Replace file") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showOverwriteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$assistantName · WORKSPACE", style = MaterialTheme.typography.titleLarge)
            Text("Your assistant identity and access rules are editable at any time.", style = MaterialTheme.typography.bodySmall)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PERSONALIZE YOUR AI", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(value = assistantName, onValueChange = { assistantName = it.take(32); preferences.assistantName = it }, label = { Text("What should you call this AI?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { preferences.allowAll(); permissionRefresh++ }) { Text("Allow all") }
                        OutlinedButton(onClick = { preferences.denyAll(); permissionRefresh++ }) { Text("Deny all") }
                    }
                    Text("TOOL PERMISSIONS · changes apply immediately", style = MaterialTheme.typography.labelMedium)
                    NexusPreferences.WORKSPACE_TOOLS.forEach { toolName ->
                        key(toolName, permissionRefresh) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(toolName.removePrefix("workspace."), style = MaterialTheme.typography.bodySmall)
                                Switch(checked = preferences.isToolAllowed(toolName), onCheckedChange = { preferences.setToolAllowed(toolName, it); permissionRefresh++ })
                            }
                        }
                    }
                    Text("Blocked by default. You can revoke access or change these choices whenever you want. Folder access is separately controlled by Android.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("WORKSPACE FOLDER", style = MaterialTheme.typography.labelMedium)
                    Text(workspaceLabel, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Choose workspace folder")
                    }
                }
            }
            OutlinedTextField(value = fileName, onValueChange = { fileName = it },
                label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = source, onValueChange = { source = it },
                label = { Text("HTML source") }, modifier = Modifier.fillMaxWidth().weight(1f), minLines = 5)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (runCatching { workspace.fileExists(fileName) }.getOrDefault(false)) {
                            showOverwriteDialog = true
                        } else {
                            val result = tools.callTool("workspace.write_file",
                                JSONObject().put("name", fileName).put("content", source))
                            status = result.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: "Tool call finished."
                            files = runCatching { workspace.listFiles() }.getOrDefault(files)
                        }
                    },
                    enabled = workspace.selectedTreeUri() != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Save file") }
                OutlinedButton(onClick = {
                    previewHtml = source
                    status = "Preview runs in a WebView without a native app bridge."
                }, modifier = Modifier.weight(1f)) { Text("Run preview") }
            }
            Text("MCP TOOLS: list_files · read_file · write_file · delete_file", style = MaterialTheme.typography.labelSmall)
            Text("FILES: " + files.joinToString().ifBlank { "None listed" }, style = MaterialTheme.typography.bodySmall)
            Text(status, style = MaterialTheme.typography.bodySmall)
            previewHtml?.let { html ->
                Card(Modifier.fillMaxWidth().weight(1f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            WebView(viewContext).apply {
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.domStorageEnabled = false
                                webViewClient = WebViewClient()
                            }
                        },
                        update = { it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) }
                    )
                }
            }
        }
    }
}
