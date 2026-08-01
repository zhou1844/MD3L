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
    val gameRunning by remember { derivedStateOf { activeProcess != null } }
    val uiLocked by remember { derivedStateOf { globalLaunching || gameRunning } }
    var launchMessage by LaunchScreenState.launchMessage
    val downloadProgress by DownloadManager.progress.collectAsState()
    val bedrockDownloading by BedrockDownloadManager.downloadingVersions.collectAsState()
    val bedrockDownloadResults by BedrockDownloadManager.downloadResults.collectAsState()

    val accountList by AccountRepository.accounts.collectAsState()
    val activeAccount by AccountRepository.activeAccount.collectAsState()
    val refreshState by AccountRepository.refreshState.collectAsState()

    var showLoginDialog by remember { mutableStateOf(false) }
    var editingOfflineName by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf("") }

    var showMsAuthDialog by remember { mutableStateOf(false) }
    var deviceCodeInfo by remember { mutableStateOf<DeviceCodeInfo?>(null) }
    var authPolling by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf("") }
    var skinModelDialogForUuid by remember { mutableStateOf<String?>(null) }

    var skinToastData by remember { mutableStateOf<SkinToastInfo?>(null) }

    LaunchedEffect(Unit) {
        AccountRepository.skinImportEvent.collect { event ->
            val modelLabel = if (isEn) event.model else if (event.model == "slim") "纤细(Alex)" else "经典(Steve)"
            skinToastData = SkinToastInfo(event.username, modelLabel)
            delay(5000)
            skinToastData = null
        }
    }

    var showErrorDialog by remember { mutableStateOf(false) }
    var errorStackTrace by remember { mutableStateOf("") }
    var errorLogPath by remember { mutableStateOf("") }

    var protonInstalling by remember { mutableStateOf(false) }
    var protonPath_ by remember { mutableStateOf<String?>(null) }

    var showThirdPartyDialog by remember { mutableStateOf(false) }
    var tpAuthServerUrl by remember { mutableStateOf("") }
    var tpServerName by remember { mutableStateOf("") }
    var tpEmail by remember { mutableStateOf("") }
    var tpPassword by remember { mutableStateOf("") }
    var tpError by remember { mutableStateOf("") }
    var tpLoading by remember { mutableStateOf(false) }
    
    val updateState by AutoUpdater.state.collectAsState()

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

    LaunchedEffect(bedrockDownloading, bedrockDownloadResults) {
        if (bedrockDownloading.isNotEmpty() || bedrockDownloadResults.isEmpty()) return@LaunchedEffect
        if (!LaunchScreenState.initialized) return@LaunchedEffect
        val loadedSettings = withContext(Dispatchers.IO) { AppSettings.load() }
        settings = loadedSettings
        rescanLocalVersions(loadedSettings, forceReselect = false)
    }

    val versionRevision by VersionRepository.revision.collectAsState()
    LaunchedEffect(versionRevision) {
        if (!LaunchScreenState.initialized) return@LaunchedEffect
        val loadedSettings = withContext(Dispatchers.IO) { AppSettings.load() }
        settings = loadedSettings
        rescanLocalVersions(loadedSettings, forceReselect = false)
    }

    var prevDoneJavaIds by remember { mutableStateOf(emptySet<String>()) }
    val hubTasks by DownloadHub.tasks.collectAsState()
    LaunchedEffect(hubTasks) {
        val doneJavaIds = hubTasks
            .filter { it.type == DownloadHub.TaskType.JavaVersion && it.status == DownloadHub.TaskStatus.Done }
            .map { it.id }
            .toSet()
        if (doneJavaIds != prevDoneJavaIds && doneJavaIds.isNotEmpty()) {
            prevDoneJavaIds = doneJavaIds
            val loadedSettings = withContext(Dispatchers.IO) { AppSettings.load() }
            settings = loadedSettings
            rescanLocalVersions(loadedSettings, forceReselect = false)
        }
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


    val md3EmphasizedDecelerate = remember { CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f) }
    val md3StandardDecelerate = remember { CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f) }
    val md3StandardAccelerate = remember { CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f) }

    Box(modifier = Modifier.fillMaxSize()) {
    Row(modifier = Modifier.fillMaxSize()) {

        val stats by PlayerStats.data.collectAsState()
        val statsData by remember {
            derivedStateOf {
                val totalLaunches = stats.javaLaunchCount
                val totalSec = stats.javaPlayTimeSec
                val javaFrac = if (totalLaunches > 0) 1f else 0f
                StatsData(totalLaunches, totalSec, javaFrac)
            }
        }
        val totalLaunches = statsData.totalLaunches
        val totalSec = statsData.totalSec
        val javaFrac = statsData.javaFrac

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

            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = 10.dp.toPx()
                            val inset = stroke / 2f
                            val sweepJ = if (totalLaunches == 0) 0f else 360f
                            drawArc(trackColor, 0f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke), topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke))
                            if (totalLaunches > 0) {
                                drawArc(primaryColor, -90f, sweepJ, false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round), topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke))
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$totalLaunches", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = onSurface)
                            Text(if (isEn) "times" else "次", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        Text(if (isEn) "Total Launches" else "总启动次数", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = onSurface)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(primaryColor))
                            Text(if (isEn) "Java" else "Java 版", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text("${stats.javaLaunchCount}", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = primaryColor)
                        }
                    }
                }
            }

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
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.BugReport, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                        Text("${stats.totalCrashCount}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(if (isEn) "Crashes" else "次崩溃", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f))
                    }
                }
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
                val totalInstalled = versions.size + bedrockVersions.size
                Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.Inventory2, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.height(4.dp))
                        Text("$totalInstalled", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(if (isEn) "Installed" else "已安装版本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f))
                    }
                }
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

        Column(
            modifier = Modifier
                .weight(0.382f)
                .fillMaxHeight()
                .padding(top = 8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val acct = activeAccount
                    val acctIdx = accountList.indexOfFirst { it.uuid == acct?.uuid }.coerceAtLeast(0)
                    val hasMultiple = accountList.size > 1

                    Spacer(Modifier.height(8.dp))

                    Box(modifier = Modifier.size(96.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        if (acct != null && acct.avatarUri.isNotBlank()) {
                            if (acct.avatarUri.startsWith("http")) {
                                KamelImage(
                                    resource = asyncPainterResource(data = acct.avatarUri),
                                    contentDescription = "头像",
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)),
                                    contentScale = ContentScale.Crop,
                                    onLoading = { AvatarPlaceholder(acct.username, 96) },
                                    onFailure = { AvatarPlaceholder(acct.username, 96) },
                                )
                            } else {
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
                    val acct = activeAccount
                    val isOffline = acct?.type == AccountType.Offline
                    val isMsaOrThirdParty = acct?.type == AccountType.MSA || acct?.type == AccountType.ThirdParty
                    val skinUri = acct?.skinUri.orEmpty()
                    val skinModelStr = if (isOffline) acct?.skinModel.orEmpty() else "classic"
                    val offlineUuid = acct?.uuid.orEmpty()
                    val hasSkinFile = isOffline && skinUri.isNotBlank() && File(skinUri).exists()
                    val hasSkinUrl = isMsaOrThirdParty && skinUri.startsWith("http")

                    val faceBmp = remember(skinUri, offlineUuid) {
                        if (hasSkinFile) loadSkinFaceBitmap(skinUri, 64) else null
                    }

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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            ) {
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
                                LaunchState.begin(if (isEn) "Launching Bedrock..." else "正在启动基岩版…")
                                LaunchState.updateProgress(20)
                                scope.launch {
                                    try {
                                        val s = withContext(Dispatchers.IO) { AppSettings.load() }
                                        LaunchState.updateProgress(40, if (isEn) "Checking Proton..." else "检查 Proton…")
                                        val protonPath = ProtonManager.getSelectedProtonPath()
                                        if (protonPath.isBlank() || !ProtonManager.isInstalled()) {
                                            protonInstalling = true
                                            protonPath_ = withContext(Dispatchers.IO) { ProtonManager.installProton() }
                                            protonInstalling = false
                                            if (protonPath_.isNullOrBlank()) {
                                                launchMessage = if (isEn) "Proton not installed" else "Proton 未安装"
                                                return@launch
                                            }
                                        }
                                        val config = withContext(Dispatchers.IO) {
                                            BedrockVersionConfig.loadFromDir(ver.versionDir)
                                        }
                                        if (config == null) {
                                            launchMessage = if (isEn) "Invalid bedrock version" else "无效的基岩版版本"
                                            return@launch
                                        }
                                        LaunchState.updateProgress(60, if (isEn) "Launching via Proton..." else "通过 Proton 启动…")
                                        val engine = BedrockLaunchEngine()
                                        val process = withContext(Dispatchers.IO) { engine.launch(config) }
                                        if (process != null) {
                                            GameProcessManager.attachProcess(
                                                process, ver.id,
                                                logFile = java.io.File(LauncherDirs.bedrockLogDir, "bedrock-${System.currentTimeMillis()}.log"),
                                                edition = GameEdition.Bedrock,
                                            )
                                            launchMessage = if (isEn) "Game launched: ${ver.id}" else "游戏已启动: ${ver.id}"
                                        } else {
                                            launchMessage = if (isEn) "Launch failed" else "启动失败"
                                        }
                                    } catch (e: Exception) {
                                        val sw = java.io.StringWriter()
                                        e.printStackTrace(java.io.PrintWriter(sw))
                                        errorStackTrace = sw.toString()
                                        errorLogPath = writeLaunchFailureLog(ver.id, sw.toString())
                                        showErrorDialog = true
                                        launchMessage = if (isEn) "Launch failed, see error dialog" else "启动失败，详情见错误弹窗/日志"
                                    } finally {
                                        LaunchState.end()
                                    }
                                }
                                return@Button
                            }

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
                                        javaQuickPlayMultiplayer = MultiplayerManager.serverAddress.value.takeIf { it.isNotBlank() } ?: s.javaQuickPlayMultiplayer,
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
    }

    }

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
                        bedrockVersions = bedrockVersions,
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
                    )
                }
            }
        }
    }

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

private data class SkinToastInfo(val username: String, val modelLabel: String)

private data class StatsData(
    val totalLaunches: Int,
    val totalSec: Long,
    val javaFrac: Float,
)

private fun writeLaunchFailureLog(versionId: String, trace: String): String {
    return try {
        val file = File(
            launcher.core.LauncherDirs.javaLogDir,
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
