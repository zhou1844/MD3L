package launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import launcher.core.*
import launcher.ui.theme.*
import java.awt.Desktop
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AppSettings()) }
    var javaInstallations by remember { mutableStateOf<List<JavaInstallation>>(emptyList()) }
    var javaDropdownExpanded by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(true) }
    var gcExpanded by remember { mutableStateOf(false) }
    var autoSaveJob by remember { mutableStateOf<Job?>(null) }

    val gcOptions = listOf("G1GC", "ZGC", "ShenandoahGC", "ParallelGC", "SerialGC")

    LaunchedEffect(Unit) {
        settings = AppSettings.load()
        DownloadManager.activeMirror = settings.downloadMirror
        javaInstallations = JavaScanner.findAll()
        isScanning = false
    }

    // 自动保存：每次 settings 变化时，延迟 500ms 后写盘（去抖动）
    fun autoSave(newSettings: AppSettings) {
        settings = newSettings
        autoSaveJob?.cancel()
        autoSaveJob = scope.launch {
            delay(500)
            AppSettings.save(newSettings)
        }
    }

    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Text("设置", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text("所有修改即时生效，自动保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        // ── 主题色 ───────────────────────────────────────────────────────────
        SettingsSection("主题", Icons.Filled.Palette) {
            Text("主题色", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AllAccents.forEachIndexed { index, accent ->
                    val isSelected = settings.accentIndex == index
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(accent.primary)
                            .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable {
                                ThemeState.accent = accent
                                autoSave(settings.copy(accentIndex = index))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = accent.onPrimary, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(AccentNames.getOrElse(settings.accentIndex) { "莫奈紫" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))

        // ── Java ─────────────────────────────────────────────────────────────
        SettingsSection("Java 运行环境", Icons.Filled.Code) {
            ExposedDropdownMenuBox(expanded = javaDropdownExpanded, onExpandedChange = { javaDropdownExpanded = it }) {
                OutlinedTextField(
                    value = settings.javaPath,
                    onValueChange = { autoSave(settings.copy(javaPath = it)) },
                    label = { Text("Java 路径") },
                    readOnly = false,
                    trailingIcon = {
                        if (isScanning) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else ExposedDropdownMenuDefaults.TrailingIcon(javaDropdownExpanded)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = javaDropdownExpanded, onDismissRequest = { javaDropdownExpanded = false }) {
                    javaInstallations.forEach { java ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(java.version, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    Text(java.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                val javaExe = if (System.getProperty("os.name").lowercase().contains("win"))
                                    "${java.path}\\bin\\java.exe" else "${java.path}/bin/java"
                                autoSave(settings.copy(javaPath = javaExe))
                                javaDropdownExpanded = false
                            },
                            leadingIcon = { Icon(if (java.is64Bit) Icons.Filled.Memory else Icons.Filled.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        )
                    }
                    if (javaInstallations.isEmpty() && !isScanning) {
                        DropdownMenuItem(text = { Text("未检测到 Java 安装") }, onClick = { javaDropdownExpanded = false })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 内存 ─────────────────────────────────────────────────────────────
        SettingsSection("内存分配", Icons.Filled.Memory) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${settings.memoryMb} MB", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("(${"%.1f".format(settings.memoryMb / 1024.0)} GB)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = settings.memoryMb.toFloat(),
                onValueChange = { autoSave(settings.copy(memoryMb = it.toInt())) },
                valueRange = 1024f..16384f,
                steps = 14,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1 GB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("16 GB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 游戏窗口 ────────────────────────────────────────────────────────
        SettingsSection("游戏窗口", Icons.Filled.Monitor) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = settings.windowWidth.toString(),
                    onValueChange = { autoSave(settings.copy(windowWidth = it.toIntOrNull() ?: 854)) },
                    label = { Text("宽度") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text("×", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = settings.windowHeight.toString(),
                    onValueChange = { autoSave(settings.copy(windowHeight = it.toIntOrNull() ?: 480)) },
                    label = { Text("高度") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { autoSave(settings.copy(fullscreen = !settings.fullscreen)) }
                    .padding(vertical = 8.dp),
            ) {
                Switch(
                    checked = settings.fullscreen,
                    onCheckedChange = { autoSave(settings.copy(fullscreen = it)) },
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("全屏模式", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                    Text("启动时以全屏模式运行游戏", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── GC 策略 ──────────────────────────────────────────────────────────
        SettingsSection("垃圾回收器 (GC)", Icons.Filled.CleaningServices) {
            ExposedDropdownMenuBox(expanded = gcExpanded, onExpandedChange = { gcExpanded = it }) {
                OutlinedTextField(
                    value = settings.gcPolicy,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("GC 策略") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(gcExpanded) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = gcExpanded, onDismissRequest = { gcExpanded = false }) {
                    gcOptions.forEach { gc ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(gc, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    Text(
                                        when (gc) {
                                            "G1GC" -> "推荐 · 低延迟"
                                            "ZGC" -> "超低延迟 · Java 15+"
                                            "ShenandoahGC" -> "低停顿 · OpenJDK"
                                            "ParallelGC" -> "高吞吐 · 大内存"
                                            "SerialGC" -> "单线程 · 低配"
                                            else -> ""
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = { autoSave(settings.copy(gcPolicy = gc)); gcExpanded = false },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 游戏目录 ─────────────────────────────────────────────────────────
        SettingsSection("游戏目录", Icons.Filled.Folder) {
            OutlinedTextField(
                value = settings.minecraftDir,
                onValueChange = { autoSave(settings.copy(minecraftDir = it)) },
                label = { Text(".minecraft 路径") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))

        // ── JVM 参数 ─────────────────────────────────────────────────────────
        SettingsSection("自定义 JVM 参数", Icons.Filled.Terminal) {
            OutlinedTextField(
                value = settings.customJvmArgs,
                onValueChange = { autoSave(settings.copy(customJvmArgs = it)) },
                label = { Text("JVM 启动参数") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text("空格分隔", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(12.dp))

        // ── 下载线程 ─────────────────────────────────────────────────────────
        SettingsSection("下载设置", Icons.Filled.CloudDownload) {
            Text("并发线程: ${settings.maxDownloadThreads}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Slider(
                value = settings.maxDownloadThreads.toFloat(),
                onValueChange = { autoSave(settings.copy(maxDownloadThreads = it.toInt())) },
                valueRange = 1f..64f,
                steps = 62,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.tertiary, activeTrackColor = MaterialTheme.colorScheme.tertiary),
            )
        }
        Spacer(Modifier.height(12.dp))

        // ── 游戏下载镜像源 ────────────────────────────────────────────────────
        var mirrorExpanded by remember { mutableStateOf(false) }
        val mirrorOptions = listOf(
            "bmclapi" to "BMCLAPI 镜像 (推荐国内)",
            "official" to "Mojang 官方源 (海外/VPN)",
        )

        SettingsSection("游戏下载镜像源", Icons.Filled.CloudDownload) {
            ExposedDropdownMenuBox(expanded = mirrorExpanded, onExpandedChange = { mirrorExpanded = it }) {
                OutlinedTextField(
                    value = mirrorOptions.firstOrNull { it.first == settings.downloadMirror }?.second ?: "BMCLAPI 镜像",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("镜像源") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(mirrorExpanded) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = mirrorExpanded, onDismissRequest = { mirrorExpanded = false }) {
                    mirrorOptions.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                DownloadManager.activeMirror = key
                                autoSave(settings.copy(downloadMirror = key))
                                mirrorExpanded = false
                            },
                            leadingIcon = {
                                if (settings.downloadMirror == key)
                                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "切换后重新获取版本列表生效。BMCLAPI 适合国内网络，官方源需要梯子或海外网络。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))

        // ── 恢复默认 ─────────────────────────────────────────────────────────
        OutlinedButton(
            onClick = {
                scope.launch {
                    val defaults = AppSettings()
                    settings = defaults
                    ThemeState.accent = AllAccents[0]
                    AppSettings.save(defaults)
                }
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("恢复默认设置")
        }
        Spacer(Modifier.height(24.dp))

        // ── 关于 + 作者署名 (从侧边栏迁移至此) ──────────────────────────────
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Spacer(Modifier.height(16.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("MD3L", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text("v1.1.0 · Kotlin + Compose Desktop · Material Design 3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "by @yunoniaodudu",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.clickable { openExternalUrl("https://space.bilibili.com/1340292263?spm_id_from=333.1007.0.0") },
                )
                Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Text(
                    "赞助",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.clickable { openExternalUrl("https://ifdian.net/a/zzh10086") },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun openExternalUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
