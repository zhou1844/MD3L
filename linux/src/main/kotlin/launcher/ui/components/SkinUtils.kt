package launcher.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

fun loadSkinFaceBitmap(skinPath: String, targetSize: Int = 64): ImageBitmap? {
    return runCatching {
        val file = File(skinPath)
        if (!file.isFile || file.length() <= 0L) return null

        val skinImage = ImageIO.read(file) ?: return null
        val skinWidth = skinImage.width
        val skinHeight = skinImage.height

        val faceX = 8
        val faceY = 8
        val faceSize = 8

        val scaleX = skinWidth.toDouble() / 64.0
        val scaleY = skinHeight.toDouble() / 64.0

        val srcX = (faceX * scaleX).toInt().coerceAtLeast(0)
        val srcY = (faceY * scaleY).toInt().coerceAtLeast(0)
        val srcW = (faceSize * scaleX).toInt().coerceAtMost(skinWidth - srcX)
        val srcH = (faceSize * scaleY).toInt().coerceAtMost(skinHeight - srcY)

        if (srcW <= 0 || srcH <= 0) return null

        val faceSub = skinImage.getSubimage(srcX, srcY, srcW, srcH)

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
