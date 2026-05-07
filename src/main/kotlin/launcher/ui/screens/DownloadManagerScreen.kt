package launcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import launcher.core.DownloadHub
import launcher.ui.layout.Navigator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen() {
    val tasks by DownloadHub.tasks.collectAsState()
    val running = tasks.filter { it.status == DownloadHub.TaskStatus.Running }
    val finished = tasks.filter { it.status != DownloadHub.TaskStatus.Running }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 标题栏 ──────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Navigator.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "下载管理",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.weight(1f))
            if (finished.isNotEmpty()) {
                TextButton(onClick = { DownloadHub.clearFinished() }) {
                    Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清除已完成")
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${running.size} 个任务进行中 · ${finished.size} 个已完成",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 48.dp),
        )
        Spacer(Modifier.height(12.dp))

        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.CloudDone,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "暂无下载或安装任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (running.isNotEmpty()) {
                    item {
                        Text(
                            "进行中",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(running, key = { it.id }) { task ->
                        TaskCard(task)
                    }
                }
                if (finished.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "已完成",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(finished, key = { it.id }) { task ->
                        TaskCard(task)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: DownloadHub.HubTask) {
    val isRunning = task.status == DownloadHub.TaskStatus.Running
    val isPaused = task.status == DownloadHub.TaskStatus.Paused
    val isDone = task.status == DownloadHub.TaskStatus.Done
    val isError = task.status == DownloadHub.TaskStatus.Error
    val cardShape = RoundedCornerShape(18.dp)

    ElevatedCard(
        shape = cardShape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = when {
                isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                isDone -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 图标
                val icon = when (task.type) {
                    DownloadHub.TaskType.JavaVersion -> Icons.Filled.SportsEsports
                    DownloadHub.TaskType.BedrockVersion -> Icons.Filled.ViewInAr
                    DownloadHub.TaskType.LoaderInstall -> Icons.Filled.Build
                    DownloadHub.TaskType.ResourceDownload -> Icons.Filled.Inventory
                }
                val tint = when {
                    isError -> MaterialTheme.colorScheme.error
                    isDone -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.tertiary
                }
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        task.step.ifBlank { if (isDone) "完成" else if (isError) task.error else "等待中" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (isRunning || isPaused) 8 else 3,
                        overflow = TextOverflow.Clip,
                    )
                }

                if (isRunning || isPaused || isDone) {
                    val percent = (task.fraction.coerceIn(0f, 1f) * 100).toInt().coerceIn(0, 100)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            "$percent%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }

                if (isRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { DownloadHub.pause(task.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Pause, contentDescription = "暂停", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                        }
                        IconButton(onClick = { DownloadHub.close(task.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                } else if (isPaused) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { DownloadHub.resume(task.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "继续", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { DownloadHub.close(task.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                } else if (isDone) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { DownloadHub.close(task.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                        IconButton(onClick = { DownloadHub.close(task.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if ((isRunning || isPaused) && task.fraction > 0f) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}
