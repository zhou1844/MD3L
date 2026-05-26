package launcher.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import launcher.core.AppSettings
import launcher.core.BedrockLaunchEngine
import launcher.core.BedrockResourceApi
import launcher.core.CfBedrockFile
import launcher.core.CfBedrockProject
import launcher.ui.layout.Navigator
import launcher.ui.nav.Route
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URL
import java.util.zip.ZipInputStream
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage

private object BedrockModState {
    val searchQuery = mutableStateOf("")
    val projects = mutableStateOf<List<CfBedrockProject>>(emptyList())
    val isLoading = mutableStateOf(false)
    val selectedType = mutableStateOf("addon")
    val isInitialLoad = mutableStateOf(true)
    val pageIndex = mutableStateOf(0)
    val hasMore = mutableStateOf(true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BedrockModScreen() {
    val scope = rememberCoroutineScope()
    var comMojangDir by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val s = AppSettings.load()
            val lastId = s.lastVersionId
            if (s.minecraftDir.isNotBlank() && lastId.isNotBlank()) {
                runCatching {
                    comMojangDir = BedrockLaunchEngine().resolveActiveComMojangPublic(s.minecraftDir, lastId)
                }
            }
        }
    }
    var searchQuery by BedrockModState.searchQuery
    var projects by BedrockModState.projects
    var isLoading by BedrockModState.isLoading
    var selectedType by BedrockModState.selectedType
    var isInitialLoad by BedrockModState.isInitialLoad
    var pageIndex by BedrockModState.pageIndex
    var hasMore by BedrockModState.hasMore
    val listState = rememberLazyListState()

    fun doSearch(resetPage: Boolean = true) {
        if (resetPage) {
            pageIndex = 0
            hasMore = true
        }
        isLoading = true
        scope.launch {
            val result = BedrockResourceApi.search(
                query = searchQuery,
                contentType = selectedType,
                limit = 30,
                index = if (resetPage) 0 else pageIndex,
            )
            if (resetPage) {
                projects = result
            } else {
                projects = projects + result
                pageIndex += result.size
            }
            hasMore = result.size == 30
            isLoading = false
            isInitialLoad = false
        }
    }

    LaunchedEffect(Unit) {
        if (!isInitialLoad) return@LaunchedEffect
        doSearch()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 标题栏 ───────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Navigator.navigate(Route.Mods) }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
            Column {
                Text(
                    "基岩版资源中心",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "来源：CurseForge Bedrock · 点击卡片查看文件版本并下载",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 类型选择 chip ────────────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "addon" to "Addon",
                "texture_pack" to "资源包",
                "map" to "地图",
                "skin" to "皮肤",
            ).forEach { (type, label) ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { selectedType = type; doSearch() },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // ── 搜索框 ───────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(52.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索 CurseForge 基岩版资源…") },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = ""; doSearch() }) {
                            Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                            doSearch(); true
                        } else false
                    },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(
                onClick = { doSearch() },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.fillMaxHeight(),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))

        // ── 列表 ─────────────────────────────────────────────────────────────
        when {
            isLoading && projects.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                }
            }
            projects.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("未找到结果", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            else -> {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                    ) {
                        items(projects, key = { it.modId }) { project ->
                            CfBedrockProjectCard(project, comMojangDir)
                        }
                        if (hasMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    if (isLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else {
                                        FilledTonalButton(onClick = { doSearch(resetPage = false) }, shape = RoundedCornerShape(10.dp)) {
                                            Text("加载更多", style = MaterialTheme.typography.labelMedium)
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
}

@Composable
private fun CfBedrockProjectCard(project: CfBedrockProject, comMojangDir: File?) {
    val scope = rememberCoroutineScope()
    var showFiles by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<CfBedrockFile>>(emptyList()) }
    var loadingFiles by remember { mutableStateOf(false) }
    var downloadStatus by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }

    ElevatedCard(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column {
            // ── 卡片主体 ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CfIconAsync(iconUrl = project.iconUrl, contentType = project.contentType, size = 52)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        project.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            formatCfDl(project.downloads),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "by ${project.author}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        if (project.gameVersions.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                project.gameVersions.take(2).joinToString(", "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 在 CurseForge 打开
                IconButton(
                    onClick = {
                        runCatching { Desktop.getDesktop().browse(URI(project.cfPageUrl)) }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.OpenInBrowser,
                        contentDescription = "在 CurseForge 打开",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                // 展开文件列表
                IconButton(
                    onClick = {
                        showFiles = !showFiles
                        if (showFiles && files.isEmpty()) {
                            loadingFiles = true
                            scope.launch {
                                files = BedrockResourceApi.getModFiles(project.modId)
                                // 如果 API 文件列表为空，用 latestFile 兜底
                                if (files.isEmpty() && project.latestDownloadUrl.isNotBlank()) {
                                    files = listOf(
                                        CfBedrockFile(
                                            fileId = 0,
                                            fileName = project.latestFileName,
                                            displayName = project.latestFileName,
                                            downloadUrl = project.latestDownloadUrl,
                                            fileSize = project.latestFileSize,
                                            gameVersions = project.gameVersions,
                                            releaseDate = "",
                                        )
                                    )
                                }
                                loadingFiles = false
                            }
                        }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (showFiles) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "展开文件",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // ── 下载进度条 ───────────────────────────────────────────────────
            if (downloading) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (downloadStatus.isNotBlank()) {
                Text(
                    downloadStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (downloadStatus.startsWith("✓"))
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp),
                )
            }

            // ── 展开的文件列表 ───────────────────────────────────────────────
            if (showFiles) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                if (loadingFiles) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else if (files.isEmpty()) {
                    Text(
                        "暂无可用文件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        files.forEach { file ->
                            CfFileRow(
                                file = file,
                                onDownload = {
                                    if (file.downloadUrl.isBlank()) {
                                        runCatching { Desktop.getDesktop().browse(URI(project.cfPageUrl)) }
                                        return@CfFileRow
                                    }
                                    downloading = true
                                    downloadStatus = ""
                                    downloadProgress = 0f
                                    scope.launch {
                                        val result = downloadCfFile(
                                            url = file.downloadUrl,
                                            fileName = file.fileName.ifBlank { "bedrock-pack.mcaddon" },
                                            contentType = project.contentType,
                                            comMojangDir = comMojangDir,
                                            onProgress = { p -> downloadProgress = p },
                                        )
                                        downloading = false
                                        downloadStatus = result
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CfFileRow(file: CfBedrockFile, onDownload: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                file.fileName.endsWith(".mcaddon", true) -> Icons.Filled.Extension
                file.fileName.endsWith(".mcpack", true) -> Icons.Filled.Palette
                file.fileName.endsWith(".mcworld", true) -> Icons.Filled.Public
                else -> Icons.Filled.InsertDriveFile
            },
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.displayName.ifBlank { file.fileName },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.gameVersions.isNotEmpty() || file.fileSize > 0) {
                Text(
                    buildString {
                        if (file.gameVersions.isNotEmpty()) append(file.gameVersions.take(3).joinToString(", "))
                        if (file.fileSize > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(formatFileSize(file.fileSize))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(
            onClick = onDownload,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(30.dp),
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("下载", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CfIconAsync(iconUrl: String, contentType: String, size: Int = 52) {
    var bitmap by remember(iconUrl) { mutableStateOf<ImageBitmap?>(null) }
    var loaded by remember(iconUrl) { mutableStateOf(false) }

    LaunchedEffect(iconUrl) {
        if (iconUrl.isNotBlank()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = URL(iconUrl).readBytes()
                    bitmap = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                }
            }
        }
        loaded = true
    }

    val bmp = bitmap
    if (bmp != null) {
        androidx.compose.foundation.Image(
            painter = BitmapPainter(bmp),
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
            modifier = Modifier.size(size.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (loaded) {
                    Icon(
                        when (contentType) {
                            "texture_pack" -> Icons.Filled.Palette
                            "map" -> Icons.Filled.Public
                            "skin" -> Icons.Filled.Person
                            else -> Icons.Filled.Extension
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size((size / 2).dp),
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size((size / 3).dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

private suspend fun downloadCfFile(
    url: String,
    fileName: String,
    contentType: String = "",
    comMojangDir: File? = null,
    onProgress: (Float) -> Unit,
): String {
    return withContext(Dispatchers.IO) {
        try {
            val tmpDir = File(System.getProperty("java.io.tmpdir"), "md3l_cf_${System.nanoTime()}")
            tmpDir.mkdirs()
            val dest = File(tmpDir, fileName)

            val proc = ProcessBuilder(
                "curl.exe", "-L", "-s",
                "--connect-timeout", "15", "--max-time", "300",
                "-o", dest.absolutePath, url,
            ).redirectErrorStream(true).start()

            val pollJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                while (true) {
                    kotlinx.coroutines.delay(300)
                    onProgress(-1f)
                }
            }
            proc.waitFor()
            pollJob.cancel()

            if (proc.exitValue() != 0 || !dest.exists() || dest.length() == 0L) {
                return@withContext "下载失败，请在浏览器打开"
            }

            onProgress(1f)

            val ext = dest.extension.lowercase()
            val mojang = comMojangDir

            if (mojang == null) {
                val fallback = File(System.getProperty("user.home"), "Downloads/$fileName")
                dest.copyTo(fallback, overwrite = true)
                return@withContext "✓ 已保存到 ${fallback.absolutePath}（未检测到基岩版版本，无法自动导入）"
            }

            when (ext) {
                "mcaddon", "mcpack" -> {
                    BedrockLaunchEngine().injectAddon(dest.absolutePath, mojang)
                    "✓ 已导入到 ${mojang.absolutePath}"
                }
                "mcworld", "mctemplate" -> {
                    val worldsDir = File(mojang, "minecraftWorlds")
                    worldsDir.mkdirs()
                    val worldName = dest.nameWithoutExtension
                    val worldDest = File(worldsDir, worldName).let { base ->
                        var candidate = base
                        var idx = 1
                        while (candidate.exists()) { candidate = File(worldsDir, "${worldName}_$idx"); idx++ }
                        candidate
                    }
                    worldDest.mkdirs()
                    ZipInputStream(dest.inputStream().buffered()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val outFile = File(worldDest, entry.name)
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { zis.copyTo(it) }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                    "✓ 地图「${worldDest.name}」已导入"
                }
                "zip" -> {
                    BedrockLaunchEngine().injectAddon(dest.absolutePath, mojang)
                    "✓ 已导入到 ${mojang.absolutePath}"
                }
                else -> {
                    val fallback = File(System.getProperty("user.home"), "Downloads/$fileName")
                    dest.copyTo(fallback, overwrite = true)
                    "✓ 已保存到 ${fallback.absolutePath}"
                }
            }
        } catch (e: Exception) {
            "导入失败: ${e.message}"
        }
    }
}

private fun formatCfDl(c: Long): String = when {
    c >= 1_000_000 -> "${"%.1f".format(c / 1_000_000.0)}M"
    c >= 1_000 -> "${"%.1f".format(c / 1_000.0)}K"
    else -> "$c"
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1_024 -> "${"%.1f".format(bytes / 1_024.0)} KB"
    else -> "$bytes B"
}
