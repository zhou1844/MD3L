package launcher.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import launcher.core.*
import launcher.ui.layout.Navigator
import launcher.ui.nav.Route
import java.awt.Desktop
import java.net.URI
import java.net.URL
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage

private object ModScreenState {
    val searchQuery = mutableStateOf("")
    val projects = mutableStateOf<List<ModrinthProject>>(emptyList())
    val cfProjects = mutableStateOf<List<CfBedrockProject>>(emptyList())
    val isLoading = mutableStateOf(false)
    val selectedEdition = mutableStateOf("java")
    val selectedType = mutableStateOf("mod")
    val bedrockType = mutableStateOf("addon")
    val mcVersionFilter = mutableStateOf("")
    val loaderFilter = mutableStateOf("")
    val isInitialLoad = mutableStateOf(true)
    val loadError = mutableStateOf("")
    val listFirstVisibleItemIndex = mutableStateOf(0)
    val listFirstVisibleItemScrollOffset = mutableStateOf(0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModScreen() {
    val scope = rememberCoroutineScope()
    val isEn = launcher.ui.theme.ThemeState.language == "en"
    var searchQuery by ModScreenState.searchQuery
    var projects by ModScreenState.projects
    var cfProjects by ModScreenState.cfProjects
    var isLoading by ModScreenState.isLoading
    var selectedEdition by ModScreenState.selectedEdition
    var selectedType by ModScreenState.selectedType
    var bedrockType by ModScreenState.bedrockType
    var mcVersionFilter by ModScreenState.mcVersionFilter
    var mcVersionExpanded by remember { mutableStateOf(false) }
    var loaderFilter by ModScreenState.loaderFilter
    var isInitialLoad by ModScreenState.isInitialLoad
    var loadError by ModScreenState.loadError
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = ModScreenState.listFirstVisibleItemIndex.value,
        initialFirstVisibleItemScrollOffset = ModScreenState.listFirstVisibleItemScrollOffset.value,
    )
    val mcVersions = remember { buildMcVersionFilters() }

    LaunchedEffect(Unit) {
        if (!isInitialLoad) return@LaunchedEffect
        isLoading = true
        loadError = ""
        try { projects = ModrinthApi.search(query = "", projectType = "mod")
        } catch (e: Exception) { loadError = "加载失败: ${e.message?.take(60) ?: "未知错误"}" }
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

    fun doSearch() {
        isLoading = true
        loadError = ""
        ModScreenState.listFirstVisibleItemIndex.value = 0
        ModScreenState.listFirstVisibleItemScrollOffset.value = 0
        scope.launch {
            try {
                if (selectedEdition == "bedrock") {
                    val result = BedrockResourceApi.search(query = searchQuery, contentType = bedrockType)
                    cfProjects = result
                    if (result.isEmpty() && searchQuery.isBlank()) loadError = "暂无结果，请检查网络或稍后重试"
                } else {
                    val facets = buildList {
                        if (mcVersionFilter.isNotBlank()) add("""["versions:$mcVersionFilter"]""")
                        if (loaderFilter.isNotBlank() && selectedType == "mod") add("""["categories:$loaderFilter"]""")
                    }.joinToString(",")
                    val result = ModrinthApi.search(query = searchQuery, projectType = selectedType, facets = facets)
                    projects = result
                    if (result.isEmpty() && searchQuery.isBlank()) loadError = "加载失败，请检查网络连接"
                }
            } catch (e: Exception) {
                loadError = "加载失败: ${e.message?.take(60) ?: "未知错误"}"
            }
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 标题区 + 版本切换 ──────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Extension, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(if (isEn) "Resource Center" else "资源中心", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text("Modrinth · CurseForge", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 版本切换内嵌到标题行右侧
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.height(36.dp),
            ) {
                Row(modifier = Modifier.padding(3.dp)) {
                    listOf("java" to (if (isEn) "Java" else "Java") to Icons.Filled.Coffee,
                           "bedrock" to (if (isEn) "Bedrock" else "基岩") to Icons.Filled.Diamond).forEach { (pair, icon) ->
                        val (edition, label) = pair
                        val selected = selectedEdition == edition
                        Surface(
                            shape = RoundedCornerShape(17.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            modifier = Modifier.clip(RoundedCornerShape(17.dp)).clickable {
                                if (selectedEdition != edition) {
                                    selectedEdition = edition; searchQuery = ""; isLoading = true
                                    scope.launch {
                                        if (edition == "bedrock") cfProjects = BedrockResourceApi.search("", bedrockType)
                                        else projects = ModrinthApi.search("", selectedType)
                                        isLoading = false
                                    }
                                }
                            },
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(icon, null, modifier = Modifier.size(13.dp), tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── 搜索框（卡片式）─────────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            if (isEn) (if (selectedEdition == "bedrock") "Search CurseForge Bedrock…" else "Search Modrinth resources…")
                            else (if (selectedEdition == "bedrock") "搜索 CurseForge 基岩版资源…" else "搜索资源（支持中文翻译）…"),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) IconButton(onClick = { searchQuery = ""; doSearch() }) {
                            Icon(Icons.Filled.Close, null, modifier = Modifier.size(17.dp))
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                        if (e.key == Key.Enter && e.type == KeyEventType.KeyDown) { doSearch(); true } else false
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        unfocusedBorderColor = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                if (selectedEdition == "java") {
                    Spacer(Modifier.width(6.dp))
                    ExposedDropdownMenuBox(expanded = mcVersionExpanded, onExpandedChange = { mcVersionExpanded = it }) {
                        OutlinedTextField(
                            value = mcVersionFilter.ifBlank { if (isEn) "All" else "全部" },
                            onValueChange = {}, readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(mcVersionExpanded) },
                            singleLine = true, shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.width(96.dp).menuAnchor(),
                            textStyle = MaterialTheme.typography.bodySmall,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                        ExposedDropdownMenu(expanded = mcVersionExpanded, onDismissRequest = { mcVersionExpanded = false }) {
                            mcVersions.forEach { ver ->
                                DropdownMenuItem(text = { Text(ver.ifBlank { if (isEn) "All" else "全部" }, style = MaterialTheme.typography.bodySmall) },
                                    onClick = { mcVersionFilter = ver; mcVersionExpanded = false; doSearch() })
                            }
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(
                    onClick = { doSearch() },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                ) { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── 过滤 Pills ───────────────────────────────────────────────────────
        if (selectedEdition == "java") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 8.dp)) {
                val types = if (isEn)
                    listOf("mod" to "Mods", "resourcepack" to "Resource Packs", "shader" to "Shaders", "modpack" to "Modpacks")
                else listOf("mod" to "模组", "resourcepack" to "材质包", "shader" to "光影", "modpack" to "整合包")
                items(types) { (type, label) ->
                    ModPill(label = label, selected = selectedType == type, primary = true) {
                        if (selectedType != type) { selectedType = type; loaderFilter = ""; doSearch() }
                    }
                }
                if (selectedType == "mod") {
                    item { Spacer(Modifier.width(4.dp)) }
                    val loaders = listOf("" to (if (isEn) "All" else "全部"), "fabric" to "Fabric", "forge" to "Forge", "neoforge" to "NeoForge", "quilt" to "Quilt")
                    items(loaders) { (loader, label) ->
                        ModPill(label = label, selected = loaderFilter == loader, primary = false) {
                            loaderFilter = loader; doSearch()
                        }
                    }
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val types = if (isEn)
                    listOf("addon" to "Addon", "texture_pack" to "Texture", "map" to "Maps", "skin" to "Skins")
                else listOf("addon" to "Addon", "texture_pack" to "材质包", "map" to "地图", "skin" to "皮肤")
                items(types) { (type, label) ->
                    ModPill(label = label, selected = bedrockType == type, primary = true) {
                        if (bedrockType != type) {
                            bedrockType = type; isLoading = true
                            scope.launch { cfProjects = BedrockResourceApi.search(searchQuery, type); isLoading = false }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── 内容列表 ─────────────────────────────────────────────────────────
        AnimatedContent(
            targetState = isLoading,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.fillMaxSize(),
        ) { loading ->
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 3.5.dp)
                        Text(if (isEn) "Searching…" else "正在搜索…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (loadError.isNotBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(80.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.WifiOff, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                        }
                        Text(if (isEn) "Load failed" else "加载失败", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text(loadError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        FilledTonalButton(onClick = { doSearch() }, shape = RoundedCornerShape(18.dp)) {
                            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isEn) "Retry" else "重试")
                        }
                    }
                }
            } else if (selectedEdition == "bedrock") {
                if (cfProjects.isEmpty()) ModEmptyState(isEn)
                else Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
                        items(cfProjects, key = { it.modId }) { CfProjectCard(it) }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            } else {
                if (projects.isEmpty()) ModEmptyState(isEn)
                else Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
                        items(projects, key = { it.slug }) { ModProjectCard(it, "java", selectedType) }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun ModPill(label: String, selected: Boolean, primary: Boolean, onClick: () -> Unit) {
    val containerColor = when {
        selected && primary -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        selected && primary -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(containerColor)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal), color = contentColor)
    }
}

@Composable
private fun ModEmptyState(isEn: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.SearchOff, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
            }
            Text(if (isEn) "No results" else "暂无结果", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (isEn) "Try different keywords or filters" else "换个关键词或筛选条件试试", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun CfProjectCard(project: CfBedrockProject) {
    ElevatedCard(
        onClick = { Navigator.navigate(Route.CfBedrockDetail(project)) },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ModIconAsync(iconUrl = project.iconUrl, projectType = project.contentType, size = 56)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(project.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    SourceBadge("CF", isCf = true)
                }
                Spacer(Modifier.height(4.dp))
                Text(project.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetaChip(icon = Icons.Filled.Download, text = formatDl(project.downloads))
                    MetaChip(icon = Icons.Filled.Person, text = project.author)
                    if (project.gameVersions.isNotEmpty()) {
                        MetaChip(icon = Icons.Filled.Tag, text = project.gameVersions.first())
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModProjectCard(project: ModrinthProject, edition: String, contentType: String) {
    ElevatedCard(
        onClick = { Navigator.navigate(Route.ModDetail(project, edition, contentType)) },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ModIconAsync(iconUrl = project.iconUrl, projectType = project.projectType, size = 56)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(project.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    SourceBadge("MR", isCf = false)
                }
                Spacer(Modifier.height(4.dp))
                Text(project.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (project.downloads >= 0) MetaChip(icon = Icons.Filled.Download, text = formatDl(project.downloads))
                    if (project.author.isNotBlank()) MetaChip(icon = Icons.Filled.Person, text = project.author)
                    if (project.categories.isNotEmpty()) MetaChip(icon = Icons.Filled.Label, text = project.categories.first())
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun SourceBadge(label: String, isCf: Boolean) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (isCf) Color(0xFFE8640C).copy(alpha = 0.18f) else Color(0xFF1BD96A).copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
            color = if (isCf) Color(0xFFE8640C) else Color(0xFF18A850))
    }
}

@Composable
private fun MetaChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
internal fun ModIconAsync(iconUrl: String, projectType: String, size: Int = 48) {
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
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(if (size >= 56) 14.dp else 10.dp)),
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
