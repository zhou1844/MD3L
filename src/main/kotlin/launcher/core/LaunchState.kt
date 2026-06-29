package launcher.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 启动**准备阶段**状态机 —— 仅管理 "正在检测 Java / 正在下载依赖" 等预启动流程。
 *
 * 游戏进程运行期间的全局锁定由 [GameProcessManager] 统一管理。
 * UI 应同时订阅 [isLaunching] (准备中) 和 [GameProcessManager.activeProcess] (运行中)
 * 以实现完整的交互锁定。
 */
object LaunchState {
    private val _isLaunching = MutableStateFlow(false)
    val isLaunching = _isLaunching.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage = _statusMessage.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress = _progress.asStateFlow()

    fun begin(msg: String = "正在启动…") {
        _isLaunching.value = true
        _statusMessage.value = msg
        _progress.value = 5
    }

    fun updateMessage(msg: String) {
        _statusMessage.value = msg
    }

    fun updateProgress(value: Int, msg: String? = null) {
        _progress.value = value.coerceIn(0, 100)
        if (msg != null) _statusMessage.value = msg
    }

    fun end() {
        _isLaunching.value = false
        _statusMessage.value = ""
        _progress.value = 0
    }

    /**
     * 注册游戏进程 —— 委托至 [GameProcessManager]。
     * 同时结束预启动阶段。
     */
    fun attachProcess(process: Process, versionId: String = "", logFile: File? = null, edition: GameEdition = GameEdition.Java, onExit: (() -> Unit)? = null) {
        _isLaunching.value = false
        _statusMessage.value = ""
        _progress.value = 0
        GameProcessManager.attachProcess(process, versionId, logFile, edition, onExit)
    }

    /**
     * 强制终结 —— 委托至 [GameProcessManager]。
     */
    fun forceKill() {
        GameProcessManager.forceKill()
        end()
    }
}
