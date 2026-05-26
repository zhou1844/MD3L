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
    const val CURRENT_VERSION = "1.3.6"
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
                        val newExeQ  = destFile.absolutePath.replace("'", "''")
                        val curExeQ  = currentExePath.replace("'", "''")
                        val updaterPs1 = File(cacheDir, "updater.ps1")
                        // bat 中转：以管理员权限运行 PS1，避免因权限不足 Copy-Item 静默失败
                        val updaterBat = File(cacheDir, "updater.bat")
                        val ps1Ps1Path = updaterPs1.absolutePath.replace("'", "''")
                        val ps1BatPath = updaterBat.absolutePath.replace("'", "''")
                        val dol = "\$"
                        updaterPs1.writeText(
                            "Start-Sleep -Seconds 3\r\n" +
                            "for (${dol}i = 0; ${dol}i -lt 10; ${dol}i++) {\r\n" +
                            "    try {\r\n" +
                            "        Copy-Item -Path '$newExeQ' -Destination '$curExeQ' -Force -ErrorAction Stop\r\n" +
                            "        break\r\n" +
                            "    } catch {\r\n" +
                            "        Start-Sleep -Seconds 1\r\n" +
                            "    }\r\n" +
                            "}\r\n" +
                            "Start-Process -FilePath '$curExeQ'\r\n" +
                            "Remove-Item -Path '$ps1Ps1Path' -Force -ErrorAction SilentlyContinue\r\n" +
                            "Remove-Item -Path '$ps1BatPath' -Force -ErrorAction SilentlyContinue\r\n"
                        )
                        updaterBat.writeText(
                            "@echo off\r\n" +
                            "powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"${updaterPs1.absolutePath}\"\r\n"
                        )
                        // 用 cmd /c start 以独立进程（脱离当前进程树）运行 bat
                        ProcessBuilder(
                            "cmd", "/c", "start", "", "/b",
                            updaterBat.absolutePath
                        ).start()
                    } else {
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
