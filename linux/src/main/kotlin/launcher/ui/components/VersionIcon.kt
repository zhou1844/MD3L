package launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import launcher.core.LoaderType

/**
 * 统一版本图标分发：
 * - Forge  → icons/forge.png
 * - Fabric → icons/fabric.png
 * - 其他   → 根据 versionType 加载草方块 / 钻石块 / 鹅卵石
 */
@Composable
fun VersionIcon(
    loaderType: LoaderType,
    versionType: String = "release",
    size: Int = 32,
) {
    val iconRes = when (loaderType) {
        LoaderType.Forge -> "icons/forge.png"
        LoaderType.NeoForge -> "icons/neoforge.png"
        LoaderType.Fabric, LoaderType.Quilt -> "icons/fabric.png"
        else -> when {
            versionType == "snapshot" || versionType.contains("snapshot", true) -> "icons/diamond_block.png"
            versionType.contains("old_alpha") || versionType.contains("old_beta") -> "icons/cobblestone.png"
            else -> "icons/grass_block.png"
        }
    }

    val hasResource = remember(iconRes) {
        try {
            Thread.currentThread().contextClassLoader?.getResource(iconRes) != null
        } catch (_: Exception) { false }
    }

    if (hasResource) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        val (letter, bg) = when (loaderType) {
            LoaderType.Forge -> "F" to Color(0xFFD85B28)
            LoaderType.NeoForge -> "N" to Color(0xFFD85B28)
            LoaderType.Fabric -> "Fa" to Color(0xFFDBCEAC)
            LoaderType.Quilt -> "Q" to Color(0xFF8B5CF6)
            else -> "G" to Color(0xFF5DA031)
        }
        Box(
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(6.dp)).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                letter.take(1),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = (size / 3).sp,
                ),
                color = Color.White,
            )
        }
    }
}
