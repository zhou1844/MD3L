package launcher

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import launcher.core.AppSettings
import launcher.core.DownloadManager
import launcher.core.DownloadHub
import launcher.core.ModpackManager
import launcher.core.WindowsElevation
import launcher.ui.layout.MainLayout
import launcher.ui.theme.*
import java.awt.Image
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.io.File

fun main() {
    val md3lDir = File(System.getProperty("user.home"), ".md3l")
    if (File(md3lDir, "software_render").exists() || File(md3lDir, "software_render.txt").exists()) {
        System.setProperty("skiko.renderApi", "SOFTWARE")
    }

    if (!WindowsElevation.ensureAdminOrRelaunch()) return
    runLauncherApp()
}

private fun runLauncherApp() = application {
    val windowState = rememberWindowState(
        size = DpSize(916.dp, 716.dp),
        position = WindowPosition(Alignment.Center),
    )
    val appIconImage = remember { loadTaskbarIconImage() }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        undecorated = true,
        transparent = true,
        title = "MD3L",
    ) {
        val scope = rememberCoroutineScope()
        var eulaAccepted by remember { mutableStateOf<Boolean?>(null) } // null = loading
        var currentSettings by remember { mutableStateOf(AppSettings()) }
        var showUpdateSuccess by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(appIconImage) {
            appIconImage?.let { image ->
                window.iconImage = image
                if (Taskbar.isTaskbarSupported()) {
                    runCatching { Taskbar.getTaskbar().iconImage = image }
                }
            }
        }

        LaunchedEffect(Unit) {
            scope.launch {
                val settings = AppSettings.load()
                currentSettings = settings
                val idx = settings.accentIndex.coerceIn(AllAccents.indices)
                ThemeState.accent = AllAccents[idx]
                ThemeState.isDark = settings.themeMode == "dark"
                DownloadManager.activeMirror = settings.downloadMirror
                eulaAccepted = settings.eulaAccepted
                runCatching { launcher.core.BundledRuntimeInstaller.ensureInstalled() }

                val updateFlag = File(System.getProperty("user.home"), ".md3l/update_success")
                if (updateFlag.exists()) {
                    showUpdateSuccess = runCatching { updateFlag.readText().trim() }.getOrNull()
                    runCatching { updateFlag.delete() }
                }

                launcher.core.AutoUpdater.checkForUpdate()
            }
        }

        MD3LTheme {
            when (eulaAccepted) {
                null -> {
                    // 加载中
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                false -> {
                    // 首次启动：显示免责声明
                    DisclaimerScreen(
                        onAccept = {
                            scope.launch {
                                val updated = currentSettings.copy(eulaAccepted = true)
                                AppSettings.save(updated)
                                currentSettings = updated
                                eulaAccepted = true
                            }
                        },
                        onDecline = ::exitApplication,
                    )
                }
                true -> {
                    // 主界面
                    AppWindow(windowState, ::exitApplication)
                    
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
                }
            }
        }
    }
}

private fun loadTaskbarIconImage(): Image? {
    val url = Thread.currentThread().contextClassLoader.getResource("app_icon.ico") ?: return null
    return Toolkit.getDefaultToolkit().createImage(url)
}

@Composable
private fun FrameWindowScope.AppWindow(
    windowState: WindowState,
    onExit: () -> Unit,
) {
    var dropMessage by remember { mutableStateOf("") }

    // 注册拖拽整合包文件到窗口
    LaunchedEffect(Unit) {
        window.dropTarget = DropTarget().apply {
            addDropTargetListener(object : DropTargetAdapter() {
                override fun drop(dtde: DropTargetDropEvent) {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY)
                    try {
                        val transferable = dtde.transferable
                        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            @Suppress("UNCHECKED_CAST")
                            val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                            val packFiles = files.filter {
                                it.isFile && (it.extension.equals("zip", ignoreCase = true) || it.extension.equals("mrpack", ignoreCase = true))
                            }
                            if (packFiles.isNotEmpty()) {
                                val importScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
                                importScope.launch {
                                    val settings = AppSettings.load()
                                    if (settings.minecraftDir.isBlank()) {
                                        dropMessage = "请先在设置中配置游戏主目录"
                                        return@launch
                                    }
                                    for (packFile in packFiles) {
                                        val importTaskId = "drag_import_${packFile.absolutePath.hashCode()}_${System.currentTimeMillis()}"
                                        DownloadHub.upsert(DownloadHub.HubTask(
                                            id = importTaskId,
                                            name = "导入整合包 ${packFile.name}",
                                            type = DownloadHub.TaskType.ResourceDownload,
                                            step = "准备导入整合包",
                                            fraction = 0f,
                                        ))
                                        println("[DragImport] 拖入文件: ${packFile.absolutePath}")
                                        val result = ModpackManager.importMrpack(packFile, settings.minecraftDir) { step, fraction ->
                                            DownloadHub.upsert(DownloadHub.HubTask(
                                                id = importTaskId,
                                                name = "导入整合包 ${packFile.name}",
                                                type = DownloadHub.TaskType.ResourceDownload,
                                                step = step,
                                                fraction = fraction.coerceIn(0f, 1f),
                                            ))
                                        }
                                        DownloadHub.upsert(DownloadHub.HubTask(
                                            id = importTaskId,
                                            name = "导入整合包 ${packFile.name}",
                                            type = DownloadHub.TaskType.ResourceDownload,
                                            status = if ("成功" in result) DownloadHub.TaskStatus.Done else DownloadHub.TaskStatus.Error,
                                            step = result,
                                            fraction = if ("成功" in result) 1f else 0f,
                                            error = if ("成功" in result) "" else result,
                                        ))
                                        dropMessage = result
                                    }
                                }
                            } else {
                                dropMessage = "请拖入 .zip 或 .mrpack 整合包文件"
                            }
                        }
                    } catch (e: Exception) {
                        dropMessage = "拖入文件处理失败: ${e.message}"
                    }
                    dtde.dropComplete(true)
                }
            })
        }
    }

    val isMaximized = windowState.placement == WindowPlacement.Maximized
    val windowShape = if (isMaximized) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp)
    val windowPadding = if (isMaximized) 0.dp else 8.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(windowPadding),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = if (isMaximized) Modifier.fillMaxSize()
            else Modifier.aspectRatio(9f / 7f).fillMaxSize(),
            shape = windowShape,
            color = MaterialTheme.colorScheme.background,
            shadowElevation = if (isMaximized) 0.dp else 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                WindowDraggableArea {
                    TitleBar(
                        isMaximized = isMaximized,
                        onMinimize = { windowState.isMinimized = true },
                        onMaximize = {
                            windowState.placement = if (isMaximized)
                                WindowPlacement.Floating else WindowPlacement.Maximized
                        },
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
5. Minecraft 基岩版（Bedrock Edition）的所有权归 Microsoft Corporation 所有。用户需通过 Microsoft Store 购买正版许可证。

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

五、基岩版特别说明

1. 基岩版的下载与安装可能涉及第三方资源，用户应自行判断资源的合法性与安全性。
2. 基岩版运行需要特定的 Windows 系统组件（如 VCLibs、.NET Native Runtime），本启动器会协助检测但不保证自动安装成功。
3. 侧载安装基岩版可能违反 Microsoft Store 的服务条款，用户需自行承担相关风险。

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
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().height(40.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Logo ──────────────────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            ) {
                Text(
                    " MD3L ",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            Spacer(Modifier.width(12.dp))
            Text(
                "Minecraft Launcher",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                maxLines = 1,
            )

            Spacer(Modifier.weight(1f))

            // ── Window buttons ────────────────────────────────────────────────
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
