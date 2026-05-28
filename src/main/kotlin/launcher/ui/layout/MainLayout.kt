package launcher.ui.layout

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import launcher.core.GameProcessManager
import launcher.core.LaunchState
import launcher.ui.components.DownloadFab
import launcher.ui.nav.Route
import launcher.ui.nav.Screen
import launcher.ui.nav.primaryTab
import launcher.ui.screens.*
import launcher.ui.theme.ThemeState
import launcher.core.AutoUpdater
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object Navigator {
    var current by mutableStateOf<Route>(Route.Launch)
        private set
    var lastPrimaryTap by mutableStateOf<Screen>(Screen.Launch)
        private set
    var initialized by mutableStateOf(false)
        private set

    fun applyStartupPage(page: String) {
        if (initialized) return
        initialized = true
        val screen = when (page) {
            "versions" -> Screen.Versions
            "download" -> Screen.Download
            else -> Screen.Launch
        }
        navigatePrimary(screen)
    }

    private val backStack = mutableListOf<Route>()

    fun navigate(route: Route) {
        backStack.add(current)
        current = route
    }

    fun back() {
        if (backStack.isNotEmpty()) {
            current = backStack.removeLast()
        }
    }

    fun navigatePrimary(screen: Screen) {
        backStack.clear()
        lastPrimaryTap = screen
        current = when (screen) {
            Screen.Launch -> Route.Launch
            Screen.Versions -> Route.Versions
            Screen.Download -> Route.Download
            Screen.Mods -> Route.Mods
            Screen.Settings -> Route.Settings
            Screen.Log -> Route.Log
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainLayout(modifier: Modifier = Modifier) {
    // 应用默认起始页设置
    LaunchedEffect(ThemeState.startupPage) {
        Navigator.applyStartupPage(ThemeState.startupPage)
    }

    val currentRoute = Navigator.current
    val activeTab = currentRoute.primaryTab()
    val isLaunching by LaunchState.isLaunching.collectAsState()
    val activeProcess by GameProcessManager.activeProcess.collectAsState()
    val uiLocked = isLaunching || activeProcess != null

    val bgPath = ThemeState.backgroundImagePath
    val bgBlur = ThemeState.backgroundBlurRadius
    val bgBrightness = ThemeState.backgroundBrightness
    // bgKey 只依赖路径，模糊由 GPU layer 实时处理，切换模糊度 0 延迟
    val bgKey = bgPath
    var bgBitmap by remember { mutableStateOf(if (ThemeState.cachedBgKey == bgKey) ThemeState.cachedBgBitmap else null) }
    LaunchedEffect(bgKey) {
        if (ThemeState.cachedBgKey == bgKey && ThemeState.cachedBgBitmap != null) {
            bgBitmap = ThemeState.cachedBgBitmap
            return@LaunchedEffect
        }
        bgBitmap = if (bgPath.isNotBlank()) withContext(Dispatchers.IO) {
            runCatching {
                var src = ImageIO.read(File(bgPath)) ?: return@runCatching null
                // 降采样到 1280px 供 GPU 渲染，无需 CPU 模糊
                val maxDim = 1280
                if (src.width > maxDim || src.height > maxDim) {
                    val scale = maxDim.toFloat() / maxOf(src.width, src.height)
                    val nw = (src.width * scale).toInt().coerceAtLeast(1)
                    val nh = (src.height * scale).toInt().coerceAtLeast(1)
                    val tmp = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
                    tmp.createGraphics().also {
                        it.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        it.drawImage(src, 0, 0, nw, nh, null); it.dispose()
                    }
                    src = tmp
                }
                val bmp = src.toComposeImageBitmap()
                ThemeState.cachedBgBitmap = bmp
                ThemeState.cachedBgKey = bgKey
                bmp
            }.getOrNull()
        } else null
    }
    val hasBg = bgBitmap != null
    val panelAlpha = if (hasBg) ThemeState.uiPanelOpacity else 1f

    // 有壁纸时面板用 uiPanelOpacity；无壁纸时保持不透明
    val railContainerColor = if (hasBg)
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = panelAlpha)
    else
        MaterialTheme.colorScheme.surfaceContainerLow
    val contentBgColor = if (hasBg)
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = panelAlpha)
    else
        MaterialTheme.colorScheme.surfaceContainer

    Box(modifier = modifier.fillMaxSize().graphicsLayer { clip = true }) {
        // ── 全局壁纸底层 ─────────────────────────────────────────────────────
        val bmp = bgBitmap
        if (bmp != null) {
            // 壁纸 + GPU 实时模糊：blurRadius 变化时只重绘此 GPU layer，0 CPU 延迟
            // clip 防止 blur 扩散到 MainLayout 外部（TitleBar 方向）
            val blurPx = bgBlur.toFloat() * 2f  // radius→sigma 映射
            androidx.compose.foundation.Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        clip = true
                        if (blurPx > 0f) {
                            renderEffect = BlurEffect(blurPx, blurPx, TileMode.Decal)
                        }
                    },
            )
            // 亮度遮罩：独立 layer，亮度变化只更新 GPU layer，不触发重组
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (1f - bgBrightness).coerceIn(0f, 0.85f) }
                    .background(Color.Black)
            )
        } else {
            // 无壁纸时填充默认背景色
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            )
        }

    Row(modifier = Modifier.fillMaxSize()) {
        // ── Left: Custom Sidebar ──────────────────────────────────────────────
        val sidebarWidthDp = ThemeState.uiSidebarWidth.dp
        val compactPad = if (ThemeState.uiCompactMode) 6.dp else 10.dp
        val cornerR = ThemeState.uiCornerRadius.dp
        val isEn = ThemeState.language == "en"
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(sidebarWidthDp)
                .padding(vertical = 8.dp, horizontal = 4.dp)
                .clip(RoundedCornerShape(cornerR))
                .background(railContainerColor)
                .alpha(if (uiLocked) 0.5f else 1f)
                .padding(vertical = compactPad, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(4.dp))
            Screen.entries.forEach { screen ->
                if (screen == Screen.Log && !ThemeState.showLogSidebar) return@forEach
                val selected = activeTab == screen
                SidebarNavItem(
                    screen = screen,
                    selected = selected,
                    locked = uiLocked,
                    label = if (isEn) screen.labelEn else screen.label,
                    cornerRadius = cornerR,
                    compact = ThemeState.uiCompactMode,
                    onClick = { if (!uiLocked) Navigator.navigatePrimary(screen) },
                )
                Spacer(Modifier.height(if (ThemeState.uiCompactMode) 1.dp else 2.dp))
            }
            Spacer(Modifier.weight(1f))
            if (ThemeState.uiShowVersionBadge) {
                Text(
                    text = "v${AutoUpdater.CURRENT_VERSION}",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        // ── Right: Main content with Crossfade + 悬浮球 ──────────────────
        var fabContainerW by remember { mutableStateOf(0) }
        var fabContainerH by remember { mutableStateOf(0) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .clip(RoundedCornerShape(cornerR))
                .background(contentBgColor)
                .onSizeChanged { fabContainerW = it.width; fabContainerH = it.height },
        ) {
            // ── MD3 动画曲线 ──────────────────────────────────────────────
            // Emphasized Decelerate: 进场——从快到慢，感觉内容「落定」
            val md3EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
            // Emphasized Accelerate: 退场——从慢到快，感觉内容「飞走」
            val md3EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

            AnimatedContent(
                targetState = currentRoute,
                transitionSpec = {
                    val isPrimaryRoute = { r: Route ->
                        r == Route.Launch || r == Route.Versions ||
                        r == Route.Download || r == Route.Mods || r == Route.Settings ||
                        r == Route.Log
                    }
                    val toSub   = isPrimaryRoute(initialState) && !isPrimaryRoute(targetState)
                    val fromSub = !isPrimaryRoute(initialState) && isPrimaryRoute(targetState)

                    val spd = ThemeState.uiAnimationSpeed
                    if (spd == 0f) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val sub350 = (350 / spd).toInt()
                        val sub200 = (200 / spd).toInt()
                        val sub150 = (150 / spd).toInt()
                        val sub220 = (220 / spd).toInt()
                        val sub300 = (300 / spd).toInt()
                        val sub90  = (90  / spd).toInt()
                        when {
                            toSub -> {
                                val enter = slideInHorizontally(
                                    initialOffsetX = { (it * 0.25f).toInt() },
                                    animationSpec = tween(sub350, easing = md3EmphasizedDecelerate)
                                ) + fadeIn(tween(sub200, easing = md3EmphasizedDecelerate))
                                val exit = slideOutHorizontally(
                                    targetOffsetX = { -(it * 0.1f).toInt() },
                                    animationSpec = tween(sub200, easing = md3EmphasizedAccelerate)
                                ) + fadeOut(tween(sub150, easing = md3EmphasizedAccelerate))
                                enter togetherWith exit
                            }
                            fromSub -> {
                                val enter = slideInHorizontally(
                                    initialOffsetX = { -(it * 0.1f).toInt() },
                                    animationSpec = tween(sub350, easing = md3EmphasizedDecelerate)
                                ) + fadeIn(tween(sub200, easing = md3EmphasizedDecelerate))
                                val exit = slideOutHorizontally(
                                    targetOffsetX = { (it * 0.25f).toInt() },
                                    animationSpec = tween(sub200, easing = md3EmphasizedAccelerate)
                                ) + fadeOut(tween(sub150, easing = md3EmphasizedAccelerate))
                                enter togetherWith exit
                            }
                            else -> {
                                val enter = fadeIn(
                                    animationSpec = tween(sub220, delayMillis = (90 / spd).toInt(), easing = md3EmphasizedDecelerate)
                                ) + scaleIn(
                                    initialScale = 0.92f,
                                    animationSpec = tween(sub300, delayMillis = (90 / spd).toInt(), easing = md3EmphasizedDecelerate)
                                )
                                val exit = fadeOut(
                                    animationSpec = tween(sub90, easing = md3EmphasizedAccelerate)
                                ) + scaleOut(
                                    targetScale = 0.96f,
                                    animationSpec = tween(sub90, easing = md3EmphasizedAccelerate)
                                )
                                enter togetherWith exit
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) { route ->
                when (route) {
                    is Route.Launch -> LaunchScreen()
                    is Route.Versions -> VersionScreen()
                    is Route.Download -> DownloadScreen()
                    is Route.Mods -> ModScreen()
                    is Route.BedrockMods -> BedrockModScreen()
                    is Route.Settings -> SettingsScreen()
                    is Route.VersionDetail -> VersionDetailScreen(route.version)
                    is Route.BedrockVersionDetail -> BedrockVersionDetailScreen(route.version)
                    is Route.ModDetail -> ModDetailScreen(route.project, route.edition, route.contentType)
                    is Route.CfBedrockDetail -> CfBedrockDetailScreen(route.project)
                    is Route.DownloadManager -> DownloadManagerScreen()
                    is Route.BedrockPackManager -> BedrockPackManagerScreen(route.versionId, route.versionDir, route.packType)
                    is Route.BedrockWorldManager -> BedrockWorldManagerScreen(route.versionId, route.versionDir)
                    is Route.Log -> LogScreen()
                }
            }

            // ── 下载悬浮球（可自由拖动）──────────────────────────────────────
            val density = LocalDensity.current
            val fabSizePx = with(density) { 56.dp.toPx() }
            val fabPadPx = with(density) { 20.dp.toPx() }
            var fabOffsetX by remember { mutableStateOf(Float.MAX_VALUE) }
            var fabOffsetY by remember { mutableStateOf(Float.MAX_VALUE) }
            LaunchedEffect(fabContainerW, fabContainerH) {
                if (fabContainerW > 0 && fabContainerH > 0) {
                    if (fabOffsetX == Float.MAX_VALUE || fabOffsetY == Float.MAX_VALUE) {
                        fabOffsetX = fabContainerW - fabSizePx - fabPadPx
                        fabOffsetY = fabContainerH - fabSizePx - fabPadPx
                    }
                    fabOffsetX = fabOffsetX.coerceIn(fabPadPx, (fabContainerW - fabSizePx - fabPadPx).coerceAtLeast(fabPadPx))
                    fabOffsetY = fabOffsetY.coerceIn(fabPadPx, (fabContainerH - fabSizePx - fabPadPx).coerceAtLeast(fabPadPx))
                }
            }
            DownloadFab(
                modifier = Modifier
                    .offset { IntOffset(fabOffsetX.roundToInt(), fabOffsetY.roundToInt()) },
                dragModifier = Modifier.pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        fabOffsetX = (fabOffsetX + dragAmount.x).coerceIn(fabPadPx, (fabContainerW - fabSizePx - fabPadPx).coerceAtLeast(fabPadPx))
                        fabOffsetY = (fabOffsetY + dragAmount.y).coerceIn(fabPadPx, (fabContainerH - fabSizePx - fabPadPx).coerceAtLeast(fabPadPx))
                    }
                },
                onClick = { Navigator.navigate(Route.DownloadManager) },
            )
        }
    }
    } // end root Box
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SidebarNavItem(
    screen: launcher.ui.nav.Screen,
    selected: Boolean,
    locked: Boolean,
    label: String = screen.label,
    cornerRadius: androidx.compose.ui.unit.Dp = 12.dp,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isHovered by remember { mutableStateOf(false) }

    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    val iconTint = when {
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        isHovered -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    val labelColor = when {
        selected -> MaterialTheme.colorScheme.primary
        isHovered -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit)  { isHovered = false }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !locked,
                onClick = onClick,
            )
            .padding(vertical = if (compact) 2.dp else 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 38.dp else 44.dp)
                .clip(RoundedCornerShape(cornerRadius))
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = screen.icon,
                contentDescription = label,
                modifier = Modifier.size(if (compact) 19.dp else 22.dp),
                tint = iconTint,
            )
        }
        Spacer(Modifier.height(if (compact) 1.dp else 3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = labelColor,
            maxLines = 1,
        )
    }
}

/**
 * 快速箱形模糊（线性时间），迭代3次近似高斯模糊效果。
 * 先缩小到 1/scale 再模糊再放大，进一步提速。
 * Public 供 Main.kt 启动时预处理调用。
 */
fun fastBoxBlur(src: BufferedImage, radius: Int): BufferedImage {
    if (radius <= 0) return src
    val scale = if (radius > 15) 4 else if (radius > 6) 2 else 1
    val sw = (src.width / scale).coerceAtLeast(1)
    val sh = (src.height / scale).coerceAtLeast(1)
    // 缩小
    var img = BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB)
    img.createGraphics().also {
        it.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        it.drawImage(src, 0, 0, sw, sh, null)
        it.dispose()
    }
    // 箱形模糊：迭代3次（近似高斯）
    val r = (radius / scale).coerceAtLeast(1)
    repeat(3) { img = boxBlurPass(img, r) }
    // 放大回原尺寸
    val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
    out.createGraphics().also {
        it.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        it.drawImage(img, 0, 0, src.width, src.height, null)
        it.dispose()
    }
    return out
}

private fun boxBlurPass(src: BufferedImage, r: Int): BufferedImage {
    val w = src.width; val h = src.height
    val pixels = IntArray(w * h)
    src.getRGB(0, 0, w, h, pixels, 0, w)
    val tmp = IntArray(w * h)
    // 水平方向
    for (y in 0 until h) {
        var rSum = 0; var gSum = 0; var bSum = 0
        val start = y * w
        for (x in -r..r) {
            val p = pixels[start + x.coerceIn(0, w - 1)]
            rSum += (p shr 16) and 0xFF; gSum += (p shr 8) and 0xFF; bSum += p and 0xFF
        }
        val div = r * 2 + 1
        for (x in 0 until w) {
            tmp[start + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
            val add = pixels[start + (x + r + 1).coerceIn(0, w - 1)]
            val rem = pixels[start + (x - r).coerceIn(0, w - 1)]
            rSum += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
            gSum += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
            bSum += (add and 0xFF) - (rem and 0xFF)
        }
    }
    // 垂直方向
    val out = IntArray(w * h)
    for (x in 0 until w) {
        var rSum = 0; var gSum = 0; var bSum = 0
        for (y in -r..r) {
            val p = tmp[y.coerceIn(0, h - 1) * w + x]
            rSum += (p shr 16) and 0xFF; gSum += (p shr 8) and 0xFF; bSum += p and 0xFF
        }
        val div = r * 2 + 1
        for (y in 0 until h) {
            out[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
            val add = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
            val rem = tmp[(y - r).coerceIn(0, h - 1) * w + x]
            rSum += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
            gSum += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
            bSum += (add and 0xFF) - (rem and 0xFF)
        }
    }
    val result = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    result.setRGB(0, 0, w, h, out, 0, w)
    return result
}
