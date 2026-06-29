package launcher.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * 全局单例进程管理器 —— 维护游戏进程句柄的唯一数据源。
 *
 * - [activeProcess]：当前正在运行的游戏进程。UI 层订阅此状态以锁定/解锁交互。
 * - [processInfo]：附加元信息（版本 ID、启动时间等），用于 UI 显示。
 * - 进程退出后自动在 IO 线程清理状态，无需手动 end()。
 * - [forceKill]：暴力销毁幽灵进程并立即重置 UI。
 */
object GameProcessManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeProcess = MutableStateFlow<Process?>(null)
    val activeProcess: StateFlow<Process?> = _activeProcess.asStateFlow()

    private val _processInfo = MutableStateFlow(ProcessInfo())
    val processInfo: StateFlow<ProcessInfo> = _processInfo.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _launchProgress = MutableStateFlow(0)
    val launchProgress: StateFlow<Int> = _launchProgress.asStateFlow()

    private val _crashReport = MutableStateFlow<CrashReport?>(null)
    val crashReport: StateFlow<CrashReport?> = _crashReport.asStateFlow()

    private var monitorJob: Job? = null

    val isRunning: Boolean get() = _activeProcess.value != null

    /**
     * 注册已启动的游戏进程。
     * 在 IO 线程启动监控协程，等待进程退出后自动清理。
     *
     * @param process 游戏进程句柄
     * @param versionId 启动的版本 ID（用于 UI 显示）
     */
    fun attachProcess(process: Process, versionId: String = "", logFile: File? = null, edition: GameEdition = GameEdition.Java, onExit: (() -> Unit)? = null) {
        _activeProcess.value = process
        _processInfo.value = ProcessInfo(
            versionId = versionId,
            startTimeMs = System.currentTimeMillis(),
            logFile = logFile?.absolutePath.orEmpty(),
            edition = edition,
        )
        _statusMessage.value = "游戏运行中: $versionId"
        _launchProgress.value = 85

        
        monitorJob?.cancel()
        monitorJob = scope.launch {
            // ── 持续消费进程 stdout/stderr，防止管道缓冲区满导致游戏卡死 ──
            val lastLines = mutableListOf<String>()
            val outputLog = logFile
            val windowJob = launch(Dispatchers.IO) {
                repeat(12) {
                    delay(250)
                    if (_launchProgress.value < 98) _launchProgress.value = (_launchProgress.value + 1).coerceAtMost(98)
                }
                if (_activeProcess.value == process) {
                    _launchProgress.value = 100
                    _statusMessage.value = "游戏进程已启动: $versionId"
                }
            }
            val drainJob = launch(Dispatchers.IO) {
                val writer = outputLog?.let { FileOutputStream(it, true).bufferedWriter(Charsets.UTF_8) }
                try {
                    var lineCount = 0
                    process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        writer?.append("[Game] ")?.append(line)?.append('\n')
                        lineCount++
                        if (lineCount % 50 == 0) writer?.flush()
                        synchronized(lastLines) {
                            lastLines.add(line)
                            if (lastLines.size > 200) lastLines.removeAt(0)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    writer?.flush()
                    writer?.close()
                }
            }

            try {
                // 在 IO 线程阻塞等待进程退出，不阻塞 UI
                val exitCode = withContext(Dispatchers.IO) {
                    process.waitFor()
                }
                val elapsed = System.currentTimeMillis() - (_processInfo.value.startTimeMs)
                val elapsedSec = elapsed / 1000

                val sessionEdition = _processInfo.value.edition
                PlayerStats.recordSession(sessionEdition, elapsedSec, versionId, crashed = exitCode != 0)
                if (exitCode != 0) {
                    val allOutput = synchronized(lastLines) { lastLines.joinToString("\n") }
                    val tail = allOutput.lines().takeLast(18).joinToString("\n")
                    val crashDir = when (_processInfo.value.edition) {
                        GameEdition.Java -> LauncherDirs.javaLogDir
                        GameEdition.Bedrock -> LauncherDirs.bedrockLogDir
                    }
                    val targetLog = outputLog ?: File(
                        crashDir,
                        "crash-${versionId.ifBlank { "unknown" }}-${System.currentTimeMillis()}.log",
                    )
                    targetLog.parentFile?.mkdirs()
                    val exitMsg = "exit $exitCode"
                    targetLog.appendText("\n\n── Game Output ($exitMsg) ──\n$allOutput\n", Charsets.UTF_8)
                    _statusMessage.value = "游戏异常退出 ($exitMsg) · ${elapsedSec}s\n崩溃日志: ${targetLog.absolutePath}"
                    _crashReport.value = CrashReport(
                        versionId = versionId,
                        exitCode = exitCode,
                        elapsedSec = elapsedSec,
                        logPath = targetLog.absolutePath,
                        tail = tail,
                    )
                } else {
                    _statusMessage.value = "游戏已正常退出 · 运行 ${elapsedSec}s"
                }
            } catch (e: CancellationException) {
                // forceKill 导致的取消
                _statusMessage.value = "游戏进程已被强制终结"
            } catch (e: Exception) {
                _statusMessage.value = "进程监控异常: ${e.message}"
            } finally {
                _activeProcess.value = null
                _processInfo.value = ProcessInfo()
                _launchProgress.value = 0
                drainJob.cancel()
                windowJob.cancel()
                // 通知调用方进程已退出（如停止皮肤服务器等清理工作）
                onExit?.invoke()
            }
        }
    }

    fun updateMessage(msg: String) {
        _statusMessage.value = msg
    }

    fun clearCrashReport() {
        _crashReport.value = null
    }

    /**
     * 强制销毁当前游戏进程。
     */
    fun forceKill() {
        _activeProcess.value?.destroyForcibly()
        try {
            ProcessBuilder("taskkill", "/F", "/IM", "Minecraft.Windows.exe")
                .redirectErrorStream(true).start()
        } catch (_: Exception) {}
        monitorJob?.cancel()
        monitorJob = null
        _activeProcess.value = null
        _processInfo.value = ProcessInfo()
        _statusMessage.value = "游戏进程已被强制终结"
        _launchProgress.value = 0
    }

    data class ProcessInfo(
        val versionId: String = "",
        val startTimeMs: Long = 0L,
        val logFile: String = "",
        val edition: GameEdition = GameEdition.Java,
    )

    data class CrashReport(
        val versionId: String,
        val exitCode: Int,
        val elapsedSec: Long,
        val logPath: String,
        val tail: String,
    )
}
