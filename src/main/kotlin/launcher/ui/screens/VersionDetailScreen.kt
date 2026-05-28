package launcher.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import launcher.core.*
import launcher.ui.components.VersionIcon
import launcher.ui.layout.Navigator
import javax.swing.JFileChooser

private enum class LoaderOption(val label: String) {
    None("不安装"),
    Forge("Forge"),
    Fabric("Fabric"),
    NeoForge("NeoForge"),
}

/**
 * 单个 Loader 版本信息
 */
private data class LoaderVersionItem(
    val version: String,
    val label: String = version,
    val metadata: Map<String, String> = emptyMap(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionDetailScreen(version: RemoteVersion) {
    val scope = rememberCoroutineScope()
    var customName by remember { mutableStateOf(version.id) }
    var selectedLoader by remember { mutableStateOf(LoaderOption.None) }
    var installOptiFine by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    var installMessage by remember { mutableStateOf("") }
    var installJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val downloadProgress by DownloadManager.progress.collectAsState()
    val loaderProgress by LoaderInstaller.progress.collectAsState()
    val scrollState = rememberScrollState()

    // ── Loader 版本列表 ──────────────────────────────────────────────────
    var loaderVersions by remember { mutableStateOf<List<LoaderVersionItem>>(emptyList()) }
    var selectedLoaderVersion by remember { mutableStateOf<LoaderVersionItem?>(null) }
    var isLoadingLoaderVersions by remember { mutableStateOf(false) }
    var loaderDropdownExpanded by remember { mutableStateOf(false) }

    // ── OptiFine 版本列表 ────────────────────────────────────────────────
    var optifineVersions by remember { mutableStateOf<List<LoaderVersionItem>>(emptyList()) }
    var selectedOptiFineVersion by remember { mutableStateOf<LoaderVersionItem?>(null) }
    var isLoadingOptiFine by remember { mutableStateOf(false) }
    var optifineDropdownExpanded by remember { mutableStateOf(false) }

    // 当用户切换 Loader 类型时，网络获取可用 Loader 版本
    LaunchedEffect(selectedLoader) {
        if (selectedLoader == LoaderOption.None) {
            loaderVersions = emptyList()
            selectedLoaderVersion = null
            return@LaunchedEffect
        }
        isLoadingLoaderVersions = true
        loaderVersions = emptyList()
        selectedLoaderVersion = null
        try {
            val list = withContext(Dispatchers.IO) { fetchLoaderVersions(version.id, selectedLoader) }
            loaderVersions = list
            if (list.isNotEmpty()) {
                selectedLoaderVersion = list.first()
            } else {
                installMessage = "获取 ${selectedLoader.label} 版本列表为空（该版本可能不支持此加载器，或网络请求失败）"
            }
        } catch (e: Exception) {
            installMessage = "获取 ${selectedLoader.label} 版本列表失败: ${e.message}"
        } finally {
            isLoadingLoaderVersions = false
        }
    }

    // 当用户开启 OptiFine 时，加载版本列表
    LaunchedEffect(installOptiFine) {
        if (!installOptiFine) return@LaunchedEffect
        if (optifineVersions.isNotEmpty()) return@LaunchedEffect
        isLoadingOptiFine = true
        try {
            val list = withContext(Dispatchers.IO) { fetchOptiFineVersions(version.id) }
            optifineVersions = list
            if (list.isNotEmpty()) selectedOptiFineVersion = list.first()
            else installMessage = "暂无可用的 OptiFine 版本（该 MC 版本可能尚未支持）"
        } catch (e: Exception) {
            installMessage = "获取 OptiFine 版本列表失败: ${e.message}"
        } finally {
            isLoadingOptiFine = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── 顶部标题区 ────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { Navigator.back() },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.DownloadForOffline, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("版本安装配置", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    Text("选择加载器和 OptiFine 后点击安装", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── 版本信息横幅 ─────────────────────────────────────────────────
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    VersionIcon(
                        loaderType = LoaderType.Vanilla,
                        versionType = version.type,
                        size = 48,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(version.id, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VdChip(
                                text = when (version.type) {
                                    "release" -> "正式版"
                                    "snapshot" -> "快照版"
                                    "old_alpha" -> "远古版"
                                    "old_beta" -> "Beta版"
                                    else -> version.type
                                },
                                containerColor = when (version.type) {
                                    "release" -> MaterialTheme.colorScheme.primaryContainer
                                    "snapshot" -> MaterialTheme.colorScheme.tertiaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            VdChip(text = version.releaseTime.take(10), containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }

            // ── 自定义版本名称 ───────────────────────────────────────────────
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.DriveFileRenameOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("自定义版本名称", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("版本名称") },
                        placeholder = { Text(version.id) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Filled.Label, null, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (customName != version.id && customName.isNotBlank()) {
                                IconButton(onClick = { customName = version.id }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "留空则使用原始 ID：${version.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            // ── 模组加载器 ───────────────────────────────────────────────────
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Build, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("模组加载器", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.weight(1f))
                        if (selectedLoader != LoaderOption.None) {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text(
                                    selectedLoader.label,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    // 加载器选项行
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        LoaderOption.entries.forEach { option ->
                            val selected = selectedLoader == option
                            FilterChip(
                                selected = selected,
                                onClick = { selectedLoader = option },
                                label = { Text(option.label, style = MaterialTheme.typography.labelMedium) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Filled.Check, null, modifier = Modifier.size(14.dp)) }
                                } else null,
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }

                    // 版本选择器
                    if (selectedLoader != LoaderOption.None) {
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(14.dp))
                        when {
                            isLoadingLoaderVersions -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text("正在获取 ${selectedLoader.label} 版本列表…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            loaderVersions.isEmpty() -> {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                        Text("该版本暂无可用的 ${selectedLoader.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                }
                            }
                            else -> {
                                ExposedDropdownMenuBox(expanded = loaderDropdownExpanded, onExpandedChange = { loaderDropdownExpanded = it }) {
                                    OutlinedTextField(
                                        value = selectedLoaderVersion?.label ?: "选择版本",
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("${selectedLoader.label} 版本") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(loaderDropdownExpanded) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                                    )
                                    ExposedDropdownMenu(expanded = loaderDropdownExpanded, onDismissRequest = { loaderDropdownExpanded = false }) {
                                        loaderVersions.forEach { lv ->
                                            DropdownMenuItem(
                                                text = { Text(lv.label, style = MaterialTheme.typography.bodySmall) },
                                                onClick = { selectedLoaderVersion = lv; loaderDropdownExpanded = false },
                                                leadingIcon = {
                                                    if (selectedLoaderVersion?.version == lv.version)
                                                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── OptiFine 选项 ────────────────────────────────────────────────
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (installOptiFine)
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(
                                if (installOptiFine) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Tune,
                                null,
                                modifier = Modifier.size(18.dp),
                                tint = if (installOptiFine) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("OptiFine 光影优化", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                            Text("同时安装 OptiFine 画质优化补丁", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = installOptiFine,
                            onCheckedChange = { installOptiFine = it },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.tertiary,
                                checkedThumbColor = MaterialTheme.colorScheme.onTertiary,
                            ),
                        )
                    }

                    // OptiFine 版本选择
                    if (installOptiFine) {
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(14.dp))
                        when {
                            isLoadingOptiFine -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.tertiary)
                                    Text("正在获取 OptiFine 版本列表…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            optifineVersions.isEmpty() -> {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                        Text("暂无可用的 OptiFine 版本（该 MC 版本可能尚未支持）", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            else -> {
                                ExposedDropdownMenuBox(expanded = optifineDropdownExpanded, onExpandedChange = { optifineDropdownExpanded = it }) {
                                    OutlinedTextField(
                                        value = selectedOptiFineVersion?.label ?: "选择 OptiFine 版本",
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("OptiFine 版本") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(optifineDropdownExpanded) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.tertiary,
                                            focusedLabelColor = MaterialTheme.colorScheme.tertiary,
                                        ),
                                    )
                                    ExposedDropdownMenu(expanded = optifineDropdownExpanded, onDismissRequest = { optifineDropdownExpanded = false }) {
                                        optifineVersions.forEach { ov ->
                                            DropdownMenuItem(
                                                text = { Text(ov.label, style = MaterialTheme.typography.bodySmall) },
                                                onClick = { selectedOptiFineVersion = ov; optifineDropdownExpanded = false },
                                                leadingIcon = {
                                                    if (selectedOptiFineVersion?.version == ov.version)
                                                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                                                },
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                                        Text(
                                            "OptiFine 将在原版安装完成后自动安装",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 安装总览摘要 ─────────────────────────────────────────────────
            if (selectedLoader != LoaderOption.None || installOptiFine) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Checklist, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text(
                            buildString {
                                append("安装计划：${customName.ifBlank { version.id }}")
                                if (selectedLoader != LoaderOption.None) append(" + ${selectedLoader.label}")
                                if (installOptiFine) append(" + OptiFine")
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // ── 加载器安装进度 ───────────────────────────────────────────────
            if (loaderProgress.isRunning || loaderProgress.done || loaderProgress.error.isNotBlank()) {
                ElevatedCard(
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = when {
                            loaderProgress.error.isNotBlank() -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                            loaderProgress.done -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                            else -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                        }
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when {
                                loaderProgress.isRunning -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.tertiary)
                                loaderProgress.done -> Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                else -> Icon(Icons.Filled.Error, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${loaderProgress.loaderName}: ${loaderProgress.step}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (loaderProgress.isRunning) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { loaderProgress.fraction },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.tertiary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── 下载进度 ─────────────────────────────────────────────────────
            if (downloadProgress.isRunning) {
                ElevatedCard(
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(downloadProgress.currentFile, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text(downloadProgress.speedMbps, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress.fraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("${downloadProgress.completedFiles}/${downloadProgress.totalFiles} 文件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${"%.1f".format(downloadProgress.downloadedBytes / 1_048_576.0)} MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ── 消息提示 ─────────────────────────────────────────────────────
            if (installMessage.isNotBlank()) {
                val isSuccess = "成功" in installMessage
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSuccess) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Error,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            installMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── 确认安装按钮 ─────────────────────────────────────────────────
            Button(
                onClick = {
                    isInstalling = true
                    installMessage = ""
                    installJob = scope.launch {
                        try {
                            var settings = AppSettings.load()
                            if (settings.minecraftDir.isBlank()) {
                                val chosen = withContext(Dispatchers.IO) {
                                    val chooser = JFileChooser(defaultMinecraftDir())
                                    chooser.dialogTitle = "选择游戏主目录 (.minecraft)"
                                    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                    chooser.approveButtonText = "确认"
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
                                        chooser.selectedFile.absolutePath else null
                                }
                                if (chosen == null) {
                                    installMessage = "未选择目录，已取消"
                                    isInstalling = false
                                    return@launch
                                }
                                settings = settings.copy(minecraftDir = chosen)
                                AppSettings.save(settings)
                            }
                            val finalName = customName.ifBlank { version.id }
                            val loader = selectedLoader
                            val loaderVer = selectedLoaderVersion

                            val req = InstallOrchestrator.InstallRequest(
                                version = version,
                                minecraftDir = settings.minecraftDir,
                                customName = finalName,
                                maxThreads = settings.maxDownloadThreads,
                                javaPath = settings.javaPath,
                                loaderType = if (loader != LoaderOption.None && loaderVer != null) loader.label else null,
                                loaderVersion = loaderVer?.version,
                                forgeBuild = if (loader == LoaderOption.Forge) loaderVer?.metadata?.get("build")?.toIntOrNull() ?: 0 else 0,
                                optifineVersion = if (installOptiFine) selectedOptiFineVersion?.version else null,
                            )
                            InstallOrchestrator.launch(req)
                            Navigator.back()
                        } catch (e: Exception) {
                            installMessage = "启动安装失败: ${e.message}"
                            isInstalling = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !isInstalling && !downloadProgress.isRunning,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
            ) {
                if (isInstalling) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("正在启动安装…", style = MaterialTheme.typography.titleSmall)
                } else {
                    Icon(Icons.Filled.RocketLaunch, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("确认下载并安装", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 网络获取 Loader 版本列表
// ══════════════════════════════════════════════════════════════════════════════

private val httpJson = Json { ignoreUnknownKeys = true }

private suspend fun fetchLoaderVersions(mcVersion: String, loader: LoaderOption): List<LoaderVersionItem> {
    return when (loader) {
        LoaderOption.Fabric -> fetchFabricVersions(mcVersion)
        LoaderOption.Forge -> fetchForgeVersions(mcVersion)
        LoaderOption.NeoForge -> fetchNeoForgeVersions(mcVersion)
        else -> emptyList()
    }
}

/** 通用 HTTP GET：先 curl.exe，后 HttpURLConnection（解决 JVM SSL 问题） */
private fun loaderHttpGet(url: String): String {
    // curl 优先（Fabric 响应很大，给充足超时）
    try {
        val proc = ProcessBuilder(
            "curl.exe", "-sL", "--connect-timeout", "15", "--max-time", "60", url
        ).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        val ok = proc.waitFor(65, java.util.concurrent.TimeUnit.SECONDS)
        if (ok && proc.exitValue() == 0 && text.isNotBlank()) {
            val trimmed = text.trim()
            // 基本 JSON 验证：以 [ 或 { 开头
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) return trimmed
        }
        println("[LoaderFetch] curl 失败 url=$url exitCode=${if (ok) proc.exitValue() else "timeout"}")
    } catch (e: Exception) {
        println("[LoaderFetch] curl 异常: ${e.message}")
    }

    // fallback: HttpURLConnection
    try {
        val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "MD3L/1.1")
        val code = conn.responseCode
        if (code == 200) {
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            if (text.isNotBlank()) return text.trim()
        }
        conn.disconnect()
        println("[LoaderFetch] HTTP fallback 失败 url=$url code=$code")
    } catch (e: Exception) {
        println("[LoaderFetch] HTTP fallback 异常: ${e.message}")
    }

    return ""
}

private suspend fun fetchFabricVersions(mcVersion: String): List<LoaderVersionItem> = withContext(Dispatchers.IO) {
    try {
        // BMCLAPI 镜像优先，官方备选
        val urls = listOf(
            "https://bmclapi2.bangbang93.com/fabric-meta/v2/versions/loader/$mcVersion",
            "https://meta.fabricmc.net/v2/versions/loader/$mcVersion",
        )
        var text = ""
        for (url in urls) {
            try { text = loaderHttpGet(url); if (text.isNotBlank()) break } catch (_: Exception) {}
        }
        if (text.isBlank()) return@withContext emptyList()
        val arr = httpJson.parseToJsonElement(text).jsonArray
        arr.mapNotNull { el ->
            val loaderObj = el.jsonObject["loader"]?.jsonObject ?: return@mapNotNull null
            val ver = loaderObj["version"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val stable = loaderObj["stable"]?.jsonPrimitive?.booleanOrNull ?: false
            LoaderVersionItem(ver, "$ver${if (stable) " (stable)" else ""}")
        }
    } catch (_: Exception) { emptyList() }
}

private suspend fun fetchForgeVersions(mcVersion: String): List<LoaderVersionItem> = withContext(Dispatchers.IO) {
    try {
        val urls = listOf(
            "https://bmclapi2.bangbang93.com/forge/minecraft/$mcVersion",
        )
        var text = ""
        for (url in urls) {
            try { text = loaderHttpGet(url); if (text.isNotBlank()) break } catch (_: Exception) {}
        }
        if (text.isNotBlank()) {
            val arr = httpJson.parseToJsonElement(text).jsonArray
            val items = arr.mapNotNull { el ->
                val obj = el.jsonObject
                val ver = obj["version"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val build = obj["build"]?.jsonPrimitive?.intOrNull?.toString() ?: ""
                LoaderVersionItem(ver, ver, if (build.isNotBlank()) mapOf("build" to build) else emptyMap())
            }.reversed()
            if (items.isNotEmpty()) return@withContext items
        }

        // fallback：Forge maven metadata（不包含 build，但可用于直接 maven 安装器下载）
        val metadataUrls = listOf(
            "https://bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/maven-metadata.xml",
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml",
        )
        val metadataText = metadataUrls.asSequence().mapNotNull { url ->
            runCatching { loaderHttpGet(url) }.getOrNull()
        }.firstOrNull { it.isNotBlank() }
            ?: return@withContext emptyList()

        val prefix = "$mcVersion-"
        val versions = Regex("<version>([^<]+)</version>")
            .findAll(metadataText)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        versions.reversed().map { LoaderVersionItem(it, it) }
    } catch (_: Exception) { emptyList() }
}

private suspend fun fetchNeoForgeVersions(mcVersion: String): List<LoaderVersionItem> = withContext(Dispatchers.IO) {
    try {
        val urls = listOf(
            "https://bmclapi2.bangbang93.com/neoforge/list/$mcVersion",
        )
        var text = ""
        for (url in urls) {
            try { text = loaderHttpGet(url); if (text.isNotBlank()) break } catch (_: Exception) {}
        }
        if (text.isBlank()) return@withContext emptyList()
        val arr = httpJson.parseToJsonElement(text).jsonArray
        arr.mapNotNull { el ->
            val ver = el.jsonObject["version"]?.jsonPrimitive?.contentOrNull
                ?: el.jsonPrimitive.contentOrNull
                ?: return@mapNotNull null
            LoaderVersionItem(ver)
        }.reversed()
    } catch (_: Exception) { emptyList() }
}

private suspend fun fetchOptiFineVersions(mcVersion: String): List<LoaderVersionItem> = withContext(Dispatchers.IO) {
    try {
        val url = "https://bmclapi2.bangbang93.com/optifine/$mcVersion"
        val text = loaderHttpGet(url)
        if (text.isBlank()) return@withContext emptyList()
        val arr = httpJson.parseToJsonElement(text).jsonArray
        arr.mapNotNull { el ->
            val obj = el.jsonObject
            val patch = obj["patch"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val type  = obj["type"]?.jsonPrimitive?.contentOrNull ?: "HD_U"
            val ver   = "${type}_$patch"
            LoaderVersionItem(version = ver, label = "OptiFine $ver")
        }
    } catch (_: Exception) { emptyList() }
}

// ══════════════════════════════════════════════════════════════════════════════
// 内部子组件
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun VdChip(text: String, containerColor: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = containerColor) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
