package launcher.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(val label: String, val labelEn: String, val icon: ImageVector) {
    Launch("启动", "Launch", Icons.Filled.RocketLaunch),
    Versions("版本", "Versions", Icons.Filled.Storage),
    Download("下载", "Download", Icons.Filled.CloudDownload),
    Mods("模组", "Mods", Icons.Filled.Extension),
    Multiplayer("联机", "Multiplayer", Icons.Filled.Groups),
    Settings("设置", "Settings", Icons.Filled.Settings),
    Log("日志", "Logs", Icons.Filled.Terminal),
}
