package launcher.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import launcher.core.*
import launcher.ui.components.VersionIcon
import java.awt.Desktop
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionScreen() {
    val scope = rememberCoroutineScope()
    val repoVersions by VersionRepository.versions.collectAsState()
    var isLoading by remember { mutableStateOf(true) }
    var filterType by remember { mutableStateOf<LoaderType?>(null) }
    var filterBedrock by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var bedrockVersions by remember { mutableStateOf<List<LocalVersion>>(emptyList()) }
    val bedrockDownloading by BedrockDownloadManager.downloadingVersions.collectAsState()
    val bedrockDownloadResults by BedrockDownloadManager.downloadResults.collectAsState()

    // ── BottomSheet 状态 ─────────────────────────────────────────────────
    var selectedVersion by remember { mutableStateOf<LocalVersion?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSheet by remember { mutableStateOf(false) }
    var sheetMessage by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }

    fun refresh() {
        isLoading = true
        scope.launch {
            val settings = AppSettings.load()
            VersionRepository.invalidateCache(settings.minecraftDir)
            bedrockVersions = VersionScanner.scanBedrock(settings.minecraftDir)
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(bedrockDownloading, bedrockDownloadResults) {
        if (bedrockDownloading.isNotEmpty() || bedrockDownloadResults.isEmpty()) return@LaunchedEffect
        refresh()
    }

    // 全部 tab 时合并 Java + Bedrock
    val allVersions = when {
        filterBedrock -> bedrockVersions
        filterType == null -> repoVersions + bedrockVersions          // 「全部」合并
        else -> repoVersions
    }
    val filteredVersions = allVersions.filter { v ->
        (filterBedrock || filterType == null || v.loaderType == filterType) &&
                (searchQuery.isBlank() || v.id.contains(searchQuery, ignoreCase = true))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("本地版本管理", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(2.dp))
        Text("共 ${repoVersions.size + bedrockVersions.size} 个版本 · 点击卡片管理", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        // ── 搜索 + 过滤 ─────────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索版本...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            Spacer(Modifier.width(8.dp))
            val types = listOf(null to "全部", LoaderType.Vanilla to "原版", LoaderType.Forge to "Forge", LoaderType.NeoForge to "NeoForge", LoaderType.Fabric to "Fabric")
            // Bedrock 独立 chip
            FilterChip(
                selected = filterBedrock,
                onClick = { filterBedrock = !filterBedrock; if (filterBedrock) filterType = null },
                label = { Text("基岩版", style = MaterialTheme.typography.labelSmall) },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(horizontal = 2.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            )
            types.forEach { (type, label) ->
                FilterChip(
                    selected = filterType == type,
                    onClick = { filterType = type; filterBedrock = false },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(horizontal = 2.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            chooseFileDialog("导入整合包", "*.zip;*.mrpack", load = true)
                        }
                        if (file != null) {
                            val settings = withContext(Dispatchers.IO) { AppSettings.load() }
                            val importTaskId = "manual_modpack_import_${file.absolutePath.hashCode()}_${System.currentTimeMillis()}"
                            DownloadHub.upsert(DownloadHub.HubTask(
                                id = importTaskId,
                                name = "导入整合包 ${file.name}",
                                type = DownloadHub.TaskType.ResourceDownload,
                                step = "准备导入整合包",
                                fraction = 0f,
                            ))
                            // 用独立协程作用域，不绑定 Compose 生命周期，防止切换页面时取消导入
                            val importScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
                            importScope.launch {
                                val result = ModpackManager.importMrpack(file, settings.minecraftDir) { step, fraction ->
                                    runCatching { sheetMessage = step }
                                    DownloadHub.upsert(DownloadHub.HubTask(
                                        id = importTaskId,
                                        name = "导入整合包 ${file.name}",
                                        type = DownloadHub.TaskType.ResourceDownload,
                                        step = step,
                                        fraction = fraction.coerceIn(0f, 1f),
                                    ))
                                }
                                DownloadHub.upsert(DownloadHub.HubTask(
                                    id = importTaskId,
                                    name = "导入整合包 ${file.name}",
                                    type = DownloadHub.TaskType.ResourceDownload,
                                    status = if ("成功" in result) DownloadHub.TaskStatus.Done else DownloadHub.TaskStatus.Error,
                                    step = result,
                                    fraction = if ("成功" in result) 1f else 0f,
                                    error = if ("成功" in result) "" else result,
                                ))
                                runCatching { sheetMessage = result }
                                runCatching { refresh() }
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入整合包")
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 版本列表 ─────────────────────────────────────────────────────────
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (filteredVersions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Inbox, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(12.dp))
                    Text("没有找到匹配的版本", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            val gridState = rememberLazyGridState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 280.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                ) {
                    items(filteredVersions, key = { it.id }) { version ->
                        VersionCard(
                            version = version,
                            onClick = {
                                selectedVersion = version
                                renameText = version.id
                                sheetMessage = ""
                                showSheet = true
                            },
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(gridState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }

    // ── ModalBottomSheet: 版本管理面板 ───────────────────────────────────
    if (showSheet && selectedVersion != null) {
        val ver = selectedVersion!!
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                // ── 标题 ─────────────────────────────────────────────────
                Text(ver.id, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text("${ver.loaderType.name} · ${ver.type}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))

                // ── 重命名（仅 Java 版支持）───────────────────────────────
                if (ver.type != "bedrock") {
                    Text("重命名版本", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            label = { Text("新名称") },
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = {
                                if (renameText.isNotBlank() && renameText.trim() != ver.id) {
                                    scope.launch {
                                        val settings = AppSettings.load()
                                        sheetMessage = VersionRepository.atomicRename(ver, renameText.trim(), settings.minecraftDir)
                                        if ("成功" in sheetMessage) {
                                            showSheet = false
                                            selectedVersion = null
                                            refresh()
                                        }
                                    }
                                } else {
                                    sheetMessage = "名称相同，无需重命名"
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("确认")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // ── 操作按钮 ─────────────────────────────────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val dir = File(ver.versionDir)
                                if (dir.exists() && Desktop.isDesktopSupported()) {
                                    Desktop.getDesktop().open(dir)
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("打开文件夹")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                val settings = AppSettings.load()
                                sheetMessage = VersionRepository.deleteVersion(ver, settings.minecraftDir)
                                showSheet = false
                                selectedVersion = null
                                refresh()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("彻底删除")
                    }
                }

                if (ver.type != "bedrock") {
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val target = chooseFileDialog("导出整合包", "*.zip", load = false, defaultName = "${ver.id}.zip")
                                if (target != null) {
                                    sheetMessage = ModpackManager.exportMrpack(ver, target)
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导出整合包")
                    }
                }

                // ── 基岩版: 行为包/资源包/Addon 导入 ──────────────────
                if (ver.type == "bedrock") {
                    Spacer(Modifier.height(16.dp))
                    Text("导入内容", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        // 行为包
                        FilledTonalButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    sheetMessage = importBedrockPack(ver.versionDir, ver.id, "behavior_packs", "mcpack", "行为包")
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("行为包")
                        }
                        // 资源包
                        FilledTonalButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    sheetMessage = importBedrockPack(ver.versionDir, ver.id, "resource_packs", "mcpack", "资源包")
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("资源包")
                        }
                        // Addon
                        FilledTonalButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    sheetMessage = importBedrockPack(ver.versionDir, ver.id, "addon", "mcaddon", "Addon")
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Addon")
                        }
                    }
                }

                // ── 消息 ─────────────────────────────────────────────────
                if (sheetMessage.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(sheetMessage, style = MaterialTheme.typography.bodySmall,
                        color = if ("成功" in sheetMessage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * 版本卡片——使用自定义 interactionSource + ripple 消除桌面端白色 Focus Ring。
 */
@Composable
private fun VersionCard(version: LocalVersion, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }

    ElevatedCard(
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 90.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VersionIcon(loaderType = version.loaderType, versionType = version.type, size = 38)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(version.id, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text(version.loaderType.name, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(22.dp),
                        )
                        SuggestionChip(
                            onClick = {},
                            label = { Text(version.type, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(22.dp),
                        )
                    }
                }
                Icon(Icons.Filled.MoreVert, contentDescription = "管理", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
            }
            if (version.inheritsFrom != null) {
                Spacer(Modifier.height(6.dp))
                Text("继承自: ${version.inheritsFrom}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// 重命名和删除操作已委托给 VersionRepository，
// 使用 java.nio.file.Files.move 原子操作 + invalidateCache() 驱动 UI 重组。

/**
 * 选择并导入基岩版包文件（行为包/资源包/addon）到版本目录。
 * @param versionDir 基岩版版本目录
 * @param subFolder  子目录名: behavior_packs / resource_packs / addon
 * @param extension  文件扩展名: mcpack / mcaddon
 * @param label      显示名
 */
private fun importBedrockPack(
    versionDir: String,
    versionId: String,
    subFolder: String,
    extension: String,
    label: String,
): String {
    return try {
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = "选择${label}文件 (.$extension)"
        chooser.isMultiSelectionEnabled = true
        chooser.fileFilter = object : javax.swing.filechooser.FileFilter() {
            override fun accept(f: File?): Boolean =
                f != null && (f.isDirectory || f.name.endsWith(".$extension", ignoreCase = true))
            override fun getDescription(): String = "$label (*.$extension)"
        }
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return "已取消"
        }
        val files = chooser.selectedFiles
        if (files.isNullOrEmpty()) return "未选择文件"

        val versionFolder = File(versionDir)
        val minecraftDir = versionFolder.parentFile?.parentFile?.absolutePath
            ?: throw RuntimeException("无法解析 Minecraft 根目录")

        val engine = BedrockLaunchEngine()
        // 通过引擎获取正确的 profile 路径（与 UWP 包同盘，避免跨盘 junction 配额问题）
        val profileDir = engine.resolveVersionProfilePublic(minecraftDir, versionId)
        var success = 0
        val failed = mutableListOf<String>()
        for (f in files) {
            val result = runCatching {
                engine.injectAddon(f.absolutePath, profileDir)
            }
            if (result.isSuccess) {
                success++
            } else {
                failed.add(f.name)
                println("[BedrockPackImport] 导入失败: ${f.name} - ${result.exceptionOrNull()?.message}")
            }
        }

        if (failed.isEmpty()) {
            "成功导入 $success 个${label}（已注入至版本存档目录）"
        } else {
            "部分导入成功：成功 $success 个，失败 ${failed.size} 个\n失败文件：${failed.take(3).joinToString("、")}" +
                if (failed.size > 3) " 等" else ""
        }
    } catch (e: Exception) {
        "导入失败: ${e.message}"
    }
}

private fun chooseFileDialog(title: String, pattern: String, load: Boolean, defaultName: String = ""): File? {
    return try {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, if (load) java.awt.FileDialog.LOAD else java.awt.FileDialog.SAVE)
        dialog.file = defaultName.ifBlank { pattern }
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val file = dialog.file ?: return null
        File(dir, file)
    } catch (_: Exception) {
        null
    }
}
