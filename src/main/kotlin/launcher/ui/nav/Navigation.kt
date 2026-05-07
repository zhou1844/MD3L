package launcher.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(val label: String, val icon: ImageVector) {
    Launch("启动", Icons.Filled.RocketLaunch),
    Versions("版本", Icons.Filled.Inventory2),
    Download("下载", Icons.Filled.CloudDownload),
    Mods("模组", Icons.Filled.Extension),
    Settings("设置", Icons.Filled.Tune),
}
