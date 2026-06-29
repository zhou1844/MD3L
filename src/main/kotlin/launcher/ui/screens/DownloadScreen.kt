package launcher.ui.screens

import launcher.ui.layout.NavBarScrollState

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import launcher.core.*
import launcher.ui.layout.Navigator
import launcher.ui.nav.Route
import java.io.File

// ═════════════════════════════════════════════════════════════════════════════
//  顶级双核 Tab：Java 版 / 基岩版
// ═════════════════════════════════════════════════════════════════════════════
private enum class EditionTab(val label: String) {
    Java("Java 版"),
    Bedrock("基岩版"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BedrockVersionDetailScreen(version: WUDownloadClient.WUVersion) {
    val scope = rememberCoroutineScope()
    var selectedSource by remember { mutableStateOf(BedrockDownloadManager.availableSources(version).first()) }
    var sourceExpanded by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf("") }

    val keyPreview = remember(version.name, version.packageType) { "${version.name}_${version.packageType}" }
    val downloadingVersions by BedrockDownloadManager.downloadingVersions.collectAsState()
    val downloadResults by BedrockDownloadManager.downloadResults.collectAsState()
    val installProgress by BedrockDownloadManager.installProgress.collectAsState()
    val isDownloading = keyPreview in downloadingVersions
    val result = downloadResults[keyPreview]

    val isPreview = version.packageType.lowercase().contains("preview")

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 顶部标题区 ────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { Navigator.back() },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(42.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Image(
                painter = painterResource("icons/command_block.png"),
                contentDescription = null,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("基岩版安装配置", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text("选择下载源后开始安装", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ── 版本信息横幅 ──────────────────────────────────────────────────────
        ElevatedCard(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (isPreview)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Image(
                    painter = painterResource("icons/command_block.png"),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        version.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isPreview) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                if (isPreview) "测试版 Preview" else "正式版 Release",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isPreview) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        if (version.displayLabel.isNotBlank() && version.displayLabel != version.name) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    version.displayLabel,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 下载源选择 ────────────────────────────────────────────────────────
        ElevatedCard(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudDownload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("下载源", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                }
                Spacer(Modifier.height(12.dp))
                ExposedDropdownMenuBox(
                    expanded = sourceExpanded,
                    onExpandedChange = { sourceExpanded = !sourceExpanded },
                ) {
                    OutlinedTextField(
                        value = selectedSource.label,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("选择下载源") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceExpanded) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Filled.Hub, null, modifier = Modifier.size(18.dp)) },
                    )
                    ExposedDropdownMenu(expanded = sourceExpanded, onDismissRequest = { sourceExpanded = false }) {
                        BedrockDownloadManager.availableSources(version).forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.label) },
                                onClick = { selectedSource = source; sourceExpanded = false },
                                leadingIcon = {
                                    if (selectedSource.label == source.label)
                                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                },
                            )
                        }
                    }
                }
            }
        }

        // ── 进度卡片 ──────────────────────────────────────────────────────────
        if (isDownloading && installProgress.versionKey == keyPreview && installProgress.phase.isNotBlank()) {
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.secondary)
                        Text(installProgress.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                    if (installProgress.phase != "done" && installProgress.phase != "error") {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { installProgress.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }
        } else if (!result.isNullOrBlank()) {
            val isErr = "失败" in result || "错误" in result || "取消" in result
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isErr) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (isErr) Icons.Filled.Error else Icons.Filled.CheckCircle,
                        null, modifier = Modifier.size(16.dp),
                        tint = if (isErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(result, style = MaterialTheme.typography.bodySmall, color = if (isErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                }
            }
        }

        // ── 错误消息 ──────────────────────────────────────────────────────────
        if (localError.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    Text(localError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // ── 安装按钮 ──────────────────────────────────────────────────────────
        Button(
            onClick = {
                val finalName = version.name
                if (finalName.isBlank()) {
                    localError = "安装版本名不能为空"
                    return@Button
                }
                localError = ""
                scope.launch {
                    runCatching {
                        BedrockDownloadManager.launchDownloadWU(
                            ver = version,
                            source = selectedSource,
                            installVersionName = finalName,
                        )
                    }.onFailure {
                        localError = "启动下载失败: ${it.message}"
                    }
                }
            },
            enabled = !isDownloading,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isPreview) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                contentColor = if (isPreview) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text("安装中…", style = MaterialTheme.typography.titleSmall)
            } else {
                Icon(Icons.Filled.RocketLaunch, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("开始下载并安装", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ── Java 版分类 ──────────────────────────────────────────────────────────────
private enum class JavaVersionTab(
    val label: String,
    val iconRes: String,
    val fallbackLetter: String,
    val fallbackBg: Color,
) {
    Release("正式版", "icons/grass_block.png", "G", Color(0xFF5DA031)),
    Snapshot("快照版", "icons/diamond_block.png", "D", Color(0xFF50D2F5)),
    OldAlpha("远古版", "icons/cobblestone.png", "C", Color(0xFF8C8C8C)),
    AprilFool("愚人节", "icons/command_block.png", "Cmd", Color(0xFFDC9632)),
}

// ── 基岩版分类 ────────────────────────────────────────────────────────────────
private enum class BedrockSubTab(val label: String) {
    Release("正式版 Release"),
    Preview("测试版 Preview"),
}

private object DownloadScreenState {
    val editionTab = mutableStateOf(EditionTab.Java)
    val javaRemoteVersions = mutableStateOf<List<RemoteVersion>>(emptyList())
    val javaIsLoading = mutableStateOf(true)
    val javaSelectedTab = mutableStateOf(JavaVersionTab.Release)
    val javaSearchQuery = mutableStateOf("")
    val javaLoadError = mutableStateOf("")
    val bedrockSubTab = mutableStateOf(BedrockSubTab.Release)
    val bedrockVersions = mutableStateOf<List<WUDownloadClient.WUVersion>>(emptyList())
    val bedrockIsLoading = mutableStateOf(true)
    val bedrockLoadError = mutableStateOf("")
    val bedrockSearchQuery = mutableStateOf("")
    val javaListFirstVisibleItemIndex = mutableStateOf(0)
    val javaListFirstVisibleItemScrollOffset = mutableStateOf(0)
    val bedrockListFirstVisibleItemIndex = mutableStateOf(0)
    val bedrockListFirstVisibleItemScrollOffset = mutableStateOf(0)
}

private fun resourceExists(path: String): Boolean {
    return try {
        Thread.currentThread().contextClassLoader?.getResource(path) != null
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun BlockIcon(tab: JavaVersionTab, size: Int = 32) {
    val hasResource = remember(tab.iconRes) { resourceExists(tab.iconRes) }
    if (hasResource) {
        Image(
            painter = painterResource(tab.iconRes),
            contentDescription = tab.label,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(tab.fallbackBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                tab.fallbackLetter.take(1),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = (size / 3).sp,
                ),
                color = Color.White,
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  全局下载进度条（Java / 基岩共用）
// ═════════════════════════════════════════════════════════════════════════════
@Composable
private fun DownloadProgressBanner() {
    val downloadProgress by DownloadManager.progress.collectAsState()
    if (downloadProgress.isRunning) {
        ElevatedCard(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(downloadProgress.currentFile, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(downloadProgress.speedMbps, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress.fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text("${downloadProgress.completedFiles}/${downloadProgress.totalFiles}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  主入口
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen() {
    val isEn = launcher.ui.theme.ThemeState.language == "en"
    var editionTab by DownloadScreenState.editionTab

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 标题区 + 内嵌版本切换 ──────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.CloudDownload, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(if (isEn) "Download Center" else "下载中心", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(if (isEn) "BMCLAPI mirror · tap version to configure" else "BMCLAPI 镜像加速 · 点击版本进入配置页", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.height(36.dp),
            ) {
                Row(modifier = Modifier.padding(3.dp)) {
                    EditionTab.entries.forEach { tab ->
                        val selected = editionTab == tab
                        Surface(
                            shape = RoundedCornerShape(17.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            modifier = Modifier.clip(RoundedCornerShape(17.dp)).clickable { editionTab = tab },
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(
                                    when (tab) { EditionTab.Java -> Icons.Filled.Terminal; EditionTab.Bedrock -> Icons.Filled.Layers },
                                    null, modifier = Modifier.size(13.dp),
                                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    when (tab) { EditionTab.Java -> "Java"; EditionTab.Bedrock -> (if (isEn) "Bedrock" else "基岩") },
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        DownloadProgressBanner()

        when (editionTab) {
            EditionTab.Java -> JavaDownloadContent()
            EditionTab.Bedrock -> BedrockDownloadContent()
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Java 版下载内容（保持原有功能）
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JavaDownloadContent() {
    val isEn = launcher.ui.theme.ThemeState.language == "en"
    var remoteVersions by DownloadScreenState.javaRemoteVersions
    var isLoading by DownloadScreenState.javaIsLoading
    var selectedTab by DownloadScreenState.javaSelectedTab
    var searchQuery by DownloadScreenState.javaSearchQuery
    var loadError by DownloadScreenState.javaLoadError
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = DownloadScreenState.javaListFirstVisibleItemIndex.value,
        initialFirstVisibleItemScrollOffset = DownloadScreenState.javaListFirstVisibleItemScrollOffset.value,
    )
    // 监听 Java 列表滚动位置，更新底栏淡出隐藏状态
    LaunchedEffect(listState) {
        snapshotFlow {
            val canScrollForward = listState.canScrollForward
            val layoutInfo = listState.layoutInfo
            val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
            val totalItems = layoutInfo.totalItemsCount
            Triple(canScrollForward, firstVisible?.index ?: 0, totalItems)
        }.collect { (canScrollForward, firstIdx, totalItems) ->
            NavBarScrollState.scrollFraction.value = when {
                totalItems == 0 -> 0f
                // 内容太少不足以滚动 → 保持导航栏可见
                !canScrollForward && firstIdx == 0 -> 0f
                !canScrollForward -> 1f
                else -> (firstIdx.toFloat() / totalItems.toFloat()).coerceIn(0f, 0.99f)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (remoteVersions.isNotEmpty() || loadError.isNotBlank()) return@LaunchedEffect
        remoteVersions = VersionManifest.fetchVersionList()
        loadError = VersionManifest.lastError
        isLoading = false
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                DownloadScreenState.javaListFirstVisibleItemIndex.value = index
                DownloadScreenState.javaListFirstVisibleItemScrollOffset.value = offset
            }
    }

    val filtered = remoteVersions.filter { v ->
        val matchesSearch = searchQuery.isBlank() || v.id.contains(searchQuery, ignoreCase = true)
        val matchesTab = when (selectedTab) {
            JavaVersionTab.Release -> v.type == "release"
            JavaVersionTab.Snapshot -> v.type == "snapshot" && v.id !in VersionScanner.aprilFoolIds
            JavaVersionTab.OldAlpha -> v.type == "old_alpha" || v.type == "old_beta"
            JavaVersionTab.AprilFool -> v.id in VersionScanner.aprilFoolIds
        }
        matchesSearch && matchesTab
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 分类Pills + 搜索框（合并卡片）────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JavaVersionTab.entries.forEach { tab ->
                        item(key = tab.name) {
                            val sel = selectedTab == tab
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { selectedTab = tab },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    BlockIcon(tab, size = 18)
                                    Text(
                                        when (tab) {
                                            JavaVersionTab.Release -> (if (isEn) "Release" else "正式版")
                                            JavaVersionTab.Snapshot -> (if (isEn) "Snapshot" else "快照版")
                                            JavaVersionTab.OldAlpha -> (if (isEn) "Legacy" else "远古版")
                                            JavaVersionTab.AprilFool -> (if (isEn) "April Fools" else "愚人节")
                                        },
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal),
                                        color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(if (isEn) "Search version ID…" else "搜索版本号…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)) },
                    trailingIcon = { if (searchQuery.isNotBlank()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Filled.Clear, null, modifier = Modifier.size(18.dp)) } },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── 版本列表 ───────────────────────────────────────────────────────────
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 3.5.dp)
                    Text(if (isEn) "Fetching version list…" else "获取版本列表…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(84.dp).clip(RoundedCornerShape(28.dp)).background(
                        if (remoteVersions.isEmpty()) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    ), contentAlignment = Alignment.Center) {
                        Icon(
                            if (remoteVersions.isEmpty()) Icons.Filled.WifiOff else Icons.Filled.Inbox, null,
                            modifier = Modifier.size(42.dp),
                            tint = if (remoteVersions.isEmpty()) MaterialTheme.colorScheme.error.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                    if (remoteVersions.isEmpty()) {
                        Text(if (isEn) "Failed to fetch version list" else "获取版本列表失败", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        if (loadError.isNotBlank()) Text(loadError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        FilledTonalButton(
                            onClick = { isLoading = true; loadError = ""; scope.launch { remoteVersions = VersionManifest.fetchVersionList(); loadError = VersionManifest.lastError; isLoading = false } },
                            shape = RoundedCornerShape(18.dp),
                        ) { Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(if (isEn) "Retry" else "重试") }
                    } else {
                        Text(if (isEn) "No results" else "无匹配结果", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text(if (isEn) "Try different keywords" else "试试其他关键词", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                ) {
                    items(filtered, key = { it.id }) { version ->
                        ElevatedCard(
                            onClick = { Navigator.navigate(Route.VersionDetail(version)) },
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                BlockIcon(selectedTab, size = 40)
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(version.id, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                    Text("${version.type} · ${version.releaseTime.take(10)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
                VerticalScrollbar(adapter = rememberScrollbarAdapter(listState), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  基岩版下载内容 —— 全新双核下载链路
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BedrockDownloadContent() {
    val isEn = launcher.ui.theme.ThemeState.language == "en"
    val scope = rememberCoroutineScope()
    var bedrockSubTab by DownloadScreenState.bedrockSubTab
    var bedrockVersions by DownloadScreenState.bedrockVersions
    var isLoading by DownloadScreenState.bedrockIsLoading
    var loadError by DownloadScreenState.bedrockLoadError
    var searchQuery by DownloadScreenState.bedrockSearchQuery
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = DownloadScreenState.bedrockListFirstVisibleItemIndex.value,
        initialFirstVisibleItemScrollOffset = DownloadScreenState.bedrockListFirstVisibleItemScrollOffset.value,
    )
    // 监听基岩版列表滚动位置，更新底栏淡出隐藏状态
    LaunchedEffect(listState) {
        snapshotFlow {
            val canScrollForward = listState.canScrollForward
            val layoutInfo = listState.layoutInfo
            val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
            val totalItems = layoutInfo.totalItemsCount
            Triple(canScrollForward, firstVisible?.index ?: 0, totalItems)
        }.collect { (canScrollForward, firstIdx, totalItems) ->
            NavBarScrollState.scrollFraction.value = when {
                totalItems == 0 -> 0f
                // 内容太少不足以滚动 → 保持导航栏可见
                !canScrollForward && firstIdx == 0 -> 0f
                !canScrollForward -> 1f
                else -> (firstIdx.toFloat() / totalItems.toFloat()).coerceIn(0f, 0.99f)
            }
        }
    }

    val downloadingVersions by BedrockDownloadManager.downloadingVersions.collectAsState()
    val downloadResults by BedrockDownloadManager.downloadResults.collectAsState()
    val installProgress by BedrockDownloadManager.installProgress.collectAsState()

    LaunchedEffect(Unit) {
        if (bedrockVersions.isNotEmpty() || loadError.isNotBlank()) {
            isLoading = false
            return@LaunchedEffect
        }
        try {
            bedrockVersions = WUDownloadClient.fetchVersions(forceNetwork = true)
            loadError = if (bedrockVersions.isEmpty()) "未能获取版本列表" else ""
        } catch (e: Exception) {
            val fallback = runCatching { WUDownloadClient.fetchVersions(forceNetwork = false) }.getOrDefault(emptyList())
            bedrockVersions = fallback
            loadError = if (fallback.isEmpty()) "获取失败: ${e.message}" else "网络刷新失败，已显示本地缓存"
        }
        isLoading = false
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                DownloadScreenState.bedrockListFirstVisibleItemIndex.value = index
                DownloadScreenState.bedrockListFirstVisibleItemScrollOffset.value = offset
            }
    }

    val filtered = bedrockVersions.filter { v ->
        val matchesSearch = searchQuery.isBlank() || v.name.contains(searchQuery, ignoreCase = true)
        val matchesTab = when (bedrockSubTab) {
            BedrockSubTab.Release -> v.isRelease
            BedrockSubTab.Preview -> v.isPreview
        }
        // GDK 版本（>=1.21.120.21）没有 UWP 包，过滤掉错误条目
        val validPackage = !(v.isUwp && v.isGdkByVersion)
        matchesSearch && matchesTab && validPackage
    }.sortedWith(
        compareByDescending<WUDownloadClient.WUVersion> {
            it.name.split(".").joinToString("") { part -> part.padStart(6, '0') }
        }.thenBy {
            if (it.isGdk) 0 else 1
        }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 基岩版分类 Pills ───────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BedrockSubTab.entries.forEach { tab ->
                val sel = bedrockSubTab == tab
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (sel) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { bedrockSubTab = tab }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (tab) {
                                BedrockSubTab.Release -> Icons.Filled.Verified
                                BedrockSubTab.Preview -> Icons.Filled.Science
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (sel) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (tab) { BedrockSubTab.Release -> (if (isEn) "Release" else "正式版 Release"); BedrockSubTab.Preview -> (if (isEn) "Preview" else "测试版 Preview") },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (sel) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── 搜索栏 ───────────────────────────────────────────────────────────
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(if (isEn) "Search Bedrock version…" else "搜索基岩版版本号…") },
            leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(20.dp)) },
            trailingIcon = { if (searchQuery.isNotBlank()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Filled.Clear, null, modifier = Modifier.size(18.dp)) } },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))

        // ── 版本列表 ───────────────────────────────────────────────────────
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.tertiary)
                    Text(if (isEn) "Fetching Bedrock version list…" else "获取基岩版版本列表…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(80.dp).clip(RoundedCornerShape(28.dp)).background(
                            if (loadError.isNotBlank()) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        ), contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (loadError.isNotBlank()) Icons.Filled.WifiOff else Icons.Filled.ViewInAr, null,
                            modifier = Modifier.size(40.dp),
                            tint = if (loadError.isNotBlank()) MaterialTheme.colorScheme.error.copy(alpha = 0.7f) else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
                        )
                    }
                    Text(if (loadError.isNotBlank()) (if (isEn) "Failed to fetch" else "获取失败") else (if (isEn) "No Bedrock versions available" else "暂无可用基岩版版本"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    if (loadError.isNotBlank()) Text(loadError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    FilledTonalButton(
                        onClick = {
                            isLoading = true; loadError = ""
                            scope.launch {
                                try {
                                    bedrockVersions = WUDownloadClient.fetchVersions(forceNetwork = true)
                                    loadError = if (bedrockVersions.isEmpty()) "未能获取版本列表" else ""
                                } catch (e: Exception) { loadError = "获取失败: ${e.message}" }
                                isLoading = false
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isEn) "Retry" else "重试")
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(end = 8.dp),
            ) {
                items(filtered, key = { "${it.name}_${it.packageType}" }) { ver ->
                    val versionKey = "${ver.name}_${ver.packageType}"
                    val isDownloading = versionKey in downloadingVersions
                    val result = downloadResults[versionKey]

                    ElevatedCard(
                        onClick = { if (!isDownloading) Navigator.navigate(Route.BedrockVersionDetail(ver)) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (ver.isRelease) MaterialTheme.colorScheme.tertiaryContainer
                                        else MaterialTheme.colorScheme.secondaryContainer
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (ver.isRelease) Icons.Filled.ViewInAr else Icons.Filled.Science,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (ver.isRelease) MaterialTheme.colorScheme.onTertiaryContainer
                                    else MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    ver.name,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    ver.displayLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                val ip = installProgress
                                if (isDownloading && ip.versionKey == versionKey && ip.phase.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        ip.message,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when (ip.phase) {
                                            "error" -> MaterialTheme.colorScheme.error
                                            "done" -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.tertiary
                                        },
                                        maxLines = 2,
                                    )
                                    if (ip.phase != "done" && ip.phase != "error") {
                                        Spacer(Modifier.height(2.dp))
                                        LinearProgressIndicator(
                                            progress = { ip.fraction.coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                                val showInlineResult = result?.let {
                                    "失败" in it || "错误" in it || "取消" in it || "暂停" in it
                                } == true
                                if (showInlineResult) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        result!!,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }

                            if (isDownloading) {
                                IconButton(
                                    onClick = { BedrockDownloadManager.cancelDownload(versionKey) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "取消下载",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            } else {
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = "进入详情",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
