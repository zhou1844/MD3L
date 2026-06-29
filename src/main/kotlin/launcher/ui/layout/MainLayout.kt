package launcher.ui.layout

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StickyNote2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import launcher.core.GameProcessManager
import launcher.core.LaunchState
import launcher.core.StickerData
import launcher.core.StickerManager
import launcher.ui.components.DownloadFab
import launcher.ui.components.StickerBoard
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
            Screen.Multiplayer -> Route.Multiplayer
            Screen.Settings -> Route.Settings
            Screen.Log -> Route.Log
        }
    }
}

/**
 * 共享滚动位置状态，由各主屏幕（Launch/Version/Download/Settings 等）写入、
 * [MainLayout] 读取来控制浮动底栏的显示/隐藏。
 *
 * - [scrollFraction] = 0f 表示页面顶部，1f 表示页面底部（完全滚动到底）
 * - `ScrollState` 使用 `value / maxValue` 计算
 * - `LazyListState` / `LazyGridState` 使用 `canScrollForward` 判断是否到底
 */
object NavBarScrollState {
    val scrollFraction = kotlinx.coroutines.flow.MutableStateFlow(0f)
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    // 导航锁定仅在「启动准备阶段」生效，游戏运行后允许自由跳转
    val navLocked = isLaunching

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

    val navigationMode = ThemeState.navigationMode
    val cornerR = ThemeState.uiCornerRadius.dp
    val isEn = ThemeState.language == "en"

    // ── MD3 动画曲线（共享）─────────────────────────────────────────────
    val md3EmphasizedDecelerate = remember { CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f) }
    val md3EmphasizedAccelerate = remember { CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f) }
    val md3StandardDecelerate = remember { CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f) }
    val md3StandardAccelerate = remember { CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f) }

    val isPrimaryRoute = remember {
        { r: Route ->
            r == Route.Launch || r == Route.Versions ||
            r == Route.Download || r == Route.Mods || r == Route.Multiplayer || r == Route.Settings ||
            r == Route.Log
        }
    }
    val animSpeed = ThemeState.uiAnimationSpeed

    // ── 浮动导航栏：基于页面滚动位置淡出隐藏 ─────────────────────────
    // 由各主屏幕（Launch/Version/Download/Settings 等）通过 snapshotFlow
    // 监听各自的 ScrollState/LazyListState 并写入 NavBarScrollState.scrollFraction。
    // 隐藏条件：滚动比例 >= 0.90（接近页底）；只要一往上拉（< 0.90）立即显示。
    // 这种方式同时支持鼠标滚轮滚动和滚动条拖拽，因为两者都会更新 Compose 的 ScrollState。
    var navBarAlpha by remember { mutableStateOf(1f) }
    LaunchedEffect(Unit) {
        NavBarScrollState.scrollFraction
            .collect { frac ->
                if (frac >= 0.90f) navBarAlpha = 0f
                else navBarAlpha = 1f
            }
    }
    // 切换导航模式或路由时重置
    LaunchedEffect(navigationMode, currentRoute) {
        NavBarScrollState.scrollFraction.value = 0f
        navBarAlpha = 1f
    }
    val navAlphaAnim by animateFloatAsState(
        targetValue = navBarAlpha,
        animationSpec = tween(350, easing = md3EmphasizedDecelerate),
        label = "navAlpha",
    )

    // 浮动导航栏背景色：比页面背景更突出以避免深色模式混淆
    val navBarBgColor = if (hasBg)
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = (panelAlpha + 0.12f).coerceAtMost(1f))
    else
        MaterialTheme.colorScheme.surfaceContainerHigh

    // ═══════════════════════════════════════════════ 统一布局 ═══
    // 内容区始终在同一个位置，侧边栏/浮动导航通过 AnimatedVisibility 显隐
    Box(modifier = modifier.fillMaxSize().graphicsLayer { clip = true }) {
        // ── 全局壁纸底层 ─────────────────────────────────────────────────────
        val bmp = bgBitmap
        if (bmp != null) {
            val blurPx = bgBlur.toFloat() * 2f
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (1f - bgBrightness).coerceIn(0f, 0.85f) }
                    .background(Color.Black)
            )
        } else {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            )
        }

        // ═══════════════════════════════════════════════ 内容布局 ═══
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                // ── Sidebar（仅侧边导航模式显示）────────────────────────────────
            AnimatedVisibility(
                visible = navigationMode == "sidebar",
                enter = fadeIn(animationSpec = tween(200)) + slideInHorizontally(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(120)) + slideOutHorizontally(animationSpec = tween(120)),
            ) {
                val sidebarWidthDp = ThemeState.uiSidebarWidth.dp
                val compactPad = if (ThemeState.uiCompactMode) 6.dp else 10.dp
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(sidebarWidthDp)
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                        .clip(RoundedCornerShape(cornerR))
                        .background(railContainerColor)
                        .alpha(if (navLocked) 0.5f else 1f)
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
                            locked = navLocked,
                            label = if (isEn) screen.labelEn else screen.label,
                            cornerRadius = cornerR,
                            compact = ThemeState.uiCompactMode,
                            onClick = { if (!navLocked) Navigator.navigatePrimary(screen) },
                        )
                        Spacer(Modifier.height(if (ThemeState.uiCompactMode) 1.dp else 2.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (ThemeState.uiShowVersionBadge) {
                        Text(
                            text = "v${AutoUpdater.CURRENT_VERSION}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            // ── Main content（始终存在，两种模式共享同一个实例）─────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
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
                    AnimatedContent(
                        targetState = currentRoute,
                        transitionSpec = {
                            val toSub   = isPrimaryRoute(initialState) && !isPrimaryRoute(targetState)
                            val fromSub = !isPrimaryRoute(initialState) && isPrimaryRoute(targetState)
                            if (animSpeed == 0f) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                val base = (300 / animSpeed).toInt().coerceAtLeast(100)
                                val fast = (180 / animSpeed).toInt().coerceAtLeast(60)
                                val slow = (400 / animSpeed).toInt().coerceAtLeast(150)
                                when {
                                    toSub -> {
                                        val enter = slideInHorizontally(animationSpec = tween(slow, easing = md3EmphasizedDecelerate)) + fadeIn(animationSpec = tween(fast, easing = md3StandardDecelerate))
                                        val exit = slideOutHorizontally(targetOffsetX = { -(it * 0.15f).toInt() }, animationSpec = tween(fast, easing = md3EmphasizedAccelerate)) + fadeOut(animationSpec = tween(fast, easing = md3StandardAccelerate))
                                        enter togetherWith exit
                                    }
                                    fromSub -> {
                                        val enter = slideInHorizontally(initialOffsetX = { -(it * 0.15f).toInt() }, animationSpec = tween(slow, easing = md3EmphasizedDecelerate)) + fadeIn(animationSpec = tween(fast, easing = md3StandardDecelerate))
                                        val exit = slideOutHorizontally(animationSpec = tween(fast, easing = md3EmphasizedAccelerate)) + fadeOut(animationSpec = tween(fast, easing = md3StandardAccelerate))
                                        enter togetherWith exit
                                    }
                                    else -> {
                                        val enter = fadeIn(animationSpec = tween(fast, delayMillis = (base / 3).coerceAtMost(60), easing = md3StandardDecelerate)) + scaleIn(initialScale = 0.95f, animationSpec = tween(base, delayMillis = (base / 3).coerceAtMost(60), easing = md3EmphasizedDecelerate))
                                        val exit = fadeOut(animationSpec = tween((fast * 0.6f).toInt(), easing = md3StandardAccelerate)) + scaleOut(targetScale = 0.97f, animationSpec = tween((fast * 0.6f).toInt(), easing = md3StandardAccelerate))
                                        enter togetherWith exit
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(16.dp).graphicsLayer { },
                    ) { route ->
                        ScreenContent(route)
                    }

                    // ── 下载悬浮球 ────────────────────────────────────────
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
                        modifier = Modifier.offset { IntOffset(fabOffsetX.roundToInt(), fabOffsetY.roundToInt()) },
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
        }

        // ── 底部浮动导航栏（仅浮动导航模式显示）─────────────────────────────
        AnimatedVisibility(
            visible = navigationMode == "floating" && navBarAlpha > 0.01f,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(animationSpec = tween(300)),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = ThemeState.navFloatingMarginSide.dp)
                    .padding(bottom = ThemeState.navFloatingMarginBottom.dp)
                    .graphicsLayer { alpha = navAlphaAnim },
            ) {
                Surface(
                    shape = RoundedCornerShape(ThemeState.navFloatingCornerRadius.dp),
                    color = navBarBgColor,
                    shadowElevation = 12.dp,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .wrapContentWidth()
                            .height(ThemeState.navFloatingHeight.dp)
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Screen.entries.forEach { screen ->
                            if (screen == Screen.Log && !ThemeState.showLogSidebar) return@forEach
                            val selected = activeTab == screen
                            FloatingNavItem(
                                screen = screen,
                                selected = selected,
                                locked = navLocked,
                                label = if (isEn) screen.labelEn else screen.label,
                                showLabel = ThemeState.navFloatingShowLabels,
                                onClick = { if (!navLocked) Navigator.navigatePrimary(screen) },
                            )
                        }
                    }
                }
                // ── 版本号徽章（浮动导航栏上方，浮动模式下隐藏）────────────
                if (ThemeState.uiShowVersionBadge && navigationMode != "floating") {
                    Text(
                        text = "v${AutoUpdater.CURRENT_VERSION}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.Center).padding(top = 2.dp).offset(y = (-4).dp),
                    )
                }
            }
        }

        // ── 全局贴纸覆盖层（所有页面可见）──────────────────────────────────
        val stickerScope = rememberCoroutineScope()
        var stickers by remember { mutableStateOf<List<StickerData>>(emptyList()) }
        var showFirstImportHint by remember { mutableStateOf(false) }
        val isEn2 = ThemeState.language == "en"

        LaunchedEffect(Unit) {
            val loaded = StickerManager.load()
            stickers = loaded
        }

        StickerBoard(
            stickers = stickers,
            onRemove = { id ->
                stickerScope.launch {
                    StickerManager.removeSticker(id)
                    stickers = StickerManager.stickers
                }
            },
            onMoved = { id, x, y ->
                stickerScope.launch {
                    StickerManager.updateSticker(id = id, x = x, y = y)
                }
            },
            onScaleChanged = { id, scale ->
                stickerScope.launch {
                    StickerManager.updateSticker(id = id, scale = scale)
                }
            },
            onPlaybackSpeedChanged = { id, speed ->
                stickerScope.launch {
                    StickerManager.updateSticker(id = id, playbackSpeed = speed)
                }
            },
            showFirstImportHint = showFirstImportHint,
            onFirstImportHintAcknowledged = { showFirstImportHint = false },
        )

        // ── 右上角贴纸添加按钮（全局）────────────────────────────────────
        IconButton(
            onClick = {
                val chooser = javax.swing.JFileChooser().apply {
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                        "图片文件", "png", "jpg", "jpeg", "gif", "webp", "bmp"
                    )
                    dialogTitle = if (isEn2) "Add Sticker" else "添加贴纸"
                }
                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    val file = chooser.selectedFile
                    if (file != null) {
                        stickerScope.launch {
                            StickerManager.addSticker(file)
                            stickers = StickerManager.stickers
                            if (stickers.size == 1) {
                                showFirstImportHint = true
                            }
                        }
                    }
                }
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp),
        ) {
            Icon(
                Icons.Filled.StickyNote2,
                contentDescription = if (isEn2) "Add Sticker" else "添加贴纸",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp),
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

    // 缓存动画规格，避免每次重组重新分配
    val colorSpec = remember { tween<Color>(300, easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)) }
    val floatSpec = remember { tween<Float>(300, easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)) }

    // 使用 animateColorAsState 实现平滑颜色过渡
    val containerColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> Color.Transparent
        },
        animationSpec = colorSpec,
        label = "sidebarContainerColor",
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            isHovered -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        },
        animationSpec = colorSpec,
        label = "sidebarIconTint",
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primary
            isHovered -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = colorSpec,
        label = "sidebarLabelColor",
    )

    // 选中时图标微放大
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.1f else 1.0f,
        animationSpec = floatSpec,
        label = "sidebarIconScale",
    )

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
                .background(containerColor)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
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

// ── Screen content router ─────────────────────────────────────────────────────
@Composable
private fun ScreenContent(route: Route) {
    when (route) {
        is Route.Launch -> LaunchScreen()
        is Route.Versions -> VersionScreen()
        is Route.Download -> DownloadScreen()
        is Route.Mods -> ModScreen()
        is Route.BedrockMods -> BedrockModScreen()
        is Route.Multiplayer -> MultiplayerScreen()
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

// ── Floating nav item（紧凑版）─────────────────────────────────────────────────
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun FloatingNavItem(
    screen: launcher.ui.nav.Screen,
    selected: Boolean,
    locked: Boolean,
    label: String = screen.label,
    showLabel: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isHovered by remember { mutableStateOf(false) }

    val colorSpec = remember { tween<Color>(300, easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)) }
    val floatSpec = remember { tween<Float>(300, easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)) }

    val containerColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> Color.Transparent
        },
        animationSpec = colorSpec,
        label = "floatingNavContainerColor",
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            isHovered -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        },
        animationSpec = colorSpec,
        label = "floatingNavIconTint",
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primary
            isHovered -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = colorSpec,
        label = "floatingNavLabelColor",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.1f else 1.0f,
        animationSpec = floatSpec,
        label = "floatingNavIconScale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !locked,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(containerColor)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = screen.icon,
                contentDescription = label,
                modifier = Modifier.size(17.dp),
                tint = iconTint,
            )
        }
        if (showLabel) {
            Spacer(Modifier.height(1.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = labelColor,
                maxLines = 1,
            )
        }
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
