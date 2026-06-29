package launcher.ui.screens

import launcher.core.LocalVersion
import launcher.core.AppSettings
import androidx.compose.runtime.mutableStateOf

/**
 * 全局持久化 LaunchScreen 状态，确保切换页面后启动页状态不丢失。
 *
 * 由于 [androidx.compose.animation.AnimatedContent] 在路由切换后会销毁
 * 离开的 Composable，[LaunchScreen] 中的 [remember] 状态会丢失。
 * 将关键状态提升到此全局 object 中可保证跨导航持久化。
 */
object LaunchScreenState {
    /** 当前选中的（即将启动的）版本 */
    val selectedVersion = mutableStateOf<LocalVersion?>(null)

    /** 已扫描的 Java 版本列表 */
    val versions = mutableStateOf<List<LocalVersion>>(emptyList())

    /** 已扫描的基岩版本列表 */
    val bedrockVersions = mutableStateOf<List<LocalVersion>>(emptyList())

    /** 当前生效的 AppSettings（内存缓存） */
    val settings = mutableStateOf(AppSettings())

    /** 启动/状态消息 */
    val launchMessage = mutableStateOf("")

    /** 是否已完成初始扫描 */
    var initialized = false
}
