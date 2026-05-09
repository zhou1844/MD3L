package launcher.core

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    const val CURRENT_VERSION = "1.2"
    private const val API_URL = "https://gitee.com/api/v5/repos/foolish-bird-crossing/md3llauncher/releases/latest"

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 10_000 }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun checkForUpdate() {
        scope.launch {
            try {
                val text = client.get(API_URL).bodyAsText()
                val release = json.decodeFromString<GiteeRelease>(text)
                val remoteVersion = release.tag_name

                if (compareVersions(remoteVersion, CURRENT_VERSION) > 0) {
                    _state.value = _state.value.copy(
                        hasUpdate = true,
                        releaseInfo = release
                    )
                }
            } catch (e: Exception) {
                println("[AutoUpdater] 检查更新失败: ${e.message}")
                // Fallback to curl if ktor fails (SSL issues etc)
                try {
                    val proc = ProcessBuilder("curl.exe", "-sL", API_URL).start()
                    val text = proc.inputStream.bufferedReader().readText()
                    if (proc.waitFor() == 0 && text.isNotBlank()) {
                        val release = json.decodeFromString<GiteeRelease>(text)
                        val remoteVersion = release.tag_name
                        if (compareVersions(remoteVersion, CURRENT_VERSION) > 0) {
                            _state.value = _state.value.copy(
                                hasUpdate = true,
                                releaseInfo = release
                            )
                        }
                    }
                } catch (e2: Exception) {
                    println("[AutoUpdater] curl 检查更新也失败: ${e2.message}")
                }
            }
        }
    }

    fun startUpdate() {
        val release = _state.value.releaseInfo ?: return
        // 优先寻找 exe，如果没找到就随便选第一个（比如 zip）
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
                val cacheDir = File(System.getProperty("user.home"), ".md3l/updates")
                cacheDir.mkdirs()
                val destFile = File(cacheDir, asset.name)
                
                // 如果已经有缓存的旧包，先删掉
                if (destFile.exists()) {
                    destFile.delete()
                }

                val task = DownloadTask(url = asset.browser_download_url, dest = destFile)
                val success = DownloadManager.downloadSingle(task) { downloaded, total, speed ->
                    _state.value = _state.value.copy(
                        downloadProgress = if (total > 0) downloaded.toFloat() / total.toFloat() else 0f,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speed
                    )
                }

                if (success && destFile.exists() && destFile.length() > 0) {
                    println("[AutoUpdater] 下载完成，准备替换: ${destFile.absolutePath}")
                    try { File(System.getProperty("user.home"), ".md3l/update_success").writeText(release.tag_name) } catch(_: Exception) {}
                    
                    val parentExe = ProcessHandle.current().parent().orElse(null)?.info()?.command()?.orElse("") ?: ""
                    val currentExePath = if (parentExe.contains("MD3L", ignoreCase = true) && parentExe.endsWith(".exe", ignoreCase = true)) {
                        parentExe
                    } else {
                        File(System.getProperty("user.dir"), "MD3L.exe").absolutePath
                    }

                    if (destFile.name.lowercase().endsWith(".exe")) {
                        val updaterPs1 = File(cacheDir, "updater.ps1")
                        updaterPs1.writeText("""
                            Start-Sleep -Seconds 2
                            Copy-Item -Path '${destFile.absolutePath.replace("'", "''")}' -Destination '${currentExePath.replace("'", "''")}' -Force
                            Start-Process -FilePath '${currentExePath.replace("'", "''")}'
                            Remove-Item -Path '${updaterPs1.absolutePath.replace("'", "''")}' -Force
                        """.trimIndent())
                        ProcessBuilder(
                            "powershell",
                            "-NoProfile",
                            "-WindowStyle", "Hidden",
                            "-ExecutionPolicy", "Bypass",
                            "-File", updaterPs1.absolutePath
                        ).start()
                    } else {
                        ProcessBuilder("cmd", "/c", "start", destFile.absolutePath).start()
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
}
