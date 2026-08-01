package launcher.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

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

    fun attachProcess(process: Process, versionId: String = "", logFile: File? = null, edition: GameEdition = GameEdition.Java, onExit: (() -> Unit)? = null) {
        _isLaunching.value = false
        _statusMessage.value = ""
        _progress.value = 0
        GameProcessManager.attachProcess(process, versionId, logFile, edition, onExit)
    }

    fun forceKill() {
        GameProcessManager.forceKill()
        end()
    }
}
