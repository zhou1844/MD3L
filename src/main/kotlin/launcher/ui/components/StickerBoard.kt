package launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import launcher.core.StickerData
import launcher.core.StickerManager
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.roundToInt

/**
 * GIF 帧数据：位图 + 该帧应显示的毫秒数。
 */
private data class GifFrame(
    val bitmap: ImageBitmap,
    val delayMs: Int,
)

/**
 * 使用 Java ImageIO 解码 GIF 所有帧。
 */
private fun decodeGifFrames(file: File): List<GifFrame> {
    val frames = mutableListOf<GifFrame>()
    try {
        val readers = ImageIO.getImageReadersByFormatName("gif")
        if (!readers.hasNext()) return frames
        val reader = readers.next() as ImageReader
        file.inputStream().use { stream ->
            reader.input = ImageIO.createImageInputStream(stream)
            val count = reader.getNumImages(true)
            for (i in 0 until count) {
                val bufImg = reader.read(i)
                // 转为 ARGB 避免色彩问题
                val rgb = java.awt.image.BufferedImage(
                    bufImg.width, bufImg.height, java.awt.image.BufferedImage.TYPE_INT_ARGB
                )
                rgb.createGraphics().apply {
                    drawImage(bufImg, 0, 0, null)
                    dispose()
                }
                val delay = getFrameDelayMs(reader, i)
                val bmp = org.jetbrains.skia.Image.makeFromEncoded(
                    toPngBytes(rgb)
                ).toComposeImageBitmap()
                frames.add(GifFrame(bmp, delay))
            }
        }
        reader.dispose()
    } catch (_: Exception) { }
    return frames
}

private fun toPngBytes(bufImg: java.awt.image.BufferedImage): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(bufImg, "png", out)
    return out.toByteArray()
}

/**
 * 读取 GIF 指定帧的延迟时间（毫秒）。
 * GIF 规范：delayTime 单位是 1/100 秒；0 表示"默认"→ 100ms（10 fps）。
 * 不低于 10ms 防止瞬时闪过。
 */
private fun getFrameDelayMs(reader: ImageReader, idx: Int): Int {
    return try {
        val meta = reader.getImageMetadata(idx) ?: return 100
        val rootNode = if (meta is IIOMetadataNode) meta
            else meta.getAsTree("javax_imageio_gif_image_1.0") as? IIOMetadataNode ?: return 100
        val gcNodes = rootNode.getElementsByTagName("GraphicControlExtension")
        if (gcNodes.length > 0) {
            val gc = gcNodes.item(0) as IIOMetadataNode
            val raw = gc.getAttribute("delayTime")?.toIntOrNull() ?: 0
            if (raw <= 0) 100 else (raw * 10).coerceAtLeast(10)
        } else 100
    } catch (_: Exception) { 100 }
}

/**
 * 可拖拽贴纸覆盖层。支持 GIF 动画、右键调整大小/速度/删除。
 */
@Composable
fun StickerBoard(
    stickers: List<StickerData>,
    onRemove: (String) -> Unit,
    onMoved: (String, Float, Float) -> Unit,
    onScaleChanged: (String, Float) -> Unit,
    onPlaybackSpeedChanged: (String, Float) -> Unit,
    onFirstImportHintAcknowledged: () -> Unit,
    showFirstImportHint: Boolean,
) {
    var boardW by remember { mutableStateOf(1f) }
    var boardH by remember { mutableStateOf(1f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boardW = it.width.toFloat().coerceAtLeast(1f); boardH = it.height.toFloat().coerceAtLeast(1f) },
        contentAlignment = Alignment.TopStart,
    ) {
        stickers.forEach { sticker ->
            key(sticker.id) {
                StickerItem(
                    sticker = sticker,
                    boardW = boardW,
                    boardH = boardH,
                    onRemove = { onRemove(sticker.id) },
                    onMoved = { xFrac, yFrac -> onMoved(sticker.id, xFrac, yFrac) },
                    onScaleChanged = { scale -> onScaleChanged(sticker.id, scale) },
                    onPlaybackSpeedChanged = { speed -> onPlaybackSpeedChanged(sticker.id, speed) },
                )
            }
        }

        // 首次导入提示（浮在左下角）
        if (showFirstImportHint) {
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f),
                shadowElevation = 6.dp,
                onClick = onFirstImportHintAcknowledged,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "右键贴纸可打开菜单：调整大小、速度或删除",  // TODO i18n
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "✕",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun StickerItem(
    sticker: StickerData,
    boardW: Float,
    boardH: Float,
    onRemove: () -> Unit,
    onMoved: (Float, Float) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onPlaybackSpeedChanged: (Float) -> Unit,
) {
    val density = LocalDensity.current

    var offsetX by remember { mutableStateOf(sticker.x * boardW) }
    var offsetY by remember { mutableStateOf(sticker.y * boardH) }
    var currentScale by remember { mutableStateOf(sticker.scale) }
    var currentSpeed by remember { mutableStateOf(sticker.playbackSpeed) }

    // 仅在首次加载（board 尺寸就绪）时从 sticker 初始值设置位置；
    // 后续拖拽等操作后的位置不会被 prop 变化覆盖。
    var positionReady by remember { mutableStateOf(false) }
    LaunchedEffect(boardW, boardH) {
        if (!positionReady && boardW > 1f && boardH > 1f) {
            offsetX = sticker.x * boardW
            offsetY = sticker.y * boardH
            positionReady = true
        }
        currentScale = sticker.scale
        currentSpeed = sticker.playbackSpeed
    }

    var showMenu by remember { mutableStateOf(false) }

    val file = remember(sticker.fileName) { File(StickerManager.stickersDir, sticker.fileName) }
    val isGif = remember(sticker.fileName) { sticker.fileName.lowercase().endsWith(".gif") }

    // GIF 帧数据（只在文件变化时解析一次）
    val gifFrames = remember(sticker.fileName) {
        if (isGif && file.exists()) decodeGifFrames(file) else emptyList()
    }

    // GIF 当前帧索引
    var gifFrameIdx by remember { mutableStateOf(0) }

    // GIF 帧播放循环（使用 playbackSpeed 调整播放速度）
    // GIF 帧播放循环（使用本地 currentSpeed 即时响应速度调整）
    LaunchedEffect(gifFrames.size, currentSpeed) {
        if (gifFrames.isNotEmpty()) {
            gifFrameIdx = 0
            val speed = currentSpeed.coerceIn(0.1f, 5.0f)
            while (true) {
                val frame = gifFrames[gifFrameIdx]
                val adjustedDelay = (frame.delayMs / speed).toLong().coerceAtLeast(10L)
                delay(adjustedDelay)
                gifFrameIdx = (gifFrameIdx + 1) % gifFrames.size
            }
        }
    }

    // 非 GIF 图片用 Skia 解码为静态位图
    val staticBitmap = remember(sticker.fileName) {
        if (!isGif) {
            try {
                if (file.exists()) {
                    SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
                } else null
            } catch (_: Exception) { null }
        } else null
    }

    // 确定要显示的位图
    val currentBitmap: ImageBitmap? = if (isGif) {
        if (gifFrames.isNotEmpty()) gifFrames[gifFrameIdx].bitmap else null
    } else staticBitmap

    if (currentBitmap == null) return

    // 计算显示尺寸
    val imgW = currentBitmap.width.toFloat()
    val imgH = currentBitmap.height.toFloat()

    val maxPx = with(density) { 150.dp.toPx() }
    val ratio = if (imgW > imgH) maxPx / imgW else maxPx / imgH
    val displayPxW = imgW * ratio * currentScale
    val displayPxH = imgH * ratio * currentScale
    val displayDpW = with(density) { displayPxW.toDp() }
    val displayDpH = with(density) { displayPxH.toDp() }

    fun savePosition() {
        val xFrac = if (boardW > 1f) (offsetX / boardW).coerceIn(0f, 1f) else sticker.x
        val yFrac = if (boardH > 1f) (offsetY / boardH).coerceIn(0f, 1f) else sticker.y
        onMoved(xFrac, yFrac)
    }

    // ── 右键菜单：缩放 + 速度 + 删除 ──
    if (showMenu) {
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(offsetX.roundToInt(), (offsetY + displayPxH).roundToInt()),
            onDismissRequest = { showMenu = false },
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
                tonalElevation = 3.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)) {
                    // ── 缩放行 ──
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val ns = (currentScale - 0.1f).coerceIn(0.3f, 3.0f)
                                currentScale = ns; onScaleChanged(ns)
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Filled.Remove, "缩小", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${(currentScale * 100).toInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(
                            onClick = {
                                val ns = (currentScale + 0.1f).coerceIn(0.3f, 3.0f)
                                currentScale = ns; onScaleChanged(ns)
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Filled.Add, "放大", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(2.dp))
                        // 删除
                        IconButton(
                            onClick = { showMenu = false; onRemove() },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Filled.Delete, "删除", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }

                    // ── 速度行（仅 GIF） ──
                    if (isGif) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.SlowMotionVideo, "速度", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            Spacer(Modifier.width(4.dp))
                            // 减速
                            IconButton(
                                onClick = {
                                    val ns = (currentSpeed - 0.25f).coerceIn(0.25f, 4.0f)
                                    currentSpeed = ns; onPlaybackSpeedChanged(ns)
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Filled.Remove, "减速", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${"%.1f".format(currentSpeed)}x", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // 加速
                            IconButton(
                                onClick = {
                                    val ns = (currentSpeed + 0.25f).coerceIn(0.25f, 4.0f)
                                    currentSpeed = ns; onPlaybackSpeedChanged(ns)
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Filled.Add, "加速", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            // 重置速度
                            IconButton(
                                onClick = {
                                    currentSpeed = 1.0f; onPlaybackSpeedChanged(1.0f)
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Filled.Speed, "重置速度", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 贴纸本体 ──
    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(displayDpW, displayDpH)
            .clip(RoundedCornerShape(6.dp))
            // 拖拽（拖拽结束时保存位置）
            .pointerInput(sticker.id) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val maxX = (boardW - displayPxW).coerceAtLeast(0f)
                        val maxY = (boardH - displayPxH).coerceAtLeast(0f)
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxX)
                        offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxY)
                    },
                    onDragEnd = { savePosition() },
                )
            }
            // 右键弹出菜单（AWT nativeEvent 兜底）
            .onPointerEvent(PointerEventType.Press) { event ->
                val awtEvent = event.nativeEvent as? java.awt.event.MouseEvent
                if (awtEvent?.button == java.awt.event.MouseEvent.BUTTON3) {
                    showMenu = true
                }
            },
    ) {
        Image(
            bitmap = currentBitmap,
            contentDescription = "贴纸: ${sticker.fileName}",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }

    // 组件销毁时自动保存位置
    DisposableEffect(sticker.id) {
        onDispose { savePosition() }
    }
}
