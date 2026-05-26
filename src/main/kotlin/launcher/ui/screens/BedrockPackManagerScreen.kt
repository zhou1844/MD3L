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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import launcher.core.BedrockLaunchEngine
import launcher.ui.layout.Navigator
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BedrockPackManagerScreen(versionId: String, versionDir: String, packType: String) {
    val scope = rememberCoroutineScope()
    val title = when (packType) { "behavior_packs" -> "行为包管理"; "addon" -> "Addon 管理"; else -> "资源包管理" }
    val packLabel = when (packType) { "behavior_packs" -> "行为包"; "addon" -> "Addon"; else -> "资源包" }

    data class PackEntry(val name: String, val displayName: String, val dir: File, val sizeBytes: Long, val sourceFolder: String = "")

    var packs by remember { mutableStateOf<List<PackEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("") }
    var confirmDeleteTarget by remember { mutableStateOf<PackEntry?>(null) }

    fun resolveProfileDir(): File? {
        return try {
            val minecraftDir = File(versionDir).parentFile?.parentFile?.absolutePath ?: return null
            val engine = BedrockLaunchEngine()
            engine.resolveBedrockVersionComMojang(minecraftDir, versionId)
        } catch (_: Exception) { null }
    }

    fun refresh() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val profileDir = resolveProfileDir()
            val entries = mutableListOf<PackEntry>()
            if (profileDir != null) {
                val dirs = if (packType == "addon") {
                    listOf("behavior_packs" to profileDir.resolve("behavior_packs"),
                           "resource_packs" to profileDir.resolve("resource_packs"))
                } else {
                    listOf(packType to profileDir.resolve(packType))
                }
                for ((folderName, dir) in dirs) {
                    if (!dir.isDirectory) continue
                    dir.listFiles()?.filter { it.isDirectory || it.isFile }
                        ?.sortedBy { it.name.lowercase() }
                        ?.forEach { f ->
                            val size = if (f.isDirectory) f.walkTopDown().sumOf { it.length() } else f.length()
                            val displayName = readPackDisplayName(f) ?: f.name
                            entries.add(PackEntry(f.name, displayName, f, size, folderName))
                        }
                }
            }
            withContext(Dispatchers.Main) {
                packs = entries
                isLoading = false
            }
        }
    }

    LaunchedEffect(versionId, packType) { refresh() }

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
                Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                Text("版本: $versionId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val result = doImportPack(versionDir, versionId, packType, packLabel)
                        withContext(Dispatchers.Main) {
                            statusMessage = result
                            if ("成功" in result) refresh()
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入")
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
                color = if ("成功" in statusMessage) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    statusMessage,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if ("成功" in statusMessage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (packs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Inbox, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))
                    Text("暂无已安装的$packLabel", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val result = doImportPack(versionDir, versionId, packType, packLabel)
                                withContext(Dispatchers.Main) {
                                    statusMessage = result
                                    if ("成功" in result) refresh()
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导入$packLabel")
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
                    items(packs, key = { "${it.sourceFolder}/${it.name}" }) { pack ->
                        ElevatedCard(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val isBehavior = pack.sourceFolder == "behavior_packs" || packType == "behavior_packs"
                                val packIconBitmap: ImageBitmap? = remember(pack.dir.absolutePath) {
                                    val iconFile = if (pack.dir.isDirectory)
                                        pack.dir.resolve("pack_icon.png")
                                    else null
                                    if (iconFile != null && iconFile.exists()) {
                                        runCatching {
                                            org.jetbrains.skia.Image.makeFromEncoded(iconFile.readBytes()).toComposeImageBitmap()
                                        }.getOrNull()
                                    } else null
                                }
                                Box(
                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isBehavior) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.tertiaryContainer
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (packIconBitmap != null) {
                                        Image(
                                            bitmap = packIconBitmap,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        Icon(
                                            if (isBehavior) Icons.Filled.Code else Icons.Filled.Image,
                                            contentDescription = null,
                                            modifier = Modifier.size(22.dp),
                                            tint = if (isBehavior) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onTertiaryContainer,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        pack.displayName,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val typeLabel = when (pack.sourceFolder) {
                                        "behavior_packs" -> "行为包"
                                        "resource_packs" -> "资源包"
                                        else -> ""
                                    }
                                    Text(
                                        buildString {
                                            if (typeLabel.isNotBlank()) append("[$typeLabel] ")
                                            append(formatSize(pack.sizeBytes))
                                            append("  ·  ${pack.name}")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(
                                    onClick = { confirmDeleteTarget = pack },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
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
            title = { Text("删除 ${target.name}？") },
            text = { Text("此操作不可恢复，将从版本存档目录中删除该${packLabel}。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDeleteTarget = null
                        scope.launch(Dispatchers.IO) {
                            val deleted = if (target.dir.isDirectory) target.dir.deleteRecursively() else target.dir.delete()
                            withContext(Dispatchers.Main) {
                                statusMessage = if (deleted) "已删除: ${target.name}" else "删除失败"
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

private fun doImportPack(versionDir: String, versionId: String, packType: String, packLabel: String): String {
    return try {
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = "选择${packLabel}文件 (.mcpack/.mcaddon)"
        chooser.isMultiSelectionEnabled = true
        chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
            "${packLabel}文件", "mcpack", "mcaddon", "zip"
        )
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return "已取消"
        val files = chooser.selectedFiles ?: return "未选择文件"
        val minecraftDir = File(versionDir).parentFile?.parentFile?.absolutePath
            ?: return "无法解析 Minecraft 根目录"
        val engine = BedrockLaunchEngine()
        val profileDir = engine.resolveBedrockVersionComMojang(minecraftDir, versionId)
        var success = 0
        val failed = mutableListOf<String>()
        for (f in files) {
            runCatching { engine.injectAddon(f.absolutePath, profileDir) }
                .onSuccess { success++ }.onFailure { failed.add(f.name) }
        }
        if (failed.isEmpty()) "成功导入 $success 个${packLabel}"
        else "部分成功：$success 个成功，${failed.size} 个失败"
    } catch (e: Exception) { "导入失败: ${e.message}" }
}

/** 从包目录的 manifest.json 读取包的显示名称 */
private fun readPackDisplayName(packFile: File): String? = runCatching {
    val manifest = if (packFile.isDirectory) File(packFile, "manifest.json")
    else return@runCatching null
    if (!manifest.exists()) return@runCatching null
    val json = Json { ignoreUnknownKeys = true }
    val root = json.parseToJsonElement(manifest.readText()).jsonObject
    root["header"]?.jsonObject?.get("name")?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
}.getOrNull()

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1_024 -> "${"%.0f".format(bytes / 1_024.0)} KB"
    else -> "${bytes} B"
}
