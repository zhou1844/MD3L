package launcher.ui.screens

import androidx.compose.foundation.VerticalScrollbar
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
    var showCustomColorDialog by remember { mutableStateOf(false) }
    var customColorHexInput by remember { mutableStateOf("CFBCFF") }

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
        ThemeState.customAccentColor = settings.customAccentColor
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
                                // 预设色板行
                                val isCustomActive = settings.customAccentColor != -1L
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AllAccents.forEachIndexed { index, accent ->
                                        val isSelected = !isCustomActive && settings.accentIndex == index
                                        Box(
                                            modifier = Modifier.size(40.dp).clip(CircleShape).background(accent.primary)
                                                .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                                .clickable {
                                                    ThemeState.accent = accent
                                                    ThemeState.customAccentColor = -1L
                                                    autoSave(settings.copy(accentIndex = index, customAccentColor = -1L))
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (isSelected) Icon(Icons.Filled.Check, null, tint = accent.onPrimary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    // 自定义选色按钮
                                    val customColor = if (isCustomActive) Color(settings.customAccentColor.toULong()) else MaterialTheme.colorScheme.surfaceContainerHighest
                                    Box(
                                        modifier = Modifier.size(40.dp).clip(CircleShape)
                                            .background(customColor)
                                            .then(if (isCustomActive) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                                            .clickable {
                                                val hex = if (isCustomActive) "%06X".format(settings.customAccentColor and 0xFFFFFF) else "CFBCFF"
                                                showCustomColorDialog = true
                                                customColorHexInput = hex
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isCustomActive) {
                                            val lum = 0.2126f * ((settings.customAccentColor shr 16 and 0xFF) / 255f) +
                                                      0.7152f * ((settings.customAccentColor shr 8  and 0xFF) / 255f) +
                                                      0.0722f * ((settings.customAccentColor        and 0xFF) / 255f)
                                            Icon(Icons.Filled.Check, null, tint = if (lum < 0.5f) Color.White else Color(0.10f, 0.10f, 0.10f), modifier = Modifier.size(18.dp))
                                        }
                                        else Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                val accentNamesEn = listOf("Monet Purple","Geek Blue","Mint Green","Amber Orange","Coral Red","Celadon Cyan","Graphite Blue")
                                Text(
                                    if (isCustomActive) (if (isEn) "Custom: #%06X".format(settings.customAccentColor and 0xFFFFFF) else "自定义：#%06X".format(settings.customAccentColor and 0xFFFFFF))
                                    else if (isEn) accentNamesEn.getOrElse(settings.accentIndex){"Monet Purple"} else AccentNames.getOrElse(settings.accentIndex){"莫奈紫"},
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // 自定义选色对话框
                                if (showCustomColorDialog) {
                                    androidx.compose.ui.window.Dialog(onDismissRequest = { showCustomColorDialog = false }) {
                                        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, modifier = Modifier.width(320.dp)) {
                                            Column(modifier = Modifier.padding(24.dp)) {
                                                Text(if (isEn) "Custom Accent Color" else "自定义主题色", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                                                Spacer(Modifier.height(16.dp))
                                                // 预览色块
                                                val previewArgb = customColorHexInput.padStart(6,'0').takeLast(6).toLongOrNull(16)
                                                val previewColor = if (previewArgb != null) Color((0xFF000000L or previewArgb).toULong()) else MaterialTheme.colorScheme.surfaceVariant
                                                Box(modifier = Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(previewColor))
                                                Spacer(Modifier.height(16.dp))
                                                // 彩虹滑块：H / S / V
                                                fun rgbToHsv(ri: Int, gi: Int, bi: Int): FloatArray {
                                                    val r = ri / 255f; val g = gi / 255f; val b = bi / 255f
                                                    val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
                                                    val h = when {
                                                        d == 0f -> 0f
                                                        mx == r -> 60f * (((g - b) / d) % 6f)
                                                        mx == g -> 60f * ((b - r) / d + 2f)
                                                        else    -> 60f * ((r - g) / d + 4f)
                                                    }.let { if (it < 0) it + 360f else it }
                                                    return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
                                                }
                                                fun hsvToHex(h: Float, s: Float, v: Float): String {
                                                    val c = v * s; val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f)); val m = v - c
                                                    val (r1, g1, b1) = when ((h / 60f).toInt() % 6) {
                                                        0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
                                                        3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
                                                    }
                                                    return "%02X%02X%02X".format(((r1+m)*255).toInt().coerceIn(0,255), ((g1+m)*255).toInt().coerceIn(0,255), ((b1+m)*255).toInt().coerceIn(0,255))
                                                }
                                                val hsv = remember(customColorHexInput) {
                                                    val argb = customColorHexInput.padStart(6,'0').takeLast(6).toLongOrNull(16)
                                                    if (argb != null) rgbToHsv(((argb shr 16) and 0xFF).toInt(), ((argb shr 8) and 0xFF).toInt(), (argb and 0xFF).toInt())
                                                    else floatArrayOf(0f, 1f, 1f)
                                                }
                                                var hue by remember(customColorHexInput) { mutableStateOf(hsv[0]) }
                                                var sat by remember(customColorHexInput) { mutableStateOf(hsv[1]) }
                                                var value by remember(customColorHexInput) { mutableStateOf(hsv[2]) }
                                                Text(if (isEn) "Hue: ${hue.toInt()}°" else "色相: ${hue.toInt()}°", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Slider(value = hue, onValueChange = { hue = it; customColorHexInput = hsvToHex(it, sat, value) }, valueRange = 0f..360f, modifier = Modifier.fillMaxWidth(),
                                                    colors = SliderDefaults.colors(thumbColor = previewColor, activeTrackColor = previewColor))
                                                Text(if (isEn) "Saturation: ${(sat*100).toInt()}%" else "饱和度: ${(sat*100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Slider(value = sat, onValueChange = { sat = it; customColorHexInput = hsvToHex(hue, it, value) }, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth(),
                                                    colors = SliderDefaults.colors(thumbColor = previewColor, activeTrackColor = previewColor))
                                                Text(if (isEn) "Brightness: ${(value*100).toInt()}%" else "亮度: ${(value*100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Slider(value = value, onValueChange = { value = it; customColorHexInput = hsvToHex(hue, sat, it) }, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth(),
                                                    colors = SliderDefaults.colors(thumbColor = previewColor, activeTrackColor = previewColor))
                                                Spacer(Modifier.height(8.dp))
                                                // HEX输入
                                                OutlinedTextField(
                                                    value = "#$customColorHexInput",
                                                    onValueChange = { v ->
                                                        val clean = v.trimStart('#').filter { it.isLetterOrDigit() }.uppercase().take(6)
                                                        customColorHexInput = clean
                                                    },
                                                    label = { Text("HEX") },
                                                    singleLine = true,
                                                    shape = RoundedCornerShape(12.dp),
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                                Spacer(Modifier.height(16.dp))
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(onClick = { showCustomColorDialog = false }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                                                        Text(if (isEn) "Cancel" else "取消")
                                                    }
                                                    Button(
                                                        onClick = {
                                                            val argb = customColorHexInput.padStart(6,'0').takeLast(6).toLongOrNull(16)
                                                            if (argb != null) {
                                                                val full = 0xFF000000L or argb
                                                                ThemeState.customAccentColor = full
                                                                autoSave(settings.copy(customAccentColor = full))
                                                            }
                                                            showCustomColorDialog = false
                                                        },
                                                        modifier = Modifier.weight(1f),
                                                        shape = RoundedCornerShape(12.dp),
                                                    ) { Text(if (isEn) "Apply" else "应用") }
                                                }
                                            }
                                        }
                                    }
                                }
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

                            // ── 界面布局 ──────────────────────────────────
                            SettingsSection(if (isEn) "Layout & Density" else "界面布局与密度", Icons.Filled.Dashboard) {
                                Text(if (isEn) "Sidebar width: ${settings.uiSidebarWidth} dp" else "侧边栏宽度: ${settings.uiSidebarWidth} dp", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.uiSidebarWidth.toFloat(), onValueChange = { v ->
                                    val iv = v.toInt(); ThemeState.uiSidebarWidth = iv; autoSave(settings.copy(uiSidebarWidth = iv))
                                }, valueRange = 64f..120f, steps = 55, modifier = Modifier.fillMaxWidth())
                                Text(if (isEn) "Corner radius: ${settings.uiCornerRadius} dp" else "全局圆角: ${settings.uiCornerRadius} dp", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.uiCornerRadius.toFloat(), onValueChange = { v ->
                                    val iv = v.toInt(); ThemeState.uiCornerRadius = iv; autoSave(settings.copy(uiCornerRadius = iv))
                                }, valueRange = 0f..32f, steps = 31, modifier = Modifier.fillMaxWidth())
                                Text(if (isEn) "Font scale: ${"%.1f".format(settings.uiFontScale)}×" else "字体缩放: ${"%.1f".format(settings.uiFontScale)}×", style = MaterialTheme.typography.bodySmall)
                                Slider(value = settings.uiFontScale, onValueChange = { v ->
                                    ThemeState.uiFontScale = v; autoSave(settings.copy(uiFontScale = v))
                                }, valueRange = 0.8f..1.4f, steps = 11, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(6.dp))
                                SettingsToggleRow(
                                    title = if (isEn) "Compact Mode" else "紧凑模式",
                                    subtitle = if (isEn) "Reduce spacing between UI elements" else "减少各组件间距，在小屏幕上展示更多内容",
                                    checked = settings.uiCompactMode,
                                    onCheckedChange = { ThemeState.uiCompactMode = it; autoSave(settings.copy(uiCompactMode = it)) },
                                )
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

                            // ── 内容显示 ──────────────────────────────────
                            SettingsSection(if (isEn) "Content Display" else "内容显示", Icons.Filled.Visibility) {
                                SettingsToggleRow(
                                    title = if (isEn) "Show version badge in sidebar" else "侧边栏显示版本号徽章",
                                    subtitle = if (isEn) "Shows launcher version number at the bottom of the sidebar" else "在侧边栏底部显示当前启动器版本号",
                                    checked = settings.uiShowVersionBadge,
                                    onCheckedChange = { ThemeState.uiShowVersionBadge = it; autoSave(settings.copy(uiShowVersionBadge = it)) },
                                )
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
                                        ThemeState.customAccentColor = -1L
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
                                Text("MD3L", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black), color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { openExternalUrl("https://md3l.top") })
                                Spacer(Modifier.height(4.dp))
                                Text("v${AutoUpdater.CURRENT_VERSION} · Kotlin + Compose Desktop · Material Design 3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("by @yunoniaodudu", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.clickable { openExternalUrl("https://space.bilibili.com/1340292263?spm_id_from=333.1007.0.0") })
                                    Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                    Text("赞助", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.clickable { openExternalUrl("https://ifdian.net/a/zzh10086") })
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }

                        // ════════════════════════════════════════ Java 版 ═
                        1 -> {
                            SettingsSection("Java 运行环境", Icons.Filled.Code) {
                                ExposedDropdownMenuBox(expanded = javaDropdownExpanded, onExpandedChange = { javaDropdownExpanded = it }) {
                                    OutlinedTextField(
                                        value = settings.javaPath,
                                        onValueChange = { autoSave(settings.copy(javaPath = it)) },
                                        label = { Text("Java 路径") }, readOnly = false,
                                        trailingIcon = {
                                            if (isScanning) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                            else ExposedDropdownMenuDefaults.TrailingIcon(javaDropdownExpanded)
                                        },
                                        singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().menuAnchor(),
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
                                                    val javaExe = if (System.getProperty("os.name").lowercase().contains("win")) "${java.path}\\bin\\java.exe" else "${java.path}/bin/java"
                                                    autoSave(settings.copy(javaPath = javaExe)); javaDropdownExpanded = false
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
                                                onClick = { autoSave(settings.copy(gcPolicy = gc)); gcExpanded = false },
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))

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
                                    value = settings.customJvmArgs, onValueChange = { autoSave(settings.copy(customJvmArgs = it)) },
                                    label = { Text("JVM 启动参数") }, minLines = 2, maxLines = 4,
                                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("空格分隔，以上 G1GC/内存参数均会自动拼入，此处追加额外参数", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
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
