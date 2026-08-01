package launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.DragData
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.onExternalDrag
import androidx.compose.ui.window.*
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import launcher.core.AppSettings
import launcher.core.DownloadManager
import launcher.core.DownloadHub
import launcher.core.LauncherDirs
import launcher.core.ModpackManager
import launcher.core.ProtonManager
import launcher.ui.layout.MainLayout
import launcher.ui.screens.SplashScreen
import launcher.ui.theme.*
import java.awt.Image
import java.awt.Taskbar
import java.io.File
import java.net.URI

fun main() {
    launcher.core.AppLogger.installSystemStreams()

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        val msg = throwable.message ?: ""
        println("[FATAL] 线程 [${thread.name}] 未捕获异常: $msg")
        throwable.printStackTrace()
        if (msg.contains("skiko", ignoreCase = true) ||
            msg.contains("render", ignoreCase = true) ||
            msg.contains("OpenGL", ignoreCase = true) ||
            throwable::class.simpleName?.contains("Render", ignoreCase = true) == true
        ) {
            runCatching {
                File(launcher.core.LauncherDirs.dataDir, ".render_fallback")
                    .writeText("SOFTWARE")
                println("[Render] 渲染崩溃，已记录回退标记 → 下次启动强制 SOFTWARE")
            }
        }
    }

    launcher.core.TrayManager.init()
    LauncherDirs.migrateFromLegacyIfNeeded()
    val md3lDir = LauncherDirs.dataDir

    runCatching {
        val settingsFile = java.io.File(md3lDir, "settings.json")
        if (settingsFile.exists()) {
            val text = settingsFile.readText(Charsets.UTF_8)
            val match = Regex("\"navigationMode\"\\s*:\\s*\"([^\"]+)\"").find(text)
            if (match != null) {
                launcher.ui.theme.ThemeState.navigationMode = match.groupValues[1]
            }
        }
    }

    if (File(md3lDir, "software_render").exists() || File(md3lDir, "software_render.txt").exists()) {
        System.setProperty("skiko.renderApi", "SOFTWARE")
        println("[Render] 检测到 software_render 标记，使用 SOFTWARE")
    } else if (File(md3lDir, ".render_fallback").exists()) {
        System.setProperty("skiko.renderApi", "SOFTWARE")
        println("[Render] 检测到上次渲染崩溃记录，使用 SOFTWARE")
    } else {
        System.setProperty("skiko.renderApi", "OPENGL")
        println("[Render] Linux 渲染 API: OPENGL")
    }

    runLauncherApp()
}

private fun runLauncherApp() = application {
    val windowState = rememberWindowState(
        size = DpSize(916.dp, 716.dp),
        position = WindowPosition(Alignment.Center),
    )
    val appIconImage = remember { loadTaskbarIconImage() }
    val windowIcon = painterResource("app_icon.png")
    var splashFinished by remember { mutableStateOf(false) }
    var showCloseConfirm by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = {
            if (ThemeState.confirmBeforeClose) showCloseConfirm = true
            else {
                windowState.isMinimized = true
            }
        },
        state = windowState,
        title = "MD3L",
        icon = windowIcon,
        undecorated = true,
        transparent = true,
        visible = true,
    ) {
        val scope = rememberCoroutineScope()
        var eulaAccepted by remember { mutableStateOf<Boolean?>(true) }
        var currentSettings by remember { mutableStateOf(AppSettings()) }
        var showUpdateSuccess by remember { mutableStateOf<String?>(null) }

        var showProtonDialog by remember { mutableStateOf(false) }
        var protonInstalling by remember { mutableStateOf(false) }
        var protonInstallResult by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            val protonInstalled = ProtonManager.isInstalled()
            if (!protonInstalled) {
                showProtonDialog = true
            }
        }

        LaunchedEffect(appIconImage) {
            appIconImage?.let { image ->
                window.iconImage = image
                if (Taskbar.isTaskbarSupported()) {
                    runCatching { Taskbar.getTaskbar().iconImage = image }
                }
            }
        }

        LaunchedEffect(Unit) {
            window.background = java.awt.Color(0, 0, 0, 0)
        }

        LaunchedEffect(Unit) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val settings = runCatching { AppSettings.load() }.getOrDefault(AppSettings())
                val bgKey = settings.backgroundImagePath
                if (settings.backgroundImagePath.isNotBlank()) {
                    runCatching {
                        var src = javax.imageio.ImageIO.read(java.io.File(settings.backgroundImagePath))
                        if (src != null) {
                            val maxDim = 1280
                            if (src.width > maxDim || src.height > maxDim) {
                                val s = maxDim.toFloat() / maxOf(src.width, src.height)
                                val nw = (src.width * s).toInt().coerceAtLeast(1)
                                val nh = (src.height * s).toInt().coerceAtLeast(1)
                                val tmp = java.awt.image.BufferedImage(nw, nh, java.awt.image.BufferedImage.TYPE_INT_RGB)
                                tmp.createGraphics().also { g ->
                                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                                    g.drawImage(src, 0, 0, nw, nh, null); g.dispose()
                                }
                                src = tmp
                            }
                            ThemeState.cachedBgBitmap = src.toComposeImageBitmap()
                            ThemeState.cachedBgKey = bgKey
                        }
                    }
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    currentSettings = settings
                    val idx = settings.accentIndex.coerceIn(AllAccents.indices)
                    ThemeState.accent = AllAccents[idx]
                    ThemeState.themeMode = settings.themeMode
                    ThemeState.isDark = settings.themeMode != "light"
                    ThemeState.backgroundImagePath = settings.backgroundImagePath
                    ThemeState.backgroundBlurRadius = settings.backgroundBlurRadius
                    ThemeState.backgroundBrightness = settings.backgroundBrightness
                    ThemeState.uiPanelOpacity = settings.uiPanelOpacity
                    ThemeState.language = settings.language
                    ThemeState.uiAnimationSpeed = settings.uiAnimationSpeed
                    ThemeState.uiFontScale = settings.uiFontScale
                    ThemeState.uiCompactMode = settings.uiCompactMode
                    ThemeState.uiShowVersionBadge = settings.uiShowVersionBadge
                    ThemeState.uiCornerRadius = settings.uiCornerRadius
                    ThemeState.uiSidebarWidth = settings.uiSidebarWidth
                    ThemeState.navigationMode = settings.navigationMode
                    ThemeState.navFloatingMarginBottom = settings.navFloatingMarginBottom
                    ThemeState.navFloatingMarginSide = settings.navFloatingMarginSide
                    ThemeState.navFloatingCornerRadius = settings.navFloatingCornerRadius
                    ThemeState.navFloatingHeight = settings.navFloatingHeight
                    ThemeState.navFloatingShowLabels = settings.navFloatingShowLabels
                    ThemeState.startupPage = settings.startupPage
                    ThemeState.closeAfterLaunch = settings.closeAfterLaunch
                    ThemeState.confirmBeforeClose = settings.confirmBeforeClose
                    ThemeState.showConsoleOnLaunch = settings.showConsoleOnLaunch
                    ThemeState.checkUpdateOnStartup = settings.checkUpdateOnStartup
                    ThemeState.showLogSidebar = settings.showLogSidebar
                    DownloadManager.activeMirror = settings.downloadMirror
                    if (!settings.eulaAccepted) eulaAccepted = false
                }

                runCatching { launcher.core.BundledRuntimeInstaller.ensureInstalled() }

                val updateFlag = File(LauncherDirs.dataDir, "update_success")
                if (updateFlag.exists()) {
                    val tag = runCatching { updateFlag.readText().trim() }.getOrNull()
                    runCatching { updateFlag.delete() }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        showUpdateSuccess = tag
                    }
                }

                if (ThemeState.checkUpdateOnStartup) launcher.core.AutoUpdater.checkForUpdate()
            }
        }

        MD3LTheme {
            if (!splashFinished) {
                SplashScreen(
                    onAnimationEnd = { splashFinished = true }
                )
            } else {
                when (eulaAccepted) {
                    null -> {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    false -> {
                        DisclaimerScreen(
                            onAccept = {
                                scope.launch {
                                    val updated = currentSettings.copy(eulaAccepted = true)
                                    AppSettings.save(updated)
                                    currentSettings = updated
                                    eulaAccepted = true
                                    val protonInstalled = ProtonManager.isInstalled()
                                    if (!protonInstalled) {
                                        showProtonDialog = true
                                    }
                                }
                            },
                            onDecline = ::exitApplication,
                        )
                    }
                    true -> {
                        if (!showProtonDialog) {
                            AppWindow(windowState, ::exitApplication)
                        }

                        if (showCloseConfirm) {
                            AlertDialog(
                                onDismissRequest = { showCloseConfirm = false },
                                title = { Text(if (ThemeState.language == "en") "Exit MD3L?" else "退出 MD3L？") },
                                text = { Text(if (ThemeState.language == "en") "Are you sure you want to exit the launcher?" else "确定要退出启动器吗？") },
                                confirmButton = {
                                    TextButton(onClick = { showCloseConfirm = false; exitApplication() }) {
                                        Text(if (ThemeState.language == "en") "Exit" else "退出", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showCloseConfirm = false }) {
                                        Text(if (ThemeState.language == "en") "Cancel" else "取消")
                                    }
                                },
                            )
                        }

                        if (showUpdateSuccess != null) {
                            AlertDialog(
                                onDismissRequest = { showUpdateSuccess = null },
                                title = { Text("更新完成") },
                                text = { Text("MD3L 已成功更新至版本: ${showUpdateSuccess}\n\n当前核心版本: ${launcher.core.AutoUpdater.CURRENT_VERSION}") },
                                confirmButton = {
                                    TextButton(onClick = { showUpdateSuccess = null }) {
                                        Text("好")
                                    }
                                }
                            )
                        }

                        if (showProtonDialog) {
                            AlertDialog(
                                onDismissRequest = {
                                    if (!protonInstalling) {
                                        exitApplication()
                                    }
                                },
                                shape = RoundedCornerShape(20.dp),
                                title = { Text("需要安装 ProtonGDK 运行环境", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            "基岩版 Minecraft 需要 ProtonGDK (Wine) 运行环境才能运行。\n" +
                                            "检测到您尚未安装此组件，请先下载并安装。",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (protonInstalling) {
                                            val progress by ProtonManager.installProgress.collectAsState()
                                            LinearProgressIndicator(
                                                progress = { progress.fraction },
                                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(progress.step.ifBlank { "正在安装 ProtonGDK..." }, style = MaterialTheme.typography.labelSmall)
                                        } else if (protonInstallResult != null) {
                                            Text(protonInstallResult!!, style = MaterialTheme.typography.bodySmall, color = if ("成功" in protonInstallResult!!) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                        }
                                    }
                                },
                                confirmButton = {
                                    if (!protonInstalling) {
                                        Button(
                                            onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    protonInstalling = true
                                                    protonInstallResult = null
                                                    val result = ProtonManager.installProton()
                                                    protonInstalling = false
                                                    if (result != null) {
                                                        protonInstallResult = "ProtonGDK 安装成功"
                                                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                                                            showProtonDialog = false
                                                        }
                                                    } else {
                                                        protonInstallResult = "ProtonGDK 安装失败，请重试或手动安装"
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                        ) { Text("立即下载并安装") }
                                    } else {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("安装中...")
                                    }
                                },
                                dismissButton = {
                                    if (!protonInstalling) {
                                        TextButton(onClick = { exitApplication() }) {
                                            Text("不安装，退出", color = MaterialTheme.colorScheme.error)
                                        }
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

private fun loadTaskbarIconImage(): Image? {
    return runCatching {
        val url = Thread.currentThread().contextClassLoader.getResource("app_icon.png") ?: return null
        val images = javax.imageio.ImageIO.read(url)
        images
    }.getOrNull()
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FrameWindowScope.AppWindow(
    windowState: WindowState,
    onExit: () -> Unit,
) {
    var dropMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    var isMaximized by remember { mutableStateOf(false) }
    val savedBounds = remember { java.awt.Rectangle() }

    val activeProcess by launcher.core.GameProcessManager.activeProcess.collectAsState()
    LaunchedEffect(activeProcess) {
        if (ThemeState.closeAfterLaunch) {
            window.isVisible = activeProcess == null
        } else {
            if (!window.isVisible) window.isVisible = true
        }
    }

    fun launchModpackImport(files: List<File>) {
        val acceptedExts = setOf("zip", "mrpack", "md3l", "md3lbackup")
        val packFiles = files.filter {
            it.isFile && it.extension.lowercase() in acceptedExts
        }
        if (packFiles.isEmpty()) {
            dropMessage = "请拖入 .zip / .mrpack / .md3l / .md3lbackup 文件"
            return
        }

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val settings = AppSettings.load()
            if (settings.minecraftDir.isBlank()) {
                dropMessage = "请先在设置中配置游戏主目录"
                return@launch
            }
            for (packFile in packFiles) {
                val ext = packFile.extension.lowercase()
                println("[DragImport] 拖入文件: ${packFile.absolutePath}")
                when (ext) {
                    "md3lbackup" -> {
                        val importTaskId = "drag_restore_${packFile.absolutePath.hashCode()}_${System.currentTimeMillis()}"
                        DownloadHub.upsert(DownloadHub.HubTask(id = importTaskId, name = "恢复备份 ${packFile.name}", type = DownloadHub.TaskType.ResourceDownload, step = "准备恢复备份", fraction = 0f))
                        DownloadHub.upsert(DownloadHub.HubTask(id = importTaskId, name = "恢复备份 ${packFile.name}", type = DownloadHub.TaskType.ResourceDownload, status = DownloadHub.TaskStatus.Error, step = "Linux 版暂不支持基岩版备份恢复", fraction = 0f, error = "Linux 版暂不支持基岩版备份恢复"))
                        dropMessage = "Linux 版暂不支持基岩版备份恢复"
                    }
                    "md3l" -> {
                        dropMessage = "已收到 .md3l 整合包，请在版本管理页面选择目标版本后导入"
                    }
                    else -> {
                        val importTaskId = "drag_import_${packFile.absolutePath.hashCode()}_${System.currentTimeMillis()}"
                        DownloadHub.upsert(DownloadHub.HubTask(id = importTaskId, name = "导入整合包 ${packFile.name}", type = DownloadHub.TaskType.ResourceDownload, step = "准备导入整合包", fraction = 0f))
                        val result = ModpackManager.importMrpack(packFile, settings.minecraftDir) { step, fraction ->
                            DownloadHub.upsert(DownloadHub.HubTask(id = importTaskId, name = "导入整合包 ${packFile.name}", type = DownloadHub.TaskType.ResourceDownload, step = step, fraction = fraction.coerceIn(0f, 1f)))
                        }
                        DownloadHub.upsert(DownloadHub.HubTask(id = importTaskId, name = "导入整合包 ${packFile.name}", type = DownloadHub.TaskType.ResourceDownload, status = if ("成功" in result) DownloadHub.TaskStatus.Done else DownloadHub.TaskStatus.Error, step = result, fraction = if ("成功" in result) 1f else 0f, error = if ("成功" in result) "" else result))
                        dropMessage = result
                    }
                }
            }
        }
    }

    val surfaceDragModifier = Modifier.onExternalDrag(
        onDragStart = { _ -> },
        onDrag = { _ -> },
        onDragExit = { },
        onDrop = { externalDragValue ->
            val dragData = externalDragValue.dragData
            if (dragData is DragData.FilesList) {
                val files = dragData.readFiles().map { pathString ->
                    if (pathString.startsWith("file:/")) {
                        runCatching { File(URI.create(pathString)) }.getOrElse { File(pathString) }
                    } else {
                        File(pathString)
                    }
                }
                launchModpackImport(files)
            }
        }
    )

    fun applyMaximized() {
        val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        val screen = ge.defaultScreenDevice.defaultConfiguration.bounds
        val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(ge.defaultScreenDevice.defaultConfiguration)
        val workArea = java.awt.Rectangle(
            screen.x + insets.left,
            screen.y + insets.top,
            screen.width - insets.left - insets.right,
            screen.height - insets.top - insets.bottom,
        )
        savedBounds.setBounds(window.x, window.y, window.width, window.height)
        window.setBounds(workArea)
        isMaximized = true
    }

    fun applyRestored() {
        if (savedBounds.width > 0 && savedBounds.height > 0) {
            window.setBounds(savedBounds)
        }
        isMaximized = false
    }

    val windowShape = if (isMaximized) RoundedCornerShape(0.dp) else RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .then(surfaceDragModifier),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = windowShape,
            color = MaterialTheme.colorScheme.background,
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!isMaximized) {
                    WindowDraggableArea {
                        TitleBar(
                            isMaximized = isMaximized,
                            onMinimize = { windowState.isMinimized = true },
                            onMaximize = { applyMaximized() },
                            onClose = onExit,
                        )
                    }
                } else {
                    TitleBar(
                        isMaximized = isMaximized,
                        onMinimize = { windowState.isMinimized = true },
                        onMaximize = { applyRestored() },
                        onClose = onExit,
                    )
                }
                MainLayout(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DisclaimerScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    val scrollState = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "使用须知与免责声明",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))

            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(modifier = Modifier.padding(20.dp).verticalScroll(scrollState)) {
                    val disclaimerText = """
在使用本启动器（以下简称"MD3L"）之前，请您仔细阅读以下条款。点击"同意"即表示您已阅读、理解并同意遵守以下全部内容。

一、知识产权声明

1. "Minecraft"（《我的世界》）是 Mojang Studios 的注册商标，Mojang Studios 是 Microsoft Corporation 的子公司。
2. 本启动器并非由 Mojang Studios、Microsoft Corporation 或其任何关联公司开发、授权、赞助或认可。
3. 本启动器不包含任何 Minecraft 游戏文件。所有游戏文件均从 Mojang 官方服务器或其授权镜像源下载。
4. Minecraft Java 版的所有权归 Mojang Studios 所有。用户需自行购买正版游戏许可证方可合法游玩。

二、使用条款

1. 本启动器仅供学习与技术研究用途，不得用于任何商业目的。
2. 用户应遵守所在国家/地区的法律法规以及 Minecraft 最终用户许可协议（EULA）。
3. 用户对通过本启动器进行的所有操作承担全部责任。
4. 本启动器可能提供第三方模组加载器（如 Forge、Fabric、NeoForge）的安装功能，这些加载器由各自的开发团队维护，与本启动器无关。

三、免责条款

1. 本启动器按"现状"提供，不提供任何形式的明示或暗示保证，包括但不限于适销性、特定用途适用性和非侵权性的保证。
2. 开发者不对因使用本启动器而导致的任何直接、间接、附带、特殊或后果性损害承担责任。
3. 开发者不对游戏文件的完整性、安全性或可用性作任何保证。
4. 本启动器的网络功能依赖第三方服务（包括但不限于 BMCLAPI 镜像、Mojang API），开发者不对这些服务的可用性负责。

四、隐私说明

1. 本启动器不收集、存储或传输任何用户个人信息至开发者服务器。
2. 所有用户配置（账号信息、游戏设置等）仅存储在本地设备上。
3. Microsoft 账号登录功能通过 Microsoft 官方 OAuth2 流程实现，本启动器不存储您的 Microsoft 密码。

如果您不同意以上任何条款，请点击"不同意"退出启动器。
                    """.trimIndent()

                    Text(
                        disclaimerText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("不同意并退出", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                }
                Button(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                ) {
                    Text("同意并继续", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun TitleBar(
    isMaximized: Boolean,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().height(40.dp),
        shape = if (isMaximized) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "MD3L",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))

            IconButton(
                onClick = onMinimize,
                modifier = Modifier.size(32.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(Icons.Filled.Remove, "最小化", Modifier.size(16.dp))
            }
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = onMaximize,
                modifier = Modifier.size(32.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    if (isMaximized) Icons.Filled.FilterNone else Icons.Filled.CropSquare,
                    "最大化",
                    Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Filled.Close, "关闭", Modifier.size(16.dp))
            }
        }
    }
}
