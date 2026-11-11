package com.keal.nexus.agent

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.json.JSONObject

// ── Design tokens (mirror main palette) ──────────────────────────────────────
private val Ink      = Color(0xFF0E0B1C)
private val Surface0 = Color(0xFF181430)
private val Surface1 = Color(0xFF181430)
private val Surface2 = Color(0xFF2A2548)
private val Mint     = Color(0xFF8B6CFF)
private val Accent   = Color(0xFF8B6CFF)
private val Muted    = Color(0xFF9892BC)
private val MutedLight= Color(0xFF9892BC)
private val MainText = Color(0xFFF1EEFF)
private val Success  = Color(0xFF8B6CFF)
private val Danger   = Color(0xFFC4472F)
private val Warning  = Color(0xFF9B3CF8)
private val Border   = Color(0xFF2A2548)

class AgentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary        = Mint,
                    onPrimary      = Ink,
                    secondary      = Accent,
                    background     = Ink,
                    surface        = Surface1,
                    surfaceVariant = Surface2,
                    onSurface      = MainText,
                    onBackground   = MainText,
                    outline        = Border
                )
            ) {
                AgentWorkspaceScreen()
            }
        }
    }
}

@Composable
private fun AgentWorkspaceScreen() {
    val context        = LocalContext.current
    val workspace      = remember { WorkspaceStore(context) }
    val preferences    = remember { NexusPreferences(context) }
    val tools          = remember { McpToolRegistry(workspace, preferences) }
    val scope          = rememberCoroutineScope()
    val snackbar       = remember { SnackbarHostState() }

    var assistantName     by remember { mutableStateOf(preferences.assistantName) }
    var permissionRefresh by remember { mutableStateOf(0) }
    var workspaceLabel    by remember { mutableStateOf(workspace.selectedTreeUri()?.lastPathSegment ?: "No folder selected") }
    var fileName          by remember { mutableStateOf("index.html") }
    var source            by remember {
        mutableStateOf(
            """<!doctype html>
<html lang="en">
<head><meta name="viewport" content="width=device-width, initial-scale=1">
<title>NEXUS workspace</title>
<style>body{font:16px system-ui;background:#0b1118;color:#edf4f8;padding:32px}
main{max-width:640px;margin:8vh auto}h1{color:#6ee7b7}p{color:#94a3b8;line-height:1.6}</style></head>
<body><main><h1>Hello from NEXUS</h1><p>Your workspace preview is running locally on this device.</p></main></body>
</html>"""
        )
    }
    var status            by remember { mutableStateOf("Choose a folder to grant workspace access.") }
    var previewHtml       by remember { mutableStateOf<String?>(null) }
    var files             by remember { mutableStateOf(emptyList<String>()) }
    var showOverwriteDialog by remember { mutableStateOf(false) }

    fun notify(msg: String) {
        status = msg
        scope.launch { snackbar.showSnackbar(msg) }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { workspace.setWorkspace(uri) }
                .onSuccess {
                    workspaceLabel = uri.lastPathSegment ?: "Workspace selected"
                    files = runCatching { workspace.listFiles() }.getOrDefault(emptyList())
                    notify("Workspace permission granted. MCP tools are ready.")
                }
                .onFailure { notify(it.message ?: "Could not access that folder.") }
        }
    }

    // ── Overwrite confirm dialog ─────────────────────────────────────────────
    if (showOverwriteDialog) {
        AlertDialog(
            onDismissRequest = { showOverwriteDialog = false },
            containerColor   = Surface1,
            title  = { Text("Replace existing file?", color = MainText, fontWeight = FontWeight.Bold) },
            text   = {
                Text(
                    "This will overwrite \"$fileName\" in the workspace folder.",
                    color = MutedLight,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOverwriteDialog = false
                        val result = tools.callTool(
                            "workspace.write_file",
                            JSONObject().put("name", fileName).put("content", source),
                            userApproved = true
                        )
                        notify(result.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: "File saved.")
                        files = runCatching { workspace.listFiles() }.getOrDefault(files)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Warning, contentColor = Ink)
                ) { Text("Replace", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showOverwriteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost   = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier            = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding      = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Page header ──────────────────────────────────────────────────
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "$assistantName  ·  Workspace",
                        color = MainText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Edit identity and tool permissions below.",
                        color = Muted,
                        fontSize = 13.sp
                    )
                }
            }

            // ── Identity card ────────────────────────────────────────────────
            item {
                AgentCard(title = "🤖  Personalize your AI") {
                    OutlinedTextField(
                        value         = assistantName,
                        onValueChange = {
                            assistantName = it.take(32)
                            preferences.assistantName = it
                        },
                        label     = { Text("Assistant name") },
                        singleLine= true,
                        modifier  = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick  = { preferences.allowAll(); permissionRefresh++ },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(10.dp),
                            colors   = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Success.copy(alpha = 0.15f),
                                contentColor   = Success
                            )
                        ) { Text("Allow all", fontSize = 13.sp) }
                        FilledTonalButton(
                            onClick  = { preferences.denyAll(); permissionRefresh++ },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(10.dp),
                            colors   = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Danger.copy(alpha = 0.15f),
                                contentColor   = Danger
                            )
                        ) { Text("Deny all", fontSize = 13.sp) }
                    }
                }
            }

            // ── Permissions card ─────────────────────────────────────────────
            item {
                AgentCard(title = "🛡  Tool Permissions") {
                    Text(
                        "Changes apply immediately. Revoke access anytime.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    NexusPreferences.WORKSPACE_TOOLS.forEachIndexed { index, toolName ->
                        key(toolName, permissionRefresh) {
                            if (index > 0) HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 2.dp))
                            ToolPermissionRow(
                                toolName  = toolName.removePrefix("workspace."),
                                checked   = preferences.isToolAllowed(toolName),
                                onChange  = { allowed ->
                                    preferences.setToolAllowed(toolName, allowed)
                                    permissionRefresh++
                                }
                            )
                        }
                    }
                }
            }

            // ── Workspace folder card ────────────────────────────────────────
            item {
                AgentCard(title = "📂  Workspace Folder") {
                    Row(
                        modifier          = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier         = Modifier
                                .size(36.dp)
                                .background(Surface2, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (workspace.selectedTreeUri() != null) "✅" else "📁", fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            workspaceLabel,
                            color     = if (workspace.selectedTreeUri() != null) MainText else Muted,
                            fontSize  = 13.sp,
                            maxLines  = 2,
                            overflow  = TextOverflow.Ellipsis,
                            modifier  = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick  = { folderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(10.dp)
                    ) {
                        Text("Choose workspace folder")
                    }
                }
            }

            // ── File editor card ─────────────────────────────────────────────
            item {
                AgentCard(title = "📝  File Editor") {
                    OutlinedTextField(
                        value         = fileName,
                        onValueChange = { fileName = it },
                        label         = { Text("File name") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value         = source,
                        onValueChange = { source = it },
                        label         = { Text("HTML source") },
                        modifier      = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        minLines      = 6
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (runCatching { workspace.fileExists(fileName) }.getOrDefault(false)) {
                                    showOverwriteDialog = true
                                } else {
                                    val result = tools.callTool(
                                        "workspace.write_file",
                                        JSONObject().put("name", fileName).put("content", source)
                                    )
                                    notify(result.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: "Saved.")
                                    files = runCatching { workspace.listFiles() }.getOrDefault(files)
                                }
                            },
                            enabled  = workspace.selectedTreeUri() != null,
                            modifier = Modifier.weight(1f),
                            colors   = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink),
                            shape    = RoundedCornerShape(10.dp)
                        ) { Text("Save", fontWeight = FontWeight.Bold) }

                        OutlinedButton(
                            onClick  = {
                                previewHtml = source
                                notify("Preview running in sandboxed WebView.")
                            },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(10.dp)
                        ) { Text("Preview") }
                    }
                }
            }

            // ── MCP tools & files info ───────────────────────────────────────
            item {
                AgentCard(title = "🔧  MCP Tools") {
                    val toolList = listOf("list_files", "read_file", "write_file", "delete_file")
                    toolList.forEach { tool ->
                        Row(
                            modifier          = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .background(Mint.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(tool, color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    if (files.isNotEmpty()) {
                        HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 4.dp))
                        Text("Workspace files:", color = Muted, fontSize = 11.sp)
                        files.take(10).forEach { f ->
                            Text("• $f", color = MutedLight, fontSize = 12.sp)
                        }
                        if (files.size > 10) {
                            Text("+ ${files.size - 10} more…", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }

            // ── WebView preview ──────────────────────────────────────────────
            if (previewHtml != null) {
                item {
                    AgentCard(title = "👁  Preview") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            shape    = RoundedCornerShape(10.dp)
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory  = { viewContext ->
                                    WebView(viewContext).apply {
                                        settings.javaScriptEnabled    = true
                                        settings.allowFileAccess      = false
                                        settings.allowContentAccess   = false
                                        settings.domStorageEnabled    = false
                                        webViewClient                 = WebViewClient()
                                    }
                                },
                                update = { it.loadDataWithBaseURL(null, previewHtml!!, "text/html", "UTF-8", null) }
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick  = { previewHtml = null },
                            modifier = Modifier.fillMaxWidth(),
                            shape    = RoundedCornerShape(10.dp),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = Danger)
                        ) { Text("Close preview") }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ── Shared components ─────────────────────────────────────────────────────────
@Composable
private fun AgentCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape  = RoundedCornerShape(16.dp)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title.uppercase(),
                color         = Muted,
                fontSize      = 10.sp,
                fontWeight    = FontWeight.SemiBold,
                letterSpacing = 1.5.sp
            )
            content()
        }
    }
}

@Composable
private fun ToolPermissionRow(
    toolName: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (checked) "✓" else "○",
                color    = if (checked) Success else Muted,
                fontSize = 14.sp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                toolName,
                color    = if (checked) MainText else Muted,
                fontSize = 13.sp
            )
        }
        Switch(
            checked         = checked,
            onCheckedChange = onChange,
            colors          = SwitchDefaults.colors(
                checkedThumbColor      = Ink,
                checkedTrackColor      = Mint,
                uncheckedThumbColor    = Muted,
                uncheckedTrackColor    = Surface2
            )
        )
    }
}
