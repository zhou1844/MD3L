package launcher.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import launcher.core.AppLogger
import launcher.core.LogLine

@Composable
fun LogScreen() {
    val isEn = launcher.ui.theme.ThemeState.language == "en"
    val lines = AppLogger.lines
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    var filterQuery by remember { mutableStateOf("") }
    var showErrOnly by remember { mutableStateOf(false) }

    val filtered = remember(lines.toList(), filterQuery, showErrOnly) {
        lines.filter { line ->
            (if (showErrOnly) line.isError else true) &&
            (filterQuery.isBlank() || line.text.contains(filterQuery, ignoreCase = true) || line.tag.contains(filterQuery, ignoreCase = true))
        }
    }

    LaunchedEffect(filtered.size) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // ── 顶部工具栏 ────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Terminal, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(if (isEn) "Launcher Log" else "启动器日志", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(if (isEn) "${filtered.size} lines" else "共 ${filtered.size} 条", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilterChip(
                selected = showErrOnly,
                onClick = { showErrOnly = !showErrOnly },
                label = { Text(if (isEn) "Errors" else "仅错误", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = if (showErrOnly) {{ Icon(Icons.Filled.Check, null, modifier = Modifier.size(13.dp)) }} else null,
                shape = RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            )
            FilterChip(
                selected = autoScroll,
                onClick = { autoScroll = !autoScroll },
                label = { Text(if (isEn) "Auto scroll" else "自动滚动", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = if (autoScroll) {{ Icon(Icons.Filled.Check, null, modifier = Modifier.size(13.dp)) }} else null,
                shape = RoundedCornerShape(10.dp),
            )
            FilledTonalIconButton(
                onClick = { AppLogger.clear() },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = if (isEn) "Clear" else "清空", modifier = Modifier.size(18.dp))
            }
        }

        // ── 搜索栏 ────────────────────────────────────────────────────────────
        OutlinedTextField(
            value = filterQuery,
            onValueChange = { filterQuery = it },
            placeholder = { Text(if (isEn) "Search log…" else "搜索日志…") },
            leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (filterQuery.isNotBlank()) {
                    IconButton(onClick = { filterQuery = "" }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        // ── 日志列表 ──────────────────────────────────────────────────────────
        ElevatedCard(
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Terminal, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                            Text(if (isEn) "No log output yet" else "暂无日志输出", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        itemsIndexed(filtered) { idx, line ->
                            LogLineItem(idx, line)
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
}

@Composable
private fun LogLineItem(index: Int, line: LogLine) {
    val tagColor = when (line.tag) {
        "ERR" -> MaterialTheme.colorScheme.error
        "SYS" -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    }
    val textColor = if (line.isError)
        MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
    else
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    val bgColor = if (line.isError)
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.08f)
    else
        androidx.compose.ui.graphics.Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            line.time,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.width(56.dp),
        )
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = tagColor.copy(alpha = 0.12f),
            modifier = Modifier.width(36.dp),
        ) {
            Text(
                line.tag.take(3),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                color = tagColor,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
        Text(
            line.text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            color = textColor,
            modifier = Modifier.weight(1f),
            softWrap = true,
        )
    }
}
