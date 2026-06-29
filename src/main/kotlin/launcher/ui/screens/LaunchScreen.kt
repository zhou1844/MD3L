package launcher.ui.screens

import launcher.ui.layout.NavBarScrollState

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.kamel.image.KamelImage
import io.kamel.image.asyncPainterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import launcher.core.*
import launcher.ui.components.LocalVersionTreeSheetContent
import launcher.ui.components.VersionIcon
import launcher.ui.components.loadSkinFaceBitmap
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Desktop
import java.io.File
import java.net.URL
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchScreen() {
    val scope = rememberCoroutineScope()
    val isEn = launcher.ui.theme.ThemeState.language == "en"
    // 使用全局持久化状态，确保切换页面后启动页状态不丢失
    var settings by LaunchScreenState.settings
    var versions by LaunchScreenState.versions
    var bedrockVersions by LaunchScreenState.bedrockVersions
    var selectedVersion by LaunchScreenState.selectedVersion
    val globalLaunching by LaunchState.isLaunching.collectAsState()
    val globalStatusMsg by LaunchState.statusMessage.collectAsState()
    val globalProgress by LaunchState.progress.collectAsState()
    val activeProcess by GameProcessManager.activeProcess.collectAsState()
    val processMsg by GameProcessManager.statusMessage.collectAsState()
    val gameProgress by GameProcessManager.launchProgress.collectAsState()
    val crashReport by GameProcessManager.crashReport.collectAsState()
    // 使用 derivedStateOf 缓存计算值，避免每次重组都重新计算
    val gameRunning by remember { derivedStateOf { activeProcess != null } }
    val uiLocked by remember { derivedStateOf { globalLaunching || gameRunning } }
    var launchMessage by LaunchScreenState.launchMessage
    val downloadProgress by DownloadManager.progress.collectAsState()
    val bedrockDownloading by BedrockDownloadManager.downloadingVersions.collectAsState()
    val bedrockDownloadResults by BedrockDownloadManager.downloadResults.collectAsState()

    // ── AccountRepository StateFlow 响应式绑定 ──────────────────────────────
    val accountList by AccountRepository.accounts.collectAsState()
    val activeAccount by AccountRepository.activeAccount.collectAsState()
    val refreshState by AccountRepository.refreshState.collectAsState()

    // 登录状态
    var showLoginDialog by remember { mutableStateOf(false) }
    var editingOfflineName by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf("") }

    // 微软 OAuth 状态
    var showMsAuthDialog by remember { mutableStateOf(false) }
    var deviceCodeInfo by remember { mutableStateOf<DeviceCodeInfo?>(null) }
    var authPolling by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf("") }
    var skinModelDialogForUuid by remember { mutableStateOf<String?>(null) }

    // 皮肤导入成功提示（右下角弹窗）
    var skinToastData by remember { mutableStateOf<SkinToastInfo?>(null) }

    // 监听皮肤导入事件 → 显示右下角弹窗（5秒后自动消失）
    LaunchedEffect(Unit) {
        AccountRepository.skinImportEvent.collect { event ->
            val modelLabel = if (isEn) event.model else if (event.model == "slim") "纤细(Alex)" else "经典(Steve)"
            skinToastData = SkinToastInfo(event.username, modelLabel)
            delay(5000)
            skinToastData = null
        }
    }

    // 致命错误弹窗
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorStackTrace by remember { mutableStateOf("") }
    var errorLogPath by remember { mutableStateOf("") }

    // 第三方登录状态
    var showThirdPartyDialog by remember { mutableStateOf(false) }
    var tpAuthServerUrl by remember { mutableStateOf("") }
    var tpServerName by remember { mutableStateOf("") }
    var tpEmail by remember { mutableStateOf("") }
    var tpPassword by remember { mutableStateOf("") }
    var tpError by remember { mutableStateOf("") }
    var tpLoading by remember { mutableStateOf(false) }
    
    // 自动更新状态
    val updateState by AutoUpdater.state.collectAsState()

    // Authlib-Injector / Littleskin 拖拽配置解析
    val tpDropTarget = remember {
        object : java.awt.dnd.DropTarget() {
            @Synchronized
            override fun drop(evt: java.awt.dnd.DropTargetDropEvent) {
                try {
                    evt.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                    val droppedFiles = evt.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as? List<*>
                    val file = droppedFiles?.firstOrNull() as? File
                    if (file != null && file.isFile && file.extension.lowercase() == "json") {
                        val text = file.readText(Charsets.UTF_8)
                        val jsonRoot = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
                        val authServerUrl = jsonRoot["authServerUrl"]?.jsonPrimitive?.contentOrNull
                            ?: jsonRoot["authUrl"]?.jsonPrimitive?.contentOrNull
                        val serverName = jsonRoot["serverName"]?.jsonPrimitive?.contentOrNull
                            ?: jsonRoot["name"]?.jsonPrimitive?.contentOrNull
                        
                        if (authServerUrl != null) {
                            tpAuthServerUrl = authServerUrl
                            if (serverName != null) {
                                tpServerName = serverName
                            }
                            showThirdPartyDialog = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // 版本树 Popup（悬浮选择器，绝不破坏底部布局流）
    var showVersionPopup by remember { mutableStateOf(false) }
    val versionPopupVisible = remember { MutableTransitionState(false) }
    LaunchedEffect(showVersionPopup) {
        versionPopupVisible.targetState = showVersionPopup
    }

    suspend fun rescanLocalVersions(loadedSettings: AppSettings, forceReselect: Boolean) {
        val scannedVersions = VersionScanner.scan(loadedSettings.minecraftDir)
        val scannedBedrock = VersionScanner.scanBedrock(loadedSettings.minecraftDir)
        versions = scannedVersions
        bedrockVersions = scannedBedrock
        val allScanned = scannedVersions + scannedBedrock
        if (forceReselect || selectedVersion == null || allScanned.none { it.id == selectedVersion?.id && it.type == selectedVersion?.type }) {
            selectedVersion = allScanned.firstOrNull {
                it.id == loadedSettings.lastVersionId &&
                    (loadedSettings.lastVersionType.isBlank() || it.type == loadedSettings.lastVersionType)
            } ?: scannedVersions.firstOrNull() ?: scannedBedrock.firstOrNull()
        }
    }

    LaunchedEffect(Unit) {
        if (LaunchScreenState.initialized) return@LaunchedEffect
        LaunchScreenState.initialized = true
        val loadedSettings = withContext(Dispatchers.IO) { AppSettings.load() }
        settings = loadedSettings
        nameInput = loadedSettings.playerName
        AccountRepository.loadFromDisk()
        PlayerStats.load()
        rescanLocalVersions(loadedSettings, forceReselect = true)
    }

    LaunchedEffect(selectedVersion, activeAccount) {
        val ver = selectedVersion ?: return@LaunchedEffect
        if (ver.type != "bedrock" || ver.versionDir.isBlank()) return@LaunchedEffect
        val currentSettings = withContext(Dispatchers.IO) { AppSettings.load() }
        if (!currentSettings.bedrockPreheatEnabled) return@LaunchedEffect
        val isMsa = activeAccount?.type == AccountType.MSA
        val versionDir = java.io.File(ver.versionDir)
        val isGdk = versionDir.name.let { id ->
            val parts = id.trim().split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size < 4) false
            else {
                val threshold = listOf(1, 21, 120, 21)
                var isAbove = false
                for (i in 0 until 4) {
                    val a = parts.getOrElse(i) { 0 }
                    val b = threshold.getOrElse(i) { 0 }
                    if (a != b) { isAbove = a > b; break }
                    if (i == 3) isAbove = true
                }
                isAbove
            }
        }
        if (isGdk && isMsa) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            BedrockLaunchEngine.preheat(versionDir, currentSettings.minecraftDir, ver.id)
        }
    }

    LaunchedEffect(bedrockDownloading, bedrockDownloadResults) {
        if (bedrockDownloading.isNotEmpty() || bedrockDownloadResults.isEmpty()) return@LaunchedEffect
        val loadedSettings = withContext(Dispatchers.IO) { AppSettings.load() }
        settings = loadedSettings
        rescanLocalVersions(loadedSettings, forceReselect = false)
    }

    LaunchedEffect(crashReport) {
        val report = crashReport ?: return@LaunchedEffect
        errorLogPath = report.logPath
        errorStackTrace = buildString {
            appendLine("游戏异常退出 (exit ${report.exitCode}) · ${report.elapsedSec}s")
            appendLine("版本: ${report.versionId}")
            appendLine("崩溃日志: ${report.logPath}")
            appendLine()
            appendLine("── 最后输出 ──")
            appendLine(report.tail)
        }
        showErrorDialog = true
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 主布局：Row 左 45% 右 55%
    // ══════════════════════════════════════════════════════════════════════════

    // MD3 缓动曲线（定义在 Row 外部顶层作用域，供底部 Column 和版本 Popup 共用）
    val md3EmphasizedDecelerate = remember { CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f) }
    val md3StandardDecelerate = remember { CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f) }
    val md3StandardAccelerate = remember { CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f) }

    Box(modifier = Modifier.fillMaxSize()) {
    Row(modifier = Modifier.fillMaxSize()) {

        // ── 左区：玩家仪表盘 ────────────────────────────────────────
        val stats by PlayerStats.data.collectAsState()
        // 使用 derivedStateOf 缓存统计数据计算，避免每次重组都重新计算
        val statsData by remember {
            derivedStateOf {
                val totalLaunches = stats.javaLaunchCount + stats.bedrockLaunchCount
                val totalSec = stats.javaPlayTimeSec + stats.bedrockPlayTimeSec
                val javaFrac = if (totalLaunches > 0) stats.javaLaunchCount.toFloat() / totalLaunches else 0f
                val bedrockFrac = 1f - javaFrac
                StatsData(totalLaunches, totalSec, javaFrac, bedrockFrac)
            }
        }
        val totalLaunches = statsData.totalLaunches
        val totalSec = statsData.totalSec
        val javaFrac = statsData.javaFrac
        val bedrockFrac = statsData.bedrockFrac

        // 格式化游玩时长
        fun fmtTime(sec: Long): String = when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}m"
            else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
        }

        val primaryColor = MaterialTheme.colorScheme.primary
        val tertiaryColor = MaterialTheme.colorScheme.tertiary
        val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        val onSurface = MaterialTheme.colorScheme.onSurface
        val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

        val statsScrollState = rememberScrollState()
        // 监听统计面板滚动位置，更新底栏淡出隐藏状态
        LaunchedEffect(statsScrollState) {
            snapshotFlow { statsScrollState.value to statsScrollState.maxValue }
                .collect { (value, max) ->
                    NavBarScrollState.scrollFraction.value = if (max > 0) value.toFloat() / max.toFloat() else 0f
                }
        }
        Column(
            modifier = Modifier
                .weight(0.618f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f))
                .verticalScroll(statsScrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── 标题 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.BarChart, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(if (isEn) "Game Stats" else "游戏数据", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text(if (isEn) "Local cumulative data" else "本机累计统计", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── 圆环图：启动次数分布 ──
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Canvas 圆环
                    Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = 10.dp.toPx()
                            val inset = stroke / 2f
                            val sweepJ = if (totalLaunches == 0) 0f else (javaFrac * 360f).coerceIn(1f, 359f)
                            val sweepB = if (totalLaunches == 0) 0f else ((bedrockFrac * 360f).coerceIn(1f, 359f))
                            // track
                            drawArc(trackColor, 0f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke), topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke))
                            if (totalLaunches == 0) {
                                drawArc(trackColor, -90f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke), topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke))
                            } else {
                                drawArc(primaryColor, -90f, sweepJ, false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round), topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke))
                                drawArc(tertiaryColor, -90f + sweepJ + 2f, sweepB - 2f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round), topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke))
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$totalLaunches", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = onSurface)
                            Text(if (isEn) "times" else "次", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
                        }
                    }
                    // 图例 + 数值
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        Text(if (isEn) "Total Launches" else "总启动次数", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = onSurface)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(primaryColor))
                            Text(if (isEn) "Java" else "Java 版", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text("${stats.javaLaunchCount}", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = primaryColor)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(tertiaryColor))
                            Text(if (isEn) "Bedrock" else "基岩版", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text("${stats.bedrockLaunchCount}", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = tertiaryColor)
                        }
                    }
                }
            }

            // ── 游玩时长卡片 ──
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Timer, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (isEn) "Total Play Time" else "累计游玩时长", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                        Text(fmtTime(totalSec), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Java  ${fmtTime(stats.javaPlayTimeSec)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                        Text("${if (isEn) "Bedrock" else "基岩"}  ${fmtTime(stats.bedrockPlayTimeSec)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                    }
                }
            }

            // ── 小数据卡片 2×2 ──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 崩溃次数
                Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.BugReport, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                        Text("${stats.totalCrashCount}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(if (isEn) "Crashes" else "次崩溃", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f))
                    }
                }
                // 最长单次
                Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.EmojiEvents, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.height(4.dp))
                        Text(fmtTime(stats.longestSessionSec), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(if (isEn) "Best Session" else "最长单次", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 已安装版本
                val totalInstalled = versions.size + bedrockVersions.size
                Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.Inventory2, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.height(4.dp))
                        Text("$totalInstalled", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(if (isEn) "Installed" else "已安装版本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f))
                    }
                }
                // Mod版本数
                val modCount = versions.count { it.loaderType != LoaderType.Vanilla }
                Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.Extension, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("$modCount", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                        Text(if (isEn) "Mod Versions" else "Mod 版本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── 上次游玩 ──
            if (stats.lastPlayedVersion.isNotBlank()) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.History, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (isEn) "Last Played" else "上次游玩", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stats.lastPlayedVersion, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (stats.lastPlayedMs > 0L) {
                            val daysAgo = ((System.currentTimeMillis() - stats.lastPlayedMs) / 86400000L).toInt()
                            Text(if (isEn) (if (daysAgo == 0) "Today" else "${daysAgo}d ago") else (if (daysAgo == 0) "今天" else "${daysAgo}天前"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ── 运行状态条 ──
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (gameRunning) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(if (gameRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant))
                    Text(
                        if (gameRunning) (if (isEn) "In Game  ${selectedVersion?.id ?: ""}" else "游戏运行中  ${selectedVersion?.id ?: ""}")
                        else if (globalLaunching) globalStatusMsg.ifBlank { if (isEn) "Launching…" else "正在启动…" }
                        else if (isEn) "Not running" else "未运行",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (gameRunning) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // ── 右区 (Control Node) ─── Column 堆叠，杜绝错位 ──────────
        Column(
            modifier = Modifier
                .weight(0.382f)
                .fillMaxHeight()
                .padding(top = 8.dp),
        ) {
            // ════════════════════════════════════════════════════════════════
            //  顶部区域 —— 账号切换 + 按钮 + 进度条 + 离线名编辑
            // ════════════════════════════════════════════════════════════════
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            ) {
                // ── Windows 风格账号卡片 ──────────────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val acct = activeAccount
                    val acctIdx = accountList.indexOfFirst { it.uuid == acct?.uuid }.coerceAtLeast(0)
                    val hasMultiple = accountList.size > 1

                    Spacer(Modifier.height(8.dp))

                    // 头像（圆角方）
                    Box(modifier = Modifier.size(96.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        if (acct != null && acct.avatarUri.isNotBlank()) {
                            if (acct.avatarUri.startsWith("http")) {
                                // 网络头像（crafatar 等）
                                KamelImage(
                                    resource = asyncPainterResource(data = acct.avatarUri),
                                    contentDescription = "头像",
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)),
                                    contentScale = ContentScale.Crop,
                                    onLoading = { AvatarPlaceholder(acct.username, 96) },
                                    onFailure = { AvatarPlaceholder(acct.username, 96) },
                                )
                            } else {
                                // 本地文件头像
                                val avatarFile = File(acct.avatarUri)
                                val avatarBitmap = remember(acct.avatarUri, acct.uuid, avatarFile.lastModified()) {
                                    try {
                                        val bytes = avatarFile.readBytes()
                                        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                                    } catch (_: Exception) { null }
                                }
                                val bmp = avatarBitmap
                                if (bmp != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bmp,
                                        contentDescription = "头像",
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    AvatarPlaceholder(acct.username, 96)
                                }
                            }
                        } else {
                            AvatarPlaceholder(acct?.username ?: "?", 96)
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // 玩家名 + 左右切换箭头
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (hasMultiple) {
                            IconButton(
                                onClick = {
                                    if (!uiLocked) {
                                        val prevIdx = if (acctIdx > 0) acctIdx - 1 else accountList.lastIndex
                                        scope.launch { AccountRepository.switchAccount(accountList[prevIdx].uuid) }
                                    }
                                },
                                modifier = Modifier.size(36.dp),
                                enabled = !uiLocked,
                            ) {
                                Icon(Icons.Filled.ChevronLeft, contentDescription = "上一个账号", modifier = Modifier.size(24.dp))
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                acct?.username ?: (if (isEn) "Not logged in" else "未登录"),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                acct?.displayType ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (hasMultiple) {
                            IconButton(
                                onClick = {
                                    if (!uiLocked) {
                                        val nextIdx = if (acctIdx < accountList.lastIndex) acctIdx + 1 else 0
                                        scope.launch { AccountRepository.switchAccount(accountList[nextIdx].uuid) }
                                    }
                                },
                                modifier = Modifier.size(36.dp),
                                enabled = !uiLocked,
                            ) {
                                Icon(Icons.Filled.ChevronRight, contentDescription = "下一个账号", modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // 操作按钮行
                    Row(horizontalArrangement = Arrangement.Center) {
                        FilledTonalButton(
                            onClick = { showLoginDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            enabled = !uiLocked,
                        ) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isEn) (if (accountList.isEmpty()) "Login" else "Add") else (if (accountList.isEmpty()) "登录" else "添加"), style = MaterialTheme.typography.labelSmall)
                        }
                        if (acct != null) {
                            // 离线账号：头像 + 换皮肤；第三方：头像 + 刷新皮肤；正版：刷新皮肤
                            if (acct.type == AccountType.Offline || acct.type == AccountType.ThirdParty) {
                                Spacer(Modifier.width(6.dp))
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch { AccountRepository.pickOfflineAvatar(acct.uuid) }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isEn) "Avatar" else "头像", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (acct.type == AccountType.Offline) {
                                Spacer(Modifier.width(6.dp))
                                FilledTonalButton(
                                    onClick = { skinModelDialogForUuid = acct.uuid },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Icon(Icons.Filled.Checkroom, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isEn) "Skin" else "皮肤", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (acct.type == AccountType.MSA || acct.type == AccountType.ThirdParty) {
                                Spacer(Modifier.width(6.dp))
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                AccountRepository.refreshSkin(acct.uuid)
                                            } catch (_: Exception) {}
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isEn) "Refresh" else "刷新", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            FilledTonalButton(
                                onClick = { scope.launch { AccountRepository.removeAccount(acct.uuid) } },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            ) {
                                Icon(Icons.Filled.PersonRemove, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                when (val rs = refreshState) {
                    is RefreshState.Refreshing -> {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(6.dp))
                            Text(if (isEn) "Refreshing token for ${rs.username}…" else "正在静默刷新 ${rs.username} 的 Token…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    is RefreshState.Failed -> {
                        Spacer(Modifier.height(4.dp))
                        Text(rs.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    else -> {}
                }

                Spacer(Modifier.height(8.dp))

                if (downloadProgress.isRunning) {
                    ElevatedCard(
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.width(8.dp))
                                Text(downloadProgress.currentFile, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                Text(downloadProgress.speedMbps, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress.fraction },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (editingOfflineName) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = { input ->
                                    nameInput = input.filter { it.isLetterOrDigit() || it == '_' }
                                    nameError = when {
                                        nameInput.isBlank() -> "不能为空"
                                        nameInput.length < 3 -> "至少3字符"
                                        nameInput.length > 16 -> "最多16字符"
                                        !nameInput.matches(Regex("^[a-zA-Z0-9_]+$")) -> "仅字母数字下划线"
                                        else -> ""
                                    }
                                },
                                label = { Text("玩家 ID") },
                                isError = nameError.isNotBlank(),
                                supportingText = { Text(nameError.ifBlank { "3-16位 字母/数字/下划线" }) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { editingOfflineName = false }) { Text("取消") }
                                Spacer(Modifier.width(4.dp))
                                Button(
                                    onClick = {
                                        if (nameError.isBlank() && nameInput.isNotBlank()) {
                                            scope.launch {
                                                AccountRepository.addOfflineAccount(nameInput)
                                                settings = settings.copy(
                                                    playerName = nameInput,
                                                    loginMode = "offline",
                                                    accessToken = "",
                                                )
                                                AppSettings.save(settings)
                                            }
                                            editingOfflineName = false
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    enabled = nameError.isBlank() && nameInput.isNotBlank(),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                ) { Text("确认") }
                            }
                        }
                    }
                }
            }

            // ════════════════════════════════════════════════════════════════
            //  中间区域 —— 启动圆环 / 面部皮肤预览（条件切换）
            //  launchProgress > 0 时显示居中的加载圆环 + 下方文字，
            //  否则显示紧凑型面部皮肤预览（已导入/默认 Steve）
            // ════════════════════════════════════════════════════════════════

            // 将 displayMsg / launchProgress 定义提前，供中间区域使用
            val displayMsg by remember {
                derivedStateOf {
                    when {
                        globalLaunching -> globalStatusMsg
                        gameRunning -> processMsg
                        processMsg.isNotBlank() && ("异常" in processMsg || "exit" in processMsg) ->
                            processMsg.lineSequence().filter { it.isNotBlank() }.take(2).joinToString("\n")
                        else -> launchMessage
                    }
                }
            }
            val launchProgress by remember {
                derivedStateOf {
                    when {
                        globalLaunching -> globalProgress
                        gameRunning && gameProgress > 0 -> gameProgress
                        else -> 0
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .wrapContentHeight(align = Alignment.CenterVertically),
            ) {
                if (launchProgress > 0) {
                    // ── 启动中：居中圆环 + 下方启动文字 ──
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val animatedProgress by animateFloatAsState(
                            targetValue = launchProgress / 100f,
                            animationSpec = tween(500, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)),
                            label = "launch_progress",
                        )

                        val progressRotation = remember { Animatable(0f) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                progressRotation.animateTo(
                                    targetValue = 360f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(2000, easing = LinearEasing),
                                        repeatMode = RepeatMode.Restart,
                                    ),
                                )
                            }
                        }

                        val primaryColor = MaterialTheme.colorScheme.primary

                        Box(contentAlignment = Alignment.Center) {
                            Canvas(modifier = Modifier.size(72.dp)) {
                                val strokeWidth = 4f
                                val sweep = animatedProgress * 360f

                                drawArc(
                                    color = primaryColor.copy(alpha = 0.12f),
                                    startAngle = 0f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                                )
                                drawArc(
                                    color = primaryColor,
                                    startAngle = progressRotation.value - 90f,
                                    sweepAngle = sweep.coerceAtLeast(5f),
                                    useCenter = false,
                                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            "启动进度 $launchProgress%",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = primaryColor,
                        )

                        if (displayMsg.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                displayMsg,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    // ── 未启动：紧凑型皮肤预览 ──
                    val acct = activeAccount
                    val isOffline = acct?.type == AccountType.Offline
                    val isMsaOrThirdParty = acct?.type == AccountType.MSA || acct?.type == AccountType.ThirdParty
                    // 离线账户取本地路径，正版/第三方取URL（可能是 https://textures.minecraft.net/...）
                    val skinUri = acct?.skinUri.orEmpty()
                    val skinModelStr = if (isOffline) acct?.skinModel.orEmpty() else "classic"
                    val offlineUuid = acct?.uuid.orEmpty()
                    val hasSkinFile = isOffline && skinUri.isNotBlank() && File(skinUri).exists()
                    val hasSkinUrl = isMsaOrThirdParty && skinUri.startsWith("http")

                    val faceBmp = remember(skinUri, offlineUuid) {
                        if (hasSkinFile) loadSkinFaceBitmap(skinUri, 64) else null
                    }

                    // 对正版/第三方账户：从URL下载皮肤图，提取面部像素，缓存到内存
                    var networkFaceBmp by remember(skinUri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                    LaunchedEffect(skinUri) {
                        if (!hasSkinUrl) return@LaunchedEffect
                        networkFaceBmp = null
                        val bmp = withContext(Dispatchers.IO) {
                            runCatching {
                                val tmpFile = java.io.File.createTempFile("md3l_net_skin", ".png").apply { deleteOnExit() }
                                val httpClient = HttpClient(CIO) { engine { requestTimeout = 15_000 } }
                                val bytes = httpClient.get(skinUri).readBytes()
                                httpClient.close()
                                tmpFile.writeBytes(bytes)
                                loadSkinFaceBitmap(tmpFile.absolutePath, 64)
                            }.getOrNull()
                        }
                        networkFaceBmp = bmp
                    }

                    val steveFaceBmp = remember {
                        runCatching {
                            val stream = javaClass.getResourceAsStream("/icons/steve.png") ?: return@runCatching null
                            val tmpFile = java.io.File.createTempFile("md3l_steve_face", ".png").apply { deleteOnExit() }
                            stream.use { input -> tmpFile.outputStream().use { output -> input.copyTo(output) } }
                            loadSkinFaceBitmap(tmpFile.absolutePath, 64)
                        }.getOrNull()
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.58f),
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 88.dp)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(16.dp),
                            ),
                    ) {
                        if (hasSkinFile && faceBmp != null) {
                            // ── 离线账户：本地皮肤文件预览 ──
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                            ) {
                                androidx.compose.foundation.Image(
                                    bitmap = faceBmp,
                                    contentDescription = "皮肤面部预览",
                                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (isEn) "Skin Preview" else "皮肤预览",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        if (skinModelStr == "slim") "Slim (Alex)" else "Classic (Steve)",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                FilledTonalIconButton(
                                    onClick = { skinModelDialogForUuid = offlineUuid },
                                    modifier = Modifier.size(40.dp),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Icon(Icons.Filled.Checkroom, contentDescription = "更换皮肤", modifier = Modifier.size(20.dp))
                                }
                            }
                        } else if (hasSkinUrl) {
                            // ── 正版/第三方账户：网络皮肤预览 ──
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                            ) {
                                if (networkFaceBmp != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = networkFaceBmp!!,
                                        contentDescription = "皮肤面部预览",
                                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    // 加载中：使用头像 crafatar 预览
                                    val avatarUrl = acct?.avatarUri.orEmpty()
                                    if (avatarUrl.startsWith("http")) {
                                        KamelImage(
                                            resource = asyncPainterResource(data = avatarUrl),
                                            contentDescription = "头像预览",
                                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
                                            contentScale = ContentScale.Crop,
                                            onLoading = {
                                                Box(
                                                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                                }
                                            },
                                            onFailure = {
                                                Box(
                                                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text("👤", fontSize = 28.sp)
                                                }
                                            },
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                        }
                                    }
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (isEn) "Account Skin" else "账号皮肤",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        acct?.username.orEmpty().ifBlank { if (isEn) "Loading…" else "加载中…" },
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        acct?.displayType.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        } else if (steveFaceBmp != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                            ) {
                                androidx.compose.foundation.Image(
                                    bitmap = steveFaceBmp,
                                    contentDescription = "Steve 默认皮肤",
                                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (isEn) "Default Skin" else "默认皮肤",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "Classic (Steve)",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                }
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
                            ) {
                                Text("👤", fontSize = 32.sp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (isEn) "Import a skin to preview" else "导入皮肤后显示预览",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            // ════════════════════════════════════════════════════════════════
            //  底部区域 —— 状态消息 + 版本锚点 + 启动按钮
            // ════════════════════════════════════════════════════════════════
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            ) {
                // 底部仅在不启动时显示状态消息，启动时的状态已挪到中间区域
                if (displayMsg.isNotBlank() && launchProgress == 0) {
                    Text(
                        displayMsg,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            "失败" in displayMsg || "错误" in displayMsg || "崩溃" in displayMsg || "异常" in displayMsg -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                val unfoldRotation by animateFloatAsState(
                    targetValue = if (showVersionPopup) 180f else 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "version_popup_arrow_rotation",
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (!uiLocked) Modifier.clickable { showVersionPopup = !showVersionPopup } else Modifier),
                    tonalElevation = 2.dp,
                    shadowElevation = 2.dp,
                    shape = RoundedCornerShape(12.dp),
                    color = if (uiLocked)
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectedVersion != null) {
                            VersionIcon(loaderType = selectedVersion!!.loaderType, versionType = selectedVersion!!.type, size = 28)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    selectedVersion!!.id,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${selectedVersion!!.loaderType.name} · ${selectedVersion!!.type}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Icon(Icons.Filled.Inventory2, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text("选择版本", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        }
                        Icon(
                            Icons.Filled.UnfoldMore,
                            contentDescription = "选择版本",
                            modifier = Modifier.size(20.dp).rotate(unfoldRotation),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (uiLocked) return@Button
                            val ver = selectedVersion ?: return@Button
                            launchMessage = ""

                            if (ver.type == "bedrock") {
                                // ── 基岩版启动（细粒度进度） ──
                                LaunchState.begin("正在初始化基岩版启动流程…")
                                LaunchState.updateProgress(5)
                                scope.launch {
                                    var attached = false
                                    try {
                                        val engine = BedrockLaunchEngine()

                                        // Step 1: 检查运行库
                                        LaunchState.updateProgress(10, "正在检查 VCLibs 运行库…")
                                        withContext(Dispatchers.IO) { engine.checkRuntime() }
                                        LaunchState.updateProgress(20, "运行库检查通过")

                                        // Step 2: 加载设置
                                        LaunchState.updateProgress(25, "正在加载启动配置…")
                                        val s = withContext(Dispatchers.IO) { AppSettings.load() }

                                        val acc = activeAccount
                                        val context = LaunchContext(
                                            version = ver, javaPath = "", memoryMb = 0,
                                            playerName = acc?.username ?: "",
                                            uuid = acc?.uuid ?: "",
                                            accessToken = "",
                                            minecraftDir = s.minecraftDir, customJvmArgs = "",
                                            windowWidth = 0, windowHeight = 0, fullscreen = false,
                                            skinUri = if (acc?.type == AccountType.Offline) acc.skinUri else "",
                                            skinModel = if (acc?.type == AccountType.Offline) acc.skinModel else "classic",
                                            accountType = acc?.type ?: AccountType.Offline,
                                        )

                                        engine.onProgress = { pct, msg -> LaunchState.updateProgress(pct, msg) }
                                        val bedrockLogFile = java.io.File(
                                            launcher.core.LauncherDirs.bedrockLogDir,
                                            "game-${ver.id}-${System.currentTimeMillis()}.log"
                                        ).also { it.parentFile?.mkdirs() }
                                        val process = withContext(Dispatchers.IO) { engine.execute(context) }

                                        LaunchState.updateProgress(95, "基岩版启动成功，正在移交进程监控…")
                                        LaunchState.attachProcess(process, ver.id, bedrockLogFile, GameEdition.Bedrock)
                                        attached = true
                                        launchMessage = "基岩版已启动: ${ver.id}"
                                    } catch (e: Exception) {
                                        val sw = StringWriter()
                                        e.printStackTrace(PrintWriter(sw))
                                        val trace = sw.toString()
                                        errorStackTrace = trace
                                        errorLogPath = writeLaunchFailureLog(ver.id, trace, isBedrock = true)
                                        showErrorDialog = true
                                        launchMessage = "启动失败，详情见错误弹窗/日志"
                                    } finally {
                                        if (!attached) LaunchState.end()
                                    }
                                }
                            } else {
                                // ── Java 版启动 ──
                                LaunchState.begin("正在检测 Java 环境…")
                                LaunchState.updateProgress(15)
                                scope.launch {
                                    try {
                                        val s = withContext(Dispatchers.IO) { AppSettings.load() }
                                        val acc = activeAccount
                                        val javaExe = JavaManager.resolveJavaForVersion(
                                            version = ver,
                                            userJavaPath = s.javaPath,
                                            onProgress = { msg -> LaunchState.updateProgress(35, msg) },
                                        )
                                        val name = acc?.username ?: s.playerName.ifBlank { "Steve" }
                                        val uuid = acc?.uuid ?: s.playerUuid.ifBlank { "00000000-0000-0000-0000-000000000000" }
                                        val token = if (acc?.type == AccountType.MSA) acc.minecraftAccessToken.ifBlank { s.accessToken } else "0"

                                        LaunchState.updateProgress(50, "正在准备启动环境…")

                                        LaunchState.updateProgress(60, "正在构建启动参数…")

                                        val context = LaunchContext(
                                            version = ver,
                                            javaPath = javaExe,
                                            memoryMb = s.memoryMb,
                                            playerName = name,
                                            uuid = uuid,
                                            accessToken = token,
                                            minecraftDir = s.minecraftDir,
                                            customJvmArgs = s.customJvmArgs,
                                            windowWidth = if (s.javaGameWidth > 0) s.javaGameWidth else s.windowWidth,
                                            windowHeight = if (s.javaGameHeight > 0) s.javaGameHeight else s.windowHeight,
                                            fullscreen = s.fullscreen,
                                            skinUri = if (acc?.type == AccountType.Offline) acc.skinUri else "",
                                            skinModel = if (acc?.type == AccountType.Offline) acc.skinModel else "classic",
                                            authServerUrl = acc?.authServerUrl ?: "",
                                            gcPolicy = s.gcPolicy,
                                            jvmMetaspaceSize = s.jvmMetaspaceSize,
                                            jvmReservedCodeCache = s.jvmReservedCodeCache,
                                            jvmG1NewSizePercent = s.jvmG1NewSizePercent,
                                            jvmG1MaxNewSizePercent = s.jvmG1MaxNewSizePercent,
                                            jvmG1HeapRegionSize = s.jvmG1HeapRegionSize,
                                            jvmG1GCPauseTarget = s.jvmG1GCPauseTarget,
                                            jvmZUncommitDelay = s.jvmZUncommitDelay,
                                            jvmConcGCThreads = s.jvmConcGCThreads,
                                            jvmShenandoahMode = s.jvmShenandoahMode,
                                            jvmShenandoahHeapSizePercent = s.jvmShenandoahHeapSizePercent,
                                            jvmParallelGCThreads = s.jvmParallelGCThreads,
                                            jvmUseLargePages = s.jvmUseLargePages,
                                            jvmAlwaysPreTouch = s.jvmAlwaysPreTouch,
                                            jvmDisableExplicitGC = s.jvmDisableExplicitGC,
                                            jvmParallelRefProcEnabled = s.jvmParallelRefProcEnabled,
                                            jvmStringDedup = s.jvmStringDedup,
                                            jvmThreadStackSize = s.jvmThreadStackSize,
                                            jvmTieredCompilation = s.jvmTieredCompilation,
                                            jvmInlineSize = s.jvmInlineSize,
                                            jvmFreqInlineSize = s.jvmFreqInlineSize,
                                            jvmLoopUnrollingLimit = s.jvmLoopUnrollingLimit,
                                            jvmEnableIEEE = s.jvmEnableIEEE,
                                            jvmNativeMemoryTracking = s.jvmNativeMemoryTracking,
                                            launchDemoMode = s.launchDemoMode,
                                            javaUseNativeGlfw = s.javaUseNativeGlfw,
                                            javaUseNativeOpenAl = s.javaUseNativeOpenAl,
                                            javaExtraGameArgs = s.javaExtraGameArgs,
                                            javaQuickPlaySingleplayer = s.javaQuickPlaySingleplayer,
                                            javaQuickPlayMultiplayer = s.javaQuickPlayMultiplayer,
                                        )

                                        val engine = JavaLaunchEngine()
                                        LaunchState.updateProgress(80, "正在启动游戏进程…")
                                        val process = withContext(Dispatchers.IO) { engine.execute(context) }
                                        LaunchState.attachProcess(process, ver.id, engine.lastLogFile, onExit = {
                                            engine.stopSkinServer()
                                        })
                                        launchMessage = if (isEn) "Game launched: ${ver.id}" else "游戏已启动: ${ver.id}"
                                    } catch (e: Exception) {
                                        val sw = StringWriter()
                                        e.printStackTrace(PrintWriter(sw))
                                        val trace = sw.toString()
                                        errorStackTrace = trace
                                        errorLogPath = writeLaunchFailureLog(ver.id, trace)
                                        showErrorDialog = true
                                        launchMessage = if (isEn) "Launch failed, see error dialog" else "启动失败，详情见错误弹窗/日志"
                                    } finally {
                                        LaunchState.end()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        enabled = selectedVersion != null && !uiLocked,
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = if (uiLocked) 0.dp else 3.dp),
                        colors = if (gameRunning) {
                            ButtonDefaults.buttonColors(
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
                            ButtonDefaults.buttonColors(
                                containerColor = if (darkTheme) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary,
                                contentColor = if (darkTheme) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary,
                            )
                        },
                    ) {
                        when {
                            globalLaunching -> {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                                Spacer(Modifier.width(8.dp))
                                Text(if (isEn) "Launching…" else "启动中…")
                            }
                            gameRunning -> {
                                Icon(Icons.Filled.SportsEsports, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isEn) "In Game…" else "游戏运行中…",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                            else -> {
                                Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isEn) "Launch" else "启动游戏",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                        }
                    }

                    if (uiLocked) {
                        FilledTonalButton(
                            onClick = {
                                GameProcessManager.forceKill()
                                LaunchState.end()
                                launchMessage = if (isEn) "Game force-killed" else "已强制结束游戏"
                            },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isEn) "Force Stop" else "强制结束", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }

            }
        }
    } // ← Row 结束

    } // ← Box 结束

    // ══════════════════════════════════════════════════════════════════════════
    // 版本选择 Popup —— 悬浮于界面上方，绝对禁止破坏底部布局流
    // ══════════════════════════════════════════════════════════════════════════
    if (versionPopupVisible.currentState || versionPopupVisible.targetState) {
        Popup(
            alignment = Alignment.BottomCenter,
            offset = androidx.compose.ui.unit.IntOffset(0, -120),
            onDismissRequest = { showVersionPopup = false },
            properties = PopupProperties(focusable = true),
        ) {
            AnimatedVisibility(
                visibleState = versionPopupVisible,
                enter = fadeIn(tween(300, easing = md3StandardDecelerate)) +
                    scaleIn(
                        initialScale = 0.94f,
                        animationSpec = tween(350, easing = md3EmphasizedDecelerate)
                    ),
                exit = fadeOut(tween(150, easing = md3StandardAccelerate)) +
                    scaleOut(
                        targetScale = 0.97f,
                        animationSpec = tween(150, easing = md3StandardAccelerate)
                    ),
                modifier = Modifier
                    .width(420.dp)
                    .fillMaxHeight(0.8f)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 8.dp,
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.widthIn(min = 300.dp, max = 500.dp).heightIn(max = 400.dp),
                ) {
                    LocalVersionTreeSheetContent(
                        localVersions = versions,
                        onVersionSelected = { ver ->
                            showVersionPopup = false
                            selectedVersion = ver
                            scope.launch {
                                val s = AppSettings.load()
                                val updated = s.copy(lastVersionId = ver.id, lastVersionType = ver.type)
                                AppSettings.save(updated)
                                settings = updated
                            }
                        },
                        bedrockVersions = bedrockVersions,
                    )
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 登录方式选择弹窗
    // ══════════════════════════════════════════════════════════════════════════
    if (showLoginDialog) {
        AlertDialog(
            onDismissRequest = { showLoginDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text(if (isEn) "Select Login Method" else "选择登录方式", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ElevatedCard(
                        onClick = {
                            showLoginDialog = false
                            showMsAuthDialog = true
                            authError = ""
                            scope.launch {
                                try {
                                    deviceCodeInfo = AuthManager.requestDeviceCode()
                                } catch (e: Exception) {
                                    authError = if (isEn) "Failed to get device code: ${e.message}" else "获取设备码失败: ${e.message}"
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (isEn) "Microsoft Login" else "微软正版登录", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                Text(if (isEn) "Device Code Flow · Requires Microsoft account" else "Device Code Flow · 需要微软账号", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    ElevatedCard(
                        onClick = {
                            showLoginDialog = false
                            editingOfflineName = true
                            nameInput = activeAccount?.username ?: settings.playerName
                            nameError = ""
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.WifiOff, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (isEn) "Offline Mode" else "离线模式", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                Text(if (isEn) "Enter a custom player name" else "输入自定义玩家 ID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    ElevatedCard(
                        onClick = {
                            showLoginDialog = false
                            showThirdPartyDialog = true
                            tpAuthServerUrl = ""
                            tpServerName = ""
                            tpEmail = ""
                            tpPassword = ""
                            tpError = ""
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.AccountCircle, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (isEn) "Third-party Login" else "第三方登录", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                Text(if (isEn) "LittleSkin or other Yggdrasil API" else "LittleSkin 或其他 Yggdrasil API", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLoginDialog = false }) { Text(if (isEn) "Cancel" else "取消") }
            },
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 微软 OAuth Device Code 验证弹窗
    // ══════════════════════════════════════════════════════════════════════════
    if (showMsAuthDialog) {
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = {
                if (!authPolling) {
                    showMsAuthDialog = false
                    deviceCodeInfo = null
                }
            },
            shape = RoundedCornerShape(20.dp),
            title = { Text(if (isEn) "Microsoft Account Verification" else "微软账号验证", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (deviceCodeInfo != null) {
                        Text(if (isEn) "Enter the following code in your browser:" else "请在浏览器中输入以下验证码：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                deviceCodeInfo!!.userCode,
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 4.sp,
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(20.dp),
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(deviceCodeInfo!!.userCode))
                                AuthManager.openBrowser(deviceCodeInfo!!.verificationUri)
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isEn) "Copy & Open Browser" else "复制并打开浏览器", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(12.dp))
                        if (authPolling) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(if (isEn) "Waiting for verification…" else "等待验证中…", style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (authError.isNotBlank()) {
                            Text(authError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        } else {
                            Button(
                                onClick = {
                                    authPolling = true
                                    authError = ""
                                    scope.launch {
                                        try {
                                            val profile = AuthManager.fullLogin(deviceCodeInfo!!.deviceCode, deviceCodeInfo!!.interval)
                                            // 通过 AccountRepository 持久化 MSA 账号
                                            AccountRepository.addMsaAccount(
                                                msAccessToken = profile.msAccessToken,
                                                refreshToken = profile.refreshToken,
                                                expiresInSeconds = profile.expiresIn,
                                            )
                                            settings = settings.copy(
                                                playerName = profile.name,
                                                playerUuid = profile.uuid,
                                                accessToken = profile.accessToken,
                                                skinUrl = profile.skinUrl,
                                                loginMode = "microsoft",
                                            )
                                            AppSettings.save(settings)
                                            showMsAuthDialog = false
                                            deviceCodeInfo = null
                                        } catch (e: Exception) {
                                            authError = if (isEn) "Verification failed: ${e.message}" else "验证失败: ${e.message}"
                                        } finally {
                                            authPolling = false
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(if (isEn) "Verified in browser, start login" else "已在浏览器中验证，开始登录")
                            }
                        }
                    } else if (authError.isNotBlank()) {
                        Text(authError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(if (isEn) "Fetching verification code…" else "获取验证码中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showMsAuthDialog = false
                    deviceCodeInfo = null
                    authPolling = false
                }) { Text(if (isEn) "Close" else "关闭") }
            },
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 第三方登录 (Authlib Injector / LittleSkin) 验证弹窗
    // ══════════════════════════════════════════════════════════════════════════
    if (showThirdPartyDialog) {
        AlertDialog(
            onDismissRequest = { if (!tpLoading) showThirdPartyDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text(if (isEn) "Add Third-party Account" else "添加第三方账号", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isEn) "Provide a Yggdrasil-compatible auth server URL. Drop an authlib-injector .json file to auto-fill." else "请提供支持 Yggdrasil API 的认证服务器信息。支持拖入 authlib-injector 格式的 json 配置文件自动解析。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    OutlinedTextField(
                        value = tpAuthServerUrl,
                        onValueChange = { tpAuthServerUrl = it; tpError = "" },
                        label = { Text(if (isEn) "Auth Server (required)" else "认证服务器 (必填)") },
                        placeholder = { Text(if (isEn) "e.g. https://littleskin.cn/api/yggdrasil" else "如 https://littleskin.cn/api/yggdrasil") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tpServerName,
                        onValueChange = { tpServerName = it },
                        label = { Text(if (isEn) "Server name (optional)" else "服务器名称 (选填)") },
                        placeholder = { Text(if (isEn) "e.g. LittleSkin" else "如 LittleSkin") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            tpAuthServerUrl = "https://littleskin.cn/api/yggdrasil"
                            if (tpServerName.isBlank()) tpServerName = "LittleSkin"
                            tpError = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isEn) "Quick-fill LittleSkin" else "快捷设置为 LittleSkin")
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tpEmail,
                        onValueChange = { tpEmail = it; tpError = "" },
                        label = { Text(if (isEn) "Email / Username" else "邮箱 / 账号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tpPassword,
                        onValueChange = { tpPassword = it; tpError = "" },
                        label = { Text(if (isEn) "Password" else "密码") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (tpLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isEn) "Authenticating…" else "正在认证中…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (tpError.isNotBlank()) {
                        Text(tpError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tpAuthServerUrl.isBlank() || tpEmail.isBlank() || tpPassword.isBlank()) {
                            tpError = if (isEn) "Please fill in server URL, email, and password" else "请填写服务器地址、邮箱和密码"
                            return@Button
                        }
                        tpLoading = true
                        tpError = ""
                        scope.launch {
                            try {
                                val session = AccountRepository.addThirdPartyAccount(
                                    authServerUrl = tpAuthServerUrl,
                                    serverName = tpServerName,
                                    email = tpEmail,
                                    password = tpPassword
                                )
                                settings = settings.copy(
                                    playerName = session.username,
                                    playerUuid = session.uuid,
                                    accessToken = session.accessToken,
                                    loginMode = "thirdparty"
                                )
                                AppSettings.save(settings)
                                showThirdPartyDialog = false
                            } catch (e: Exception) {
                                tpError = e.message ?: (if (isEn) "Login failed" else "登录失败")
                            } finally {
                                tpLoading = false
                            }
                        }
                    },
                    enabled = !tpLoading,
                    shape = RoundedCornerShape(10.dp)
                ) { Text(if (isEn) "Login" else "完成登录") }
            },
            dismissButton = {
                TextButton(onClick = { if (!tpLoading) showThirdPartyDialog = false }) { Text(if (isEn) "Cancel" else "取消") }
            }
        )
    }

    skinModelDialogForUuid?.let { uuid ->
        AlertDialog(
            onDismissRequest = { skinModelDialogForUuid = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text(if (isEn) "Select Skin Model" else "选择皮肤模型", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isEn) "Select the model type for this skin before importing." else "导入前请选择这张皮肤使用的材质模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ElevatedCard(
                        onClick = {
                            skinModelDialogForUuid = null
                            scope.launch {
                                AccountRepository.pickOfflineSkin(uuid, "classic")
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Classic (Steve)", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                Text(if (isEn) "Classic model, 4-pixel arm width" else "经典模型，手臂宽度 4 像素", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    ElevatedCard(
                        onClick = {
                            skinModelDialogForUuid = null
                            scope.launch {
                                AccountRepository.pickOfflineSkin(uuid, "slim")
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AccessibilityNew, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Slim (Alex)", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                Text(if (isEn) "Slim model, 3-pixel arm width" else "纤细模型，手臂宽度 3 像素", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { skinModelDialogForUuid = null }) { Text(if (isEn) "Cancel" else "取消") }
            },
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 自动更新弹窗
    // ══════════════════════════════════════════════════════════════════════════
    if (updateState.hasUpdate && updateState.releaseInfo != null) {
        AlertDialog(
            onDismissRequest = {
                if (!updateState.isDownloading) {
                    AutoUpdater.dismissUpdate()
                }
            },
            shape = RoundedCornerShape(20.dp),
            title = { Text(if (isEn) "New version: ${updateState.releaseInfo!!.tag_name}" else "发现新版本: ${updateState.releaseInfo!!.tag_name}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isEn) "Changelog:" else "更新内容:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(updateState.releaseInfo!!.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    
                    if (updateState.isDownloading) {
                        val totalMb = if (updateState.totalBytes > 0) updateState.totalBytes / 1024f / 1024f else -1f
                        val downloadedMb = updateState.downloadedBytes / 1024f / 1024f
                        val speedMb = updateState.speedBytesPerSec / 1024f / 1024f
                        val progressText = if (updateState.totalBytes > 0) {
                            if (isEn) "Downloading… ${"%.1f".format(downloadedMb)}MB / ${"%.1f".format(totalMb)}MB · ${"%.2f".format(speedMb)}MB/s · ${(updateState.downloadProgress * 100).toInt()}%"
                            else "正在下载... ${"%.1f".format(downloadedMb)}MB / ${"%.1f".format(totalMb)}MB · ${"%.2f".format(speedMb)}MB/s · ${(updateState.downloadProgress * 100).toInt()}%"
                        } else {
                            if (isEn) "Downloading… ${"%.1f".format(downloadedMb)}MB · ${"%.2f".format(speedMb)}MB/s"
                            else "正在下载... ${"%.1f".format(downloadedMb)}MB · ${"%.2f".format(speedMb)}MB/s"
                        }

                        Spacer(Modifier.height(8.dp))
                        if (updateState.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { updateState.downloadProgress },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(progressText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    } else if (updateState.error.isNotBlank()) {
                        Text(if (isEn) "Error: ${updateState.error}" else "错误: ${updateState.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { AutoUpdater.startUpdate() },
                    enabled = !updateState.isDownloading,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (updateState.isDownloading) (if (isEn) "Updating…" else "正在更新...") else (if (isEn) "Update Now" else "立即更新"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { AutoUpdater.dismissUpdate() },
                    enabled = !updateState.isDownloading
                ) {
                    Text(if (isEn) "Later" else "暂不更新")
                }
            }
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 致命错误 StackTrace 弹窗
    // ══════════════════════════════════════════════════════════════════════════
    if (showErrorDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isEn) "Launch Failed · Fatal Error" else "启动失败 · 致命错误", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error)
                }
            },
            text = {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                ) {
                    SelectionContainer {
                        Text(
                            errorStackTrace,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (errorLogPath.isNotBlank()) {
                        try {
                            Desktop.getDesktop().open(File(errorLogPath).parentFile)
                        } catch (_: Exception) { }
                    }
                }, shape = RoundedCornerShape(8.dp), enabled = errorLogPath.isNotBlank()) { Text(if (isEn) "Open Log Folder" else "打开日志目录") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            if (errorLogPath.isNotBlank()) {
                                try {
                                    Desktop.getDesktop().open(File(errorLogPath))
                                } catch (_: Exception) { }
                            }
                        },
                        enabled = errorLogPath.isNotBlank(),
                    ) { Text(if (isEn) "Open Log" else "打开日志") }
                    TextButton(onClick = {
                        showErrorDialog = false
                        GameProcessManager.clearCrashReport()
                    }) { Text(if (isEn) "Close" else "关闭") }
                }
            },
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 皮肤导入成功右下角弹窗（5秒自动消失）
    // ══════════════════════════════════════════════════════════════════════════
    skinToastData?.let { info ->
        val animAlpha by animateFloatAsState(
            targetValue = if (skinToastData != null) 1f else 0f,
            animationSpec = tween(300),
            label = "skin_toast_alpha",
        )
        val density = LocalDensity.current
        Popup(
            alignment = Alignment.BottomEnd,
            offset = with(density) { IntOffset(x = (-20).dp.roundToPx(), y = (-20).dp.roundToPx()) },
            onDismissRequest = { skinToastData = null },
            properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 8.dp,
                tonalElevation = 4.dp,
                modifier = Modifier.alpha(animAlpha),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (isEn) "Skin imported" else "皮肤导入成功",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                        Text(
                            if (isEn) "${info.username} · ${info.modelLabel}"
                            else "${info.username} · ${info.modelLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    TextButton(onClick = { skinToastData = null }) {
                        Text(
                            if (isEn) "OK" else "知道了",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                }
            }
        }
    }
}

/** 皮肤导入成功时右下角弹窗的数据 */
private data class SkinToastInfo(val username: String, val modelLabel: String)

/** 缓存统计数据的数据类，避免在 derivedStateOf 中返回 Pair/Triple */
private data class StatsData(
    val totalLaunches: Int,
    val totalSec: Long,
    val javaFrac: Float,
    val bedrockFrac: Float,
)

private fun writeLaunchFailureLog(versionId: String, trace: String, isBedrock: Boolean = false): String {
    return try {
        val logDir = if (isBedrock) launcher.core.LauncherDirs.bedrockLogDir
            else launcher.core.LauncherDirs.javaLogDir
        val file = File(
            logDir,
            "launch-failure-${versionId.ifBlank { "unknown" }}-${System.currentTimeMillis()}.log",
        )
        file.parentFile?.mkdirs()
        file.writeText(trace, Charsets.UTF_8)
        file.absolutePath
    } catch (_: Exception) {
        ""
    }
}



@Composable
private fun AvatarPlaceholder(name: String, size: Int) {
    Box(
        modifier = Modifier.size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = (size / 2.5).sp,
            ),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
