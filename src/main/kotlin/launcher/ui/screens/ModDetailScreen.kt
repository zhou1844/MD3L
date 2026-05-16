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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.kamel.image.KamelImage
import io.kamel.image.asyncPainterResource
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import launcher.core.*
import launcher.ui.layout.Navigator
import java.awt.Desktop
import java.io.File
import java.net.URI
import javax.swing.JFileChooser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModDetailScreen(project: ModrinthProject, edition: String = "java", contentType: String = project.projectType) {
    val scope = rememberCoroutineScope()
    var versions by remember { mutableStateOf<List<ModrinthVersion>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("") }

    // 过滤状态
    var filterGameVersion by remember { mutableStateOf("") }
    var filterLoader by remember { mutableStateOf("") }
    var gameVersionExpanded by remember { mutableStateOf(false) }

    // 目标版本选择（模组将下载到该版本的 mods 文件夹）
    var localVersions by remember { mutableStateOf<List<LocalVersion>>(emptyList()) }
    var selectedTargetVersion by remember { mutableStateOf<LocalVersion?>(null) }
    var targetVersionExpanded by remember { mutableStateOf(false) }
    val isJavaModpackPage = edition == "java" && (contentType == "modpack" || project.projectType == "modpack")

    LaunchedEffect(project.slug, edition) {
        val settings = AppSettings.load()
        if (settings.minecraftDir.isNotBlank()) {
            localVersions = if (edition == "bedrock") {
                VersionScanner.scanBedrock(settings.minecraftDir)
            } else {
                VersionScanner.scan(settings.minecraftDir)
            }
            selectedTargetVersion = localVersions.firstOrNull()
        }
        versions = if (edition == "bedrock") {
            BedrockResourceApi.getProjectVersions(project.slug, project.title)
        } else {
            ModrinthApi.getProjectVersions(project.slug)
        }
        isLoading = false
    }

    // 收集可用的游戏版本和加载器
    val availableGameVersions = remember(versions) {
        versions.flatMap { it.gameVersions }.distinct().sortedDescending()
    }
    val availableLoaders = remember(versions) {
        versions.flatMap { it.loaders }.distinct().sorted()
    }

    val filteredVersions = versions.filter { v ->
        (filterGameVersion.isBlank() || filterGameVersion in v.gameVersions) &&
                (filterLoader.isBlank() || filterLoader in v.loaders)
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
                    project.title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    project.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // ── 项目信息卡片 ─────────────────────────────────────────────────────
        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (project.iconUrl.isNotBlank()) {
                    KamelImage(
                        resource = asyncPainterResource(data = project.iconUrl),
                        contentDescription = project.title,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                        onLoading = {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                modifier = Modifier.size(56.dp),
                            ) { Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) } }
                        },
                        onFailure = {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                modifier = Modifier.size(56.dp),
                            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) } }
                        },
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Row {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(formatCount(project.downloads), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.Filled.Category, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(project.projectType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (project.categories.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            project.categories.joinToString(", "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ── 目标版本选择器 ─────────────────────────────────────────────────
        if (localVersions.isNotEmpty() && !isJavaModpackPage) {
            ExposedDropdownMenuBox(
                expanded = targetVersionExpanded,
                onExpandedChange = { targetVersionExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = selectedTargetVersion?.let { "${it.id}${if (it.loaderType != LoaderType.Vanilla) " (${it.loaderType})" else ""}" }
                        ?: if (edition == "bedrock") "请选择基岩版本" else "全局目录（未选择版本）",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (edition == "bedrock") "下载到基岩版本" else "下载到版本") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(targetVersionExpanded) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = targetVersionExpanded,
                    onDismissRequest = { targetVersionExpanded = false },
                ) {
                    if (edition != "bedrock") {
                        DropdownMenuItem(
                            text = { Text("全局目录") },
                            onClick = { selectedTargetVersion = null; targetVersionExpanded = false },
                        )
                    }
                    localVersions.forEach { lv ->
                        DropdownMenuItem(
                            text = { Text("${lv.id}${if (lv.loaderType != LoaderType.Vanilla) " (${lv.loaderType})" else ""}") },
                            onClick = { selectedTargetVersion = lv; targetVersionExpanded = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── 过滤器 ───────────────────────────────────────────────────────────
        if (edition != "bedrock") Row(verticalAlignment = Alignment.CenterVertically) {
            // 游戏版本下拉
            ExposedDropdownMenuBox(
                expanded = gameVersionExpanded,
                onExpandedChange = { gameVersionExpanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = filterGameVersion.ifBlank { "全部版本" },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("MC 版本") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(gameVersionExpanded) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = gameVersionExpanded,
                    onDismissRequest = { gameVersionExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("全部版本") },
                        onClick = { filterGameVersion = ""; gameVersionExpanded = false },
                    )
                    availableGameVersions.take(30).forEach { gv ->
                        DropdownMenuItem(
                            text = { Text(gv) },
                            onClick = { filterGameVersion = gv; gameVersionExpanded = false },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))

            // 加载器过滤 chips
            Column {
                Text("加载器", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = filterLoader.isBlank(),
                        onClick = { filterLoader = "" },
                        label = { Text("全部", style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(8.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                    availableLoaders.take(4).forEach { loader ->
                        FilterChip(
                            selected = filterLoader == loader,
                            onClick = { filterLoader = if (filterLoader == loader) "" else loader },
                            label = { Text(loader, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        }
        if (edition != "bedrock") Spacer(Modifier.height(12.dp))

        // ── 状态消息 ─────────────────────────────────────────────────────────
        if (statusMessage.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if ("成功" in statusMessage) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    statusMessage,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if ("成功" in statusMessage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── 版本列表 ─────────────────────────────────────────────────────────
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (filteredVersions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.SearchOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text("没有匹配的版本", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(end = 8.dp),
            ) {
                items(filteredVersions, key = { it.id }) { ver ->
                    val isDownloading = downloadingId == ver.id
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
                                Text(
                                    ver.name.ifBlank { ver.versionNumber },
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ver.gameVersions.take(3).forEach { gv ->
                                        SuggestionChip(
                                            onClick = {},
                                            label = { Text(gv, style = MaterialTheme.typography.labelSmall) },
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.height(22.dp),
                                        )
                                    }
                                    ver.loaders.forEach { ld ->
                                        SuggestionChip(
                                            onClick = {},
                                            label = { Text(ld, style = MaterialTheme.typography.labelSmall) },
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.height(22.dp),
                                        )
                                    }
                                }
                                if (ver.files.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        ver.files.first().filename + " · ${"%.1f".format(ver.files.first().size / 1_048_576.0)} MB",
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
                                        downloadingId = ver.id
                                        statusMessage = ""
                                        scope.launch {
                                            val file = ver.files.firstOrNull { it.primary } ?: ver.files.firstOrNull()
                                            if (file != null) {
                                                if (edition == "bedrock" && selectedTargetVersion == null) {
                                                    statusMessage = "请先选择要下载到的基岩版本"
                                                    downloadingId = null
                                                    return@launch
                                                }
                                                if (edition == "bedrock" && !isDirectBedrockDownload(file.url)) {
                                                    try {
                                                        if (Desktop.isDesktopSupported()) {
                                                            Desktop.getDesktop().browse(URI(file.url))
                                                        }
                                                        statusMessage = "已打开下载页面: ${file.filename}"
                                                    } catch (_: Exception) {
                                                        statusMessage = "无法打开下载页面"
                                                    }
                                                    downloadingId = null
                                                    return@launch
                                                }
                                                var settings = AppSettings.load()
                                                if (settings.minecraftDir.isBlank()) {
                                                    val chosen = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        val chooser = JFileChooser(defaultMinecraftDir())
                                                        chooser.dialogTitle = "选择游戏主目录 (.minecraft)"
                                                        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                                        chooser.approveButtonText = "确认"
                                                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
                                                            chooser.selectedFile.absolutePath else null
                                                    }
                                                    if (chosen == null) {
                                                        statusMessage = "未选择游戏目录，已取消下载"
                                                        downloadingId = null
                                                        return@launch
                                                    }
                                                    settings = settings.copy(minecraftDir = chosen)
                                                    AppSettings.save(settings)
                                                }

                                                val baseDir = selectedTargetVersion?.versionDir ?: settings.minecraftDir
                                                val isJavaModpack = isJavaModpackPage
                                                val targetDir = when {
                                                    isJavaModpack -> File(settings.minecraftDir, "cache/modpacks")
                                                    edition == "bedrock" && contentType == "behaviorpack" -> File(baseDir, "behavior_packs")
                                                    edition == "bedrock" && contentType == "bedrock_resourcepack" -> File(baseDir, "resource_packs")
                                                    edition == "bedrock" && contentType == "bedrock_modpack" -> File(baseDir, "addon")
                                                    contentType == "shader" || project.projectType == "shader" -> File(baseDir, "shaderpacks")
                                                    contentType == "resourcepack" || project.projectType == "resourcepack" -> File(baseDir, "resourcepacks")
                                                    contentType == "modpack" || project.projectType == "modpack" -> File(baseDir, "modpacks")
                                                    else -> File(baseDir, "mods")
                                                }
                                                targetDir.mkdirs()
                                                val dest = File(targetDir, file.filename)
                                                val versionHint = selectedTargetVersion?.let { " → ${it.id}" } ?: if (isJavaModpack) " → 导入为新版本" else " → 全局目录"
                                                ResourceDownloadManager.launch(
                                                    name = file.filename,
                                                    url = file.url,
                                                    dest = dest,
                                                    size = file.size,
                                                ) { ok, finishedFile ->
                                                    if (ok && isJavaModpack) {
                                                        // 用独立协程作用域，不绑定 Compose 生命周期，防止切换页面时取消导入
                                                        val importScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
                                                        importScope.launch {
                                                            val importTaskId = "modpack_import_${finishedFile.absolutePath.hashCode()}_${System.currentTimeMillis()}"
                                                            DownloadHub.upsert(DownloadHub.HubTask(
                                                                id = importTaskId,
                                                                name = "导入整合包 ${finishedFile.name}",
                                                                type = DownloadHub.TaskType.ResourceDownload,
                                                                step = "准备导入整合包",
                                                                fraction = 0f,
                                                            ))
                                                            val result = ModpackManager.importMrpack(finishedFile, settings.minecraftDir) { step, fraction ->
                                                                runCatching { statusMessage = step }
                                                                DownloadHub.upsert(DownloadHub.HubTask(
                                                                    id = importTaskId,
                                                                    name = "导入整合包 ${finishedFile.name}",
                                                                    type = DownloadHub.TaskType.ResourceDownload,
                                                                    step = step,
                                                                    fraction = fraction.coerceIn(0f, 1f),
                                                                ))
                                                            }
                                                            DownloadHub.upsert(DownloadHub.HubTask(
                                                                id = importTaskId,
                                                                name = "导入整合包 ${finishedFile.name}",
                                                                type = DownloadHub.TaskType.ResourceDownload,
                                                                status = if ("成功" in result) DownloadHub.TaskStatus.Done else DownloadHub.TaskStatus.Error,
                                                                step = result,
                                                                fraction = if ("成功" in result) 1f else 0f,
                                                                error = if ("成功" in result) "" else result,
                                                            ))
                                                            runCatching { localVersions = VersionScanner.scan(settings.minecraftDir) }
                                                            runCatching { statusMessage = result }
                                                            runCatching { downloadingId = null }
                                                        }
                                                    } else {
                                                        scope.launch {
                                                            statusMessage = if (ok) {
                                                                "下载任务已完成，请在下载管理查看详情: ${file.filename}$versionHint"
                                                            } else {
                                                                "下载任务已结束（失败或已暂停），请在下载管理查看详情"
                                                            }
                                                            downloadingId = null
                                                        }
                                                    }
                                                }
                                                statusMessage = if (isJavaModpack) "整合包已加入下载管理，完成后会自动导入为新版本" else "已加入下载管理: ${file.filename}$versionHint"
                                                downloadingId = null
                                            } else {
                                                statusMessage = "未找到可下载文件"
                                                downloadingId = null
                                            }
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

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "${"%.1f".format(count / 1_000_000.0)}M"
    count >= 1_000 -> "${"%.1f".format(count / 1_000.0)}K"
    else -> "$count"
}

private fun isDirectBedrockDownload(url: String): Boolean {
    val clean = url.substringBefore('?').lowercase()
    return clean.endsWith(".mcpack") ||
        clean.endsWith(".mcaddon") ||
        clean.endsWith(".mcworld") ||
        clean.endsWith(".mctemplate") ||
        clean.endsWith(".zip")
}
