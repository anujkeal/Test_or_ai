package com.keal.nexus

import android.app.DownloadManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

// ── Design tokens ────────────────────────────────────────────────────────────
private val Ink        = Color(0xFF090D12)
private val Surface0   = Color(0xFF0F1620)
private val Surface1   = Color(0xFF161F2B)
private val Surface2   = Color(0xFF1E2A38)
private val Mint       = Color(0xFF6EE7B7)
private val MintDim    = Color(0xFF34D399)
private val Accent     = Color(0xFF818CF8)
private val Muted      = Color(0xFF64748B)
private val MutedLight = Color(0xFF94A3B8)
private val MainText   = Color(0xFFE2E8F0)
private val Warning    = Color(0xFFFBBF24)
private val Success    = Color(0xFF34D399)
private val Danger     = Color(0xFFF87171)
private val Border     = Color(0xFF1E2D3D)

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
            primary        = Mint,
            onPrimary      = Ink,
            secondary      = Accent,
            background     = Ink,
            surface        = Surface1,
            surfaceVariant = Surface2,
            onSurface      = MainText,
            onBackground   = MainText,
            outline        = Border
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
    val scope   = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var destination        by remember { mutableStateOf(Destination.HOME) }
    var installed          by remember { mutableStateOf(ModelManager.installedModels(context)) }
    var activeDownload     by remember { mutableStateOf<ActiveDownload?>(null) }
    var downloadProgress   by remember { mutableFloatStateOf(0f) }
    var downloadedBytes    by remember { mutableLongStateOf(0L) }
    var totalDownloadBytes by remember { mutableLongStateOf(-1L) }
    var downloadDiagnostic by remember { mutableStateOf("Waiting for Android Download Manager…") }
    var notice             by remember { mutableStateOf("Your models stay on this device.") }
    var isBusy             by remember { mutableStateOf(false) }

    fun showNotice(msg: String) {
        notice = msg
        scope.launch { snackbar.showSnackbar(msg) }
    }

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
                    showNotice("Imported ${it.name}")
                }.onFailure {
                    showNotice(it.message ?: "Could not import model.")
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
                showNotice("Download status unavailable.")
                activeDownload = null
                break
            }
            downloadProgress   = snapshot.progress.coerceIn(0f, 1f)
            downloadedBytes    = snapshot.downloadedBytes.coerceAtLeast(0L)
            totalDownloadBytes = snapshot.totalBytes
            downloadDiagnostic = "Status ${snapshot.status} · reason ${snapshot.reason}"
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val file     = ModelManager.modelFile(context, current.model)
                    val checksum = current.model.sha256
                    val verified = checksum == null || withContext(Dispatchers.IO) {
                        ModelManager.verifySha256(file, checksum)
                    }
                    if (verified) {
                        installed = ModelManager.installedModels(context)
                        showNotice(
                            if (checksum == null) "Downloaded ${current.model.name}. Review its model card."
                            else "✓ Downloaded & verified ${current.model.name}."
                        )
                    } else {
                        withContext(Dispatchers.IO) { file.delete() }
                        showNotice("⚠ Checksum mismatch — file deleted for safety.")
                    }
                    activeDownload = null
                    break
                }
                DownloadManager.STATUS_FAILED -> {
                    showNotice("Download failed. Check connection and storage.")
                    activeDownload = null
                    break
                }
                else -> delay(900)
            }
        }
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost   = { SnackbarHost(snackbar) },
        bottomBar = {
            NexusNavBar(
                current   = destination,
                onChange  = { destination = it }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Ink)
        ) {
            TopHeader()
            when (destination) {
                Destination.HOME    -> HomeScreen(
                    installedCount = installed.size,
                    notice         = notice,
                    onOpenModels   = { destination = Destination.MODELS },
                    onOpenAgent    = { context.startActivity(Intent(context, com.keal.nexus.agent.AgentActivity::class.java)) }
                )
                Destination.MODELS  -> ModelsScreen(
                    installed      = installed,
                    activeDownload = activeDownload,
                    progress       = downloadProgress,
                    downloadedBytes= downloadedBytes,
                    totalBytes     = totalDownloadBytes,
                    diagnostic     = downloadDiagnostic,
                    notice         = notice,
                    isBusy         = isBusy,
                    onDownload     = { model ->
                        if (activeDownload != null) {
                            showNotice("Finish the current download first.")
                        } else {
                            runCatching { ModelManager.enqueueDownload(context, model) }
                                .onSuccess { id ->
                                    activeDownload   = ActiveDownload(id, model)
                                    downloadProgress = 0f
                                    showNotice("Downloading ${model.name} — Wi-Fi required.")
                                }
                                .onFailure { showNotice(it.message ?: "Could not start download.") }
                        }
                    },
                    onImport       = { importLauncher.launch(arrayOf("*/*")) },
                    onDelete       = { fileName ->
                        scope.launch {
                            withContext(Dispatchers.IO) { ModelManager.deleteModel(context, fileName) }
                            installed = ModelManager.installedModels(context)
                            showNotice("Removed $fileName")
                        }
                    }
                )
                Destination.SETTINGS -> SettingsScreen()
            }
        }
    }
}

// ── Navigation bar ────────────────────────────────────────────────────────────
@Composable
private fun NexusNavBar(current: Destination, onChange: (Destination) -> Unit) {
    NavigationBar(
        containerColor = Surface0,
        tonalElevation = 0.dp
    ) {
        Destination.entries.forEach { item ->
            NavigationBarItem(
                selected  = current == item,
                onClick   = { onChange(item) },
                icon      = { Text(item.symbol, fontSize = 18.sp) },
                label     = { Text(item.label, fontSize = 11.sp) },
                colors    = NavigationBarItemDefaults.colors(
                    selectedIconColor   = Mint,
                    selectedTextColor   = Mint,
                    indicatorColor      = Surface2,
                    unselectedIconColor = Muted,
                    unselectedTextColor = Muted
                )
            )
        }
    }
}

// ── Top header ────────────────────────────────────────────────────────────────
@Composable
private fun TopHeader() {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Logo
        Box(
            modifier          = Modifier
                .size(40.dp)
                .background(
                    brush  = Brush.linearGradientBrush(listOf(Mint, Accent)),
                    shape  = RoundedCornerShape(12.dp)
                ),
            contentAlignment  = Alignment.Center
        ) {
            Text("N", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Nexus AI", color = MainText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Local Intelligence", color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Chip("v0.2")
    }
}

@Composable
private fun Chip(label: String, color: Color = Mint) {
    Box(
        modifier         = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Home screen ───────────────────────────────────────────────────────────────
@Composable
private fun HomeScreen(
    installedCount: Int,
    notice: String,
    onOpenModels: () -> Unit,
    onOpenAgent: () -> Unit
) {
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Your AI.\nYour device.\nYour data.",
                    color      = MainText,
                    fontSize   = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Run open-weight AI privately — no cloud, no tracking.",
                    color      = MutedLight,
                    fontSize   = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }

        // Status card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface1),
                shape  = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionLabel("Device Status")

                    // Status row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(Warning)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Inference engine", color = MainText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Coming in next milestone", color = MutedLight, fontSize = 12.sp)
                        }
                    }

                    HorizontalDivider(color = Border)

                    // Stats row
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                installedCount.toString(),
                                color      = Mint,
                                fontSize   = 36.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text("Local models", color = Muted, fontSize = 12.sp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onOpenModels,
                                colors  = ButtonDefaults.buttonColors(
                                    containerColor = Mint,
                                    contentColor   = Ink
                                ),
                                shape   = RoundedCornerShape(12.dp)
                            ) {
                                Text("Explore models", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            OutlinedButton(
                                onClick = onOpenAgent,
                                shape   = RoundedCornerShape(12.dp)
                            ) {
                                Text("Open workspace", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // Features
        item { SectionLabel("How it works") }
        item { FeatureCard("🧠", "Choose a model", "Browse compact 1B–3B GGUF models curated for mobile.") }
        item { FeatureCard("📦", "Keep it on-device", "Import or download models directly into app storage.") }
        item { FeatureCard("🔒", "Stay in control", "Remove any model file whenever you want. Full privacy.") }

        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun FeatureCard(emoji: String, title: String, description: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape  = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier          = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier         = Modifier
                    .size(44.dp)
                    .background(Surface2, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 20.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, color = MainText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = MutedLight, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

// ── Models screen ─────────────────────────────────────────────────────────────
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
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Model Library", color = MainText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Download over Wi-Fi or import a GGUF file from your device.",
                    color = MutedLight,
                    fontSize = 13.sp
                )
            }
        }

        // Import button
        item {
            FilledTonalButton(
                onClick  = onImport,
                enabled  = !isBusy,
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Surface2,
                    contentColor   = MainText
                )
            ) {
                Text(
                    if (isBusy) "⏳  Importing model…" else "＋  Import a .gguf file",
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Active download card
        if (activeDownload != null) {
            item {
                AnimatedVisibility(
                    visible = true,
                    enter   = fadeIn() + slideInVertically()
                ) {
                    DownloadProgressCard(
                        name           = activeDownload.model.name,
                        progress       = progress,
                        downloadedBytes= downloadedBytes,
                        totalBytes     = totalBytes,
                        diagnostic     = diagnostic
                    )
                }
            }
        }

        // Curated section header
        item { SectionLabel("Curated Models") }

        // Model cards
        items(ModelCatalog.models, key = { it.id }) { model ->
            val localFile = installed.firstOrNull { it.name == model.fileName }
            ModelCard(
                model         = model,
                isInstalled   = localFile != null,
                isDownloading = activeDownload?.model?.id == model.id,
                onDownload    = { onDownload(model) },
                onDelete      = { onDelete(model.fileName) }
            )
        }

        // Imported models
        val imported = installed.filter { file ->
            ModelCatalog.models.none { it.fileName == file.name }
        }
        if (imported.isNotEmpty()) {
            item { SectionLabel("Imported Files") }
            items(imported, key = { it.name }) { file ->
                ImportedFileCard(file, onDelete)
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun DownloadProgressCard(
    name: String,
    progress: Float,
    downloadedBytes: Long,
    totalBytes: Long,
    diagnostic: String
) {
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "dlProgress")

    Card(
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape  = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Mint)
                Spacer(Modifier.width(8.dp))
                Text("Downloading", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(name, color = MainText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                progress    = { animatedProgress },
                modifier    = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color       = Mint,
                trackColor  = Surface2,
                strokeCap   = StrokeCap.Round
            )
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatBytes(downloadedBytes) +
                        if (totalBytes > 0L) " / ${formatBytes(totalBytes)}" else " downloaded",
                    color    = MainText,
                    fontSize = 12.sp
                )
                Text(
                    "${(progress * 100).toInt()}%",
                    color      = Mint,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(diagnostic, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape  = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header row
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(model.name, color = MainText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(model.publisher, color = Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                Chip(model.parameters, Accent)
            }

            // Description
            Text(model.description, color = MutedLight, fontSize = 12.sp, lineHeight = 17.sp)

            HorizontalDivider(color = Border)

            // Footer row
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier              = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(model.quantization, color = MutedLight, fontSize = 11.sp)
                    Text(model.sizeLabel, color = Muted, fontSize = 11.sp)
                }

                when {
                    isInstalled -> Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("✓ On device", color = Success, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedButton(
                            onClick            = onDelete,
                            shape              = RoundedCornerShape(10.dp),
                            contentPadding     = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors             = ButtonDefaults.outlinedButtonColors(contentColor = Danger)
                        ) {
                            Text("Remove", fontSize = 11.sp)
                        }
                    }
                    isDownloading -> FilledTonalButton(
                        onClick  = {},
                        enabled  = false,
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.filledTonalButtonColors(containerColor = Surface2)
                    ) {
                        Text("Downloading…", fontSize = 11.sp, color = Muted)
                    }
                    else -> Button(
                        onClick = onDownload,
                        colors  = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink),
                        shape   = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Download", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportedFileCard(file: File, onDelete: (String) -> Unit) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = Surface1),
        shape    = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier         = Modifier
                    .size(40.dp)
                    .background(Surface2, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("📁", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    color     = MainText,
                    fontSize  = 13.sp,
                    fontWeight= FontWeight.SemiBold,
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis
                )
                Text("GGUF · ${formatBytes(file.length())}", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick        = { onDelete(file.name) },
                shape          = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                colors         = ButtonDefaults.outlinedButtonColors(contentColor = Danger)
            ) {
                Text("Remove", fontSize = 11.sp)
            }
        }
    }
}

// ── Settings screen ───────────────────────────────────────────────────────────
@Composable
private fun SettingsScreen() {
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Settings", color = MainText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        item {
            InfoCard(
                emoji = "🔒",
                title = "Privacy",
                body  = "Models are stored in app-specific storage on your device. No data leaves your phone."
            )
        }
        item {
            InfoCard(
                emoji = "📡",
                title = "Network",
                body  = "Downloads use unmetered Wi-Fi by default. No cloud inference endpoint is configured."
            )
        }
        item {
            InfoCard(
                emoji = "⚙️",
                title = "Runtime",
                body  = "llama.cpp Android integration is the next milestone. Downloaded files are not loaded in this version."
            )
        }
        item {
            InfoCard(
                emoji = "ℹ️",
                title = "Version",
                body  = "Nexus AI 0.2.0 · Android API 26+ · GGUF model manager"
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ── Shared components ─────────────────────────────────────────────────────────
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color         = Muted,
        fontSize      = 10.sp,
        fontWeight    = FontWeight.SemiBold,
        letterSpacing = 1.5.sp
    )
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun InfoCard(emoji: String, title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape  = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier         = Modifier
                    .size(40.dp)
                    .background(Surface2, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 18.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = MainText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(body, color = MutedLight, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────
private fun formatBytes(bytes: Long): String =
    if (bytes < 1_000_000_000L) "${bytes / 1_000_000L} MB"
    else "%.2f GB".format(bytes / 1_000_000_000.0)

private fun Brush.Companion.linearGradientBrush(colors: List<Color>) =
    linearGradient(colors)
