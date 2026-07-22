package launcher.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import launcher.core.ModToggleManager
import launcher.ui.layout.Navigator
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JavaModManagerScreen(versionId: String, versionDir: String) {
    val scope = rememberCoroutineScope()
    val isEn = launcher.ui.theme.ThemeState.language == "en"
    val title = if (isEn) "Mod Management" else "模组管理"

    var mods by remember { mutableStateOf<List<ModToggleManager.ModItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("") }
    var confirmDeleteTarget by remember { mutableStateOf<ModToggleManager.ModItem?>(null) }

    val refresh: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            mods = ModToggleManager.scanJavaMods(versionDir)
            isLoading = false
        }
    }

    LaunchedEffect(versionDir) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            (if (isEn) "Version: " else "版本: ") + versionId,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { Navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (isEn) "Back" else "返回")
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val result = doImportMod(versionDir, versionId)
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
                        Text(if (isEn) "Import" else "导入")
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = if (isEn) "Refresh" else "刷新", tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

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
            } else if (mods.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Inbox,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (isEn) "No mods installed" else "未找到模组",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (isEn) "Put .jar files in the version's mods/ folder, or use the Import button above." else "将 .jar 文件放入版本目录的 mods/ 文件夹中，或点击上方\"导入\"按钮。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val result = doImportMod(versionDir, versionId)
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
                            Text(if (isEn) "Import Mod" else "导入模组")
                        }
                    }
                }
            } else {
                val listState = rememberLazyListState()
                val enabledCount = mods.count { it.isEnabled }
                val totalCount = mods.size

                // Summary bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isEn) "$enabledCount / $totalCount enabled" else "$enabledCount / $totalCount 已启用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (totalCount > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                scope.launch {
                                    mods.filter { it.isEnabled }.forEach { m ->
                                        ModToggleManager.toggleJavaMod(versionDir, m.filePath, false)
                                    }
                                    mods = ModToggleManager.scanJavaMods(versionDir)
                                }
                            }) { Text(if (isEn) "Disable All" else "全部禁用", style = MaterialTheme.typography.labelSmall) }
                            TextButton(onClick = {
                                scope.launch {
                                    mods.filter { !it.isEnabled }.forEach { m ->
                                        ModToggleManager.toggleJavaMod(versionDir, m.filePath, true)
                                    }
                                    mods = ModToggleManager.scanJavaMods(versionDir)
                                }
                            }) { Text(if (isEn) "Enable All" else "全部启用", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                    ) {
                        items(mods, key = { it.filePath }) { mod ->
                            ElevatedCard(
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (mod.isEnabled)
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    else
                                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // File icon
                                    Box(
                                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.secondaryContainer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            if (mod.isEnabled) Icons.Filled.Extension else Icons.Filled.ExtensionOff,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = if (mod.isEnabled)
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            mod.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (mod.isEnabled)
                                                MaterialTheme.colorScheme.onSurface
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        )
                                        Text(
                                            if (mod.isEnabled)
                                                (if (isEn) "Enabled" else "已启用")
                                            else
                                                (if (isEn) "Disabled" else "已禁用"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (mod.isEnabled)
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            else
                                                MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        )
                                    }
                                    // Enable/Disable toggle
                                    Switch(
                                        checked = mod.isEnabled,
                                        onCheckedChange = { checked ->
                                            scope.launch {
                                                val ok = ModToggleManager.toggleJavaMod(versionDir, mod.filePath, checked)
                                                if (ok) {
                                                    mods = ModToggleManager.scanJavaMods(versionDir)
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(width = 40.dp, height = 24.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    // Delete button
                                    IconButton(
                                        onClick = { confirmDeleteTarget = mod },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.DeleteOutline,
                                            contentDescription = if (isEn) "Delete" else "删除",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
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
    }

    // 删除确认对话框
    val target = confirmDeleteTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTarget = null },
            title = { Text(if (isEn) "Delete ${target.name}?" else "删除 ${target.name}？") },
            text = { Text(if (isEn) "This action is irreversible. The .jar file will be permanently deleted." else "此操作不可恢复，将永久删除该 .jar 文件。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDeleteTarget = null
                        scope.launch(Dispatchers.IO) {
                            val deleted = File(target.filePath).delete()
                            withContext(Dispatchers.Main) {
                                statusMessage = if (deleted)
                                    (if (isEn) "Deleted: ${target.name}" else "已删除: ${target.name}")
                                else
                                    (if (isEn) "Delete failed" else "删除失败")
                                refresh()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(if (isEn) "Delete" else "删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteTarget = null }) { Text(if (isEn) "Cancel" else "取消") }
            },
        )
    }
}

@Suppress("UNUSED_PARAMETER")
private fun doImportMod(versionDir: String, versionId: String): String {
    return try {
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = "选择模组文件 (.jar)"
        chooser.isMultiSelectionEnabled = true
        chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("模组文件", "jar")
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return "已取消"
        val files = chooser.selectedFiles ?: return "未选择文件"

        val gameDir = File(versionDir, ".minecraft").takeIf { it.isDirectory } ?: File(versionDir)
        val modsDir = File(gameDir, "mods")
        modsDir.mkdirs()

        var success = 0
        val failed = mutableListOf<String>()
        for (f in files) {
            val dest = File(modsDir, f.name)
            try {
                if (f.absolutePath != dest.absolutePath) f.copyTo(dest, overwrite = true)
                success++
            } catch (e: Exception) {
                failed.add(f.name)
            }
        }
        if (failed.isEmpty()) "成功导入 $success 个模组"
        else "部分成功：$success 个成功，${failed.size} 个失败"
    } catch (e: Exception) { "导入失败: ${e.message}" }
}
