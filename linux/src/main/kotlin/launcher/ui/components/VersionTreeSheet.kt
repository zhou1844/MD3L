package launcher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import launcher.core.RemoteVersion

/**
 * 二级树状版本选择器 —— 作为 ModalBottomSheet 的内容。
 *
 * 数据源是一个二级树状结构：
 *   Node.Java   → Release | Snapshot | Old Beta/Alpha
 *   Node.Bedrock → Release | Preview
 *
 * 点击全局版本锚点时弹出此 Sheet。
 */

sealed class VersionTreeNode(val label: String, val icon: @Composable () -> Unit) {
    abstract val children: List<VersionCategory>
}

data class VersionCategory(
    val label: String,
    val type: String,
    val versions: List<RemoteVersion>,
)

class JavaVersionTree(
    releases: List<RemoteVersion>,
    snapshots: List<RemoteVersion>,
    oldBeta: List<RemoteVersion>,
    oldAlpha: List<RemoteVersion>,
) : VersionTreeNode(
    label = "Java Edition",
    icon = { Icon(Icons.Filled.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
) {
    override val children = listOf(
        VersionCategory("正式版 (Release)", "release", releases),
        VersionCategory("快照 (Snapshot)", "snapshot", snapshots.filter { it.id !in launcher.core.VersionScanner.aprilFoolIds }),
        VersionCategory("愚人节 (April Fool)", "april_fool", snapshots.filter { it.id in launcher.core.VersionScanner.aprilFoolIds }),
        VersionCategory("远古 Beta", "old_beta", oldBeta),
        VersionCategory("远古 Alpha", "old_alpha", oldAlpha),
    ).filter { it.versions.isNotEmpty() }
}

class BedrockVersionTree(
    releases: List<RemoteVersion>,
    previews: List<RemoteVersion>,
) : VersionTreeNode(
    label = "Bedrock Edition",
    icon = { Icon(Icons.Filled.ViewInAr, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
) {
    override val children = listOf(
        VersionCategory("正式版 (Release)", "release", releases),
        VersionCategory("预览版 (Preview)", "preview", previews),
    )
}

/**
 * 将平坦的远程版本列表构建为二级树。
 */
fun buildVersionTree(javaVersions: List<RemoteVersion>, bedrockVersions: List<RemoteVersion> = emptyList()): List<VersionTreeNode> {
    val tree = mutableListOf<VersionTreeNode>()

    if (javaVersions.isNotEmpty()) {
        tree.add(
            JavaVersionTree(
                releases = javaVersions.filter { it.type == "release" },
                snapshots = javaVersions.filter { it.type == "snapshot" },
                oldBeta = javaVersions.filter { it.type == "old_beta" },
                oldAlpha = javaVersions.filter { it.type == "old_alpha" },
            )
        )
    }

    if (bedrockVersions.isNotEmpty()) {
        tree.add(
            BedrockVersionTree(
                releases = bedrockVersions.filter { it.type == "release" },
                previews = bedrockVersions.filter { it.type == "preview" },
            )
        )
    }

    return tree
}

// ═════════════════════════════════════════════════════════════════════════════
//  本地版本树（数据源严格锁死为 VersionScanner 扫描结果）
// ═════════════════════════════════════════════════════════════════════════════

data class LocalVersionCategory(
    val label: String,
    val type: String,
    val versions: List<launcher.core.LocalVersion>,
)

/**
 * 将本地版本平坦列表构建为二级分类树：
 * - 正式版 (release)
 * - 快照 (snapshot)
 * - 远古 Beta (old_beta)
 * - 远古 Alpha (old_alpha)
 *
 * 数据源**严格**来自 VersionScanner，绝不混入远端 Manifest。
 */
fun buildLocalVersionTree(
    localVersions: List<launcher.core.LocalVersion>,
    bedrockVersions: List<launcher.core.LocalVersion> = emptyList(),
): List<LocalVersionCategory> {
    return listOf(
        LocalVersionCategory("正式版 (Release)", "release", localVersions.filter { it.type == "release" }),
        LocalVersionCategory("快照 (Snapshot)", "snapshot", localVersions.filter { it.type == "snapshot" && it.id !in launcher.core.VersionScanner.aprilFoolIds }),
        LocalVersionCategory("愚人节 (April Fool)", "april_fool", localVersions.filter { it.id in launcher.core.VersionScanner.aprilFoolIds }),
        LocalVersionCategory("基岩版 (Bedrock)", "bedrock", bedrockVersions),
        LocalVersionCategory("远古 Beta", "old_beta", localVersions.filter { it.type == "old_beta" }),
        LocalVersionCategory("远古 Alpha", "old_alpha", localVersions.filter { it.type == "old_alpha" }),
    ).filter { it.versions.isNotEmpty() }
}

@Composable
fun LocalVersionTreeSheetContent(
    localVersions: List<launcher.core.LocalVersion>,
    onVersionSelected: (launcher.core.LocalVersion) -> Unit,
    modifier: Modifier = Modifier,
    bedrockVersions: List<launcher.core.LocalVersion> = emptyList(),
) {
    val categories = remember(localVersions, bedrockVersions) { buildLocalVersionTree(localVersions, bedrockVersions) }
    var expandedCategory by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            "选择已安装版本",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "仅显示本地已下载的版本 · 共 ${localVersions.size + bedrockVersions.size} 个",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (localVersions.isEmpty() && bedrockVersions.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Inbox, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))
                    Text("暂无本地版本", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("请前往下载中心安装版本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
            ) {
                categories.forEach { category ->
                    val catExpanded = expandedCategory == category.type
                    item(key = "lcat_group_${category.type}") {
                        // MD3 Standard Decelerate 曲线
                        val md3StandardDecelerate = remember { CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f) }
                        
                        val containerColor by animateColorAsState(
                            if (catExpanded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                        )
                        
                        Column(modifier = Modifier.fillMaxWidth().animateContentSize(tween(350, easing = md3StandardDecelerate))) {
                            ElevatedCard(
                                onClick = { expandedCategory = if (catExpanded) null else category.type },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (catExpanded) 0.dp else 1.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        category.label,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${category.versions.size} 个",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        if (catExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            
                            AnimatedVisibility(
                                visible = catExpanded,
                                enter = expandVertically(tween(350, easing = md3StandardDecelerate)) + fadeIn(),
                                exit = shrinkVertically(tween(200, easing = md3StandardDecelerate)) + fadeOut(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    category.versions.forEach { version ->
                                        Surface(
                                            onClick = { onVersionSelected(version) },
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(start = 20.dp, top = 2.dp, bottom = 2.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                VersionIcon(
                                                    loaderType = version.loaderType,
                                                    versionType = version.type,
                                                    size = 24,
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        version.id,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        "${version.loaderType.name} · ${version.type}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                    )
                                                }
                                                Icon(
                                                    Icons.Filled.PlayArrow,
                                                    contentDescription = "选择",
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  远端版本树（仅用于下载中心，不在启动页使用）
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionTreeSheetContent(
    tree: List<VersionTreeNode>,
    onVersionSelected: (RemoteVersion) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedNode by remember { mutableStateOf<String?>(null) }
    var expandedCategory by remember { mutableStateOf<String?>(null) }
    // 缓存 MD3 曲线，避免每次重组重新分配
    val md3StandardDecelerate = remember { CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f) }

    Column(modifier = modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            "选择版本",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "从版本树中选择要安装或启动的版本",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
        ) {
            // ── 一级节点 (Java / Bedrock) ────────────────────────────────
            tree.forEach { node ->
                val nodeExpanded = expandedNode == node.label
                item(key = "node_${node.label}") {
                    val containerColor by animateColorAsState(
                        if (nodeExpanded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    )
                    
                    ElevatedCard(
                        onClick = {
                            expandedNode = if (nodeExpanded) null else node.label
                            expandedCategory = null
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (nodeExpanded) 0.dp else 1.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            node.icon()
                            Spacer(Modifier.width(12.dp))
                            Text(
                                node.label,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f),
                            )
                            val totalCount = node.children.sumOf { it.versions.size }
                            Text(
                                "$totalCount 个版本",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                if (nodeExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ── 二级分类 (Release / Snapshot / ...) ──────────────────
                if (nodeExpanded) {
                    node.children.filter { it.versions.isNotEmpty() }.forEach { category ->
                        val catKey = "${node.label}_${category.label}"
                        val catExpanded = expandedCategory == catKey

                        item(key = "cat_group_$catKey") {
                            val catColor by animateColorAsState(
                                if (catExpanded) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.surfaceContainer,
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                            )
                            val iconRotation by animateFloatAsState(
                                targetValue = if (catExpanded) 180f else 0f,
                                animationSpec = tween(200, easing = md3StandardDecelerate)
                            )
                            
                            Column(modifier = Modifier.fillMaxWidth().animateContentSize(tween(350, easing = md3StandardDecelerate))) {
                                Surface(
                                    onClick = { expandedCategory = if (catExpanded) null else catKey },
                                    shape = RoundedCornerShape(10.dp),
                                    color = catColor,
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(start = 20.dp, top = 2.dp, bottom = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.FolderOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            category.label,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "${category.versions.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            if (catExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp).graphicsLayer(rotationZ = iconRotation),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                AnimatedVisibility(
                                    visible = catExpanded,
                                    enter = expandVertically(tween(350, easing = md3StandardDecelerate)) + fadeIn(),
                                    exit = shrinkVertically(tween(200, easing = md3StandardDecelerate)) + fadeOut(),
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                                        val displayVersions = category.versions.take(100)
                                        displayVersions.forEach { version ->
                                            Surface(
                                                onClick = { onVersionSelected(version) },
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                modifier = Modifier.fillMaxWidth()
                                                    .padding(start = 44.dp, top = 2.dp, bottom = 2.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    VersionIcon(
                                                        loaderType = launcher.core.LoaderType.Vanilla,
                                                        versionType = version.type,
                                                        size = 24,
                                                    )
                                                    Spacer(Modifier.width(10.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            version.id,
                                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        if (version.releaseTime.isNotBlank()) {
                                                            Text(
                                                                version.releaseTime.take(10),
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                            )
                                                        }
                                                    }
                                                    Icon(
                                                        Icons.Filled.Download,
                                                        contentDescription = "安装",
                                                        modifier = Modifier.size(18.dp),
                                                        tint = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
