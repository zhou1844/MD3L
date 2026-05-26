package launcher.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.JPanel

// ─────────────────────────────────────────────────────────────────────────────
//  Minecraft 皮肤预览 — 基于 SwingPanel + Java2D 的像素级皮肤渲染
//  将皮肤纹理按标准 Minecraft UV 布局绘制为正面+侧面2.5D视图
//  参考 LittleSkin / HMCL 的静态皮肤展示方案
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Decodes raw skin file bytes into a BufferedImage.
 */
private fun ByteArray.toSkinBufferedImage(): BufferedImage? =
    runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(this)) }.getOrNull()

/**
 * Renders the Minecraft skin as a 2D front-facing view using proper UV mapping.
 * Layout matches the standard Minecraft skin format (64×64).
 *
 * The "canvas" is 16×32 skin units:
 *   head  8×8  at (4, 0)
 *   body  8×12 at (4, 8)
 *   r-arm 4×12 at (0, 8)   [slim: 3×12]
 *   l-arm 4×12 at (12, 8)  [slim: 3×12]
 *   r-leg 4×12 at (4, 20)
 *   l-leg 4×12 at (8, 20)
 *
 * This canvas is 16 wide × 32 tall in skin units.
 */
private fun renderSkin2D(skin: BufferedImage?, slim: Boolean, w: Int, h: Int): BufferedImage {
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
    g.background = Color(0, 0, 0, 0)
    g.clearRect(0, 0, w, h)

    // canvas is 16 wide × 32 tall skin-units; scale to fit
    val armW = if (slim) 3 else 4
    val canvasW = 16
    val canvasH = 32
    val scale = minOf(w.toFloat() / canvasW, h.toFloat() / canvasH)
    val offX = ((w - canvasW * scale) / 2).toInt()
    val offY = ((h - canvasH * scale) / 2).toInt()

    fun dst(cx: Int, cy: Int, cw: Int, ch: Int): Rectangle =
        Rectangle(offX + (cx * scale).toInt(), offY + (cy * scale).toInt(),
                  (cw * scale).toInt().coerceAtLeast(1), (ch * scale).toInt().coerceAtLeast(1))

    // Draw a UV region from skin onto dst rectangle
    fun blit(srcX: Int, srcY: Int, srcW: Int, srcH: Int, dst: Rectangle, brightness: Float = 1f) {
        if (skin == null) return
        val sw = skin.width; val sh = skin.height
        val sx = (srcX * sw / 64.0).toInt()
        val sy = (srcY * sh / 64.0).toInt()
        val sW = (srcW * sw / 64.0).toInt().coerceAtLeast(1)
        val sH = (srcH * sh / 64.0).toInt().coerceAtLeast(1)
        // Draw scaled
        g.drawImage(skin, dst.x, dst.y, dst.x + dst.width, dst.y + dst.height,
                    sx, sy, sx + sW, sy + sH, null)
        // Apply brightness darkening for side/back faces
        if (brightness < 1f) {
            val alpha = ((1f - brightness) * 180).toInt().coerceIn(0, 200)
            g.color = Color(0, 0, 0, alpha)
            g.fillRect(dst.x, dst.y, dst.width, dst.height)
        }
    }

    // Overlay layer (outer layer on top with transparency)
    fun blitOverlay(srcX: Int, srcY: Int, srcW: Int, srcH: Int, dst: Rectangle) {
        if (skin == null) return
        val sw = skin.width; val sh = skin.height
        val sx = (srcX * sw / 64.0).toInt()
        val sy = (srcY * sh / 64.0).toInt()
        val sW = (srcW * sw / 64.0).toInt().coerceAtLeast(1)
        val sH = (srcH * sh / 64.0).toInt().coerceAtLeast(1)
        val composite = g.composite
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f)
        g.drawImage(skin, dst.x, dst.y, dst.x + dst.width, dst.y + dst.height,
                    sx, sy, sx + sW, sy + sH, null)
        g.composite = composite
    }

    if (skin == null) {
        // Draw default Steve silhouette with flat colors
        g.color = Color(0x3B, 0x27, 0x14)
        g.fillRect(offX + (4*scale).toInt(), offY, (8*scale).toInt(), (8*scale).toInt())
        g.color = Color(0xC8, 0x95, 0x6C)
        g.fillRect(offX + (4*scale).toInt(), offY + (1*scale).toInt(), (8*scale).toInt(), (6*scale).toInt())
        g.color = Color(0x4B, 0x73, 0xAD)
        g.fillRect(offX + (4*scale).toInt(), offY + (8*scale).toInt(), (8*scale).toInt(), (12*scale).toInt())
        g.color = Color(0x4B, 0x73, 0xAD)
        g.fillRect(offX, offY + (8*scale).toInt(), (armW*scale).toInt(), (12*scale).toInt())
        g.fillRect(offX + ((16-armW)*scale).toInt(), offY + (8*scale).toInt(), (armW*scale).toInt(), (12*scale).toInt())
        g.color = Color(0x3F, 0x5F, 0xA8)
        g.fillRect(offX + (4*scale).toInt(), offY + (20*scale).toInt(), (4*scale).toInt(), (12*scale).toInt())
        g.fillRect(offX + (8*scale).toInt(), offY + (20*scale).toInt(), (4*scale).toInt(), (12*scale).toInt())
    } else {
        // ── Head inner ──
        blit(8, 8, 8, 8, dst(4, 0, 8, 8))
        // ── Torso ──
        blit(20, 20, 8, 12, dst(4, 8, 8, 12))
        // ── Right arm (left side on screen) ──
        blit(44, 20, armW, 12, dst(4 - armW, 8, armW, 12), brightness = 0.85f)
        // ── Left arm (right side on screen) ──
        blit(36, 52, armW, 12, dst(12, 8, armW, 12), brightness = 0.85f)
        // ── Right leg ──
        blit(4, 20, 4, 12, dst(4, 20, 4, 12))
        // ── Left leg ──
        blit(20, 52, 4, 12, dst(8, 20, 4, 12))
        // ── Head outer (hat) ──
        blitOverlay(40, 8, 8, 8, dst(4, 0, 8, 8))
        // ── Body outer ──
        blitOverlay(20, 36, 8, 12, dst(4, 8, 8, 12))
        // ── Right arm outer ──
        blitOverlay(44, 36, armW, 12, dst(4 - armW, 8, armW, 12))
        // ── Left arm outer ──
        blitOverlay(52, 52, armW, 12, dst(12, 8, armW, 12))
        // ── Right leg outer ──
        blitOverlay(4, 36, 4, 12, dst(4, 20, 4, 12))
        // ── Left leg outer ──
        blitOverlay(4, 52, 4, 12, dst(8, 20, 4, 12))
    }

    g.dispose()
    return out
}

@Composable
fun SkinViewer3D(
    skinBytes: ByteArray?,
    modifier: Modifier = Modifier,
    slim: Boolean = false,
) {
    val awtSkin: BufferedImage? = remember(skinBytes) {
        skinBytes?.toSkinBufferedImage()
    }

    var size by remember { mutableStateOf(IntSize(200, 400)) }
    val rendered: BufferedImage = remember(awtSkin, slim, size) {
        renderSkin2D(awtSkin, slim, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
    }

    Box(
        modifier = modifier.onSizeChanged { size = it },
    ) {
        SwingPanel(
            modifier = Modifier.fillMaxSize(),
            background = androidx.compose.ui.graphics.Color.Transparent,
            factory = {
                object : JPanel() {
                    init { isOpaque = false }
                    override fun paintComponent(g2d: Graphics) {
                        super.paintComponent(g2d)
                        val g = g2d as Graphics2D
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                        g.drawImage(rendered, 0, 0, width, height, null)
                    }
                }
            },
            update = { panel -> panel.repaint() },
        )
    }
}
