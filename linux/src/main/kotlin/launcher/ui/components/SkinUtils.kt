package launcher.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 从 Minecraft 皮肤 PNG 文件中提取面部区域（8x8 像素，位于皮肤纹理坐标 (8,8)），
 * 并放大到指定尺寸。
 *
 * Minecraft 皮肤布局（64x64 标准格式）：
 *   - 头部正面：坐标 (8, 8)，尺寸 8×8
 *   - 头部覆盖层（帽子层）：坐标 (40, 8)，尺寸 8×8
 *
 * 此函数提取头部正面 + 覆盖层叠加后的面部预览。
 *
 * @param skinPath 皮肤 PNG 文件的绝对路径
 * @param targetSize 输出位图的边长（默认 64）
 * @return Compose ImageBitmap，如果文件无效或格式不支持则返回 null
 */
fun loadSkinFaceBitmap(skinPath: String, targetSize: Int = 64): ImageBitmap? {
    return runCatching {
        val file = File(skinPath)
        if (!file.isFile || file.length() <= 0L) return null

        val skinImage = ImageIO.read(file) ?: return null
        val skinWidth = skinImage.width
        val skinHeight = skinImage.height

        // 标准 Minecraft 皮肤是 64×64（旧格式 64×32）
        // 面部主纹理位于 (8, 8)，尺寸 8×8
        // 如果皮肤高度 <= 32（1.7 旧格式），面部仍位于 (8, 8)
        val faceX = 8
        val faceY = 8
        val faceSize = 8

        // 如果皮肤图片太小，尝试按比例缩放坐标
        val scaleX = skinWidth.toDouble() / 64.0
        val scaleY = skinHeight.toDouble() / 64.0

        val srcX = (faceX * scaleX).toInt().coerceAtLeast(0)
        val srcY = (faceY * scaleY).toInt().coerceAtLeast(0)
        val srcW = (faceSize * scaleX).toInt().coerceAtMost(skinWidth - srcX)
        val srcH = (faceSize * scaleY).toInt().coerceAtMost(skinHeight - srcY)

        if (srcW <= 0 || srcH <= 0) return null

        // 提取面部子图像
        val faceSub = skinImage.getSubimage(srcX, srcY, srcW, srcH)

        // 使用 NEAREST_NEIGHBOR 插值放大，保持像素风格清晰（Minecraft 是像素艺术）
        val scaled = BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_SPEED)
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_OFF)
        g.drawImage(faceSub, 0, 0, targetSize, targetSize, null)
        g.dispose()

        scaled.toComposeImageBitmap()
    }.getOrNull()
}
