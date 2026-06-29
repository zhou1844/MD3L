package launcher.ui.screens

import launcher.ui.layout.NavBarScrollState

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
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
import androidx.compose.ui.graphics.Color
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
    var selectedTab by remember { mutableStateOf(0) }
    var showTutorial by remember { mutableStateOf(false) }
    val gcOptions = listOf("G1GC", "ZGC", "ShenandoahGC", "ParallelGC", "SerialGC")
    val isEn = ThemeState.language == "en"
    val tabs = listOf(
        (if (isEn) "General" else "通用") to Icons.Filled.Tune,
        (if (isEn) "Java" else "Java 版") to Icons.Filled.Coffee,
        (if (isEn) "Bedrock" else "基岩版") to Icons.Filled.Diamond,
    )

    LaunchedEffect(Unit) {
        settings = AppSettings.load()
        DownloadManager.activeMirror = settings.downloadMirror
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
        ThemeState.showLogSidebar = settings.showLogSidebar
        ThemeState.navigationMode = settings.navigationMode
        ThemeState.navFloatingMarginBottom = settings.navFloatingMarginBottom
        ThemeState.navFloatingMarginSide = settings.navFloatingMarginSide
        ThemeState.navFloatingCornerRadius = settings.navFloatingCornerRadius
        ThemeState.navFloatingHeight = settings.navFloatingHeight
        ThemeState.navFloatingShowLabels = settings.navFloatingShowLabels
        javaInstallations = JavaScanner.findAll()
        isScanning = false
    }

    fun autoSave(newSettings: AppSettings, immediate: Boolean = false) {
        settings = newSettings
        if (immediate) {
            autoSaveJob?.cancel()
            scope.launch(kotlinx.coroutines.NonCancellable) { AppSettings.save(newSettings) }
        } else {
            autoSaveJob?.cancel()
            autoSaveJob = scope.launch(kotlinx.coroutines.NonCancellable) {
                delay(500)
                AppSettings.save(newSettings)
            }
        }
    }

    val scrollState = rememberScrollState()
    // 监听页面滚动位置，更新底栏淡出隐藏状态
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value to scrollState.maxValue }
            .collect { (value, max) ->
                NavBarScrollState.scrollFraction.value = if (max > 0) value.toFloat() / max.toFloat() else 0f
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 标题区 ────────────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Settings, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("设置", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    Text("所有修改即时生效，自动保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showTutorial = true }) {
                    Icon(
                        Icons.Filled.HelpOutline,
                        contentDescription = if (isEn) "Tutorial" else "调整教程",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // ── 分类切换 Pills ────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                tabs.forEachIndexed { index, (label, icon) ->
                    val sel = selectedTab == index
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { selectedTab = index }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal),
                                color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(end = 8.dp)) {
                    when (selectedTab) {
                        // ════════════════════════════════════════ 通用 ═══
                        0 -> {
                            // ── 语言 ─────────────────────────────────────
                            SettingsSection(if (isEn) "Language" else "语言", Icons.Filled.Language) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    listOf("zh" to "中文", "en" to "English").forEach { (code, label) ->
                                        val sel = settings.language == code
                                        Surface(
                                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable {
                                                ThemeState.language = code
                                                autoSave(settings.copy(language = code))
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        ) {
                                            Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                if (sel) Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                                else Spacer(Modifier.size(16.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal),
                                                    color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            // ── 外观 ────────────────────────────────────
                            SettingsSection(if (isEn) "Appearance" else "外观", Icons.Filled.Palette) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            val newDark = !settings.themeMode.equals("dark")
                                            ThemeState.isDark = newDark
                                            autoSave(settings.copy(themeMode = if (newDark) "dark" else "light"))
                                        }.padding(vertical = 8.dp),
                                ) {
                                    Switch(
                                        checked = settings.themeMode == "dark",
                                        onCheckedChange = {
                                            ThemeState.isDark = it
                                            autoSave(settings.copy(themeMode = if (it) "dark" else "light"))
                                        },
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(if (isEn) "Dark Mode" else "深色模式", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                        Text(if (isEn) "Switch between light and dark theme" else "切换亮色或暗色主题", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Spacer(Modifier.height(16.dp))
                                Text(if (isEn) "Accent Color" else "主题色", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                Spacer(Modifier.height(8.dp))
                                val accentNamesEn = listOf(
                                    "Monet Purple","Geek Blue","Mint Green","Amber Orange","Coral Red","Celadon Cyan","Graphite Blue",
                                    "Stardust Rose","Abyss Indigo","Jade Moss","Warm Sand","Moonlight","Aurora"
                                )
                                // 第一行 7 个 + 第二行 6 个
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(0..6, 7..12).forEach { range ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            range.forEach { index ->
                                                val accent = AllAccents.getOrNull(index) ?: return@forEach
                                                val isSelected = settings.accentIndex == index
                                                Box(
                                                    modifier = Modifier.size(36.dp).clip(CircleShape).background(accent.primary)
                                                        .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                                        .clickable {
                                                            ThemeState.accent = accent
                                                            autoSave(settings.copy(accentIndex = index))
                                                        },
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    if (isSelected) Icon(Icons.Filled.Check, null, tint = accent.onPrimary, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (isEn) accentNamesEn.getOrElse(settings.accentIndex){"Monet Purple"} else AccentNames.getOrElse(settings.accentIndex){"莫奈紫"},
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(16.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Spacer(Modifier.height(16.dp))
                                Text(if (isEn) "Background Image" else "背景图片", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = settings.backgroundImagePath,
                                        onValueChange = {},
                                        readOnly = true,
                                        placeholder = { Text("未设置（点击选择图片）", style = MaterialTheme.typography.bodySmall) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    FilledTonalButton(
                                        onClick = {
                                            scope.launch {
                                                val chosen = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    val chooser = javax.swing.JFileChooser(System.getProperty("user.home"))
                                                    chooser.dialogTitle = "选择背景图片"
                                                    chooser.fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
                                                    chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("图片文件", "jpg", "jpeg", "png", "bmp", "webp")
                                                    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
                                                }
                                                if (chosen != null) {
                                                    ThemeState.backgroundImagePath = chosen
                                                    autoSave(settings.copy(backgroundImagePath = chosen), immediate = true)
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                    ) { Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (isEn) "Browse" else "选择") }
                                    if (settings.backgroundImagePath.isNotBlank()) {
                                        Spacer(Modifier.width(6.dp))
                                        FilledTonalButton(
                                            onClick = { ThemeState.backgroundImagePath = ""; autoSave(settings.copy(backgroundImagePath = ""), immediate = true) },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                        ) { Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    }
                                }
                                if (settings.backgroundImagePath.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(if (isEn) "Blur  ${settings.backgroundBlurRadius}px" else "模糊强度  ${settings.backgroundBlurRadius}px", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = settings.backgroundBlurRadius.toFloat(),
                                        onValueChange = { v -> val iv = v.toInt(); settings = settings.copy(backgroundBlurRadius = iv); ThemeState.backgroundBlurRadius = iv },
                                        onValueChangeFinished = { autoSave(settings.copy(backgroundBlurRadius = settings.backgroundBlurRadius)) },
                                        valueRange = 0f..60f, steps = 59, modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(if (isEn) "Brightness  ${"%.0f".format(settings.backgroundBrightness * 100)}%" else "亮度  ${"%.0f".format(settings.backgroundBrightness * 100)}%", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = settings.backgroundBrightness,
                                        onValueChange = { ThemeState.backgroundBrightness = it; autoSave(settings.copy(backgroundBrightness = it)) },
                                        valueRange = 0.1f..1f, modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(if (isEn) "Panel Opacity  ${"%.0f".format(settings.uiPanelOpacity * 100)}%" else "组件不透明度  ${"%.0f".format(settings.uiPanelOpacity * 100)}%", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = settings.uiPanelOpacity,
                                        onValueChange = { settings = settings.copy(uiPanelOpacity = it); ThemeState.uiPanelOpacity = it; autoSave(settings.copy(uiPanelOpacity = it)) },
                                        valueRange = 0.1f..1f, modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            // ── 导航方式 ──────────────────────────────────
                            SettingsSection(if (isEn) "Navigation" else "导航方式", Icons.Filled.Explore) {
                                Text(if (isEn) "Navigation style" else "导航样式", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    listOf(
                                        "sidebar" to (if (isEn) "Sidebar" else "侧边导航"),
                                        "floating" to (if (isEn) "Floating Nav" else "悬浮导航"),
                                    ).forEach { (mode, label) ->
                                        val sel = settings.navigationMode == mode
                                        Surface(
                                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable {
                                                ThemeState.navigationMode = mode
                                                autoSave(settings.copy(navigationMode = mode))
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        ) {
                                            Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                if (sel) Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                                else Spacer(Modifier.size(16.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal),
                                                    color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Spacer(Modifier.height(16.dp))

                                // ── 根据导航方式显示不同微调选项 ────────────
                                if (settings.navigationMode == "sidebar") {
                                    // ── 侧边导航微调 ──
                                    Text(if (isEn) "Sidebar width: ${settings.uiSidebarWidth} dp" else "侧边栏宽度: ${settings.uiSidebarWidth} dp", style = MaterialTheme.typography.bodySmall)
                                    Slider(value = settings.uiSidebarWidth.toFloat(), onValueChange = { v ->
                                        val iv = v.toInt(); ThemeState.uiSidebarWidth = iv; autoSave(settings.copy(uiSidebarWidth = iv))
                                    }, valueRange = 64f..120f, steps = 55, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(6.dp))
                                    SettingsToggleRow(
                                        title = if (isEn) "Compact Mode" else "紧凑模式",
                                        subtitle = if (isEn) "Reduce spacing between navigation items" else "减少导航项之间的间距",
                                        checked = settings.uiCompactMode,
                                        onCheckedChange = { ThemeState.uiCompactMode = it; autoSave(settings.copy(uiCompactMode = it)) },
                                    )
                                    SettingsToggleRow(
                                        title = if (isEn) "Show version badge" else "侧边栏显示版本号徽章",
                                        subtitle = if (isEn) "Shows launcher version number at the bottom of the sidebar" else "在侧边栏底部显示当前启动器版本号",
                                        checked = settings.uiShowVersionBadge,
                                        onCheckedChange = { ThemeState.uiShowVersionBadge = it; autoSave(settings.copy(uiShowVersionBadge = it)) },
                                    )
                                    SettingsToggleRow(
                                        title = if (isEn) "Show Log entry" else "侧边栏显示日志入口",
                                        subtitle = if (isEn) "Show a Log tab in the sidebar" else "在侧边栏显示日志页入口",
                                        checked = settings.showLogSidebar,
                                        onCheckedChange = { ThemeState.showLogSidebar = it; autoSave(settings.copy(showLogSidebar = it)) },
                                    )
                                } else {
                                    // ── 浮动导航微调 ──
                                    Text(if (isEn) "Bottom margin: ${settings.navFloatingMarginBottom} dp" else "底部边距: ${settings.navFloatingMarginBottom} dp", style = MaterialTheme.typography.bodySmall)
                                    Slider(value = settings.navFloatingMarginBottom.toFloat(), onValueChange = { v ->
                                        val iv = v.toInt(); ThemeState.navFloatingMarginBottom = iv; autoSave(settings.copy(navFloatingMarginBottom = iv))
                                    }, valueRange = 4f..24f, steps = 9, modifier = Modifier.fillMaxWidth())
                                    Text(if (isEn) "Side margin: ${settings.navFloatingMarginSide} dp" else "两侧边距: ${settings.navFloatingMarginSide} dp", style = MaterialTheme.typography.bodySmall)
                                    Slider(value = settings.navFloatingMarginSide.toFloat(), onValueChange = { v ->
                                        val iv = v.toInt(); ThemeState.navFloatingMarginSide = iv; autoSave(settings.copy(navFloatingMarginSide = iv))
                                    }, valueRange = 4f..32f, steps = 13, modifier = Modifier.fillMaxWidth())
                                    Text(if (isEn) "Nav bar corner radius: ${settings.navFloatingCornerRadius} dp" else "导航栏圆角: ${settings.navFloatingCornerRadius} dp", style = MaterialTheme.typography.bodySmall)
                                    Slider(value = settings.navFloatingCornerRadius.toFloat(), onValueChange = { v ->
                                        val iv = v.toInt(); ThemeState.navFloatingCornerRadius = iv; autoSave(settings.copy(navFloatingCornerRadius = iv))
                                    }, valueRange = 8f..32f, steps = 11, modifier = Modifier.fillMaxWidth())
                                    Text(if (isEn) "Nav bar height: ${settings.navFloatingHeight} dp" else "导航栏高度: ${settings.navFloatingHeight} dp", style = MaterialTheme.typography.bodySmall)
                                    Slider(value = settings.navFloatingHeight.toFloat(), onValueChange = { v ->
                                        val iv = v.toInt(); ThemeState.navFloatingHeight = iv; autoSave(settings.copy(navFloatingHeight = iv))
                                    }, valueRange = 52f..80f, steps = 13, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(6.dp))
                                    SettingsToggleRow(
                                        title = if (isEn) "Show labels" else "显示导航标签文字",
                                        subtitle = if (isEn) "Show text labels below navigation icons" else "在导航图标下方显示文字标签",
                                        checked = settings.navFloatingShowLabels,
                                        onCheckedChange = { ThemeState.navFloatingShowLabels = it; autoSave(settings.copy(navFloatingShowLabels = it)) },
                                    )
                                    SettingsToggleRow(
                                        title = if (isEn) "Show version badge" else "显示版本号徽章",
                                        subtitle = if (isEn) "Show launcher version number near the nav bar" else "在导航栏附近显示当前启动器版本号",
                                        checked = settings.uiShowVersionBadge,
                                        onCheckedChange = { ThemeState.uiShowVersionBadge = it; autoSave(settings.copy(uiShowVersionBadge = it)) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            // ── 界面布局（通用）─────────────────────────────
                            SettingsSection(if (isEn) "Layout" else "界面布局", Icons.Filled.Dashboard) {
                                Text(if (isEn) "Corner radius: ${settings.uiCornerRadius} dp" else "全局圆角: ${settings.uiCornerRadius} dp", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.uiCornerRadius.toFloat(), onValueChange = { v ->
                                    val iv = v.toInt(); ThemeState.uiCornerRadius = iv; autoSave(settings.copy(uiCornerRadius = iv))
                                }, valueRange = 0f..32f, steps = 31, modifier = Modifier.fillMaxWidth())
                                Text(if (isEn) "Font scale: ${"%.1f".format(settings.uiFontScale)}×" else "字体缩放: ${"%.1f".format(settings.uiFontScale)}×", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.uiFontScale, onValueChange = { v ->
                                    ThemeState.uiFontScale = v; autoSave(settings.copy(uiFontScale = v))
                                }, valueRange = 0.8f..1.4f, steps = 11, modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.height(12.dp))

                            // ── 动效 ─────────────────────────────────────
                            SettingsSection(if (isEn) "Animation" else "动效", Icons.Filled.SlowMotionVideo) {
                                Text(
                                    if (isEn) "Animation speed: ${when { settings.uiAnimationSpeed == 0f -> "Off"; settings.uiAnimationSpeed < 0.8f -> "${"%.1f".format(settings.uiAnimationSpeed)}× (Reduced)"; settings.uiAnimationSpeed > 1.2f -> "${"%.1f".format(settings.uiAnimationSpeed)}× (Fast)"; else -> "${"%.1f".format(settings.uiAnimationSpeed)}×" }}"
                                    else "动画速度: ${when { settings.uiAnimationSpeed == 0f -> "关闭"; settings.uiAnimationSpeed < 0.8f -> "${"%.1f".format(settings.uiAnimationSpeed)}×（减弱）"; settings.uiAnimationSpeed > 1.2f -> "${"%.1f".format(settings.uiAnimationSpeed)}×（加速）"; else -> "${"%.1f".format(settings.uiAnimationSpeed)}×" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Slider(value = settings.uiAnimationSpeed, onValueChange = { v ->
                                    ThemeState.uiAnimationSpeed = v; autoSave(settings.copy(uiAnimationSpeed = v))
                                }, valueRange = 0f..2f, steps = 19, modifier = Modifier.fillMaxWidth())
                                Text(if (isEn) "0 = all animations disabled · 1.0 = default · 2.0 = 2× speed" else "0 = 关闭所有动画 · 1.0 = 默认 · 2.0 = 两倍速",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(12.dp))

                            // ── 游戏目录 ─────────────────────────────────
                            SettingsSection(if (isEn) "Game Directory" else "游戏目录", Icons.Filled.Folder) {
                                OutlinedTextField(
                                    value = settings.minecraftDir,
                                    onValueChange = { autoSave(settings.copy(minecraftDir = it)) },
                                    label = { Text(if (isEn) ".minecraft path" else ".minecraft 路径") },
                                    singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.height(12.dp))

                            // ── 下载设置 ─────────────────────────────────
                            var mirrorExpanded by remember { mutableStateOf(false) }
                            val mirrorOptions = if (isEn)
                                listOf("bmclapi" to "BMCLAPI Mirror (China)", "official" to "Mojang Official (Global)")
                            else
                                listOf("bmclapi" to "BMCLAPI 镜像 (推荐国内)", "official" to "Mojang 官方源 (海外/VPN)")
                            SettingsSection(if (isEn) "Download" else "下载设置", Icons.Filled.CloudDownload) {
                                Text(if (isEn) "Concurrent threads: ${settings.maxDownloadThreads}" else "并发线程: ${settings.maxDownloadThreads}", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(8.dp))
                                Slider(
                                    value = settings.maxDownloadThreads.toFloat(),
                                    onValueChange = { autoSave(settings.copy(maxDownloadThreads = it.toInt())) },
                                    valueRange = 1f..64f, steps = 62, modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.tertiary, activeTrackColor = MaterialTheme.colorScheme.tertiary),
                                )
                                Spacer(Modifier.height(12.dp))
                                ExposedDropdownMenuBox(expanded = mirrorExpanded, onExpandedChange = { mirrorExpanded = it }) {
                                    OutlinedTextField(
                                        value = mirrorOptions.firstOrNull { it.first == settings.downloadMirror }?.second ?: "BMCLAPI 镜像",
                                        onValueChange = {}, readOnly = true, label = { Text("镜像源") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(mirrorExpanded) },
                                        singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().menuAnchor(),
                                    )
                                    ExposedDropdownMenu(expanded = mirrorExpanded, onDismissRequest = { mirrorExpanded = false }) {
                                        mirrorOptions.forEach { (key, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                                onClick = { DownloadManager.activeMirror = key; autoSave(settings.copy(downloadMirror = key)); mirrorExpanded = false },
                                                leadingIcon = { if (settings.downloadMirror == key) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) },
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(if (isEn) "BMCLAPI recommended for China · Official requires VPN" else "BMCLAPI 适合国内网络，官方源需要梯子或海外网络。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(12.dp))

                            // ── 启动行为 ─────────────────────────────────
                            var startupPageExpanded by remember { mutableStateOf(false) }
                            val startupPageOptions = if (isEn)
                                listOf("launch" to "Launch", "versions" to "Versions", "download" to "Downloads")
                            else
                                listOf("launch" to "启动页", "versions" to "版本管理", "download" to "下载中心")
                            SettingsSection(if (isEn) "Launcher Behavior" else "启动行为", Icons.Filled.PlayArrow) {
                                SettingsToggleRow(title = if (isEn) "Hide window after launch" else "启动游戏后隐藏窗口", subtitle = if (isEn) "Auto-hide launcher after game starts, process stays alive" else "游戏启动后自动隐藏启动器，不退出启动器进程", checked = settings.closeAfterLaunch, onCheckedChange = { autoSave(settings.copy(closeAfterLaunch = it)) })
                                SettingsToggleRow(title = if (isEn) "Check for updates on startup" else "启动时检查更新", subtitle = if (isEn) "Auto check for new launcher versions in background" else "启动器开启时自动在后台检查新版本", checked = settings.checkUpdateOnStartup, onCheckedChange = { autoSave(settings.copy(checkUpdateOnStartup = it)) })
                                SettingsToggleRow(title = if (isEn) "Confirm before close" else "关闭前确认", subtitle = if (isEn) "Show confirmation dialog before closing" else "点击关闭按钮时弹出确认对话框", checked = settings.confirmBeforeClose, onCheckedChange = { autoSave(settings.copy(confirmBeforeClose = it)) })
                                SettingsToggleRow(title = if (isEn) "Show console on launch" else "启动时显示控制台", subtitle = if (isEn) "Auto open log console window after game starts" else "启动游戏后自动弹出日志控制台窗口", checked = settings.showConsoleOnLaunch, onCheckedChange = { autoSave(settings.copy(showConsoleOnLaunch = it)) })
                                Spacer(Modifier.height(8.dp))
                                Text(if (isEn) "Log retention: ${settings.logRetentionDays} days" else "日志保留天数: ${settings.logRetentionDays} 天", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.logRetentionDays.toFloat(), onValueChange = { autoSave(settings.copy(logRetentionDays = it.toInt())) }, valueRange = 1f..30f, steps = 28, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                ExposedDropdownMenuBox(expanded = startupPageExpanded, onExpandedChange = { startupPageExpanded = it }) {
                                    OutlinedTextField(
                                        value = startupPageOptions.firstOrNull { it.first == settings.startupPage }?.second ?: if (isEn) "Launch" else "启动页",
                                        onValueChange = {}, readOnly = true, label = { Text(if (isEn) "Default start page" else "默认起始页") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(startupPageExpanded) },
                                        singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().menuAnchor(),
                                    )
                                    ExposedDropdownMenu(expanded = startupPageExpanded, onDismissRequest = { startupPageExpanded = false }) {
                                        startupPageOptions.forEach { (key, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                                onClick = { autoSave(settings.copy(startupPage = key)); startupPageExpanded = false },
                                                leadingIcon = { if (settings.startupPage == key) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) },
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            // ── 网络代理 ─────────────────────────────────
                            SettingsSection(if (isEn) "Network Proxy" else "网络代理", Icons.Filled.NetworkCheck) {
                                Text(if (isEn) "All HTTP requests (downloads, login, news) route through proxy when set" else "配置后所有 HTTP 请求（下载/登录/资讯）均经过代理，空则直连", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(value = settings.httpProxyHost, onValueChange = { autoSave(settings.copy(httpProxyHost = it)) }, label = { Text(if (isEn) "Proxy host" else "代理主机") }, placeholder = { Text("127.0.0.1") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f))
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedTextField(value = if (settings.httpProxyPort == 0) "" else settings.httpProxyPort.toString(), onValueChange = { autoSave(settings.copy(httpProxyPort = it.toIntOrNull() ?: 0)) }, label = { Text(if (isEn) "Port" else "端口") }, placeholder = { Text("7890") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.width(100.dp))
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(if (isEn) "Timeout: ${settings.networkTimeoutSec} s" else "网络超时: ${settings.networkTimeoutSec} 秒", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.networkTimeoutSec.toFloat(), onValueChange = { autoSave(settings.copy(networkTimeoutSec = it.toInt())) }, valueRange = 5f..120f, steps = 22, modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.height(12.dp))

                            // ── 恢复默认 ─────────────────────────────────
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val d = AppSettings()
                                        settings = d
                                        ThemeState.accent = AllAccents[0]
                                        ThemeState.uiAnimationSpeed = 1.0f
                                        ThemeState.uiFontScale = 1.0f
                                        ThemeState.uiCompactMode = false
                                        ThemeState.uiShowVersionBadge = true
                                        ThemeState.uiCornerRadius = 16
                                        ThemeState.uiSidebarWidth = 80
                                        ThemeState.navigationMode = "sidebar"
                                        ThemeState.navFloatingMarginBottom = 12
                                        ThemeState.navFloatingMarginSide = 16
                                        ThemeState.navFloatingCornerRadius = 24
                                        ThemeState.navFloatingHeight = 64
                                        ThemeState.navFloatingShowLabels = true
                                        AppSettings.save(d)
                                    }
                                },
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(44.dp),
                            ) {
                                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (isEn) "Reset to defaults" else "恢复默认设置")
                            }
                            Spacer(Modifier.height(24.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            Spacer(Modifier.height(16.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                // 使用 Box+clickable 替代直接 Text.clickable，避免滚动容器中点击事件被拦截
                                Box(modifier = Modifier.clickable { openExternalUrl("https://md3l.top") }) {
                                    Text("MD3L", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black), color = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("v${AutoUpdater.CURRENT_VERSION} · Kotlin + Compose Desktop · Material Design 3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.clickable { openExternalUrl("https://space.bilibili.com/1340292263") }) {
                                        Text("by @yunoniaodudu", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    }
                                    Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                    Box(modifier = Modifier.clickable { openExternalUrl("https://ifdian.net/a/zzh10086") }) {
                                        Text("赞助", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.tertiary)
                                    }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }

                        // ════════════════════════════════════════ Java 版 ═
                        1 -> {
                            SettingsSection("Java 运行环境", Icons.Filled.Code) {
                                ExposedDropdownMenuBox(expanded = javaDropdownExpanded, onExpandedChange = { javaDropdownExpanded = it }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = settings.javaPath,
                                            onValueChange = { autoSave(settings.copy(javaPath = it)) },
                                            label = { Text("Java 路径") }, readOnly = false,
                                            trailingIcon = {
                                                if (isScanning) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                                else ExposedDropdownMenuDefaults.TrailingIcon(javaDropdownExpanded)
                                            },
                                            singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).menuAnchor(),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        FilledTonalIconButton(
                                            onClick = {
                                                scope.launch {
                                                    val chosen = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        val chooser = javax.swing.JFileChooser()
                                                        chooser.dialogTitle = "选择 Java 可执行文件"
                                                        chooser.fileFilter = object : javax.swing.filechooser.FileFilter() {
                                                            override fun accept(f: java.io.File?): Boolean =
                                                                f != null && (f.isDirectory || f.name.endsWith(".exe", ignoreCase = true))
                                                            override fun getDescription(): String = "Java 可执行文件 (*.exe)"
                                                        }
                                                        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                                            chooser.selectedFile.absolutePath
                                                        } else null
                                                    }
                                                    if (chosen != null) {
                                                        autoSave(settings.copy(javaPath = chosen))
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                        ) {
                                            Icon(Icons.Filled.FolderOpen, contentDescription = "浏览", modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))

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
                                    valueRange = 1024f..16384f, steps = 14, modifier = Modifier.fillMaxWidth(),
                                )
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("1 GB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("16 GB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("游戏窗口", Icons.Filled.Monitor) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(value = settings.windowWidth.toString(), onValueChange = { autoSave(settings.copy(windowWidth = it.toIntOrNull() ?: 854)) }, label = { Text("宽度") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f))
                                    Spacer(Modifier.width(8.dp))
                                    Text("×", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedTextField(value = settings.windowHeight.toString(), onValueChange = { autoSave(settings.copy(windowHeight = it.toIntOrNull() ?: 480)) }, label = { Text("高度") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { autoSave(settings.copy(fullscreen = !settings.fullscreen)) }.padding(vertical = 8.dp),
                                ) {
                                    Switch(checked = settings.fullscreen, onCheckedChange = { autoSave(settings.copy(fullscreen = it)) })
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text("全屏模式", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                        Text("启动时以全屏模式运行游戏", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("垃圾回收器 (GC)", Icons.Filled.CleaningServices) {
                                ExposedDropdownMenuBox(expanded = gcExpanded, onExpandedChange = { gcExpanded = it }) {
                                    OutlinedTextField(
                                        value = settings.gcPolicy, onValueChange = {}, readOnly = true, label = { Text("GC 策略") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(gcExpanded) },
                                        singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().menuAnchor(),
                                    )
                                    ExposedDropdownMenu(expanded = gcExpanded, onDismissRequest = { gcExpanded = false }) {
                                        gcOptions.forEach { gc ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(gc, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                                        Text(when (gc) { "G1GC" -> "推荐 · 低延迟"; "ZGC" -> "超低延迟 · Java 15+"; "ShenandoahGC" -> "低停顿 · OpenJDK"; "ParallelGC" -> "高吞吐 · 大内存"; "SerialGC" -> "单线程 · 低配"; else -> "" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                },
                                                onClick = {
                                                    // 双向同步：GC 策略变更 → 更新 customJvmArgs 中的 GC 标志
                                                    val oldGcFlag = "-XX:+Use${settings.gcPolicy}"
                                                    val newGcFlag = when (gc) {
                                                        "G1GC" -> "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions"
                                                        "ZGC" -> "-XX:+UseZGC -XX:+UnlockExperimentalVMOptions"
                                                        "ShenandoahGC" -> "-XX:+UseShenandoahGC -XX:+UnlockExperimentalVMOptions"
                                                        "ParallelGC" -> "-XX:+UseParallelGC"
                                                        "SerialGC" -> "-XX:+UseSerialGC"
                                                        else -> ""
                                                    }
                                                    val oldArgs = settings.customJvmArgs.split("\\s+".toRegex())
                                                        .filter { it.isNotBlank() && (!it.startsWith("-XX:+Use") || !it.contains("GC")) }
                                                    val newArgs = (oldArgs + newGcFlag.split(" ")).joinToString(" ")
                                                    autoSave(settings.copy(gcPolicy = gc, customJvmArgs = newArgs.trim()))
                                                    gcExpanded = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            // ── 动态 GC 精细调节面板 ────────────────────────────────
                            when (settings.gcPolicy) {
                                "G1GC" -> {
                                    SettingsSection("G1GC 精细调节", Icons.Filled.Tune) {
                                        Text("以下参数在 GC 策略为 G1GC 时生效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(10.dp))
                                        Text("G1NewSizePercent: ${settings.jvmG1NewSizePercent}%", style = MaterialTheme.typography.bodySmall)
                                        Slider(value = settings.jvmG1NewSizePercent.toFloat(), onValueChange = { autoSave(settings.copy(jvmG1NewSizePercent = it.toInt())) }, valueRange = 5f..40f, steps = 34, modifier = Modifier.fillMaxWidth())
                                        Text("G1MaxNewSizePercent: ${settings.jvmG1MaxNewSizePercent}%", style = MaterialTheme.typography.bodySmall)
                                        Slider(value = settings.jvmG1MaxNewSizePercent.toFloat(), onValueChange = { autoSave(settings.copy(jvmG1MaxNewSizePercent = it.toInt())) }, valueRange = 20f..80f, steps = 59, modifier = Modifier.fillMaxWidth())
                                        Text("G1HeapRegionSize: ${settings.jvmG1HeapRegionSize} MB", style = MaterialTheme.typography.bodySmall)
                                        Slider(value = settings.jvmG1HeapRegionSize.toFloat(), onValueChange = { autoSave(settings.copy(jvmG1HeapRegionSize = it.toInt())) }, valueRange = 1f..32f, steps = 30, modifier = Modifier.fillMaxWidth())
                                        Text("MaxGCPauseMillis: ${settings.jvmG1GCPauseTarget} ms", style = MaterialTheme.typography.bodySmall)
                                        Slider(value = settings.jvmG1GCPauseTarget.toFloat(), onValueChange = { autoSave(settings.copy(jvmG1GCPauseTarget = it.toInt())) }, valueRange = 10f..200f, steps = 18, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                                "ZGC" -> {
                                    SettingsSection("ZGC 精细调节", Icons.Filled.Tune) {
                                        Text("以下参数在 GC 策略为 ZGC 时生效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(10.dp))
                                        Text("ZUncommitDelay: ${settings.jvmZUncommitDelay} 秒", style = MaterialTheme.typography.bodySmall)
                                        Slider(value = settings.jvmZUncommitDelay.toFloat(), onValueChange = { autoSave(settings.copy(jvmZUncommitDelay = it.toInt())) }, valueRange = 10f..300f, steps = 28, modifier = Modifier.fillMaxWidth())
                                        Text("ConcGCThreads: ${if (settings.jvmConcGCThreads == 0) "自动" else "${settings.jvmConcGCThreads}"}", style = MaterialTheme.typography.bodySmall)
                                        Slider(value = settings.jvmConcGCThreads.toFloat(), onValueChange = { autoSave(settings.copy(jvmConcGCThreads = it.toInt())) }, valueRange = 0f..16f, steps = 15, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                                "ShenandoahGC" -> {
                                    SettingsSection("Shenandoah 精细调节", Icons.Filled.Tune) {
                                        Text("以下参数在 GC 策略为 ShenandoahGC 时生效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(10.dp))
                                        Text("ShenandoahGCMode: ${settings.jvmShenandoahMode}", style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.height(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            listOf("iu" to "IU (推荐)", "passive" to "Passive").forEach { (mode, label) ->
                                                val sel = settings.jvmShenandoahMode == mode
                                                Surface(
                                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { autoSave(settings.copy(jvmShenandoahMode = mode)) },
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                                ) {
                                                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        if (sel) Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                                        else Spacer(Modifier.size(16.dp))
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal))
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Text("ShenandoahHeapSizePercent: ${settings.jvmShenandoahHeapSizePercent}%", style = MaterialTheme.typography.bodySmall)
                                        Slider(value = settings.jvmShenandoahHeapSizePercent.toFloat(), onValueChange = { autoSave(settings.copy(jvmShenandoahHeapSizePercent = it.toInt())) }, valueRange = 5f..50f, steps = 44, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                                "ParallelGC" -> {
                                    SettingsSection("Parallel GC 精细调节", Icons.Filled.Tune) {
                                        Text("以下参数在 GC 策略为 ParallelGC 时生效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(10.dp))
                                        Text("ParallelGCThreads: ${if (settings.jvmParallelGCThreads == 0) "自动" else "${settings.jvmParallelGCThreads}"}", style = MaterialTheme.typography.bodySmall)
                                        Slider(value = settings.jvmParallelGCThreads.toFloat(), onValueChange = { autoSave(settings.copy(jvmParallelGCThreads = it.toInt())) }, valueRange = 0f..16f, steps = 15, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                                "SerialGC" -> {
                                    SettingsSection("Serial GC", Icons.Filled.Tune) {
                                        Text("SerialGC 为单线程 GC，无可用的精细调节参数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("内存与编译器", Icons.Filled.Speed) {
                                Text("MetaspaceSize: ${settings.jvmMetaspaceSize} MB", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.jvmMetaspaceSize.toFloat(), onValueChange = { autoSave(settings.copy(jvmMetaspaceSize = it.toInt())) }, valueRange = 64f..512f, steps = 27, modifier = Modifier.fillMaxWidth())
                                Text("ReservedCodeCacheSize: ${settings.jvmReservedCodeCache} MB", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.jvmReservedCodeCache.toFloat(), onValueChange = { autoSave(settings.copy(jvmReservedCodeCache = it.toInt())) }, valueRange = 64f..512f, steps = 27, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                SettingsToggleRow(title = "AlwaysPreTouch", subtitle = "JVM 启动时立即提交全部堆内存，消除运行时的懒分配停顿", checked = settings.jvmAlwaysPreTouch, onCheckedChange = { autoSave(settings.copy(jvmAlwaysPreTouch = it)) })
                                SettingsToggleRow(title = "大页内存 (LargePages)", subtitle = "使用 HugePage 减少 TLB miss，需要系统支持并以管理员运行", checked = settings.jvmUseLargePages, onCheckedChange = { autoSave(settings.copy(jvmUseLargePages = it)) })
                                SettingsToggleRow(title = "禁用显式 GC", subtitle = "-XX:+DisableExplicitGC，阻止游戏内 System.gc() 触发停顿", checked = settings.jvmDisableExplicitGC, onCheckedChange = { autoSave(settings.copy(jvmDisableExplicitGC = it)) })
                                SettingsToggleRow(title = "并行引用处理", subtitle = "-XX:+ParallelRefProcEnabled，G1GC 并行处理软/弱引用", checked = settings.jvmParallelRefProcEnabled, onCheckedChange = { autoSave(settings.copy(jvmParallelRefProcEnabled = it)) })
                                SettingsToggleRow(title = "字符串去重 (G1)", subtitle = "-XX:+UseStringDeduplication，减少堆内重复字符串内存占用", checked = settings.jvmStringDedup, onCheckedChange = { autoSave(settings.copy(jvmStringDedup = it)) })
                                SettingsToggleRow(title = "激进优化 (AggressiveOpts)", subtitle = "开启 JVM 实验性高级优化，仅 JDK 8 有效，可能不稳定", checked = settings.jvmEnableAggressiveOpts, onCheckedChange = { autoSave(settings.copy(jvmEnableAggressiveOpts = it)) })
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("JVM 运行时调节", Icons.Filled.Speed) {
                                Text("线程栈大小 (Xss): ${if (settings.jvmThreadStackSize == 0) "默认" else "${settings.jvmThreadStackSize} KB"}", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.jvmThreadStackSize.toFloat(), onValueChange = { autoSave(settings.copy(jvmThreadStackSize = it.toInt())) }, valueRange = 0f..2048f, steps = 31, modifier = Modifier.fillMaxWidth())
                                Text("MaxInlineSize: ${settings.jvmInlineSize}", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.jvmInlineSize.toFloat(), onValueChange = { autoSave(settings.copy(jvmInlineSize = it.toInt())) }, valueRange = 35f..1000f, steps = 96, modifier = Modifier.fillMaxWidth())
                                Text("FreqInlineSize: ${settings.jvmFreqInlineSize}", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.jvmFreqInlineSize.toFloat(), onValueChange = { autoSave(settings.copy(jvmFreqInlineSize = it.toInt())) }, valueRange = 35f..1000f, steps = 96, modifier = Modifier.fillMaxWidth())
                                Text("LoopUnrollingLimit: ${settings.jvmLoopUnrollingLimit}", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.jvmLoopUnrollingLimit.toFloat(), onValueChange = { autoSave(settings.copy(jvmLoopUnrollingLimit = it.toInt())) }, valueRange = 0f..250f, steps = 49, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                SettingsToggleRow(title = "分层编译 (TieredCompilation)", subtitle = "C1+C2 双层 JIT，加速冷启动同时保持峰值性能（强烈建议开启）", checked = settings.jvmTieredCompilation, onCheckedChange = { autoSave(settings.copy(jvmTieredCompilation = it)) })
                                SettingsToggleRow(title = "IEEE 严格浮点 (UseStrictFP)", subtitle = "强制 IEEE 754 浮点运算，影响极小，调试用途", checked = settings.jvmEnableIEEE, onCheckedChange = { autoSave(settings.copy(jvmEnableIEEE = it)) })
                                SettingsToggleRow(title = "原生内存追踪 (NMT)", subtitle = "输出 JVM 各子系统内存占用 summary，调试内存泄漏用，有轻微性能损耗", checked = settings.jvmNativeMemoryTracking, onCheckedChange = { autoSave(settings.copy(jvmNativeMemoryTracking = it)) })
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("游戏分辨率（覆盖窗口大小）", Icons.Filled.AspectRatio) {
                                Text("设置后覆盖上方窗口大小设定，0 表示使用窗口设定", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(value = if (settings.javaGameWidth == 0) "" else settings.javaGameWidth.toString(), onValueChange = { autoSave(settings.copy(javaGameWidth = it.toIntOrNull() ?: 0)) }, label = { Text("游戏宽度") }, placeholder = { Text("同窗口") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f))
                                    Spacer(Modifier.width(8.dp))
                                    Text("×", style = MaterialTheme.typography.titleLarge)
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedTextField(value = if (settings.javaGameHeight == 0) "" else settings.javaGameHeight.toString(), onValueChange = { autoSave(settings.copy(javaGameHeight = it.toIntOrNull() ?: 0)) }, label = { Text("游戏高度") }, placeholder = { Text("同窗口") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f))
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("原生库 & 快速启动", Icons.Filled.FlashOn) {
                                SettingsToggleRow(title = "使用原生 GLFW", subtitle = "传入 --useNativeGlfw，使用系统 GLFW 替代内置版本（实验性）", checked = settings.javaUseNativeGlfw, onCheckedChange = { autoSave(settings.copy(javaUseNativeGlfw = it)) })
                                SettingsToggleRow(title = "使用原生 OpenAL", subtitle = "传入 --useNativeOpenAL，使用系统 OpenAL 替代内置版本", checked = settings.javaUseNativeOpenAl, onCheckedChange = { autoSave(settings.copy(javaUseNativeOpenAl = it)) })
                                SettingsToggleRow(title = "跳过版本兼容性检查", subtitle = "跳过启动前的 JSON 校验，加快冷启动（损坏版本可能崩溃）", checked = settings.skipVersionCheck, onCheckedChange = { autoSave(settings.copy(skipVersionCheck = it)) })
                                SettingsToggleRow(title = "Demo 模式", subtitle = "传入 --demo 参数，以演示账号限时游玩，用于测试", checked = settings.launchDemoMode, onCheckedChange = { autoSave(settings.copy(launchDemoMode = it)) })
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("快速游戏 (QuickPlay)", Icons.Filled.PlayCircle) {
                                Text("填入后启动游戏时直接进入指定存档或服务器，留空则不生效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = settings.javaQuickPlaySingleplayer, onValueChange = { autoSave(settings.copy(javaQuickPlaySingleplayer = it)) }, label = { Text("快速进入存档") }, placeholder = { Text("存档名称") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = settings.javaQuickPlayMultiplayer, onValueChange = { autoSave(settings.copy(javaQuickPlayMultiplayer = it)) }, label = { Text("快速连接服务器") }, placeholder = { Text("ip:port") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("额外游戏参数", Icons.Filled.Terminal) {
                                OutlinedTextField(value = settings.javaExtraGameArgs, onValueChange = { autoSave(settings.copy(javaExtraGameArgs = it)) }, label = { Text("额外游戏参数") }, placeholder = { Text("--arg value ...") }, minLines = 2, maxLines = 3, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(4.dp))
                                Text("追加到游戏参数末尾，空格分隔", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("自定义 JVM 参数", Icons.Filled.Terminal) {
                                OutlinedTextField(
                                    value = settings.customJvmArgs,
                                    onValueChange = { newValue ->
                                        val detectedGc = parseGcFromArgs(newValue)
                                        autoSave(settings.copy(customJvmArgs = newValue, gcPolicy = detectedGc ?: settings.gcPolicy))
                                    },
                                    label = { Text("JVM 启动参数") }, minLines = 2, maxLines = 4,
                                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("空格分隔，GC 标志与精细调节参数将自动同步，此处可追加额外参数", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                            Spacer(Modifier.height(24.dp))
                        }

                        // ════════════════════════════════════════ 基岩版 ═
                        2 -> {
                            SettingsSection("启动优化", Icons.Filled.RocketLaunch) {
                                SettingsToggleRow(
                                    title = "快速启动 (预热)",
                                    subtitle = "选择版本后自动在后台完成存档切换和注册，点击启动时近乎瞬间完成",
                                    checked = settings.bedrockPreheatEnabled,
                                    onCheckedChange = { autoSave(settings.copy(bedrockPreheatEnabled = it)) },
                                )
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Spacer(Modifier.height(12.dp))
                                SettingsToggleRow(
                                    title = "快速版本切换",
                                    subtitle = "版本切换时仅修改 Junction 指向，不复制文件（<100ms）",
                                    checked = settings.bedrockFastSwitchEnabled,
                                    onCheckedChange = { autoSave(settings.copy(bedrockFastSwitchEnabled = it)) },
                                )
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Spacer(Modifier.height(12.dp))
                                SettingsToggleRow(
                                    title = "命中缓存时跳过注册",
                                    subtitle = "预热命中后直接激活，跳过 Add-AppxPackage，进一步降低启动耗时",
                                    checked = settings.bedrockSkipRegistrationIfCached,
                                    onCheckedChange = { autoSave(settings.copy(bedrockSkipRegistrationIfCached = it)) },
                                )
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Spacer(Modifier.height(12.dp))
                                SettingsToggleRow(
                                    title = "强制每次重新注册",
                                    subtitle = "每次启动都重新执行 Add-AppxPackage，适合调试包损坏问题（会变慢）",
                                    checked = settings.bedrockForceRegisterEveryLaunch,
                                    onCheckedChange = { autoSave(settings.copy(bedrockForceRegisterEveryLaunch = it)) },
                                )
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Spacer(Modifier.height(12.dp))
                                Text("包信息缓存有效期: ${settings.bedrockPackageCacheTtlMinutes} 分钟", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = settings.bedrockPackageCacheTtlMinutes.toFloat(),
                                    onValueChange = { autoSave(settings.copy(bedrockPackageCacheTtlMinutes = it.toInt())) },
                                    valueRange = 1f..30f, steps = 28, modifier = Modifier.fillMaxWidth(),
                                )
                                Text("Get-AppxPackage 查询结果的内存缓存时长，越长启动越快但包变更后可能需要手动刷新", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("数据隔离", Icons.Filled.Shield) {
                                SettingsToggleRow(
                                    title = "版本存档隔离",
                                    subtitle = "每个版本独立保存存档、行为包、资源包，切换版本不会混用数据",
                                    checked = settings.bedrockVersionIsolation,
                                    onCheckedChange = { autoSave(settings.copy(bedrockVersionIsolation = it)) },
                                )
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Spacer(Modifier.height(12.dp))
                                SettingsToggleRow(
                                    title = "首次启动自动迁移数据",
                                    subtitle = "首次切换版本时自动把原 com.mojang 数据迁移到版本专属 profile，保护历史存档",
                                    checked = settings.bedrockAutoMigrateOnFirstLaunch,
                                    onCheckedChange = { autoSave(settings.copy(bedrockAutoMigrateOnFirstLaunch = it)) },
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "版本数据路径：%LOCALAPPDATA%/Packages/Microsoft.MinecraftUWP_.../LocalState/md3l_profiles/<版本号>/com.mojang",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("版本管理高级选项", Icons.Filled.ManageAccounts) {
                                Text("并行安装任务上限: ${settings.bedrockMaxParallelInstalls}", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.bedrockMaxParallelInstalls.toFloat(), onValueChange = { autoSave(settings.copy(bedrockMaxParallelInstalls = it.toInt())) }, valueRange = 1f..4f, steps = 2, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                SettingsToggleRow(title = "安装后保留缓存文件", subtitle = "安装完成后不删除缓存目录中的 .appx/.msixvc，方便重复安装同版本", checked = settings.bedrockKeepCacheAfterInstall, onCheckedChange = { autoSave(settings.copy(bedrockKeepCacheAfterInstall = it)) })
                                SettingsToggleRow(title = "跳过哈希验证", subtitle = "安装前不校验下载包完整性，加快安装但可能安装损坏包（不推荐）", checked = settings.bedrockSkipHashVerify, onCheckedChange = { autoSave(settings.copy(bedrockSkipHashVerify = it)) })
                                SettingsToggleRow(title = "Junction 失败时复制文件", subtitle = "当 mklink /J 建立失败时回退为 Robocopy 文件复制（跨盘符或权限不足时有用）", checked = settings.bedrockJunctionFallbackCopy, onCheckedChange = { autoSave(settings.copy(bedrockJunctionFallbackCopy = it)) })
                                SettingsToggleRow(title = "启动器打开时迁移旧 profiles", subtitle = "检测并将旧 bedrock_profiles 目录数据合并到对应 bedrock_versions 目录", checked = settings.bedrockMigrateFromProfilesOnOpen, onCheckedChange = { autoSave(settings.copy(bedrockMigrateFromProfilesOnOpen = it)) })
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("存档路径配置", Icons.Filled.FolderSpecial) {
                                Text("默认情况下，存档放在 UWP 包目录（%LOCALAPPDATA%/Packages/.../LocalState/md3l_profiles）。设置自定义路径可避免跨盘符拷贝。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = settings.bedrockProfilesDir,
                                        onValueChange = { autoSave(settings.copy(bedrockProfilesDir = it)) },
                                        label = { Text("存档根目录") },
                                        placeholder = { Text("空 = 自动（UWP 包目录）") },
                                        singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    FilledTonalButton(
                                        onClick = {
                                            scope.launch {
                                                val chosen = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    val chooser = javax.swing.JFileChooser(System.getProperty("user.home"))
                                                    chooser.dialogTitle = "选择存档根目录"
                                                    chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                                                    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
                                                }
                                                if (chosen != null) autoSave(settings.copy(bedrockProfilesDir = chosen), immediate = true)
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                    ) { Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("选择") }
                                    if (settings.bedrockProfilesDir.isNotBlank()) {
                                        Spacer(Modifier.width(6.dp))
                                        FilledTonalButton(
                                            onClick = { autoSave(settings.copy(bedrockProfilesDir = ""), immediate = true) },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                        ) { Icon(Icons.Filled.Clear, null, modifier = Modifier.size(16.dp)) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("游戏行为注入（进阶）", Icons.Filled.Gamepad) {
                                Text("以下选项通过修改 options.txt 注入到游戏设置文件，启动前生效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                SettingsToggleRow(title = "显示坐标", subtitle = "注入 show_coordinates=1 到游戏设置", checked = settings.bedrockShowCoordinates, onCheckedChange = { autoSave(settings.copy(bedrockShowCoordinates = it)) })
                                SettingsToggleRow(title = "隐藏 HUD", subtitle = "注入 hide_hud=1，适合录屏/截图用途", checked = settings.bedrockHideHud, onCheckedChange = { autoSave(settings.copy(bedrockHideHud = it)) })
                                SettingsToggleRow(title = "允许使用指令", subtitle = "注入 allow_cheats=1，新地图启用指令模式", checked = settings.bedrockAllowCheats, onCheckedChange = { autoSave(settings.copy(bedrockAllowCheats = it)) })
                                SettingsToggleRow(title = "静音启动", subtitle = "注入 audio_master_volume=0，适合后台运行场景", checked = settings.bedrockMuteSounds, onCheckedChange = { autoSave(settings.copy(bedrockMuteSounds = it)) })
                                Spacer(Modifier.height(8.dp))
                                Text("帧率上限: ${if (settings.bedrockFpsLimit == 0) "不限制" else "${settings.bedrockFpsLimit} FPS"}", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.bedrockFpsLimit.toFloat(), onValueChange = { autoSave(settings.copy(bedrockFpsLimit = it.toInt())) }, valueRange = 0f..240f, steps = 23, modifier = Modifier.fillMaxWidth())
                                Text("视距: ${if (settings.bedrockRenderDistance == 0) "游戏默认" else "${settings.bedrockRenderDistance} 区块"}", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.bedrockRenderDistance.toFloat(), onValueChange = { autoSave(settings.copy(bedrockRenderDistance = it.toInt())) }, valueRange = 0f..64f, steps = 63, modifier = Modifier.fillMaxWidth())
                                Text("模拟距离: ${if (settings.bedrockSimulationDistance == 0) "游戏默认" else "${settings.bedrockSimulationDistance} 区块"}", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.bedrockSimulationDistance.toFloat(), onValueChange = { autoSave(settings.copy(bedrockSimulationDistance = it.toInt())) }, valueRange = 0f..12f, steps = 11, modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("调试", Icons.Filled.BugReport) {
                                SettingsToggleRow(
                                    title = "显示注册日志",
                                    subtitle = "在启动日志窗口输出 Add-AppxPackage 完整 PowerShell 日志",
                                    checked = settings.bedrockShowRegistrationLog,
                                    onCheckedChange = { autoSave(settings.copy(bedrockShowRegistrationLog = it)) },
                                )
                            }
                            Spacer(Modifier.height(12.dp))

                            SettingsSection("关于基岩版支持", Icons.Filled.Info) {
                                Text(
                                    "MD3L 通过 Add-AppxPackage -Register 旁载方式管理基岩版，支持多版本并行安装。" +
                                    "GDK 版本（1.21.120.21+）正版账号可直接启动；离线/第三方账号需 -Register 注册激活。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Text(
                                        "基岩版版本数据来源: mcappx.com",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
        if (showTutorial) {
            SettingsTutorialDialog(onDismiss = { showTutorial = false })
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun openExternalUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

private fun parseGcFromArgs(args: String): String? {
    val gcFlags = mapOf(
        "UseG1GC" to "G1GC",
        "UseZGC" to "ZGC",
        "UseShenandoahGC" to "ShenandoahGC",
        "UseParallelGC" to "ParallelGC",
        "UseSerialGC" to "SerialGC",
    )
    val tokens = args.split("\\s+".toRegex())
    for (token in tokens) {
        gcFlags.forEach { (flag, name) ->
            if (token.contains(flag)) return name
        }
    }
    return null
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

// ═══════════════════════════════════════════════════════════════════════════════
// 调整教程对话框
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTutorialDialog(onDismiss: () -> Unit) {
    val isEn = ThemeState.language == "en"
    var tab by remember { mutableIntStateOf(0) }
    val tabs = if (isEn) listOf("General", "Java", "Bedrock")
               else listOf("通用", "Java 版", "基岩版")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = 800.dp).fillMaxHeight(0.92f),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 标题栏 ────────────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.School, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (isEn) "Settings Tutorial" else "调整教程",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            if (isEn) "Detailed explanation of every option" else "每一项设置的详细说明",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
                // ── 标签切换 ──────────────────────────────────────────────────────
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) {
                    tabs.forEachIndexed { i, label ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = { Text(label, style = MaterialTheme.typography.labelLarge) },
                        )
                    }
                }
                HorizontalDivider()
                // ── 内容区域 ────────────────────────────────────────────────────────
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                    val scrollState = rememberScrollState()
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(vertical = 12.dp, horizontal = 4.dp)) {
                        when (tab) {
                            // ════════════════════════════════════════════ 通用 ═══
                            0 -> GeneralTutorial(isEn)
                            // ════════════════════════════════════════════ Java 版 ═══
                            1 -> JavaTutorial(isEn)
                            // ════════════════════════════════════════════ 基岩版 ═══
                            2 -> BedrockTutorial(isEn)
                        }
                        Spacer(Modifier.height(80.dp))
                    }
                }
            }
        }
    }
}

// ── 通用设置教程 ─────────────────────────────────────────────────────────────
@Composable
private fun GeneralTutorial(isEn: Boolean) {
    TutorialGroup(icon = Icons.Filled.Palette, title = if (isEn) "Appearance" else "外观") {
        TutorialItem(
            name = if (isEn) "Language" else "语言",
            type = if (isEn) "Toggle" else "二选一",
            desc = if (isEn)
                "Switch between Chinese and English interface. Takes effect immediately without restart."
            else
                "在中文和英文界面之间切换。修改后即时生效，无需重启。",
            recommend = if (isEn) "Choose your preferred language" else "根据你的语言习惯选择",
        )
        TutorialItem(
            name = if (isEn) "Theme Mode" else "主题模式",
            type = if (isEn) "Toggle" else "深色/浅色切换",
            desc = if (isEn)
                "Dark theme reduces eye strain in low-light environments; light theme is clearer in bright environments. Affects the main color scheme of the launcher."
            else
                "深色主题在低光环境下减少眼部疲劳；浅色主题在明亮环境下更清晰。影响启动器整体配色。",
            recommend = if (isEn) "Dark（深色）" else "Dark（深色）",
        )
        TutorialItem(
            name = if (isEn) "Accent Color" else "强调色",
            type = if (isEn) "Color Picker" else "颜色选择器",
            desc = if (isEn)
                "The primary accent color of the launcher. Affects buttons, selected states, links, and highlights. Based on Material Design 3 dynamic color palette."
            else
                "启动器的主色调强调色。影响按钮、选中状态、链接和高亮显示。基于 Material Design 3 动态颜色方案。",
            recommend = if (isEn) "Pick one that fits your style" else "选择你喜欢的颜色即可",
        )
        TutorialItem(
            name = if (isEn) "Background Image" else "背景图片",
            type = if (isEn) "File Picker" else "文件选择",
            desc = if (isEn)
                "Set a custom background image for the launcher. Supports PNG/JPG formats. After selecting, you can adjust blur, brightness, and panel opacity below."
            else
                "为启动器设置自定义背景图片。支持 PNG/JPG 格式。选择后可配合下方的模糊、亮度和面板透明度进行调整。",
            recommend = if (isEn) "Optional, clear images work best" else "可选，建议使用较清晰的图片",
        )
        TutorialItem(
            name = if (isEn) "BG Blur Radius" else "背景模糊半径",
            type = if (isEn) "Slider (0-50)" else "滑块（0-50）",
            desc = if (isEn)
                "Controls the Gaussian blur strength of the background image. 0 = no blur, 50 = maximum blur. Higher values create a softer, more abstract background."
            else
                "控制背景图片的高斯模糊强度。0=无模糊，50=最大模糊。值越高背景越柔和、越抽象。建议值：20。",
            recommend = if (isEn) "20 (balanced)" else "20（平衡）",
        )
        TutorialItem(
            name = if (isEn) "BG Brightness" else "背景亮度",
            type = if (isEn) "Slider (0-1)" else "滑块（0-1）",
            desc = if (isEn)
                "Darkens the background image to improve text readability. 0 = completely black, 1 = original image. Combine with blur for a glass-like effect."
            else
                "压暗背景图片以增强文字可读性。0=全黑，1=原图亮度。配合模糊可实现毛玻璃效果。建议值：0.75。",
            recommend = if (isEn) "0.75 (recommended)" else "0.75（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Panel Opacity" else "面板不透明度",
            type = if (isEn) "Slider (0-1)" else "滑块（0-1）",
            desc = if (isEn)
                "Controls the transparency of panels, cards, and navigation surfaces. 0 = fully transparent, 1 = fully opaque. Lower values show more background."
            else
                "控制面板、卡片和导航区域的透明度。0=全透明，1=完全不透明。值越低背景越明显。建议值：0.75。",
            recommend = if (isEn) "0.75 (recommended)" else "0.75（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Animation Speed" else "动画速度",
            type = if (isEn) "Slider (0.3-3)" else "滑块（0.3-3）",
            desc = if (isEn)
                "Multiplier for all UI animation durations. 0.3x = very fast (minimal animation), 1x = standard speed, 3x = slow and dramatic."
            else
                "所有界面动画时长的倍率。0.3x=极快（几乎无动画），1x=标准速度，3x=缓慢华丽。建议值：1.0。",
            recommend = if (isEn) "1.0 (standard)" else "1.0（标准）",
        )
        TutorialItem(
            name = if (isEn) "Font Scale" else "字体缩放",
            type = if (isEn) "Slider (0.7-1.5)" else "滑块（0.7-1.5）",
            desc = if (isEn)
                "Global font size multiplier. 0.7x = smaller (shows more content), 1x = default size, 1.5x = larger (better readability)."
            else
                "全局字体大小倍率。0.7x=更小（显示更多内容），1x=默认大小，1.5x=更大（更易阅读）。",
            recommend = if (isEn) "1.0 (default)" else "1.0（默认）",
        )
        TutorialItem(
            name = if (isEn) "Compact Mode" else "紧凑模式",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Reduces spacing between UI elements to display more information on screen. Useful on smaller displays."
            else
                "减少界面元素之间的间距，在有限空间内显示更多信息。适合小屏幕使用。",
            recommend = if (isEn) "Off by default" else "默认关闭",
        )
        TutorialItem(
            name = if (isEn) "Corner Radius" else "圆角大小",
            type = if (isEn) "Slider (4-28)" else "滑块（4-28）",
            desc = if (isEn)
                "Global corner radius for cards, panels, dialogs, and buttons. 4 = sharp/almost square, 28 = very rounded/pill-shaped."
            else
                "所有卡片、面板、对话框和按钮的全局圆角半径。4=接近直角，28=非常圆润。建议值：16。",
            recommend = if (isEn) "16 (MD3 standard)" else "16（MD3 标准）",
        )
        TutorialItem(
            name = if (isEn) "Sidebar Width" else "侧边栏宽度",
            type = if (isEn) "Slider (60-160 dp)" else "滑块（60-160 dp）",
            desc = if (isEn)
                "Width of the sidebar navigation. Only applies when Navigation Mode is set to Sidebar. Width is in dp (density-independent pixels)."
            else
                "侧边导航栏的宽度。仅在导航方式设置为「侧边栏」时生效。单位为 dp。建议值：80。",
            recommend = if (isEn) "80 (recommended)" else "80（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Show Version Badge" else "显示版本徽章",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Displays the current app version number at the bottom of the sidebar for easy reference."
            else
                "在侧边栏底部显示当前应用版本号，方便查看。",
            recommend = if (isEn) "On" else "推荐开启",
        )
        TutorialItem(
            name = if (isEn) "Show Log Entry" else "显示日志入口",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Adds a log viewer button to the navigation. Useful for troubleshooting and monitoring launch processes."
            else
                "在导航栏添加日志查看按钮。排查问题和监控启动过程时很有用。",
            recommend = if (isEn) "Off by default" else "默认关闭，需要时开启",
        )
    }

    TutorialGroup(icon = Icons.Filled.DirectionsBoat, title = if (isEn) "Navigation" else "导航") {
        TutorialItem(
            name = if (isEn) "Navigation Mode" else "导航方式",
            type = if (isEn) "Dropdown" else "下拉选择",
            desc = if (isEn)
                "Sidebar: classic left-side navigation bar. Floating: bottom floating navigation bar that auto-hides when scrolling to the bottom of a page."
            else
                "侧边栏：经典左侧导航栏。浮动底栏：底部浮动导航栏，滚动到页面底端时自动淡出隐藏。",
            recommend = if (isEn) "Sidebar for desktop, Floating for compact" else "侧边栏适合大屏，浮动底栏适合紧凑布局",
        )
        TutorialItem(
            name = if (isEn) "Floating: Bottom Margin" else "浮动底栏：底边距",
            type = if (isEn) "Slider (4-60 dp)" else "滑块（4-60 dp）",
            desc = if (isEn)
                "Distance between the floating nav bar and the bottom edge of the window."
            else
                "浮动导航栏与窗口底部的距离。建议值：12 dp。",
            recommend = if (isEn) "12 (recommended)" else "12（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Floating: Side Margin" else "浮动底栏：侧边距",
            type = if (isEn) "Slider (4-60 dp)" else "滑块（4-60 dp）",
            desc = if (isEn)
                "Horizontal padding on left and right sides of the floating nav bar."
            else
                "浮动导航栏左右两侧的水平内边距。建议值：16 dp。",
            recommend = if (isEn) "16 (recommended)" else "16（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Floating: Corner Radius" else "浮动底栏：圆角",
            type = if (isEn) "Slider (8-40 dp)" else "滑块（8-40 dp）",
            desc = if (isEn)
                "Corner radius of the floating navigation bar. Higher values make it more pill-shaped."
            else
                "浮动导航栏的圆角半径。值越高越像胶囊形。建议值：24 dp。",
            recommend = if (isEn) "24 (recommended)" else "24（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Floating: Height" else "浮动底栏：高度",
            type = if (isEn) "Slider (44-92 dp)" else "滑块（44-92 dp）",
            desc = if (isEn)
                "Height of the floating navigation bar. Taller values provide larger touch targets."
            else
                "浮动导航栏的高度。越高的值触控区域越大。建议值：64 dp。",
            recommend = if (isEn) "64 (recommended)" else "64（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Floating: Show Labels" else "浮动底栏：显示标签",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Shows text labels below navigation icons in floating mode. Disable for a cleaner look with only icons."
            else
                "在浮动模式下图标下方显示文字标签。关闭后只显示图标，更简洁。",
            recommend = if (isEn) "On" else "推荐开启",
        )
        TutorialItem(
            name = if (isEn) "Startup Page" else "启动页",
            type = if (isEn) "Dropdown" else "下拉选择",
            desc = if (isEn)
                "Which page to show by default when the launcher starts: Launch (main), Versions (version management), or Download (mod/resource pack download)."
            else
                "启动器启动时默认显示的页面：启动（主页）、版本（版本管理）或下载（模组/资源包下载）。",
            recommend = if (isEn) "Launch" else "Launch（启动）",
        )
    }

    TutorialGroup(icon = Icons.Filled.Tune, title = if (isEn) "Behavior" else "行为") {
        TutorialItem(
            name = if (isEn) "Close After Launch" else "启动后关闭",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Automatically closes the launcher after starting a game. Reduces memory usage during gameplay."
            else
                "启动游戏后自动关闭启动器。节省游戏运行时的内存占用。",
            recommend = if (isEn) "Off" else "默认关闭",
        )
        TutorialItem(
            name = if (isEn) "Check Update on Startup" else "启动检查更新",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Automatically checks for new launcher versions on startup. Disable if you want faster startup times."
            else
                "启动时自动检查启动器新版本。关闭可加快启动速度。",
            recommend = if (isEn) "On" else "推荐开启",
        )
        TutorialItem(
            name = if (isEn) "Log Retention Days" else "日志保留天数",
            type = if (isEn) "Slider (1-90)" else "滑块（1-90）",
            desc = if (isEn)
                "How many days to keep log files before automatic cleanup. Logs are stored in the dist/log/ directory."
            else
                "日志文件自动清理前的保留天数。日志存储在 dist/log/ 目录下。",
            recommend = if (isEn) "7 (recommended)" else "7（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Confirm Before Close" else "关闭前确认",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Shows a confirmation dialog when closing the launcher to prevent accidental exit."
            else
                "关闭启动器时弹出确认对话框，防止误操作退出。",
            recommend = if (isEn) "On" else "推荐开启",
        )
        TutorialItem(
            name = if (isEn) "HTTP Proxy Host" else "HTTP 代理地址",
            type = if (isEn) "Text Input" else "文本输入",
            desc = if (isEn)
                "HTTP proxy server address for network requests (e.g., 127.0.0.1 or proxy.example.com). Leave empty if not using a proxy."
            else
                "网络请求的 HTTP 代理服务器地址（如 127.0.0.1 或 proxy.example.com）。不使用代理时留空。",
            recommend = if (isEn) "Leave empty" else "留空（不使用代理）",
        )
        TutorialItem(
            name = if (isEn) "HTTP Proxy Port" else "HTTP 代理端口",
            type = if (isEn) "Number Input" else "数字输入",
            desc = if (isEn)
                "Port number of the proxy server (e.g., 7890, 10809). Only valid when a proxy host is set."
            else
                "代理服务器的端口号（如 7890、10809）。仅在设置了代理地址时生效。",
            recommend = if (isEn) "0 (disabled)" else "0（未启用）",
        )
        TutorialItem(
            name = if (isEn) "Network Timeout" else "网络超时",
            type = if (isEn) "Slider (5-120s)" else "滑块（5-120 秒）",
            desc = if (isEn)
                "Maximum time to wait for a network response before giving up. Higher values help with slow connections."
            else
                "网络请求的最大等待时间。值越高对慢速网络越友好。建议值：30 秒。",
            recommend = if (isEn) "30 (recommended)" else "30（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Max Download Threads" else "最大下载线程数",
            type = if (isEn) "Slider (1-128)" else "滑块（1-128）",
            desc = if (isEn)
                "Number of parallel download threads for version files, mods, and resource packs. Higher values = faster downloads but more bandwidth usage."
            else
                "版本文件、模组和资源包的并行下载线程数。值越高下载越快，但占用更多带宽。建议值：64。",
            recommend = if (isEn) "64 (balanced)" else "64（平衡）",
        )
        TutorialItem(
            name = if (isEn) "Download Mirror" else "下载镜像源",
            type = if (isEn) "Dropdown" else "下拉选择",
            desc = if (isEn)
                "BMCLAPI: recommended mirror in China for fast downloads. Mojang: official source, may be slow in some regions."
            else
                "BMCLAPI：国内推荐镜像源，下载速度快。Mojang：官方源，部分地区可能较慢。",
            recommend = if (isEn) "BMCLAPI (recommended for CN)" else "BMCLAPI（国内推荐）",
        )
    }
}

// ── Java 版设置教程 ─────────────────────────────────────────────────────────
@Composable
private fun JavaTutorial(isEn: Boolean) {
    TutorialGroup(icon = Icons.Filled.FolderOpen, title = if (isEn) "Basic" else "基础") {
        TutorialItem(
            name = if (isEn) "Minecraft Directory" else "Minecraft 目录",
            type = if (isEn) "Directory Picker" else "目录选择",
            desc = if (isEn)
                "The root directory where Minecraft versions, saves, resource packs, and mods are stored. Default is the .minecraft folder."
            else
                "Minecraft 游戏根目录，存放版本、存档、资源包和模组。默认为 .minecraft 文件夹。",
            recommend = if (isEn) "Default .minecraft or custom path" else "默认 .minecraft 或自定义路径",
        )
        TutorialItem(
            name = if (isEn) "Java Path" else "Java 路径",
            type = if (isEn) "Dropdown + Scan" else "下拉选择 + 扫描",
            desc = if (isEn)
                "Java Runtime Environment path. MD3L auto-scans for installed Java. Java 17+ is recommended. Click the scan button to refresh."
            else
                "Java 运行环境路径。MD3L 会自动扫描已安装的 Java。建议使用 Java 17+。点击扫描按钮可刷新列表。",
            recommend = if (isEn) "Java 17+ (Java 8 for old versions)" else "Java 17+（老版本用 Java 8）",
        )
        TutorialItem(
            name = if (isEn) "Memory (MB)" else "内存分配（MB）",
            type = if (isEn) "Slider (512-32768)" else "滑块（512-32768）",
            desc = if (isEn)
                "Amount of RAM allocated to Minecraft. 4096 MB is sufficient for most modpacks. Increase for heavy modpacks with 100+ mods."
            else
                "分配给 Minecraft 的内存量。4096 MB 对大多数整合包够用。含 100+ 模组的大型整合包可适当增加。",
            recommend = if (isEn) "4096 (default), 6144-8192 for modpacks" else "4096（默认），整合包用 6144-8192",
        )
        TutorialItem(
            name = if (isEn) "Custom JVM Args" else "自定义 JVM 参数",
            type = if (isEn) "Text Input" else "文本输入",
            desc = if (isEn)
                "Additional JVM arguments passed to the game process. Advanced users only. The GC flag here is automatically parsed for the GC Policy selector."
            else
                "传递给游戏进程的额外 JVM 参数。仅限高级用户。此处的 GC 标志会自动同步到 GC 策略选择器。",
            recommend = if (isEn) "Default args are fine for most users" else "默认参数对大多数用户已足够",
        )
    }

    TutorialGroup(icon = Icons.Filled.Memory, title = if (isEn) "Garbage Collection" else "垃圾回收 (GC)") {
        TutorialItem(
            name = if (isEn) "GC Policy" else "GC 策略",
            type = if (isEn) "Dropdown" else "下拉选择",
            desc = if (isEn)
                "G1GC: balanced throughput & latency, best for most setups. ZGC: ultra-low pause (<1ms), needs >=16GB RAM. Shenandoah: concurrent compaction. ParallelGC: max throughput. SerialGC: single-threaded, minimal overhead."
            else
                "G1GC：吞吐量与延迟平衡，适合大多数配置。ZGC：超低暂停（<1ms），需要 >=16GB 内存。Shenandoah：并发压缩。ParallelGC：最大吞吐量。SerialGC：单线程，最低开销。",
            recommend = if (isEn) "G1GC (default) or ZGC (if you have enough RAM)" else "G1GC（默认）或 ZGC（内存充足时）",
        )
        TutorialItem(
            name = if (isEn) "Metaspace Size (MB)" else "元空间大小（MB）",
            type = if (isEn) "Slider (64-1024)" else "滑块（64-1024）",
            desc = if (isEn)
                "-XX:MetaspaceSize. Controls the initial class metadata allocation. Larger values reduce class loading overhead for modded environments."
            else
                "-XX:MetaspaceSize。控制初始类元数据分配。较大值可减少模组环境的类加载开销。建议值：256 MB。",
            recommend = if (isEn) "256 (recommended for modded)" else "256（模组环境推荐）",
        )
        TutorialItem(
            name = if (isEn) "Reserved Code Cache (MB)" else "保留代码缓存（MB）",
            type = if (isEn) "Slider (64-512)" else "滑块（64-512）",
            desc = if (isEn)
                "-XX:ReservedCodeCacheSize. JIT compiled code cache. Larger values prevent JIT from stopping early in large modpacks."
            else
                "-XX:ReservedCodeCacheSize。JIT 编译代码缓存。较大值可防止大型整合包中 JIT 过早停止。建议值：256 MB。",
            recommend = if (isEn) "256 (recommended)" else "256（推荐）",
        )
        TutorialItem(
            name = if (isEn) "G1 New Size %" else "G1 新生代 %",
            type = if (isEn) "Slider (5-60)" else "滑块（5-60）",
            desc = if (isEn)
                "-XX:G1NewSizePercent. Initial young generation size as percentage of heap. Only applies to G1GC. Higher = more frequent minor GC."
            else
                "-XX:G1NewSizePercent。初始年轻代占堆的百分比。仅 G1GC 生效。建议值：20%。",
            recommend = if (isEn) "20 (default)" else "20（默认）",
        )
        TutorialItem(
            name = if (isEn) "G1 Max New Size %" else "G1 最大新生代 %",
            type = if (isEn) "Slider (10-80)" else "滑块（10-80）",
            desc = if (isEn)
                "-XX:G1MaxNewSizePercent. Maximum young generation size. Higher = more objects collected in minor GC, less in mixed GC."
            else
                "-XX:G1MaxNewSizePercent。最大年轻代大小。建议值：50%。",
            recommend = if (isEn) "50 (default)" else "50（默认）",
        )
        TutorialItem(
            name = if (isEn) "G1 Heap Region Size (MB)" else "G1 堆区域大小（MB）",
            type = if (isEn) "Slider (1-64)" else "滑块（1-64）",
            desc = if (isEn)
                "-XX:G1HeapRegionSize. Size of each G1 region. Affects humongous object allocation. Must be a power of 2."
            else
                "-XX:G1HeapRegionSize。每个 G1 区域的大小，影响大对象分配。建议值：16 MB。",
            recommend = if (isEn) "16 (balanced)" else "16（平衡）",
        )
        TutorialItem(
            name = if (isEn) "G1 GC Pause Target (ms)" else "G1 GC 暂停目标（ms）",
            type = if (isEn) "Slider (10-500)" else "滑块（10-500）",
            desc = if (isEn)
                "-XX:MaxGCPauseMillis. Target max GC pause time. Lower = more GC cycles but smoother gameplay. Higher = less CPU overhead but bigger spikes."
            else
                "-XX:MaxGCPauseMillis。GC 最大暂停时间目标。值越低 GC 越频繁但游戏更流畅。建议值：50 ms。",
            recommend = if (isEn) "50 (recommended)" else "50（推荐）",
        )
        TutorialItem(
            name = if (isEn) "ZGC: Uncommit Delay (s)" else "ZGC：取消提交延迟（秒）",
            type = if (isEn) "Slider (30-600)" else "滑块（30-600）",
            desc = if (isEn)
                "-XX:ZUncommitDelay. How long unused memory is kept before being returned to OS. Only for ZGC."
            else
                "-XX:ZUncommitDelay。未使用内存在返还给 OS 前的保留时间。仅 ZGC 生效。建议值：60 秒。",
            recommend = if (isEn) "60 (default)" else "60（默认）",
        )
        TutorialItem(
            name = if (isEn) "Conc GC Threads" else "并发 GC 线程数",
            type = if (isEn) "Slider (0-16)" else "滑块（0-16）",
            desc = if (isEn)
                "-XX:ConcGCThreads. 0 = auto (JVM detects). Manual control for tuning concurrent GC phases."
            else
                "-XX:ConcGCThreads。0=自动（JVM 检测）。手动调整并发 GC 阶段的线程数。",
            recommend = if (isEn) "0 (auto)" else "0（自动）",
        )
    }

    TutorialGroup(icon = Icons.Filled.Timer, title = if (isEn) "JVM Runtime" else "JVM 运行时") {
        TutorialItem(
            name = if (isEn) "Thread Stack Size (KB)" else "线程栈大小（KB）",
            type = if (isEn) "Slider (0-4096)" else "滑块（0-4096）",
            desc = if (isEn)
                "-Xss. Stack size per thread. 0 = JVM default. Increase if you see StackOverflowError in complex modpacks."
            else
                "-Xss。每个线程的栈大小。0=JVM 默认。遇到 StackOverflowError 时增加。",
            recommend = if (isEn) "0 (default)" else "0（默认）",
        )
        TutorialItem(
            name = if (isEn) "Enable JIT" else "启用 JIT",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "-Xint vs default. Disabling JIT forces pure interpreted mode (very slow). Only disable for debugging."
            else
                "关闭后强制纯解释模式（极慢）。仅调试时关闭。",
            recommend = if (isEn) "On (default)" else "开启（默认）",
        )
        TutorialItem(
            name = if (isEn) "Tiered Compilation" else "分层编译",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "-XX:-TieredCompilation. Disabling reduces startup time but lowers peak performance. Enable for best long-run performance."
            else
                "关闭可减少启动时间但降低峰值性能。建议开启以获得最佳长期性能。",
            recommend = if (isEn) "On (recommended)" else "开启（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Max Inline Size" else "最大内联大小",
            type = if (isEn) "Slider (50-500)" else "滑块（50-500）",
            desc = if (isEn)
                "-XX:MaxInlineSize. Max bytecode size for method inlining. Higher = more aggressive inlining, potentially faster but larger code cache."
            else
                "-XX:MaxInlineSize。方法内联的最大字节码大小。建议值：325。",
            recommend = if (isEn) "325 (default)" else "325（默认）",
        )
        TutorialItem(
            name = if (isEn) "Freq Inline Size" else "频繁内联大小",
            type = if (isEn) "Slider (50-500)" else "滑块（50-500）",
            desc = if (isEn)
                "-XX:FreqInlineSize. Similar to MaxInlineSize but for frequently called methods. Balanced with MaxInlineSize."
            else
                "-XX:FreqInlineSize。类似 MaxInlineSize，但针对频繁调用的方法。建议值：325。",
            recommend = if (isEn) "325 (default)" else "325（默认）",
        )
        TutorialItem(
            name = if (isEn) "Always PreTouch" else "预触内存",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "-XX:+AlwaysPreTouch. Pre-commits all heap memory at startup. Slower startup but better runtime performance with no lazy commit pauses."
            else
                "-XX:+AlwaysPreTouch。启动时预先提交所有堆内存。启动稍慢但运行时无延迟提交暂停。建议开启。",
            recommend = if (isEn) "On (recommended)" else "开启（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Use Large Pages" else "大内存页",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "-XX:+UseLargePages. Uses OS large memory pages for heap. Can improve performance but requires OS configuration (Windows needs Lock Pages in Memory privilege)."
            else
                "-XX:+UseLargePages。使用操作系统大内存页。可能提升性能，但需要系统配置（Windows 需要赋予 Lock Pages in Memory 权限）。",
            recommend = if (isEn) "Off (unless configured in OS)" else "关闭（除非已配置系统）",
        )
        TutorialItem(
            name = if (isEn) "Disable Explicit GC" else "禁用显式 GC",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "-XX:+DisableExplicitGC. Prevents System.gc() calls from triggering full GC. Some mods call System.gc() aggressively."
            else
                "-XX:+DisableExplicitGC。阻止 System.gc() 调用触发 Full GC。某些模组会频繁调用 System.gc()。建议开启。",
            recommend = if (isEn) "On (recommended)" else "开启（推荐）",
        )
        TutorialItem(
            name = if (isEn) "String Dedup" else "字符串去重",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "-XX:+UseStringDeduplication. Deduplicates identical strings in heap to save memory. Beneficial for large modpacks."
            else
                "-XX:+UseStringDeduplication。堆中相同字符串去重以节省内存。大型整合包有益。建议开启。",
            recommend = if (isEn) "On (recommended)" else "开启（推荐）",
        )
    }

    TutorialGroup(icon = Icons.Filled.PlayCircle, title = if (isEn) "Launch Behavior" else "启动行为") {
        TutorialItem(
            name = if (isEn) "Game Window Width/Height" else "游戏窗口宽/高",
            type = if (isEn) "Number Input" else "数字输入",
            desc = if (isEn)
                "Custom resolution for the game window. 0 = use game's default settings. Does not affect fullscreen mode."
            else
                "游戏窗口的自定义分辨率。0=使用游戏默认设置。不影响全屏模式。",
            recommend = if (isEn) "0 (default) or your preferred resolution" else "0（默认）或你偏好的分辨率",
        )
        TutorialItem(
            name = if (isEn) "Demo Mode" else "演示模式",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Launches Minecraft in demo mode with time-limited gameplay. Useful for testing."
            else
                "以演示模式启动 Minecraft，有时限的游戏。用于测试。",
            recommend = if (isEn) "Off" else "关闭",
        )
        TutorialItem(
            name = if (isEn) "Skip Version Check" else "跳过版本检查",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Skips the version compatibility check. Not recommended as it may cause crashes."
            else
                "跳过版本兼容性检查。不推荐，可能导致崩溃。",
            recommend = if (isEn) "Off (not recommended)" else "关闭（不推荐开启）",
        )
        TutorialItem(
            name = if (isEn) "Native GLFW" else "原生 GLFW",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Use native GLFW library instead of software rendering. May improve performance on some systems."
            else
                "使用原生 GLFW 库而非软渲染。某些系统上可能提升性能。",
            recommend = if (isEn) "Off (unless experiencing issues)" else "关闭（除非遇到问题）",
        )
        TutorialItem(
            name = if (isEn) "Native OpenAL" else "原生 OpenAL",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Use native OpenAL library for audio. May fix audio issues on some systems."
            else
                "使用原生 OpenAL 音频库。可能修复某些系统上的音频问题。",
            recommend = if (isEn) "Off (unless experiencing audio issues)" else "关闭（除非有音频问题）",
        )
        TutorialItem(
            name = if (isEn) "Extra Game Args" else "额外游戏参数",
            type = if (isEn) "Text Input" else "文本输入",
            desc = if (isEn)
                "Additional command-line arguments passed to the Minecraft game client itself (not JVM)."
            else
                "额外传递给 Minecraft 游戏客户端（而非 JVM）的命令行参数。",
            recommend = if (isEn) "Leave empty unless needed" else "留空，除非有特殊需要",
        )
    }
}

// ── 基岩版设置教程 ──────────────────────────────────────────────────────────
@Composable
private fun BedrockTutorial(isEn: Boolean) {
    TutorialGroup(icon = Icons.Filled.FolderOpen, title = if (isEn) "Version Management" else "版本管理") {
        TutorialItem(
            name = if (isEn) "Bedrock Versions Directory" else "基岩版版本目录",
            type = if (isEn) "Directory Picker" else "目录选择",
            desc = if (isEn)
                "Where Bedrock Edition versions are stored. Default is auto-detected. Each version gets its own subdirectory."
            else
                "基岩版版本的存放目录。默认自动检测。每个版本独立子目录。",
            recommend = if (isEn) "Auto (default)" else "自动（默认）",
        )
        TutorialItem(
            name = if (isEn) "Version Isolation" else "版本隔离",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Each Bedrock version gets its own profile directory (com.mojang). Prevents saves/resource packs from conflicting between versions."
            else
                "每个基岩版版本使用独立的 profile 目录（com.mojang）。防止存档/资源包在不同版本间冲突。建议开启。",
            recommend = if (isEn) "On (recommended)" else "开启（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Profiles Directory" else "存档目录",
            type = if (isEn) "Directory Picker" else "目录选择",
            desc = if (isEn)
                "Where Bedrock profiles (saves, resource packs) are stored. Auto = same drive as versions. You can set a custom location."
            else
                "基岩版 profile（存档、资源包）的存放位置。自动=与版本同盘。可自定义。",
            recommend = if (isEn) "Auto (default)" else "自动（默认）",
        )
        TutorialItem(
            name = if (isEn) "Max Parallel Installs" else "最大并行安装数",
            type = if (isEn) "Slider (1-8)" else "滑块（1-8）",
            desc = if (isEn)
                "How many Bedrock versions can be installed simultaneously. Higher = faster bulk install but uses more bandwidth."
            else
                "同时安装的基岩版版本数量。值越高批量安装越快，但占用更多带宽。建议值：2。",
            recommend = if (isEn) "2 (balanced)" else "2（平衡）",
        )
        TutorialItem(
            name = if (isEn) "Skip Hash Verify" else "跳过哈希验证",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Skip integrity verification after download. Speeds up install but may risk corrupted files. Only enable if you trust the source."
            else
                "下载后跳过完整性校验。加快安装速度但有文件损坏风险。仅在信任来源时开启。",
            recommend = if (isEn) "Off" else "关闭",
        )
        TutorialItem(
            name = if (isEn) "Keep Cache After Install" else "安装后保留缓存",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Keep downloaded installation packages after install for reinstallation without re-downloading."
            else
                "安装后保留下载的安装包，方便重新安装时无需重新下载。建议开启。",
            recommend = if (isEn) "On (recommended)" else "开启（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Migrate From Profiles" else "从 Profiles 迁移",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "On first open, migrate existing Minecraft Bedrock data from the Windows AppData profiles folder to MD3L managed directory."
            else
                "首次打开时，从 Windows AppData profiles 目录迁移现有存档到 MD3L 管理目录。建议开启。",
            recommend = if (isEn) "On (recommended for first time)" else "开启（首次使用推荐）",
        )
    }

    TutorialGroup(icon = Icons.Filled.Speed, title = if (isEn) "Launch & Performance" else "启动与性能") {
        TutorialItem(
            name = if (isEn) "Preheat on Startup" else "启动预热",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Pre-launches Bedrock Edition in background when MD3L starts. Significantly reduces first-launch time. Background process uses minimal resources."
            else
                "MD3L 启动时预先在后台启动基岩版。大幅减少首次启动时间。后台进程资源占用极低。建议开启。",
            recommend = if (isEn) "On (recommended)" else "开启（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Fast Switch" else "快速切换",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "When switching between Bedrock versions, reuses the already-running process instead of fully restarting. Much faster switching."
            else
                "切换基岩版版本时复用已在运行的进程而非完全重启。切换速度大幅提升。建议开启。",
            recommend = if (isEn) "On (recommended)" else "开启（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Skip Registration If Cached" else "已缓存跳过注册",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "If a Bedrock version was already registered via Add-AppxPackage -Register, skip re-registration on subsequent launches."
            else
                "如果基岩版版本已通过 Add-AppxPackage -Register 注册过，后续启动跳过注册步骤。建议开启。",
            recommend = if (isEn) "On (recommended)" else "开启（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Force Register Every Launch" else "强制每次注册",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Force re-registration every time Bedrock is launched. Slower but ensures a clean registration state. Useful for development/testing."
            else
                "每次启动基岩版时强制重新注册。较慢但确保注册状态干净。开发/测试时有用。",
            recommend = if (isEn) "Off (use Skip Registration instead)" else "关闭（用跳过注册代替）",
        )
        TutorialItem(
            name = if (isEn) "Auto Migrate on First Launch" else "首次启动自动迁移",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "On first launch of a Bedrock version, automatically migrate existing world saves to the version-isolated directory."
            else
                "首次启动某基岩版版本时自动将现有存档迁移到该版本的隔离目录。建议开启。",
            recommend = if (isEn) "On (recommended)" else "开启（推荐）",
        )
        TutorialItem(
            name = if (isEn) "Junction Fallback Copy" else "链接回退复制",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "If junction/symlink creation fails, fall back to copying files instead. Slower but works on all filesystems."
            else
                "如果创建符号链接失败，回退到复制文件方式。较慢但兼容所有文件系统。",
            recommend = if (isEn) "Off (junction is preferred)" else "关闭（优先使用符号链接）",
        )
    }

    TutorialGroup(icon = Icons.Filled.Gamepad, title = if (isEn) "In-Game Settings" else "游戏内设置") {
        TutorialItem(
            name = if (isEn) "FPS Limit" else "帧率上限",
            type = if (isEn) "Slider (0-240)" else "滑块（0-240）",
            desc = if (isEn)
                "Limits the in-game FPS. 0 = unlimited (uses game default). Lower values save battery on laptops."
            else
                "限制游戏内帧率。0=无限制（使用游戏默认值）。低值可节省笔记本电量。",
            recommend = if (isEn) "0 (unlimited) or 60/120" else "0（无限制）或 60/120",
        )
        TutorialItem(
            name = if (isEn) "Render Distance" else "渲染距离",
            type = if (isEn) "Slider (0-64)" else "滑块（0-64）",
            desc = if (isEn)
                "Chunk render distance. 0 = default. Higher values show more terrain but impact performance. 12-24 is a good range."
            else
                "区块渲染距离。0=默认。值越高看到的地形越多，但影响性能。12-24 为佳。",
            recommend = if (isEn) "0 (default) or 12-24" else "0（默认）或 12-24",
        )
        TutorialItem(
            name = if (isEn) "Graphics Mode" else "画质模式",
            type = if (isEn) "Dropdown" else "下拉选择",
            desc = if (isEn)
                "Fancy: full graphics with shadows and effects. Simple: lower quality for better performance. Ray Tracing: realistic lighting (RTX GPU required)."
            else
                "华丽：完整画面效果，含阴影和特效。流畅：较低画质换取性能。光追：逼真光照（需要 RTX 显卡）。",
            recommend = if (isEn) "Fancy (balanced)" else "华丽（平衡）",
        )
        TutorialItem(
            name = if (isEn) "Simulation Distance" else "模拟距离",
            type = if (isEn) "Slider (0-12)" else "滑块（0-12）",
            desc = if (isEn)
                "How many chunks around the player are ticked (entities, redstone, farms). Higher = more activity but more CPU usage."
            else
                "玩家周围被模拟（实体、红石、农场等）的区块数。值越高活动越多，但占用更多 CPU。",
            recommend = if (isEn) "0 (default) or 4-8" else "0（默认）或 4-8",
        )
        TutorialItem(
            name = if (isEn) "Show Coordinates" else "显示坐标",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Display XYZ coordinates on screen. Useful for navigation and building."
            else
                "在屏幕上显示 XYZ 坐标。方便导航和建筑。",
            recommend = if (isEn) "Off by default" else "默认关闭",
        )
        TutorialItem(
            name = if (isEn) "Hide HUD" else "隐藏 HUD",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Hides the entire heads-up display for screenshots or immersive gameplay."
            else
                "隐藏整个界面 HUD，用于截图或沉浸式游戏。",
            recommend = if (isEn) "Off" else "关闭",
        )
        TutorialItem(
            name = if (isEn) "Allow Cheats" else "允许作弊",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Enables cheats (commands, creative mode switching, etc.) when launching a world. Disables achievements."
            else
                "启动世界时启用作弊（命令、创造模式切换等）。会禁用成就。",
            recommend = if (isEn) "Off (only enable when needed)" else "关闭（需要时开启）",
        )
        TutorialItem(
            name = if (isEn) "Mute Sounds" else "静音",
            type = if (isEn) "Switch" else "开关",
            desc = if (isEn)
                "Starts the game with all audio muted. Useful if you want to listen to your own music while playing."
            else
                "以静音状态启动游戏。边玩游戏边听音乐时有用。",
            recommend = if (isEn) "Off" else "关闭",
        )
        TutorialItem(
            name = if (isEn) "Package Cache TTL (min)" else "包缓存 TTL（分钟）",
            type = if (isEn) "Slider (1-120)" else "滑块（1-120）",
            desc = if (isEn)
                "How long to cache Bedrock package registration info before re-checking. Lower = more accurate but slower switching."
            else
                "基岩版包注册信息的缓存时间。值越低信息越准确，但切换更慢。建议值：5 分钟。",
            recommend = if (isEn) "5 (balanced)" else "5（平衡）",
        )
        TutorialItem(
            name = if (isEn) "Extra Env Args" else "额外环境变量",
            type = if (isEn) "Text Input" else "文本输入",
            desc = if (isEn)
                "Additional environment variables passed to Bedrock Edition. Format: KEY=VALUE, one per line. For advanced users only."
            else
                "传递给基岩版的额外环境变量。格式：KEY=VALUE，每行一个。仅高级用户使用。",
            recommend = if (isEn) "Leave empty" else "留空",
        )
    }
}

// ── 辅助组件 ────────────────────────────────────────────────────────────────

@Composable
private fun TutorialGroup(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
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
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun TutorialItem(
    name: String,
    type: String,
    desc: String,
    recommend: String,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(4.dp))
                Text(
                    if (ThemeState.language == "en") "Recommend: " else "建议：",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(recommend, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}
