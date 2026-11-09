package com.keal.nexus

import android.app.DownloadManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keal.nexus.data.ModelCatalog
import com.keal.nexus.data.ModelManager
import com.keal.nexus.data.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Ink = Color(0xFF090D12)
private val Panel = Color(0xFF131A22)
private val Mint = Color(0xFFB8F2D0)
private val Muted = Color(0xFF93A0AE)
private val MainText = Color(0xFFEAF0F6)
private val Warning = Color(0xFFFFC875)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NexusTheme { NexusApp() } }
    }
}

@Composable
private fun NexusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mint,
            onPrimary = Ink,
            secondary = Color(0xFF9CB8FF),
            background = Ink,
            surface = Panel,
            onSurface = MainText,
            onBackground = MainText
        ),
        content = content
    )
}

private enum class Destination(val label: String, val symbol: String) {
    HOME("Home", "⌂"),
    MODELS("Models", "◈"),
    SETTINGS("Settings", "⚙")
}

private data class ActiveDownload(val id: Long, val model: ModelSpec)

@Composable
private fun NexusApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(Destination.HOME) }
    var installed by remember { mutableStateOf(ModelManager.installedModels(context)) }
    var activeDownload by remember { mutableStateOf<ActiveDownload?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var totalDownloadBytes by remember { mutableLongStateOf(-1L) }
    var downloadDiagnostic by remember { mutableStateOf("Waiting for Android Download Manager…") }
    var notice by remember { mutableStateOf("Your models stay on this device.") }
    var isBusy by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isBusy = true
                val result = runCatching {
                    withContext(Dispatchers.IO) { ModelManager.importModel(context, uri) }
                }
                result.onSuccess {
                    installed = ModelManager.installedModels(context)
                    notice = "Imported " + it.name
                }.onFailure {
                    notice = it.message ?: "Could not import model."
                }
                isBusy = false
            }
        }
    }

    LaunchedEffect(activeDownload?.id) {
        val current = activeDownload ?: return@LaunchedEffect
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                ModelManager.queryDownload(context, current.id)
            }
            if (snapshot == null) {
                notice = "Download status is unavailable."
                activeDownload = null
                break
            }
            downloadProgress = snapshot.progress.coerceIn(0f, 1f)
            downloadedBytes = snapshot.downloadedBytes.coerceAtLeast(0L)
            totalDownloadBytes = snapshot.totalBytes
            downloadDiagnostic = "Android status " + snapshot.status + " · reason " + snapshot.reason
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val file = ModelManager.modelFile(context, current.model)
                    val checksum = current.model.sha256
                    val verified = checksum == null || withContext(Dispatchers.IO) {
                        ModelManager.verifySha256(file, checksum)
                    }
                    if (verified) {
                        installed = ModelManager.installedModels(context)
                        notice = if (checksum == null) {
                            "Downloaded " + current.model.name + ". Review its model card."
                        } else {
                            "Downloaded and checksum-verified " + current.model.name + "."
                        }
                    } else {
                        withContext(Dispatchers.IO) { file.delete() }
                        notice = "Checksum mismatch. The downloaded file was deleted."
                    }
                    activeDownload = null
                    break
                }
                DownloadManager.STATUS_FAILED -> {
                    notice = "Download failed. Check connection and available storage."
                    activeDownload = null
                    break
                }
                else -> delay(900)
            }
        }
    }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Panel, contentColor = MainText) {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Text(item.symbol, fontSize = 20.sp) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).background(Ink)
        ) {
            TopHeader()
            when (destination) {
                Destination.HOME -> HomeScreen(
                    installedCount = installed.size,
                    notice = notice,
                    onOpenModels = { destination = Destination.MODELS },
                    onOpenAgent = { context.startActivity(Intent(context, com.keal.nexus.agent.AgentActivity::class.java)) }
                )
                Destination.MODELS -> ModelsScreen(
                    installed = installed,
                    activeDownload = activeDownload,
                    progress = downloadProgress,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalDownloadBytes,
                    diagnostic = downloadDiagnostic,
                    notice = notice,
                    isBusy = isBusy,
                    onDownload = { model ->
                        if (activeDownload != null) {
                            notice = "Finish the current download before starting another."
                        } else {
                            runCatching { ModelManager.enqueueDownload(context, model) }
                                .onSuccess { id ->
                                    activeDownload = ActiveDownload(id, model)
                                    downloadProgress = 0f
                                    notice = "Downloading " + model.name + ". Wi-Fi is required."
                                }
                                .onFailure { notice = it.message ?: "Could not start download." }
                        }
                    },
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onDelete = { fileName ->
                        scope.launch {
                            withContext(Dispatchers.IO) { ModelManager.deleteModel(context, fileName) }
                            installed = ModelManager.installedModels(context)
                            notice = "Removed " + fileName
                        }
                    }
                )
                Destination.SETTINGS -> SettingsScreen(notice)
            }
        }
    }
}

@Composable
private fun TopHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(42.dp).background(Mint, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("N", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("NEXUS AI", color = MainText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("LOCAL INTELLIGENCE", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("V0.1", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomeScreen(installedCount: Int, notice: String, onOpenModels: () -> Unit, onOpenAgent: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text("Your AI. Your device. Your data.", color = MainText, fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold)
        Text("A private space for running open-weight AI models locally.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("DEVICE STATUS", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(Warning, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("Inference engine not connected yet", color = MainText, fontSize = 13.sp)
                }
                Text(
                    "This build manages model files. Offline text generation arrives with the native runtime milestone.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Divider(color = Color(0xFF27313C))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(installedCount.toString(), color = MainText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("LOCAL MODELS", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = onOpenModels, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
                            Text("Explore models", fontWeight = FontWeight.Bold)
                        }
                        androidx.compose.material3.OutlinedButton(onClick = onOpenAgent) {
                            Text("Open MCP workspace")
                        }
                    }
                }
            }
        }
        Text("BUILT FOR ON-DEVICE AI", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
        FeatureRow("01", "Choose your model", "Browse compact 1B–3B GGUF models.")
        FeatureRow("02", "Keep it on-device", "Import or download models into app storage.")
        FeatureRow("03", "Stay in control", "Remove model files whenever you want.")
        Spacer(Modifier.weight(1f))
        Text(notice, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 12.dp))
    }
}

@Composable
private fun FeatureRow(number: String, title: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = MainText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ModelsScreen(
    installed: List<File>,
    activeDownload: ActiveDownload?,
    progress: Float,
    downloadedBytes: Long,
    totalBytes: Long,
    diagnostic: String,
    notice: String,
    isBusy: Boolean,
    onDownload: (ModelSpec) -> Unit,
    onImport: () -> Unit,
    onDelete: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Model library", color = MainText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Download a model over Wi-Fi or import a GGUF file from your device.", color = Muted, fontSize = 12.sp)
        OutlinedButton(onClick = onImport, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
            Text(if (isBusy) "Importing model…" else "＋  Import a .gguf file")
        }
        if (activeDownload != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DOWNLOADING · " + activeDownload.model.name, color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Mint,
                        trackColor = Color(0xFF303B46)
                    )
                    Text((progress * 100).toInt().toString() + "% · " + formatBytes(downloadedBytes) + if (totalBytes > 0L) " / " + formatBytes(totalBytes) else " downloaded · size unknown", color = MainText, fontSize = 12.sp)
                    Text(diagnostic, color = Muted, fontSize = 11.sp)
                }
            }
        }
        Text("CURATED MODELS", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ModelCatalog.models, key = { it.id }) { model ->
                val localFile = installed.firstOrNull { it.name == model.fileName }
                ModelCard(
                    model = model,
                    isInstalled = localFile != null,
                    isDownloading = activeDownload?.model?.id == model.id,
                    onDownload = { onDownload(model) },
                    onDelete = { onDelete(model.fileName) }
                )
            }
            val imported = installed.filter { file -> ModelCatalog.models.none { it.fileName == file.name } }
            if (imported.isNotEmpty()) {
                item {
                    Text("IMPORTED MODEL FILES", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp))
                }
                items(imported, key = { it.name }) { file ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, color = MainText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("Local GGUF · " + formatBytes(file.length()), color = Muted, fontSize = 10.sp)
                            }
                            TextButtonLike("Remove") { onDelete(file.name) }
                        }
                    }
                }
            }
        }
        Text(notice, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun ModelCard(
    model: ModelSpec,
    isInstalled: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.name, color = MainText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(model.publisher, color = Muted, fontSize = 10.sp)
                }
                Box(Modifier.background(Color(0xFF202B35), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(model.parameters, color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(model.description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.quantization + " · " + model.sizeLabel, color = MainText, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                if (isInstalled) {
                    TextButtonLike("Remove", onDelete)
                } else {
                    Button(
                        onClick = onDownload,
                        enabled = !isDownloading,
                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (isDownloading) "Downloading…" else "Download", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (isInstalled) Text("✓  Stored on this device", color = Mint, fontSize = 10.sp)
        }
    }
}

@Composable
private fun TextButtonLike(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)
    ) {
        Text(label, fontSize = 10.sp)
    }
}

@Composable
private fun SettingsScreen(notice: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", color = MainText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        InfoCard("PRIVACY", "Model files are stored in app-specific storage. V0.1 downloads models from Hugging Face; inference is not yet enabled.")
        InfoCard("NETWORK", "Model downloads use unmetered Wi-Fi by default. No cloud inference endpoint is configured.")
        InfoCard("RUNTIME", "llama.cpp Android integration is the next engineering milestone. Downloaded files are not loaded into memory in this version.")
        InfoCard("VERSION", "NEXUS AI 0.1.0 · Android API 26+ · GGUF model manager")
        Text(notice, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(body, color = MainText, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_000_000_000L) return (bytes / 1_000_000L).toString() + " MB"
    return String.format("%.2f GB", bytes / 1_000_000_000.0)
}
