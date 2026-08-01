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
    val CURRENT_VERSION: String by lazy {
        runCatching {
            val url = AutoUpdater::class.java.classLoader.getResource("version")
                ?: error("version 资源文件未找到")
            url.readText().trim()
        }.onFailure { e ->
            System.err.println("[AutoUpdater] 读取版本文件失败: ${e.message}")
        }.getOrDefault("1.4.4.a")
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
                    LauncherDirs.curlCmd(), "-sL",
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

        val isWindows = System.getProperty("os.name", "").contains("Windows", ignoreCase = true)

        val asset = if (isWindows) {
            release.assets.firstOrNull { it.name.lowercase().endsWith(".exe") }
                ?: release.assets.firstOrNull()
        } else {
            release.assets.firstOrNull { it.name.lowercase().endsWith(".appimage") }
        }

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
                    println("[AutoUpdater] 下载完成: ${destFile.absolutePath}")
                    try { File(LauncherDirs.dataDir, "update_success").writeText(release.tag_name) } catch (_: Exception) {}

                    if (isWindows) {
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
                            val dir = File(System.getProperty("user.dir"))
                            val exes = dir.listFiles { f -> f.extension.equals("exe", ignoreCase = true) }
                            val best = exes?.firstOrNull { it.name.contains("MD3L", ignoreCase = true) }
                                ?: exes?.firstOrNull()
                            return best?.absolutePath ?: File(dir, "MD3L.exe").absolutePath
                        }
                        val currentExePath = resolveCurrentExe()
                        println("[AutoUpdater] 当前EXE路径: $currentExePath")

                        if (destFile.name.lowercase().endsWith(".exe")) {
                            val updaterPaths = listOf(
                                File(File(currentExePath).parentFile, "MD3LUpdater.exe"),
                                File(LauncherDirs.dataDir, "MD3LUpdater.exe"),
                                File(cacheDir, "MD3LUpdater.exe"),
                            )
                            val updaterExe = updaterPaths.firstOrNull { it.exists() }
                                ?: updaterPaths.first()
                            val currentPid = ProcessHandle.current().pid()
                            val updaterPath = updaterExe.absolutePath

                            if (updaterExe.exists()) {
                                println("[AutoUpdater] 启动更新器: $updaterPath")
                            } else {
                                println("[AutoUpdater] 更新器不存在，尝试直接启动: $updaterPath")
                            }

                            val updaterArgs = "\"${destFile.absolutePath}\" \"${currentExePath}\" --wait-pid ${currentPid}"

                            val psLaunched = runCatching {
                                val psCmd = "Start-Process -FilePath '${updaterPath}' -ArgumentList '${updaterArgs}' -Verb RunAs -WindowStyle Hidden"
                                ProcessBuilder(
                                    "powershell", "-NoProfile", "-Command", psCmd
                                ).redirectErrorStream(true).start()
                                println("[AutoUpdater] PowerShell RunAs 已启动")
                                true
                            }.getOrDefault(false)

                            if (!psLaunched) {
                                println("[AutoUpdater] PowerShell 方式失败，尝试 cmd /c start...")
                                runCatching {
                                    val cmd = "cmd.exe /c start \"\" /B \"${updaterPath}\" ${updaterArgs}"
                                    Runtime.getRuntime().exec(cmd)
                                    println("[AutoUpdater] cmd start 已启动")
                                }.onFailure { e2 ->
                                    println("[AutoUpdater] cmd start 也失败: ${e2.message}")
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
                            ProcessBuilder("cmd", "/c", "start", "", destFile.absolutePath).start()
                        }
                        exitProcess(0)
                    } else {
                        println("[AutoUpdater] Linux AppImage 已下载: ${destFile.absolutePath}")
                        val currentAppImage = resolveCurrentAppImage()
                        if (currentAppImage != null) {
                            runCatching {
                                val currentFile = File(currentAppImage)
                                val backupFile = File(currentAppImage + ".bak")
                                if (backupFile.exists()) backupFile.delete()
                                if (currentFile.exists()) currentFile.copyTo(backupFile)
                                destFile.copyTo(currentFile, overwrite = true)
                                println("[AutoUpdater] 已原地替换当前 AppImage: $currentAppImage")
                            }.onFailure { e ->
                                println("[AutoUpdater] 原地替换失败: ${e.message}")
                                runCatching {
                                    ProcessBuilder("xdg-open", destFile.absolutePath)
                                        .redirectErrorStream(true)
                                        .start()
                                }.onFailure { }
                            }
                        } else {
                            runCatching {
                                ProcessBuilder("xdg-open", destFile.absolutePath)
                                    .redirectErrorStream(true)
                                    .start()
                            }.onFailure { }
                        }
                        _state.value = _state.value.copy(
                            isDownloading = false,
                            downloadProgress = 1f,
                        )
                    }
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

    private fun resolveCurrentAppImage(): String? {
        var ph: ProcessHandle? = ProcessHandle.current()
        repeat(8) {
            val cmd = runCatching { ph?.info()?.command()?.orElse("") ?: "" }.getOrDefault("")
            if (cmd.isNotBlank() && cmd.lowercase().endsWith(".appimage") && File(cmd).exists()) return cmd
            ph = ph?.parent()?.orElse(null)
        }
        return null
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

    private suspend fun pickFastestMirror(mirrors: List<String>): String? =
        withContext(Dispatchers.IO) {
            val deferreds = mirrors.map { url ->
                async {
                    val ok = runCatching {
                        val proc = ProcessBuilder(
                            LauncherDirs.curlCmd(), "-sIL",
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

    private suspend fun downloadWithCurl(
        url: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long, speedBytesPerSec: Long) -> Unit,
    ): Boolean {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        val totalBytes: Long = withContext(Dispatchers.IO) {
            runCatching {
                val proc = ProcessBuilder(
                    LauncherDirs.curlCmd(), "-sI", "-L",
                    "--connect-timeout", "10", "--max-time", "15", url
                ).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor(18, java.util.concurrent.TimeUnit.SECONDS)
                out.lines()
                    .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.toLong() ?: -1L
            }.getOrDefault(-1L)
        }

        val proc = withContext(Dispatchers.IO) {
            ProcessBuilder(
                LauncherDirs.curlCmd(), "-L", "-s",
                "--retry", "3", "--retry-delay", "2",
                "--connect-timeout", "15", "--max-time", "1800",
                "-o", dest.absolutePath,
                url
            ).redirectErrorStream(true).start()
        }

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
