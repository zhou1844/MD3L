package launcher.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import launcher.core.*
import launcher.ui.layout.Navigator
import java.awt.Desktop
import java.io.File
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CfBedrockDetailScreen(project: CfBedrockProject) {
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<CfBedrockFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("") }
    var downloadingId by remember { mutableStateOf<Int?>(null) }

    var localVersions by remember { mutableStateOf<List<LocalVersion>>(emptyList()) }
    var selectedTargetVersion by remember { mutableStateOf<LocalVersion?>(null) }
    var targetVersionExpanded by remember { mutableStateOf(false) }

    val contentTypeLabel = when (project.contentType) {
        "texture_pack" -> "材质/资源包"
        "map" -> "地图"
        "skin" -> "皮肤"
        else -> "Addon/模组"
    }

    LaunchedEffect(project.modId) {
        withContext(Dispatchers.IO) {
            val settings = AppSettings.load()
            if (settings.minecraftDir.isNotBlank()) {
                val scanned = VersionScanner.scanBedrock(settings.minecraftDir)
                val firstMatch = scanned.firstOrNull { lv ->
                    project.gameVersions.any { gv -> lv.id.contains(gv, ignoreCase = true) }
                } ?: scanned.firstOrNull()
                withContext(Dispatchers.Main) {
                    localVersions = scanned
                    selectedTargetVersion = firstMatch
                }
            }
        }
        val fetched = BedrockResourceApi.getModFiles(project.modId)
        files = if (fetched.isNotEmpty()) fetched else {
            if (project.latestDownloadUrl.isNotBlank()) {
                listOf(CfBedrockFile(
                    fileId = 0,
                    fileName = project.latestFileName.ifBlank { "${project.slug}.mcaddon" },
                    displayName = project.latestFileName.ifBlank { project.name },
                    downloadUrl = project.latestDownloadUrl,
                    fileSize = project.latestFileSize,
                    gameVersions = project.gameVersions,
                    releaseDate = "",
                ))
            } else emptyList()
        }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 返回 + 标题 ──────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { Navigator.back() },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    project.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { runCatching { Desktop.getDesktop().browse(URI(project.cfPageUrl)) } }) {
                Icon(Icons.Filled.OpenInBrowser, contentDescription = "CurseForge", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 项目信息卡片 ─────────────────────────────────────────────────────
        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                ModIconAsync(iconUrl = project.iconUrl, projectType = project.contentType, size = 52)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatCfCount(project.downloads), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SuggestionChip(
                            onClick = {},
                            label = { Text(contentTypeLabel, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(22.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("by ${project.author}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    if (project.gameVersions.isNotEmpty()) {
                        Text(project.gameVersions.take(4).joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 目标版本选择 ─────────────────────────────────────────────────────
        if (localVersions.isNotEmpty()) {
            ExposedDropdownMenuBox(
                expanded = targetVersionExpanded,
                onExpandedChange = { targetVersionExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = selectedTargetVersion?.id ?: "请选择基岩版本",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("下载到版本") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(targetVersionExpanded) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = targetVersionExpanded, onDismissRequest = { targetVersionExpanded = false }) {
                    localVersions.forEach { lv ->
                        DropdownMenuItem(
                            text = { Text(lv.id) },
                            onClick = { selectedTargetVersion = lv; targetVersionExpanded = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── 状态消息 ─────────────────────────────────────────────────────────
        if (statusMessage.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if ("成功" in statusMessage || "加入" in statusMessage)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    statusMessage,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if ("成功" in statusMessage || "加入" in statusMessage)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── 文件版本列表 ─────────────────────────────────────────────────────
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.SearchOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text("暂无可用文件，请在 CurseForge 网页查看", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { runCatching { Desktop.getDesktop().browse(URI(project.cfPageUrl)) } }) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("前往 CurseForge")
                    }
                }
            }
        } else {
            // 将 RP/BP 配对：提取版本标识（去掉 [RP]/[BP] 前缀后相同则为一对）
            data class FilePair(
                val pairKey: String,        // 用于 LazyColumn key
                val displayLabel: String,   // 卡片标题
                val gameVersions: List<String>,
                val totalSize: Long,
                val rpFile: CfBedrockFile?,
                val bpFile: CfBedrockFile?,
                val singleFile: CfBedrockFile?, // 非RP/BP的普通文件
            )
            val displayPairs: List<FilePair> = remember(files) {
                fun stripPrefix(name: String) = name
                    .removePrefix("[RP]").removePrefix("[BP]")
                    .trim().trimStart('[').substringAfterLast(']').trim()
                val rpFiles = files.filter { it.fileName.startsWith("[RP]") }
                val bpFiles = files.filter { it.fileName.startsWith("[BP]") }
                val otherFiles = files.filter { !it.fileName.startsWith("[RP]") && !it.fileName.startsWith("[BP]") }
                val pairs = mutableListOf<FilePair>()
                val usedBp = mutableSetOf<Int>()
                for (rp in rpFiles) {
                    val rpBase = stripPrefix(rp.fileName)
                    val bp = bpFiles.firstOrNull { stripPrefix(it.fileName) == rpBase && it.fileId !in usedBp }
                    if (bp != null) {
                        usedBp.add(bp.fileId)
                        pairs.add(FilePair(
                            pairKey = "pair_${rp.fileId}",
                            displayLabel = rpBase.substringBeforeLast('.').trim().ifBlank { rpBase }.ifBlank { rp.displayName.ifBlank { rp.fileName } },
                            gameVersions = rp.gameVersions,
                            totalSize = rp.fileSize + bp.fileSize,
                            rpFile = rp, bpFile = bp, singleFile = null,
                        ))
                    } else {
                        pairs.add(FilePair("single_${rp.fileId}", (rp.displayName.ifBlank { rp.fileName }).substringBeforeLast('.').trim().ifBlank { rp.displayName },
                            rp.gameVersions, rp.fileSize, rpFile = rp, bpFile = null, singleFile = null))
                    }
                }
                for (bp in bpFiles.filter { it.fileId !in usedBp }) {
                    pairs.add(FilePair("single_${bp.fileId}", (bp.displayName.ifBlank { bp.fileName }).substringBeforeLast('.').trim().ifBlank { bp.displayName },
                        bp.gameVersions, bp.fileSize, rpFile = null, bpFile = bp, singleFile = null))
                }
                for (f in otherFiles) {
                    pairs.add(FilePair("single_${f.fileId}", (f.displayName.ifBlank { f.fileName }).substringBeforeLast('.').trim().ifBlank { f.displayName },
                        f.gameVersions, f.fileSize, rpFile = null, bpFile = null, singleFile = f))
                }
                pairs
            }

            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                ) {
                    items(displayPairs, key = { it.pairKey }) { pair ->
                        val isPaired = pair.rpFile != null && pair.bpFile != null
                        val representativeId = pair.rpFile?.fileId ?: pair.bpFile?.fileId ?: pair.singleFile?.fileId ?: 0
                        val isDownloading = downloadingId == representativeId
                        ElevatedCard(
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (isPaired) {
                                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                                Text("RP+BP", modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                            }
                                        } else if (pair.rpFile != null) {
                                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                                                Text("RP", modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                            }
                                        } else if (pair.bpFile != null) {
                                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                                Text("BP", modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                            }
                                        }
                                        Text(
                                            pair.displayLabel,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    if (pair.gameVersions.isNotEmpty()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            pair.gameVersions.take(4).forEach { gv ->
                                                SuggestionChip(
                                                    onClick = {},
                                                    label = { Text(gv, style = MaterialTheme.typography.labelSmall) },
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.height(22.dp),
                                                )
                                            }
                                        }
                                    }
                                    if (pair.totalSize > 0) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            "${"%.2f".format(pair.totalSize / 1_048_576.0)} MB" + if (isPaired) "（RP + BP）" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                if (isDownloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                                } else {
                                    FilledIconButton(
                                        onClick = {
                                            scope.launch {
                                                val filesToDownload = listOfNotNull(pair.rpFile, pair.bpFile, pair.singleFile)
                                                if (filesToDownload.all { it.downloadUrl.isBlank() }) {
                                                    runCatching { Desktop.getDesktop().browse(URI(project.cfPageUrl)) }
                                                    statusMessage = "无直链，已打开 CurseForge 网页"
                                                    return@launch
                                                }
                                                downloadingId = representativeId
                                                statusMessage = ""
                                                val settings = AppSettings.load()
                                                val engine = BedrockLaunchEngine()
                                                val comMojangDir: File? = if (settings.minecraftDir.isNotBlank()) {
                                                    withContext(Dispatchers.IO) {
                                                        val verId = selectedTargetVersion?.id ?: localVersions.firstOrNull()?.id
                                                        if (verId != null) engine.resolveActiveComMojangPublic(settings.minecraftDir, verId) else null
                                                    }
                                                } else null
                                                val tmpDir = File(System.getProperty("java.io.tmpdir"), "md3l_cf_dl").also { it.mkdirs() }
                                                val versionHint = selectedTargetVersion?.let { " → ${it.id}" } ?: ""
                                                var pendingCount = filesToDownload.size
                                                var successCount = 0

                                                for (dlFile in filesToDownload) {
                                                    if (dlFile.downloadUrl.isBlank()) { pendingCount--; continue }
                                                    val dlName = dlFile.fileName.ifBlank { "${project.slug}.mcpack" }
                                                    val dlExt = dlName.substringAfterLast('.', "").lowercase()
                                                    val isInjectable = dlExt in listOf("mcaddon", "mcpack", "zip")
                                                    val dest = File(tmpDir, dlName)
                                                    val capturedMojang = comMojangDir
                                                    ResourceDownloadManager.launch(
                                                        name = dlName,
                                                        url = dlFile.downloadUrl,
                                                        dest = dest,
                                                        size = dlFile.fileSize,
                                                    ) { ok, finishedFile ->
                                                        scope.launch {
                                                            if (ok && capturedMojang != null) {
                                                                withContext(Dispatchers.IO) {
                                                                    runCatching {
                                                                        if (isInjectable) {
                                                                            engine.injectAddon(finishedFile.absolutePath, capturedMojang)
                                                                        } else {
                                                                            val targetDir = when {
                                                                                dlName.startsWith("[RP]") -> File(capturedMojang, "resource_packs")
                                                                                dlName.startsWith("[BP]") -> File(capturedMojang, "behavior_packs")
                                                                                project.contentType == "texture_pack" -> File(capturedMojang, "resource_packs")
                                                                                project.contentType == "map" -> File(capturedMojang, "minecraftWorlds")
                                                                                else -> File(capturedMojang, "behavior_packs")
                                                                            }
                                                                            targetDir.mkdirs()
                                                                            finishedFile.copyTo(File(targetDir, dlName), overwrite = true)
                                                                        }
                                                                        finishedFile.delete()
                                                                        successCount++
                                                                    }.onFailure { e -> statusMessage = "安装失败: ${e.message}" }
                                                                }
                                                            } else if (!ok) {
                                                                statusMessage = "下载失败，请重试"
                                                            }
                                                            pendingCount--
                                                            if (pendingCount <= 0) {
                                                                if (successCount > 0) statusMessage = "已安装 $successCount 个文件$versionHint"
                                                                downloadingId = null
                                                            }
                                                        }
                                                    }
                                                }
                                                statusMessage = "正在下载${if (filesToDownload.size > 1) " ${filesToDownload.size} 个文件" else ""}$versionHint"
                                                downloadingId = null
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.size(42.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                    ) {
                                        Icon(Icons.Filled.Download, contentDescription = "下载", modifier = Modifier.size(22.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

private fun formatCfCount(c: Long): String = when {
    c >= 1_000_000 -> "${"%.1f".format(c / 1_000_000.0)}M"
    c >= 1_000 -> "${"%.1f".format(c / 1_000.0)}K"
    else -> "$c"
}
