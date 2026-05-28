package launcher.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import launcher.core.*
import launcher.ui.layout.Navigator
import javax.swing.JFileChooser

private enum class LoaderOption(val label: String) {
    None("不安装"),
    Forge("Forge"),
    Fabric("Fabric"),
    NeoForge("NeoForge"),
    OptiFine("OptiFine"),
}

/**
 * 单个 Loader 版本信息
 */
private data class LoaderVersionItem(
    val version: String,
    val label: String = version,
    val metadata: Map<String, String> = emptyMap(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionDetailScreen(version: RemoteVersion) {
    val scope = rememberCoroutineScope()
    var customName by remember { mutableStateOf(version.id) }
    var selectedLoader by remember { mutableStateOf(LoaderOption.None) }
    var isInstalling by remember { mutableStateOf(false) }
    var installMessage by remember { mutableStateOf("") }
    var installJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val downloadProgress by DownloadManager.progress.collectAsState()
    val loaderProgress by LoaderInstaller.progress.collectAsState()
    val scrollState = rememberScrollState()

    // ── Loader 版本列表 ──────────────────────────────────────────────────
    var loaderVersions by remember { mutableStateOf<List<LoaderVersionItem>>(emptyList()) }
    var selectedLoaderVersion by remember { mutableStateOf<LoaderVersionItem?>(null) }
    var isLoadingLoaderVersions by remember { mutableStateOf(false) }
    var loaderDropdownExpanded by remember { mutableStateOf(false) }

    // 当用户切换 Loader 类型时，网络获取可用 Loader 版本
    LaunchedEffect(selectedLoader) {
        if (selectedLoader == LoaderOption.None || selectedLoader == LoaderOption.OptiFine) {
            loaderVersions = emptyList()
            selectedLoaderVersion = null
            if (selectedLoader == LoaderOption.OptiFine) {
                isLoadingLoaderVersions = true
                try {
                    val list = withContext(Dispatchers.IO) { fetchOptiFineVersions(version.id) }
                    loaderVersions = list
                    if (list.isNotEmpty()) selectedLoaderVersion = list.first()
                    else installMessage = "暂无可用的 OptiFine 版本（该 MC 版本可能尚未支持）"
                } catch (e: Exception) {
                    installMessage = "获取 OptiFine 版本列表失败: ${e.message}"
                } finally {
                    isLoadingLoaderVersions = false
                }
            }
            return@LaunchedEffect
        }
        isLoadingLoaderVersions = true
        loaderVersions = emptyList()
        selectedLoaderVersion = null
        try {
            val list = withContext(Dispatchers.IO) { fetchLoaderVersions(version.id, selectedLoader) }
            loaderVersions = list
            if (list.isNotEmpty()) {
                selectedLoaderVersion = list.first()
            } else {
                installMessage = "获取 ${selectedLoader.label} 版本列表为空（该版本可能不支持此加载器，或网络请求失败）"
            }
        } catch (e: Exception) {
            installMessage = "获取 ${selectedLoader.label} 版本列表失败: ${e.message}"
        } finally {
            isLoadingLoaderVersions = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(end = 8.dp)) {
        // ── 返回 + 标题 ──────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { Navigator.back() }, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("版本配置", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                Text("配置后点击底部按钮安装", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))

        // ── 版本信息 ─────────────────────────────────────────────────────────
        SettingsCard("版本信息", Icons.Filled.Info) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip("版本", version.id)
                InfoChip("类型", version.type)
                InfoChip("发布", version.releaseTime.take(10))
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 自定义名称 ──────────────────────────────────────────────────────
        SettingsCard("自定义版本名称", Icons.Filled.DriveFileRenameOutline) {
            OutlinedTextField(
                value = customName,
                onValueChange = { customName = it },
                label = { Text("版本名称") },
                placeholder = { Text(version.id) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text("留空使用: ${version.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(12.dp))

        // ── 加载器选择 ──────────────────────────────────────────────────────
        SettingsCard("模组加载器", Icons.Filled.Build) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LoaderOption.entries.forEach { option ->
                    FilterChip(
                        selected = selectedLoader == option,
                        onClick = { selectedLoader = option },
                        label = { Text(option.label) },
                        leadingIcon = if (selectedLoader == option) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            // ── Loader 版本下拉 ─────────────────────────────────────────────
            if (selectedLoader != LoaderOption.None) {
                Spacer(Modifier.height(12.dp))
                if (isLoadingLoaderVersions) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在获取 ${selectedLoader.label} 版本列表…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (loaderVersions.isEmpty()) {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("该版本暂无可用的 ${selectedLoader.label}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    ExposedDropdownMenuBox(expanded = loaderDropdownExpanded, onExpandedChange = { loaderDropdownExpanded = it }) {
                        OutlinedTextField(
                            value = selectedLoaderVersion?.label ?: "选择版本",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("${selectedLoader.label} 版本") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(loaderDropdownExpanded) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(expanded = loaderDropdownExpanded, onDismissRequest = { loaderDropdownExpanded = false }) {
                            loaderVersions.forEach { lv ->
                                DropdownMenuItem(
                                    text = { Text(lv.label, style = MaterialTheme.typography.bodySmall) },
                                    onClick = {
                                        selectedLoaderVersion = lv
                                        loaderDropdownExpanded = false
                                    },
                                    leadingIcon = {
                                        if (selectedLoaderVersion?.version == lv.version)
                                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 加载器安装进度 ─────────────────────────────────────────────────
        if (loaderProgress.isRunning || loaderProgress.done || loaderProgress.error.isNotBlank()) {
            ElevatedCard(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = when {
                        loaderProgress.error.isNotBlank() -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        loaderProgress.done -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        else -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                    }
                ),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (loaderProgress.isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.tertiary)
                        } else if (loaderProgress.done) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${loaderProgress.loaderName}: ${loaderProgress.step}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (loaderProgress.isRunning) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { loaderProgress.fraction },
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── 下载进度 ───────────────────────────────────────────────────────────
        if (downloadProgress.isRunning) {
            ElevatedCard(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(downloadProgress.currentFile, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(downloadProgress.speedMbps, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress.fraction },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("${downloadProgress.completedFiles}/${downloadProgress.totalFiles} 文件 · ${"%.1f".format(downloadProgress.downloadedBytes / 1_048_576.0)} MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── 消息 ─────────────────────────────────────────────────────────────
        if (installMessage.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if ("成功" in installMessage) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(installMessage, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                    color = if ("成功" in installMessage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.weight(1f))

        // ── 确认安装 ─────────────────────────────────────────────────────────
        Button(
            onClick = {
                isInstalling = true
                installMessage = ""
                installJob = scope.launch {
                    try {
                        var settings = AppSettings.load()
                        if (settings.minecraftDir.isBlank()) {
                            val chosen = withContext(Dispatchers.IO) {
                                val chooser = JFileChooser(defaultMinecraftDir())
                                chooser.dialogTitle = "选择游戏主目录 (.minecraft)"
                                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                chooser.approveButtonText = "确认"
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
                                    chooser.selectedFile.absolutePath else null
                            }
                            if (chosen == null) {
                                installMessage = "未选择目录，已取消"
                                isInstalling = false
                                return@launch
                            }
                            settings = settings.copy(minecraftDir = chosen)
                            AppSettings.save(settings)
                        }
                        val finalName = customName.ifBlank { version.id }
                        val loader = selectedLoader
                        val loaderVer = selectedLoaderVersion

                        // 构建请求，全局 scope 执行，切换页面不中断
                        val req = InstallOrchestrator.InstallRequest(
                            version = version,
                            minecraftDir = settings.minecraftDir,
                            customName = finalName,
                            maxThreads = settings.maxDownloadThreads,
                            javaPath = settings.javaPath,
                            loaderType = if (loader != LoaderOption.None && loader != LoaderOption.OptiFine && loaderVer != null) loader.label else null,
                            loaderVersion = if (loader != LoaderOption.OptiFine) loaderVer?.version else null,
                            forgeBuild = if (loader == LoaderOption.Forge)
                                loaderVer?.metadata?.get("build")?.toIntOrNull() ?: 0 else 0,
                            optifineVersion = if (loader == LoaderOption.OptiFine) loaderVer?.version else null,
                        )
                        InstallOrchestrator.launch(req)

                        // 自动返回上一页
                        Navigator.back()
                    } catch (e: Exception) {
                        installMessage = "启动安装失败: ${e.message}"
                        isInstalling = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            enabled = !isInstalling && !downloadProgress.isRunning,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
        ) {
            if (isInstalling) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text("正在安装…", style = MaterialTheme.typography.titleSmall)
            } else {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("确认下载并安装", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
    )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 网络获取 Loader 版本列表
// ══════════════════════════════════════════════════════════════════════════════

private val httpJson = Json { ignoreUnknownKeys = true }

private suspend fun fetchLoaderVersions(mcVersion: String, loader: LoaderOption): List<LoaderVersionItem> {
    return when (loader) {
        LoaderOption.Fabric -> fetchFabricVersions(mcVersion)
        LoaderOption.Forge -> fetchForgeVersions(mcVersion)
        LoaderOption.NeoForge -> fetchNeoForgeVersions(mcVersion)
        else -> emptyList()
    }
}

/** 通用 HTTP GET：先 curl.exe，后 HttpURLConnection（解决 JVM SSL 问题） */
private fun loaderHttpGet(url: String): String {
    // curl 优先（Fabric 响应很大，给充足超时）
    try {
        val proc = ProcessBuilder(
            "curl.exe", "-sL", "--connect-timeout", "15", "--max-time", "60", url
        ).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        val ok = proc.waitFor(65, java.util.concurrent.TimeUnit.SECONDS)
        if (ok && proc.exitValue() == 0 && text.isNotBlank()) {
            val trimmed = text.trim()
            // 基本 JSON 验证：以 [ 或 { 开头
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) return trimmed
        }
        println("[LoaderFetch] curl 失败 url=$url exitCode=${if (ok) proc.exitValue() else "timeout"}")
    } catch (e: Exception) {
        println("[LoaderFetch] curl 异常: ${e.message}")
    }

    // fallback: HttpURLConnection
    try {
        val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "MD3L/1.1")
        val code = conn.responseCode
        if (code == 200) {
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            if (text.isNotBlank()) return text.trim()
        }
        conn.disconnect()
        println("[LoaderFetch] HTTP fallback 失败 url=$url code=$code")
    } catch (e: Exception) {
        println("[LoaderFetch] HTTP fallback 异常: ${e.message}")
    }

    return ""
}

private suspend fun fetchFabricVersions(mcVersion: String): List<LoaderVersionItem> = withContext(Dispatchers.IO) {
    try {
        // BMCLAPI 镜像优先，官方备选
        val urls = listOf(
            "https://bmclapi2.bangbang93.com/fabric-meta/v2/versions/loader/$mcVersion",
            "https://meta.fabricmc.net/v2/versions/loader/$mcVersion",
        )
        var text = ""
        for (url in urls) {
            try { text = loaderHttpGet(url); if (text.isNotBlank()) break } catch (_: Exception) {}
        }
        if (text.isBlank()) return@withContext emptyList()
        val arr = httpJson.parseToJsonElement(text).jsonArray
        arr.mapNotNull { el ->
            val loaderObj = el.jsonObject["loader"]?.jsonObject ?: return@mapNotNull null
            val ver = loaderObj["version"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val stable = loaderObj["stable"]?.jsonPrimitive?.booleanOrNull ?: false
            LoaderVersionItem(ver, "$ver${if (stable) " (stable)" else ""}")
        }
    } catch (_: Exception) { emptyList() }
}

private suspend fun fetchForgeVersions(mcVersion: String): List<LoaderVersionItem> = withContext(Dispatchers.IO) {
    try {
        val urls = listOf(
            "https://bmclapi2.bangbang93.com/forge/minecraft/$mcVersion",
        )
        var text = ""
        for (url in urls) {
            try { text = loaderHttpGet(url); if (text.isNotBlank()) break } catch (_: Exception) {}
        }
        if (text.isNotBlank()) {
            val arr = httpJson.parseToJsonElement(text).jsonArray
            val items = arr.mapNotNull { el ->
                val obj = el.jsonObject
                val ver = obj["version"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val build = obj["build"]?.jsonPrimitive?.intOrNull?.toString() ?: ""
                LoaderVersionItem(ver, ver, if (build.isNotBlank()) mapOf("build" to build) else emptyMap())
            }.reversed()
            if (items.isNotEmpty()) return@withContext items
        }

        // fallback：Forge maven metadata（不包含 build，但可用于直接 maven 安装器下载）
        val metadataUrls = listOf(
            "https://bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/maven-metadata.xml",
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml",
        )
        val metadataText = metadataUrls.asSequence().mapNotNull { url ->
            runCatching { loaderHttpGet(url) }.getOrNull()
        }.firstOrNull { it.isNotBlank() }
            ?: return@withContext emptyList()

        val prefix = "$mcVersion-"
        val versions = Regex("<version>([^<]+)</version>")
            .findAll(metadataText)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        versions.reversed().map { LoaderVersionItem(it, it) }
    } catch (_: Exception) { emptyList() }
}

private suspend fun fetchNeoForgeVersions(mcVersion: String): List<LoaderVersionItem> = withContext(Dispatchers.IO) {
    try {
        val urls = listOf(
            "https://bmclapi2.bangbang93.com/neoforge/list/$mcVersion",
        )
        var text = ""
        for (url in urls) {
            try { text = loaderHttpGet(url); if (text.isNotBlank()) break } catch (_: Exception) {}
        }
        if (text.isBlank()) return@withContext emptyList()
        val arr = httpJson.parseToJsonElement(text).jsonArray
        arr.mapNotNull { el ->
            val ver = el.jsonObject["version"]?.jsonPrimitive?.contentOrNull
                ?: el.jsonPrimitive.contentOrNull
                ?: return@mapNotNull null
            LoaderVersionItem(ver)
        }.reversed()
    } catch (_: Exception) { emptyList() }
}

private suspend fun fetchOptiFineVersions(mcVersion: String): List<LoaderVersionItem> = withContext(Dispatchers.IO) {
    try {
        val url = "https://bmclapi2.bangbang93.com/optifine/$mcVersion"
        val text = loaderHttpGet(url)
        if (text.isBlank()) return@withContext emptyList()
        val arr = httpJson.parseToJsonElement(text).jsonArray
        arr.mapNotNull { el ->
            val obj = el.jsonObject
            val patch = obj["patch"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val type  = obj["type"]?.jsonPrimitive?.contentOrNull ?: "HD_U"
            val ver   = "${type}_$patch"
            LoaderVersionItem(version = ver, label = "OptiFine $ver")
        }
    } catch (_: Exception) { emptyList() }
}

// ══════════════════════════════════════════════════════════════════════════════
// 内部子组件
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SettingsCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}
