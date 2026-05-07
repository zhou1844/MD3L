package launcher.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
    var editionTab by DownloadScreenState.editionTab

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "下载中心",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "BMCLAPI 镜像加速 · 点击版本进入配置页",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        DownloadProgressBanner()

        // ── 全局双核 Tab：Java 版 / 基岩版 ────────────────────────────────────
        TabRow(
            selectedTabIndex = EditionTab.entries.indexOf(editionTab),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
            indicator = {},
            divider = {},
            modifier = Modifier.clip(RoundedCornerShape(12.dp)),
        ) {
            EditionTab.entries.forEach { tab ->
                Tab(
                    selected = editionTab == tab,
                    onClick = { editionTab = tab },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .background(
                            if (editionTab == tab) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (tab) {
                                EditionTab.Java -> Icons.Filled.Code
                                EditionTab.Bedrock -> Icons.Filled.ViewInAr
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (editionTab == tab)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = if (editionTab == tab)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 分发到对应 Tab 内容 ───────────────────────────────────────────────
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

    val aprilFoolIds = setOf(
        "1.RV-Pre1", "15w14a", "3D Shareware v1.34",
        "20w14infinite", "22w13oneblockatatime", "23w13a_or_b",
        "24w14potato",
    )

    val filtered = remoteVersions.filter { v ->
        val matchesSearch = searchQuery.isBlank() || v.id.contains(searchQuery, ignoreCase = true)
        val matchesTab = when (selectedTab) {
            JavaVersionTab.Release -> v.type == "release"
            JavaVersionTab.Snapshot -> v.type == "snapshot" && v.id !in aprilFoolIds
            JavaVersionTab.OldAlpha -> v.type == "old_alpha" || v.type == "old_beta"
            JavaVersionTab.AprilFool -> v.id in aprilFoolIds
        }
        matchesSearch && matchesTab
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 分类 Tabs ──────────────────────────────────────────────────────
        ScrollableTabRow(
            selectedTabIndex = JavaVersionTab.entries.indexOf(selectedTab),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 0.dp,
            divider = {},
        ) {
            JavaVersionTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BlockIcon(tab, size = 24)
                            Spacer(Modifier.width(6.dp))
                            Text(tab.label, style = MaterialTheme.typography.labelMedium)
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── 搜索栏 ───────────────────────────────────────────────────────────
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("搜索版本号...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))

        // ── 版本列表 ──────────────────────────────────────────────────────────
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(8.dp))
                    Text("获取版本列表...", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Inbox, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))
                    if (remoteVersions.isEmpty()) {
                        Text("获取版本列表失败", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        if (loadError.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(loadError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = {
                            isLoading = true
                            loadError = ""
                            scope.launch {
                                remoteVersions = VersionManifest.fetchVersionList()
                                loadError = VersionManifest.lastError
                                isLoading = false
                            }
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重试")
                        }
                    } else {
                        Text("无结果", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.id }) { version ->
                    ElevatedCard(
                        onClick = { Navigator.navigate(Route.VersionDetail(version)) },
                        shape = RoundedCornerShape(12.dp),
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
                            BlockIcon(selectedTab, size = 36)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    version.id,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "${version.type} · ${version.releaseTime.take(10)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                }
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

    val downloadingVersions by BedrockDownloadManager.downloadingVersions.collectAsState()
    val downloadResults by BedrockDownloadManager.downloadResults.collectAsState()
    val installProgress by BedrockDownloadManager.installProgress.collectAsState()

    LaunchedEffect(Unit) {
        if (bedrockVersions.isNotEmpty() || loadError.isNotBlank()) {
            isLoading = false
            return@LaunchedEffect
        }
        try {
            bedrockVersions = WUDownloadClient.fetchVersions()
            loadError = if (bedrockVersions.isEmpty()) "未能获取版本列表" else ""
            scope.launch {
                try {
                    bedrockVersions = WUDownloadClient.fetchVersions(forceNetwork = true)
                    loadError = if (bedrockVersions.isEmpty()) "未能获取版本列表" else ""
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            loadError = "获取失败: ${e.message}"
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
        matchesSearch && matchesTab
    }.sortedWith(
        compareByDescending<WUDownloadClient.WUVersion> {
            it.name.split(".").joinToString("") { part -> part.padStart(6, '0') }
        }.thenBy {
            if (it.isGdk) 0 else 1
        }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 基岩版子 Tab ───────────────────────────────────────────────────
        ScrollableTabRow(
            selectedTabIndex = BedrockSubTab.entries.indexOf(bedrockSubTab),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.tertiary,
            edgePadding = 0.dp,
            divider = {},
        ) {
            BedrockSubTab.entries.forEach { tab ->
                Tab(
                    selected = bedrockSubTab == tab,
                    onClick = { bedrockSubTab = tab },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (tab) {
                                    BedrockSubTab.Release -> Icons.Filled.Verified
                                    BedrockSubTab.Preview -> Icons.Filled.Science
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (bedrockSubTab == tab) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(tab.label, style = MaterialTheme.typography.labelMedium)
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── 搜索栏 ───────────────────────────────────────────────────────────
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("搜索基岩版版本号...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))

        // ── 版本列表 ───────────────────────────────────────────────────────
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.height(8.dp))
                    Text("正在获取版本列表...", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.ViewInAr, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))
                    Text("暂无可用基岩版版本", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (loadError.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(loadError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
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
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重试", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { "${it.name}_${it.packageType}" }) { ver ->
                    val versionKey = "${ver.name}_${ver.packageType}"
                    val isDownloading = versionKey in downloadingVersions
                    val result = downloadResults[versionKey]

                    ElevatedCard(
                        shape = RoundedCornerShape(12.dp),
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
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                BedrockDownloadManager.launchDownloadWU(ver)
                                            } catch (e: Exception) {
                                                loadError = "启动下载失败: ${e.message}"
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    ),
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("下载安装", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }
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
