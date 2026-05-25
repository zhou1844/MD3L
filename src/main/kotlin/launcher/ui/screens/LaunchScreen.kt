package launcher.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.kamel.image.KamelImage
import io.kamel.image.asyncPainterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import launcher.core.*
import launcher.ui.components.LocalVersionTreeSheetContent
import launcher.ui.components.VersionIcon
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Desktop
import java.io.File
import java.net.URL
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URI
import launcher.ui.theme.IosAppLaunchCurve
import launcher.ui.theme.IosAppLaunchDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchScreen() {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AppSettings()) }
    var versions by remember { mutableStateOf<List<LocalVersion>>(emptyList()) }
    var bedrockVersions by remember { mutableStateOf<List<LocalVersion>>(emptyList()) }
    var selectedVersion by remember { mutableStateOf<LocalVersion?>(null) }
    val globalLaunching by LaunchState.isLaunching.collectAsState()
    val globalStatusMsg by LaunchState.statusMessage.collectAsState()
    val globalProgress by LaunchState.progress.collectAsState()
    val activeProcess by GameProcessManager.activeProcess.collectAsState()
    val processMsg by GameProcessManager.statusMessage.collectAsState()
    val gameProgress by GameProcessManager.launchProgress.collectAsState()
    val crashReport by GameProcessManager.crashReport.collectAsState()
    val gameRunning = activeProcess != null
    val uiLocked = globalLaunching || gameRunning
    var launchMessage by remember { mutableStateOf("") }
    val downloadProgress by DownloadManager.progress.collectAsState()
    val bedrockDownloading by BedrockDownloadManager.downloadingVersions.collectAsState()
    val bedrockDownloadResults by BedrockDownloadManager.downloadResults.collectAsState()

    // ── AccountRepository StateFlow 响应式绑定 ──────────────────────────────
    val accountList by AccountRepository.accounts.collectAsState()
    val activeAccount by AccountRepository.activeAccount.collectAsState()
    val refreshState by AccountRepository.refreshState.collectAsState()

    // ── 官方资讯流 ──────────────────────────────────────────────────────
    val newsList by NewsRepository.news.collectAsState()
    val newsLoading by NewsRepository.isLoading.collectAsState()
    val newsError by NewsRepository.error.collectAsState()

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
        val loadedSettings = withContext(Dispatchers.IO) { AppSettings.load() }
        settings = loadedSettings
        nameInput = loadedSettings.playerName
        AccountRepository.loadFromDisk()
        rescanLocalVersions(loadedSettings, forceReselect = true)
        // 异步抓取官方资讯流
        NewsRepository.refresh()
    }

    LaunchedEffect(selectedVersion, activeAccount) {
        val ver = selectedVersion ?: return@LaunchedEffect
        if (ver.type != "bedrock" || ver.versionDir.isBlank()) return@LaunchedEffect
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
            BedrockLaunchEngine.preheat(versionDir)
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
    // 主布局：Row 左 50% 右 50% —— 对称布局
    // ══════════════════════════════════════════════════════════════════════════
    Row(modifier = Modifier.fillMaxSize()) {

        // ── 左区 (官方资讯) ──────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Newspaper, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("Minecraft 资讯", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                if (newsLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { scope.launch { NewsRepository.refresh() } }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (newsError.isNotBlank() && newsList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(newsError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { scope.launch { NewsRepository.refresh() } }) { Text("重试") }
                    }
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(newsList) { entry ->
                        NewsCard(entry)
                    }
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // ── 右区 (Control Node) ─── Box 绝对定位，彻底杜绝错位 ────────
        Box(
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight(),
        ) {
            // ════════════════════════════════════════════════════════════════
            //  顶部区域 —— 账号切换 + 按钮 + 进度条 + 离线名编辑
            //  Align.TopCenter，自然向下堆叠
            // ════════════════════════════════════════════════════════════════
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                // ── Windows 风格账号卡片 ──────────────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val acct = activeAccount
                    val acctIdx = accountList.indexOfFirst { it.uuid == acct?.uuid }.coerceAtLeast(0)
                    val hasMultiple = accountList.size > 1

                    Spacer(Modifier.height(12.dp))

                    // 大头像
                    Box(modifier = Modifier.size(110.dp).clip(CircleShape)) {
                        if (acct != null && acct.type == AccountType.MSA && acct.avatarUri.isNotBlank()
                            && acct.avatarUri.startsWith("http")) {
                            KamelImage(
                                resource = asyncPainterResource(data = acct.avatarUri),
                                contentDescription = "Xbox Gamerpic",
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop,
                                onLoading = { AvatarPlaceholder(acct.username, 110) },
                                onFailure = { AvatarPlaceholder(acct.username, 110) },
                            )
                        } else if (acct != null && acct.avatarUri.isNotBlank()
                            && !acct.avatarUri.startsWith("http")) {
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
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                AvatarPlaceholder(acct.username, 110)
                            }
                        } else {
                            AvatarPlaceholder(acct?.username ?: "?", 110)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

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
                                acct?.username ?: "未登录",
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
                            Text(if (accountList.isEmpty()) "登录" else "添加", style = MaterialTheme.typography.labelSmall)
                        }
                        if (acct != null) {
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
                                Text("头像", style = MaterialTheme.typography.labelSmall)
                            }
                            if (acct.type == AccountType.Offline) {
                                Spacer(Modifier.width(6.dp))
                                FilledTonalButton(
                                    onClick = {
                                        skinModelDialogForUuid = acct.uuid
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Icon(Icons.Filled.Checkroom, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("皮肤", style = MaterialTheme.typography.labelSmall)
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
                            Text("正在静默刷新 ${rs.username} 的 Token…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
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
            //  底部区域 —— 状态消息 + 版本锚点 + 启动按钮
            //  Align.BottomCenter，死钉底部，绝不因状态改变而偏移
            // ════════════════════════════════════════════════════════════════
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                val displayMsg = when {
                    globalLaunching -> globalStatusMsg
                    gameRunning -> processMsg
                    // 游戏退出后如果有异常信息，优先显示
                    processMsg.isNotBlank() && ("异常" in processMsg || "exit" in processMsg) ->
                        processMsg.lineSequence().filter { it.isNotBlank() }.take(2).joinToString("\n")
                    else -> launchMessage
                }
                if (displayMsg.isNotBlank()) {
                    Text(
                        displayMsg,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            "失败" in displayMsg || "错误" in displayMsg || "崩溃" in displayMsg || "异常" in displayMsg -> MaterialTheme.colorScheme.error
                            gameRunning -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                val launchProgress = when {
                    globalLaunching -> globalProgress
                    gameRunning && gameProgress > 0 -> gameProgress
                    else -> 0
                }
                if (launchProgress > 0) {
                    Text(
                        "启动进度 $launchProgress%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    LinearProgressIndicator(
                        progress = { launchProgress / 100f },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
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
                                            launcher.core.LauncherDirs.dataDir,
                                            "crashes/${ver.id}-bedrock-${System.currentTimeMillis()}.log"
                                        ).also { it.parentFile?.mkdirs() }
                                        val process = withContext(Dispatchers.IO) { engine.execute(context) }

                                        LaunchState.updateProgress(95, "基岩版启动成功，正在移交进程监控…")
                                        LaunchState.attachProcess(process, ver.id, bedrockLogFile)
                                        attached = true
                                        launchMessage = "基岩版已启动: ${ver.id}"
                                    } catch (e: Exception) {
                                        val sw = StringWriter()
                                        e.printStackTrace(PrintWriter(sw))
                                        val trace = sw.toString()
                                        errorStackTrace = trace
                                        errorLogPath = writeLaunchFailureLog(ver.id, trace)
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
                                            windowWidth = s.windowWidth,
                                            windowHeight = s.windowHeight,
                                            fullscreen = s.fullscreen,
                                            skinUri = if (acc?.type == AccountType.Offline) acc.skinUri else "",
                                            skinModel = if (acc?.type == AccountType.Offline) acc.skinModel else "classic",
                                            authServerUrl = acc?.authServerUrl ?: "",
                                            gcPolicy = s.gcPolicy,
                                        )

                                        val engine = JavaLaunchEngine()
                                        LaunchState.updateProgress(80, "正在启动游戏进程…")
                                        val process = withContext(Dispatchers.IO) { engine.execute(context) }
                                        LaunchState.attachProcess(process, ver.id, engine.lastLogFile)
                                        launchMessage = "游戏已启动: ${ver.id}"
                                    } catch (e: Exception) {
                                        val sw = StringWriter()
                                        e.printStackTrace(PrintWriter(sw))
                                        val trace = sw.toString()
                                        errorStackTrace = trace
                                        errorLogPath = writeLaunchFailureLog(ver.id, trace)
                                        showErrorDialog = true
                                        launchMessage = "启动失败，详情见错误弹窗/日志"
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
                                Text("启动中…")
                            }
                            gameRunning -> {
                                Icon(Icons.Filled.SportsEsports, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "游戏运行中…",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                            else -> {
                                Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "启动游戏",
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
                                launchMessage = "已强制结束游戏"
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
                            Text("强制结束", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }
    }

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
                enter = fadeIn(tween(IosAppLaunchDuration, easing = IosAppLaunchCurve)) +
                    scaleIn(
                        initialScale = 0.94f,
                        animationSpec = tween(IosAppLaunchDuration, easing = IosAppLaunchCurve)
                    ),
                exit = fadeOut(tween(IosAppLaunchDuration / 2, easing = IosAppLaunchCurve)) +
                    scaleOut(
                        targetScale = 0.97f,
                        animationSpec = tween(IosAppLaunchDuration / 2, easing = IosAppLaunchCurve)
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
            title = { Text("选择登录方式", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ElevatedCard(
                        onClick = {
                            showLoginDialog = false
                            showMsAuthDialog = true
                            authError = ""
                            scope.launch {
                                try {
                                    deviceCodeInfo = AuthManager.requestDeviceCode()
                                } catch (e: Exception) {
                                    authError = "获取设备码失败: ${e.message}"
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("微软正版登录", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                Text("Device Code Flow · 需要微软账号", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("离线模式", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                Text("输入自定义玩家 ID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("第三方登录", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                Text("LittleSkin 或其他 Yggdrasil API", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLoginDialog = false }) { Text("取消") }
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
            title = { Text("微软账号验证", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (deviceCodeInfo != null) {
                        Text("请在浏览器中输入以下验证码：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text("复制并打开浏览器", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(12.dp))
                        if (authPolling) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("等待验证中…", style = MaterialTheme.typography.bodySmall)
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
                                            authError = "验证失败: ${e.message}"
                                        } finally {
                                            authPolling = false
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text("已在浏览器中验证，开始登录")
                            }
                        }
                    } else if (authError.isNotBlank()) {
                        Text(authError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("获取验证码中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showMsAuthDialog = false
                    deviceCodeInfo = null
                    authPolling = false
                }) { Text("关闭") }
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
            title = { Text("添加第三方账号", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("请提供支持 Yggdrasil API 的认证服务器信息。支持拖入 authlib-injector 格式的 json 配置文件自动解析。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    OutlinedTextField(
                        value = tpAuthServerUrl,
                        onValueChange = { tpAuthServerUrl = it; tpError = "" },
                        label = { Text("认证服务器 (必填)") },
                        placeholder = { Text("如 https://littleskin.cn/api/yggdrasil") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tpServerName,
                        onValueChange = { tpServerName = it },
                        label = { Text("服务器名称 (选填)") },
                        placeholder = { Text("如 LittleSkin") },
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
                        Text("快捷设置为 LittleSkin")
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tpEmail,
                        onValueChange = { tpEmail = it; tpError = "" },
                        label = { Text("邮箱 / 账号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tpPassword,
                        onValueChange = { tpPassword = it; tpError = "" },
                        label = { Text("密码") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (tpLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在认证中…", style = MaterialTheme.typography.bodySmall)
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
                            tpError = "请填写服务器地址、邮箱和密码"
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
                                tpError = e.message ?: "登录失败"
                            } finally {
                                tpLoading = false
                            }
                        }
                    },
                    enabled = !tpLoading,
                    shape = RoundedCornerShape(10.dp)
                ) { Text("完成登录") }
            },
            dismissButton = {
                TextButton(onClick = { if (!tpLoading) showThirdPartyDialog = false }) { Text("取消") }
            }
        )
    }

    skinModelDialogForUuid?.let { uuid ->
        AlertDialog(
            onDismissRequest = { skinModelDialogForUuid = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("选择皮肤模型", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("导入前请选择这张皮肤使用的材质模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text("经典模型，手臂宽度 4 像素", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text("纤细模型，手臂宽度 3 像素", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { skinModelDialogForUuid = null }) { Text("取消") }
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
            title = { Text("发现新版本: ${updateState.releaseInfo!!.tag_name}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("更新内容:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
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
                            "正在下载... ${"%.1f".format(downloadedMb)}MB / ${"%.1f".format(totalMb)}MB · ${"%.2f".format(speedMb)}MB/s · ${(updateState.downloadProgress * 100).toInt()}%"
                        } else {
                            "正在下载... ${"%.1f".format(downloadedMb)}MB · ${"%.2f".format(speedMb)}MB/s"
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
                        Text("错误: ${updateState.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { AutoUpdater.startUpdate() },
                    enabled = !updateState.isDownloading,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (updateState.isDownloading) "正在更新..." else "立即更新")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { AutoUpdater.dismissUpdate() },
                    enabled = !updateState.isDownloading
                ) {
                    Text("暂不更新")
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
                    Text("启动失败 · 致命错误", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error)
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
                }, shape = RoundedCornerShape(8.dp), enabled = errorLogPath.isNotBlank()) { Text("打开日志目录") }
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
                    ) { Text("打开日志") }
                    TextButton(onClick = {
                        showErrorDialog = false
                        GameProcessManager.clearCrashReport()
                    }) { Text("关闭") }
                }
            },
        )
    }
}

private fun writeLaunchFailureLog(versionId: String, trace: String): String {
    return try {
        val file = File(
            launcher.core.LauncherDirs.dataDir,
            "crashes/${versionId.ifBlank { "unknown" }}-launch-${System.currentTimeMillis()}.log",
        )
        file.parentFile?.mkdirs()
        file.writeText(trace, Charsets.UTF_8)
        file.absolutePath
    } catch (_: Exception) {
        ""
    }
}

@Composable
private fun NewsCard(entry: NewsRepository.NewsEntry) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    Card(
        onClick = {
            if (entry.readMoreLink.isNotBlank()) runCatching { uriHandler.openUri(entry.readMoreLink) }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            // 封面图
            if (entry.imageUrl.isNotBlank()) {
                KamelImage(
                    resource = asyncPainterResource(data = entry.imageUrl),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                    contentScale = ContentScale.Crop,
                    onLoading = { Box(Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.surfaceVariant)) },
                    onFailure = { Box(Modifier.fillMaxWidth().height(60.dp).background(MaterialTheme.colorScheme.surfaceVariant)) },
                )
            }
            Column(modifier = Modifier.padding(12.dp)) {
                // Tag chip
                if (entry.tag.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(bottom = 6.dp),
                    ) {
                        Text(
                            entry.tag.uppercase(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.text.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.date.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CalendarToday, contentDescription = null,
                            modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(3.dp))
                        Text(entry.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
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
