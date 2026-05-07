package launcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import launcher.core.*
import launcher.ui.layout.Navigator
import launcher.ui.nav.Route
import java.io.File
import java.net.URL
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage

private object ModScreenState {
    val searchQuery = mutableStateOf("")
    val projects = mutableStateOf<List<ModrinthProject>>(emptyList())
    val isLoading = mutableStateOf(false)
    val selectedEdition = mutableStateOf("java")
    val selectedType = mutableStateOf("mod")
    val mcVersionFilter = mutableStateOf("")
    val loaderFilter = mutableStateOf("")
    val isInitialLoad = mutableStateOf(true)
    val listFirstVisibleItemIndex = mutableStateOf(0)
    val listFirstVisibleItemScrollOffset = mutableStateOf(0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModScreen() {
    val scope = rememberCoroutineScope()
    var searchQuery by ModScreenState.searchQuery
    var projects by ModScreenState.projects
    var isLoading by ModScreenState.isLoading
    var selectedEdition by ModScreenState.selectedEdition
    var selectedType by ModScreenState.selectedType
    var mcVersionFilter by ModScreenState.mcVersionFilter
    var mcVersionExpanded by remember { mutableStateOf(false) }
    var loaderFilter by ModScreenState.loaderFilter
    var isInitialLoad by ModScreenState.isInitialLoad
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = ModScreenState.listFirstVisibleItemIndex.value,
        initialFirstVisibleItemScrollOffset = ModScreenState.listFirstVisibleItemScrollOffset.value,
    )

    val mcVersions = remember { buildMcVersionFilters() }

    LaunchedEffect(Unit) {
        if (!isInitialLoad) return@LaunchedEffect
        isLoading = true
        projects = ModrinthApi.search(query = "", projectType = "mod")
        isLoading = false
        isInitialLoad = false
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                ModScreenState.listFirstVisibleItemIndex.value = index
                ModScreenState.listFirstVisibleItemScrollOffset.value = offset
            }
    }

    fun sourceType(): String = when (selectedType) {
        "behaviorpack" -> "mod"
        "bedrock_resourcepack" -> "resourcepack"
        "bedrock_modpack" -> "modpack"
        else -> selectedType
    }

    fun doSearch() {
        isLoading = true
        ModScreenState.listFirstVisibleItemIndex.value = 0
        ModScreenState.listFirstVisibleItemScrollOffset.value = 0
        scope.launch {
            projects = if (selectedEdition == "bedrock") {
                BedrockResourceApi.search(searchQuery, selectedType)
            } else {
                val facets = buildList {
                    if (mcVersionFilter.isNotBlank()) add("""["versions:$mcVersionFilter"]""")
                    if (loaderFilter.isNotBlank() && selectedType == "mod") add("""["categories:$loaderFilter"]""")
                }.joinToString(",")
                ModrinthApi.search(query = searchQuery, projectType = sourceType(), facets = facets)
            }
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("模组 & 资源中心", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(
            "来源：Modrinth/CurseForge · 点击卡片查看版本详情并下载安装到目标版本",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("java" to "Java版").forEach { (edition, label) ->
                FilterChip(
                    selected = selectedEdition == edition,
                    onClick = {
                        selectedEdition = edition
                        selectedType = "mod"
                        loaderFilter = ""
                        doSearch()
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = {
                        Icon(
                            if (edition == "java") Icons.Filled.Coffee else Icons.Filled.ViewInAr,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // ── 搜索 + MC版本过滤 ────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索资源（支持中文自动翻译）…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) IconButton(onClick = { searchQuery = ""; doSearch() }) { Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp)) }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                            doSearch(); true
                        } else false
                    },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(6.dp))
            ExposedDropdownMenuBox(expanded = mcVersionExpanded, onExpandedChange = { mcVersionExpanded = it }) {
                OutlinedTextField(
                    value = mcVersionFilter.ifBlank { "全部" },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("MC", style = MaterialTheme.typography.labelSmall) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(mcVersionExpanded) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.width(110.dp).menuAnchor(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                ExposedDropdownMenu(expanded = mcVersionExpanded, onDismissRequest = { mcVersionExpanded = false }) {
                    mcVersions.forEach { ver ->
                        DropdownMenuItem(text = { Text(ver.ifBlank { "全部" }) }, onClick = { mcVersionFilter = ver; mcVersionExpanded = false; doSearch() })
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(onClick = { doSearch() }, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))

        if (selectedEdition == "java" && selectedType == "mod") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("加载器", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                listOf("" to "全部", "fabric" to "Fabric", "forge" to "Forge", "neoforge" to "NeoForge", "quilt" to "Quilt").forEach { (loader, label) ->
                    FilterChip(
                        selected = loaderFilter == loader,
                        onClick = { loaderFilter = loader; doSearch() },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(end = 4.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── 类型过滤 ─────────────────────────────────────────────────────────
        Row {
            val typeItems = if (selectedEdition == "java") {
                listOf("shader" to "光影", "mod" to "模组", "resourcepack" to "材质包", "modpack" to "整合包")
            } else {
                listOf("behaviorpack" to "行为包", "bedrock_resourcepack" to "材质包", "bedrock_modpack" to "整合包")
            }
            typeItems.forEach { (type, label) ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { selectedType = type; doSearch() },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(end = 4.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // ── 结果列表 ─────────────────────────────────────────────────────────
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
        } else if (projects.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (isInitialLoad) Icons.Filled.Extension else Icons.Filled.SearchOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))
                    Text(if (isInitialLoad) "加载中…" else "未找到结果", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(projects, key = { it.slug }) { project ->
                    ModProjectCard(project, selectedEdition, selectedType)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModProjectCard(project: ModrinthProject, edition: String, contentType: String) {
    var downloadingThis by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }

    ElevatedCard(
        onClick = { Navigator.navigate(Route.ModDetail(project, edition, contentType)) },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // ── 模组图标 (异步网络加载) ───────────────────────────────────
                ModIconAsync(
                    iconUrl = project.iconUrl,
                    projectType = project.projectType,
                    size = 48,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(project.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(project.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (project.downloads >= 0) Icons.Filled.Download else Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.width(3.dp))
                        Text(if (project.downloads >= 0) formatDl(project.downloads) else project.author.ifBlank { "ModBay" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        if (project.categories.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(project.categories.take(2).joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        }
                    }
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
            }
            // 下载进度条
            if (downloadingThis) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

/**
 * 全局 LRU 内存缓存 —— 512 张图，滑出屏幕再滑回 0ms 复用，绝不二次网络请求。
 */
private object IconLruCache {
    private const val MAX_SIZE = 512
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > MAX_SIZE
        }
    }
    private val lock = Any()
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun get(key: String): ImageBitmap? = synchronized(lock) { cache[key] }

    fun put(key: String, bitmap: ImageBitmap) = synchronized(lock) { cache[key] = bitmap }

    fun markInflight(key: String): Boolean = inflight.putIfAbsent(key, true) == null

    fun clearInflight(key: String) { inflight.remove(key) }
}

/**
 * 异步加载模组网络图标。LRU 内存缓存注入，彻底消灭 LazyColumn 滑动闪烁。
 */
@Composable
private fun ModIconAsync(iconUrl: String, projectType: String, size: Int = 48) {
    var bitmap by remember(iconUrl) { mutableStateOf(IconLruCache.get(iconUrl)) }
    var loaded by remember(iconUrl) { mutableStateOf(bitmap != null) }

    LaunchedEffect(iconUrl) {
        if (bitmap != null) return@LaunchedEffect
        if (iconUrl.isNotBlank()) {
            val cached = IconLruCache.get(iconUrl)
            if (cached != null) {
                bitmap = cached
                loaded = true
                return@LaunchedEffect
            }
            withContext(Dispatchers.IO) {
                if (!IconLruCache.markInflight(iconUrl)) return@withContext
                try {
                    val bytes = URL(iconUrl).readBytes()
                    val skImage = SkiaImage.makeFromEncoded(bytes)
                    val bmp = skImage.toComposeImageBitmap()
                    IconLruCache.put(iconUrl, bmp)
                    bitmap = bmp
                } catch (_: Exception) {
                } finally {
                    IconLruCache.clearInflight(iconUrl)
                }
            }
        }
        loaded = true
    }

    val bmp = bitmap
    if (bmp != null) {
        androidx.compose.foundation.Image(
            painter = BitmapPainter(bmp),
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
            modifier = Modifier.size(size.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                val icon = when (projectType) {
                    "shader" -> Icons.Filled.AutoAwesome
                    "resourcepack" -> Icons.Filled.Palette
                    "modpack" -> Icons.Filled.Inventory
                    else -> Icons.Filled.Extension
                }
                if (loaded) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), modifier = Modifier.size((size / 2).dp))
                } else {
                    CircularProgressIndicator(modifier = Modifier.size((size / 3).dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

private fun formatDl(c: Long): String = when {
    c >= 1_000_000 -> "${"%.1f".format(c / 1_000_000.0)}M"
    c >= 1_000 -> "${"%.1f".format(c / 1_000.0)}K"
    else -> "$c"
}

private fun buildMcVersionFilters(): List<String> {
    return listOf(
        "",
        "1.21.8", "1.21.7", "1.21.6", "1.21.5", "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21",
        "1.20.6", "1.20.5", "1.20.4", "1.20.3", "1.20.2", "1.20.1", "1.20",
        "1.19.4", "1.19.3", "1.19.2", "1.19.1", "1.19",
        "1.18.2", "1.18.1", "1.18",
        "1.17.1", "1.17",
        "1.16.5", "1.16.4", "1.16.3", "1.16.2", "1.16.1", "1.16",
        "1.15.2", "1.15.1", "1.15",
        "1.14.4", "1.14.3", "1.14.2", "1.14.1", "1.14",
        "1.13.2", "1.13.1", "1.13",
        "1.12.2", "1.12.1", "1.12",
        "1.11.2", "1.11.1", "1.11",
        "1.10.2", "1.10.1", "1.10",
        "1.9.4", "1.9.3", "1.9.2", "1.9.1", "1.9",
        "1.8.9", "1.8.8", "1.8",
        "1.7.10", "1.7.2",
        "1.6.4", "1.6.2",
        "1.5.2", "1.5.1",
        "1.4.7", "1.4.6", "1.4.2",
        "1.3.2", "1.3.1",
        "1.2.5", "1.2.4",
        "1.1", "1.0",
        "b1.7.3", "b1.6.6", "a1.2.6",
    )
}
