package launcher.core

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

@Serializable
data class GiteeAsset(val name: String, val browser_download_url: String)

@Serializable
data class GiteeRelease(
    val tag_name: String,
    val body: String,
    val assets: List<GiteeAsset> = emptyList()
)

@Serializable
data class GithubAsset(val name: String, val browser_download_url: String, val size: Long = -1)

@Serializable
data class GithubRelease(
    val tag_name: String,
    val name: String = "",
    val body: String = "",
    val assets: List<GithubAsset> = emptyList()
)

data class UpdateState(
    val hasUpdate: Boolean = false,
    val releaseInfo: GiteeRelease? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSec: Long = 0L,
    val error: String = ""
)

object AutoUpdater {
    /**
     * 从内置资源文件 /version 读取当前版本号。
     * 该文件位于 src/main/resources/version，构建时会被打包到 jar 中。
     */
    val CURRENT_VERSION: String by lazy {
        runCatching {
            val url = AutoUpdater::class.java.classLoader.getResource("version")
                ?: error("version 资源文件未找到")
            url.readText().trim()
        }.onFailure { e ->
            System.err.println("[AutoUpdater] 读取版本文件失败: ${e.message}")
        }.getOrDefault("1.3.8")
    }
    private const val GITEE_OWNER = "foolish-bird-crossing"
    private const val GITEE_REPO = "md3llauncher"
    private val API_URLS = listOf(
        "https://gitee.com/api/v5/repos/$GITEE_OWNER/$GITEE_REPO/releases/latest",
    )

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 10_000 }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun checkForUpdate() {
        scope.launch {
            for (apiUrl in API_URLS) {
                val found = tryCheckFromUrl(apiUrl)
                if (found) return@launch
            }
            println("[AutoUpdater] 所有更新源均不可用")
        }
    }

    private suspend fun tryCheckFromUrl(apiUrl: String): Boolean {
        return try {
            val text = withContext(Dispatchers.IO) {
                val proc = ProcessBuilder(
                    "curl.exe", "-sL",
                    "--connect-timeout", "8", "--max-time", "15",
                    "-H", "User-Agent: MD3L-Launcher",
                    apiUrl,
                ).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
                if (proc.exitValue() == 0 && out.length > 50) out else null
            } ?: return false
            val release = json.decodeFromString<GiteeRelease>(text)
            if (release.tag_name.isBlank()) return false
            if (compareVersions(release.tag_name, CURRENT_VERSION) > 0) {
                _state.value = _state.value.copy(hasUpdate = true, releaseInfo = release)
            }
            true
        } catch (e: Exception) {
            println("[AutoUpdater] $apiUrl 检查失败: ${e.message}")
            false
        }
    }

    fun startUpdate() {
        val release = _state.value.releaseInfo ?: return
        val asset = release.assets.firstOrNull { it.name.lowercase().endsWith(".exe") }
            ?: release.assets.firstOrNull()

        if (asset == null) {
            _state.value = _state.value.copy(error = "未找到可下载的更新文件")
            return
        }

        _state.value = _state.value.copy(
            isDownloading = true,
            downloadProgress = 0f,
            downloadedBytes = 0L,
            totalBytes = -1L,
            speedBytesPerSec = 0L,
            error = ""
        )

        scope.launch {
            try {
                val cacheDir = File(LauncherDirs.dataDir, "updates")
                cacheDir.mkdirs()
                val destFile = File(cacheDir, asset.name)

                if (destFile.exists()) destFile.delete()

                // Gitee 附件是国内直连，直接用原始 URL
                val downloadUrl = asset.browser_download_url
                println("[AutoUpdater] 下载: $downloadUrl")

                val success = downloadWithCurl(downloadUrl, destFile) { downloaded, total, speed ->
                    _state.value = _state.value.copy(
                        downloadProgress = if (total > 0) downloaded.toFloat() / total.toFloat() else 0f,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speed
                    )
                }

                if (success && destFile.exists() && destFile.length() > 0) {
                    println("[AutoUpdater] 下载完成，准备替换: ${destFile.absolutePath}")
                    try { File(LauncherDirs.dataDir, "update_success").writeText(release.tag_name) } catch (_: Exception) {}

                    // 1. 优先用当前进程自身命令（JVM打包EXE时有效）
                    // 2. 其次遍历父进程链找 MD3L*.exe
                    // 3. 最后 fallback 到 user.dir/MD3L.exe
                    fun resolveCurrentExe(): String {
                        val own = ProcessHandle.current().info().command().orElse("") ?: ""
                        if (own.endsWith(".exe", ignoreCase = true) &&
                            !own.contains("java", ignoreCase = true) &&
                            !own.contains("javaw", ignoreCase = true)) return own

                        var ph: ProcessHandle? = ProcessHandle.current().parent().orElse(null)
                        repeat(4) {
                            val cmd = ph?.info()?.command()?.orElse("") ?: ""
                            if (cmd.endsWith(".exe", ignoreCase = true) &&
                                !cmd.contains("java", ignoreCase = true) &&
                                !cmd.contains("powershell", ignoreCase = true) &&
                                !cmd.contains("cmd.exe", ignoreCase = true)) return cmd
                            ph = ph?.parent()?.orElse(null)
                        }
                        // fallback: 找 user.dir 下唯一的 .exe
                        val dir = File(System.getProperty("user.dir"))
                        val exes = dir.listFiles { f -> f.extension.equals("exe", ignoreCase = true) }
                        val best = exes?.firstOrNull { it.name.contains("MD3L", ignoreCase = true) }
                            ?: exes?.firstOrNull()
                        return best?.absolutePath ?: File(dir, "MD3L.exe").absolutePath
                    }
                    val currentExePath = resolveCurrentExe()
                    println("[AutoUpdater] 当前EXE路径: $currentExePath")

                    if (destFile.name.lowercase().endsWith(".exe")) {
                        // ── 使用 MD3LUpdater.exe 替换 ──────────────────────────
                        // 查找 MD3LUpdater.exe：优先在启动器同级目录，其次在 dataDir
                        val updaterPaths = listOf(
                            File(File(currentExePath).parentFile, "MD3LUpdater.exe"),
                            File(LauncherDirs.dataDir, "MD3LUpdater.exe"),
                            File(cacheDir, "MD3LUpdater.exe"),
                        )
                        val updaterExe = updaterPaths.firstOrNull { it.exists() }
                            ?: updaterPaths.first() // fallback 到第一个路径

                        val currentPid = ProcessHandle.current().pid()
                        val updaterPath = updaterExe.absolutePath

                        if (updaterExe.exists()) {
                            println("[AutoUpdater] 启动更新器: $updaterPath")
                        } else {
                            println("[AutoUpdater] 更新器不存在，尝试直接启动: $updaterPath")
                        }

                        // ── 启动更新器 ──────────────────────────────────────────
                        // 问题：MD3LUpdater.exe manifest 声明了 requireAdministrator，
                        // 但 ProcessBuilder 默认 UseShellExecute=false，不会触发 manifest 提权，
                        // 导致 UAC 弹窗"请求的操作需要提升"。
                        //
                        // 解决方案：使用 ShellExecute 方式启动，让 Windows 读取 manifest 并弹出 UAC 提权对话框。
                        // 策略：
                        //   1. 优先用 PowerShell Start-Process -Verb RunAs（最可靠，直接请求管理员权限）
                        //   2. 其次用 cmd /c start（ShellExecute，会触发 manifest 提权）
                        //   3. 最后 fallback 到直接 ProcessBuilder（无提权，用于调试/降级场景）
                        val updaterArgs = "\"${destFile.absolutePath}\" \"${currentExePath}\" --wait-pid ${currentPid}"

                        // 方式1: PowerShell Start-Process -Verb RunAs（推荐）
                        // 这会弹出 UAC 对话框请求管理员权限
                        val psLaunched = runCatching {
                            val psCmd = "Start-Process -FilePath '${updaterPath}' -ArgumentList '${updaterArgs}' -Verb RunAs -WindowStyle Hidden"
                            ProcessBuilder(
                                "powershell", "-NoProfile", "-Command", psCmd
                            ).redirectErrorStream(true).start()
                            // 不等 PowerShell 返回，它会在后台运行
                            println("[AutoUpdater] PowerShell RunAs 已启动")
                            true
                        }.getOrDefault(false)

                        if (!psLaunched) {
                            println("[AutoUpdater] PowerShell 方式失败，尝试 cmd /c start...")
                            // 方式2: cmd /c start（ShellExecute，会触发 manifest 提权）
                            runCatching {
                                // start 命令使用 ShellExecute，能识别 exe 的 manifest 提权请求
                                val cmd = "cmd.exe /c start \"\" /B \"${updaterPath}\" ${updaterArgs}"
                                Runtime.getRuntime().exec(cmd)
                                println("[AutoUpdater] cmd start 已启动")
                            }.onFailure { e2 ->
                                println("[AutoUpdater] cmd start 也失败: ${e2.message}")
                                // 方式3: 直接 ProcessBuilder（无提权，作为最后手段）
                                println("[AutoUpdater] 降级到直接 ProcessBuilder 启动（无提权）")
                                ProcessBuilder(
                                    updaterPath,
                                    destFile.absolutePath,
                                    currentExePath,
                                    "--wait-pid", currentPid.toString()
                                ).redirectErrorStream(true).start()
                            }
                        }
                    } else {
                        // 非 exe 更新（如 jar），直接启动
                        ProcessBuilder("cmd", "/c", "start", "", destFile.absolutePath).start()
                    }
                    exitProcess(0)
                } else {
                    _state.value = _state.value.copy(
                        isDownloading = false,
                        error = "下载失败或文件损坏"
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isDownloading = false,
                    error = "更新失败: ${e.message}"
                )
            }
        }
    }

    fun dismissUpdate() {
        _state.value = _state.value.copy(hasUpdate = false)
    }

    private fun extractVersionNumbers(v: String): List<Int> {
        val match = Regex("""\d+(\.\d+)*""").find(v)
        val versionStr = match?.value ?: "0"
        return versionStr.split(".").map { it.toIntOrNull() ?: 0 }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val p1 = extractVersionNumbers(v1)
        val p2 = extractVersionNumbers(v2)
        val len = maxOf(p1.size, p2.size)
        for (i in 0 until len) {
            val n1 = p1.getOrElse(i) { 0 }
            val n2 = p2.getOrElse(i) { 0 }
            if (n1 != n2) return n1.compareTo(n2)
        }
        return 0
    }

    /**
     * 并发竞速：同时对所有镜像发 HEAD 请求，返回最先响应 200 的 URL。
     * 比串行测试快数倍。
     */
    private suspend fun pickFastestMirror(mirrors: List<String>): String? =
        withContext(Dispatchers.IO) {
            // 并发发起所有 HEAD 请求，取第一个成功的
            val deferreds = mirrors.map { url ->
                async {
                    val ok = runCatching {
                        val proc = ProcessBuilder(
                            "curl.exe", "-sIL",
                            "--connect-timeout", "6", "--max-time", "10",
                            url
                        ).redirectErrorStream(true).start()
                        val out = proc.inputStream.bufferedReader().readText()
                        proc.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)
                        proc.exitValue() == 0 && out.contains("HTTP/") &&
                            (out.contains(" 200") || out.contains(" 206"))
                    }.getOrDefault(false)
                    if (ok) url else null
                }
            }
            val deadline = System.currentTimeMillis() + 14_000
            var winner: String? = null
            while (winner == null && System.currentTimeMillis() < deadline) {
                for (d in deferreds) {
                    if (d.isCompleted && !d.isCancelled) {
                        val v = runCatching { d.await() }.getOrNull()
                        if (v != null) { winner = v; break }
                    }
                }
                if (winner == null) delay(100)
            }
            deferreds.forEach { it.cancel() }
            winner
        }

    /**
     * 用 curl.exe 静默下载，另起协程轮询文件大小来计算进度和速度。
     * 不依赖 stderr 解析，100% 可靠。
     */
    private suspend fun downloadWithCurl(
        url: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long, speedBytesPerSec: Long) -> Unit,
    ): Boolean {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        // 先用 HEAD 请求拿 Content-Length
        val totalBytes: Long = withContext(Dispatchers.IO) {
            runCatching {
                val proc = ProcessBuilder(
                    "curl.exe", "-sI", "-L",
                    "--connect-timeout", "10", "--max-time", "15", url
                ).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor(18, java.util.concurrent.TimeUnit.SECONDS)
                out.lines()
                    .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.toLong() ?: -1L
            }.getOrDefault(-1L)
        }

        // 启动 curl 静默下载
        val proc = withContext(Dispatchers.IO) {
            ProcessBuilder(
                "curl.exe", "-L", "-s",
                "--retry", "3", "--retry-delay", "2",
                "--connect-timeout", "15", "--max-time", "1800",
                "-o", dest.absolutePath,
                url
            ).redirectErrorStream(true).start()
        }

        // 轮询文件大小更新进度（独立协程，直接在调用者 scope 里运行）
        val pollJob = scope.launch {
            var lastSize = 0L
            var lastTime = System.currentTimeMillis()
            while (isActive) {
                delay(500)
                val size = if (dest.exists()) dest.length() else 0L
                val now = System.currentTimeMillis()
                val elapsed = (now - lastTime).coerceAtLeast(1L)
                val speed = (size - lastSize) * 1000L / elapsed
                lastSize = size
                lastTime = now
                onProgress(size, totalBytes, speed)
            }
        }

        withContext(Dispatchers.IO) { proc.waitFor() }
        pollJob.cancelAndJoin()

        val exitCode = proc.exitValue()
        val finalSize = if (dest.exists()) dest.length() else 0L
        return if (exitCode == 0 && finalSize > 0) {
            onProgress(finalSize, finalSize.coerceAtLeast(totalBytes), 0L)
            true
        } else {
            println("[AutoUpdater] curl 失败 exitCode=$exitCode size=$finalSize")
            false
        }
    }
}
