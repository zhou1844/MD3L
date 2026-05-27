package launcher.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import launcher.core.BedrockLaunchEngine
import launcher.ui.layout.Navigator
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BedrockWorldManagerScreen(versionId: String, versionDir: String) {
    val scope = rememberCoroutineScope()

    data class WorldEntry(
        val name: String,
        val dir: File,
        val sizeBytes: Long,
        val levelName: String,
    )

    var worlds by remember { mutableStateOf<List<WorldEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("") }
    var confirmDeleteTarget by remember { mutableStateOf<WorldEntry?>(null) }
    var exportingWorld by remember { mutableStateOf<String?>(null) }

    fun resolveWorldsDir(): File? {
        return try {
            val minecraftDir = File(versionDir).parentFile?.parentFile?.absolutePath ?: return null
            val engine = BedrockLaunchEngine()
            val profile = engine.resolveBedrockVersionComMojang(minecraftDir, versionId)
            File(profile, "minecraftWorlds")
        } catch (_: Exception) { null }
    }

    fun refresh() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val worldsDir = resolveWorldsDir()
            val entries = mutableListOf<WorldEntry>()
            if (worldsDir != null && worldsDir.isDirectory) {
                worldsDir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedBy { it.name.lowercase() }
                    ?.forEach { dir ->
                        val size = dir.walkTopDown().sumOf { it.length() }
                        val levelName = readLevelName(dir) ?: dir.name
                        entries.add(WorldEntry(dir.name, dir, size, levelName))
                    }
            }
            withContext(Dispatchers.Main) {
                worlds = entries
                isLoading = false
            }
        }
    }

    LaunchedEffect(versionId) { refresh() }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶部栏 ───────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { Navigator.back() },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("地图管理", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                Text("版本: $versionId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val result = doImportWorld(versionDir, versionId)
                        withContext(Dispatchers.Main) {
                            statusMessage = result
                            if ("成功" in result) refresh()
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入地图")
            }
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(10.dp))

        if (statusMessage.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if ("成功" in statusMessage || "✓" in statusMessage)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    statusMessage,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if ("成功" in statusMessage || "✓" in statusMessage)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (worlds.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Public,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("暂无地图存档", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val result = doImportWorld(versionDir, versionId)
                                withContext(Dispatchers.Main) {
                                    statusMessage = result
                                    if ("成功" in result) refresh()
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导入地图")
                    }
                }
            }
        } else {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                ) {
                    items(worlds, key = { it.name }) { world ->
                        ElevatedCard(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val worldIconBitmap: ImageBitmap? = remember(world.dir.absolutePath) {
                                    val jpegFile = world.dir.resolve("world_icon.jpeg")
                                    val pngFile = world.dir.resolve("world_icon.png")
                                    val iconFile = when {
                                        jpegFile.exists() -> jpegFile
                                        pngFile.exists() -> pngFile
                                        else -> null
                                    }
                                    iconFile?.let {
                                        runCatching {
                                            org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()
                                        }.getOrNull()
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (worldIconBitmap != null) {
                                        Image(
                                            bitmap = worldIconBitmap,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.Public,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        world.levelName,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        buildString {
                                            append(formatWorldSize(world.sizeBytes))
                                            if (world.name != world.levelName) {
                                                append("  ·  ${world.name}")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                // 导出按钮
                                val isExporting = exportingWorld == world.name
                                IconButton(
                                    onClick = {
                                        if (!isExporting) {
                                            exportingWorld = world.name
                                            scope.launch(Dispatchers.IO) {
                                                val result = doExportWorld(world.dir, world.levelName)
                                                withContext(Dispatchers.Main) {
                                                    exportingWorld = null
                                                    statusMessage = result
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    if (isExporting) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(
                                            Icons.Filled.FileUpload,
                                            contentDescription = "导出为 .mcworld",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                // 删除按钮
                                IconButton(
                                    onClick = { confirmDeleteTarget = world },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }

    // ── 删除确认对话框 ────────────────────────────────────────────────────────
    val target = confirmDeleteTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTarget = null },
            title = { Text("删除「${target.levelName}」？") },
            text = { Text("此操作不可恢复，将永久删除该地图存档目录。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDeleteTarget = null
                        scope.launch(Dispatchers.IO) {
                            val deleted = target.dir.deleteRecursively()
                            withContext(Dispatchers.Main) {
                                statusMessage = if (deleted) "已删除: ${target.levelName}" else "删除失败"
                                refresh()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteTarget = null }) { Text("取消") }
            },
        )
    }
}

private fun doImportWorld(versionDir: String, versionId: String): String {
    return try {
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = "选择地图文件 (.mcworld)"
        chooser.isMultiSelectionEnabled = true
        chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
            "Minecraft 地图文件", "mcworld", "mctemplate", "zip"
        )
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return "已取消"
        val files = chooser.selectedFiles ?: return "未选择文件"

        val minecraftDir = File(versionDir).parentFile?.parentFile?.absolutePath
            ?: return "无法解析 Minecraft 根目录"
        val engine = BedrockLaunchEngine()
        val profileDir = engine.resolveBedrockVersionComMojang(minecraftDir, versionId)
        val worldsDir = File(profileDir, "minecraftWorlds").apply { mkdirs() }

        var success = 0
        val failed = mutableListOf<String>()
        for (f in files) {
            runCatching {
                val worldName = f.nameWithoutExtension
                val dest = File(worldsDir, worldName).let { base ->
                    var candidate = base
                    var idx = 1
                    while (candidate.exists()) { candidate = File(worldsDir, "${worldName}_$idx"); idx++ }
                    candidate
                }
                dest.mkdirs()
                // First pass: detect single top-level wrapper dir (mctemplate pattern)
                val allEntries = mutableListOf<String>()
                java.util.zip.ZipFile(f).use { zf -> zf.entries().asSequence().forEach { allEntries += it.name } }
                val topDirs = allEntries.mapNotNull { it.split('/').firstOrNull()?.takeIf { d -> d.isNotBlank() } }.toSet()
                val stripPrefix = if (topDirs.size == 1 && allEntries.all { it.startsWith(topDirs.first()) }) topDirs.first() + "/" else ""
                ZipInputStream(f.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val relPath = (if (stripPrefix.isNotEmpty() && entry.name.startsWith(stripPrefix))
                                entry.name.removePrefix(stripPrefix) else entry.name)
                                .replace('\\', '/').trimStart('/')
                            if (relPath.isNotBlank() && !relPath.contains("..")) {
                                val out = File(dest, relPath)
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zis.copyTo(it) }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                success++
            }.onFailure { failed.add(f.name) }
        }
        if (failed.isEmpty()) "✓ 成功导入 $success 个地图"
        else "部分成功：$success 个成功，${failed.size} 个失败（${failed.joinToString()}）"
    } catch (e: Exception) { "导入失败: ${e.message}" }
}

private fun doExportWorld(worldDir: File, levelName: String): String {
    return try {
        val safeFileName = levelName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = "导出地图为 .mcworld"
        chooser.selectedFile = File("${safeFileName}.mcworld")
        chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Minecraft 地图文件", "mcworld")
        if (chooser.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return "已取消"
        var dest = chooser.selectedFile ?: return "未选择保存路径"
        if (!dest.name.endsWith(".mcworld", ignoreCase = true)) dest = File(dest.parent, dest.name + ".mcworld")

        ZipOutputStream(dest.outputStream().buffered()).use { zos ->
            worldDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val entryName = worldDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        "✓ 已导出到 ${dest.absolutePath}"
    } catch (e: Exception) { "导出失败: ${e.message}" }
}

/** 尝试从 levelname.txt 读取地图显示名，如不存在则返回 null */
private fun readLevelName(worldDir: File): String? {
    val nameFile = File(worldDir, "levelname.txt")
    if (nameFile.isFile) {
        val name = runCatching { nameFile.readText(Charsets.UTF_8).trim() }.getOrNull()
        if (!name.isNullOrBlank()) return name
    }
    return null
}

private fun formatWorldSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1_024 -> "${"%.0f".format(bytes / 1_024.0)} KB"
    else -> "${bytes} B"
}
