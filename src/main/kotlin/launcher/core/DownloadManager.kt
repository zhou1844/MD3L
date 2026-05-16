package launcher.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class DownloadTask(
    val url: String,
    val dest: File,
    val sha1: String? = null,
    val size: Long = -1L,
)

data class DownloadProgress(
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val isRunning: Boolean = false,
    val currentFile: String = "",
    val failed: List<String> = emptyList(),
) {
    val fraction: Float
        get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else
            if (totalFiles > 0) completedFiles.toFloat() / totalFiles else 0f

    val speedMbps: String
        get() = "%.2f MB/s".format(speedBytesPerSec / 1_048_576.0)
}

object DownloadManager {

    private const val BMCLAPI = "https://bmclapi2.bangbang93.com"

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    private var downloadJob: Job? = null

    /** 当前生效的镜像源，由 settings 写入 */
    @Volatile
    var activeMirror: String = "bmclapi"

    fun mirrorUrl(original: String): String {
        return when (activeMirror) {
            "official" -> original                                   // 不做任何替换
            else -> original                                         // bmclapi
                .replace("https://launchermeta.mojang.com", BMCLAPI)
                .replace("https://launcher.mojang.com", BMCLAPI)
                .replace("https://piston-meta.mojang.com", BMCLAPI)
                .replace("https://piston-data.mojang.com", BMCLAPI)
                .replace("https://resources.download.minecraft.net", "$BMCLAPI/assets")
                .replace("https://libraries.minecraft.net", "$BMCLAPI/maven")
        }
    }

    suspend fun downloadAll(
        tasks: List<DownloadTask>,
        maxConcurrency: Int = 64,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) {
        if (tasks.isEmpty()) return
        downloadJob?.cancel()

        val semaphore = Semaphore(maxConcurrency)
        val failed = mutableListOf<String>()
        var completedFiles = 0
        var downloadedBytes = 0L
        val totalBytes = tasks.filter { it.size > 0 }.sumOf { it.size }
        val startTime = System.currentTimeMillis()

        _progress.value = DownloadProgress(
            totalFiles = tasks.size,
            totalBytes = totalBytes,
            isRunning = true,
        )

        val job = scope.launch {
            try {
                val jobs = tasks.map { task ->
                    launch {
                        semaphore.withPermit {
                            _progress.value = _progress.value.copy(currentFile = task.dest.name)
                            val ok = downloadSingleFile(task, null) { bytes ->
                                downloadedBytes += bytes
                                val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                                val speed = downloadedBytes * 1000 / elapsed
                                _progress.value = _progress.value.copy(
                                    downloadedBytes = downloadedBytes,
                                    speedBytesPerSec = speed,
                                )
                            }
                            if (ok) {
                                completedFiles++
                                _progress.value = _progress.value.copy(completedFiles = completedFiles)
                            } else {
                                synchronized(failed) { failed.add(task.url) }
                            }
                        }
                    }
                }
                jobs.joinAll()
            } finally {
                _progress.value = _progress.value.copy(
                    isRunning = false,
                    failed = failed.toList(),
                )
            }
        }
        downloadJob = job

        job.join()
        if (job.isCancelled) throw CancellationException("下载已取消")
        val finalProgress = _progress.value
        if (finalProgress.failed.isNotEmpty()) {
            throw RuntimeException("下载失败 ${finalProgress.failed.size} 个文件")
        }
    }

    fun cancel() {
        downloadJob?.cancel(CancellationException("下载已取消"))
        _progress.value = _progress.value.copy(isRunning = false)
    }

    /**
     * 独立下载：不影响全局 downloadJob/progress 状态，适用于加载器安装等并行场景。
     * 会抛异常如果有文件下载失败。
     */
    suspend fun downloadAllIsolated(
        tasks: List<DownloadTask>,
        maxConcurrency: Int = 64,
        onFileComplete: ((completed: Int, total: Int, currentFile: String) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        if (tasks.isEmpty()) return@withContext
        val semaphore = Semaphore(maxConcurrency)
        var completedFiles = 0
        val total = tasks.size
        var remaining = tasks.toList()

        // 最多重试 2 轮
        for (round in 1..2) {
            val failed = mutableListOf<DownloadTask>()
            coroutineScope {
                remaining.map { task ->
                    launch {
                        semaphore.withPermit {
                            val ok = downloadSingleFile(task, null) { }
                            if (ok) {
                                completedFiles++
                                onFileComplete?.invoke(completedFiles, total, task.dest.name)
                            } else {
                                synchronized(failed) { failed.add(task) }
                            }
                        }
                    }
                }.joinAll()
            }
            if (failed.isEmpty()) return@withContext
            if (round < 2) {
                remaining = failed
                println("[DL] 第 $round 轮有 ${failed.size} 个文件失败，重试...")
                delay(1000)
            } else {
                throw RuntimeException("下载失败 ${failed.size}/${tasks.size} 个文件")
            }
        }
    }

    suspend fun downloadSingle(
        task: DownloadTask,
        onProgress: (downloaded: Long, total: Long, speedBytesPerSec: Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        var downloaded = 0L
        var total = task.size
        downloadSingleFile(task, onStart = { contentLength ->
            if (total <= 0 && contentLength > 0) total = contentLength
        }) { bytes ->
            downloaded += bytes
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            onProgress(downloaded, total, downloaded * 1000L / elapsed)
        }
    }

    private suspend fun downloadSingleFile(
        task: DownloadTask,
        onStart: ((Long) -> Unit)? = null,
        onBytesRead: (Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val mirroredUrl = mirrorUrl(task.url)
        repeat(3) { attempt ->
            try {
                task.dest.parentFile?.mkdirs()
                val isMcappx = mirroredUrl.contains("mcappx.com")
                val ua = if (isMcappx)
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0"
                else "CraftNova/1.0"

                // 手动处理重定向（解决跨协议 HTTP↔HTTPS 不跟随的问题）
                var currentUrl = mirroredUrl
                var conn: HttpURLConnection
                var redirectCount = 0
                while (true) {
                    conn = URL(currentUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 60_000
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", ua)
                    if (isMcappx) {
                        conn.setRequestProperty("Referer", "https://www.mcappx.com/")
                    }

                    val code = conn.responseCode
                    if (code in 301..308) {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc.isNullOrBlank() || ++redirectCount > 5) break
                        
                        // Deal with relative redirects and spaces in URL
                        val normalizedLoc = loc.replace(" ", "%20")
                        currentUrl = if (normalizedLoc.startsWith("http")) normalizedLoc else URL(URL(currentUrl), normalizedLoc).toString()
                        continue
                    }
                    break
                }

                if (conn.responseCode != 200) {
                    println("[DL] HTTP ${conn.responseCode} for $currentUrl")
                    conn.disconnect()
                    return@repeat
                }

                onStart?.invoke(conn.contentLengthLong)

                conn.inputStream.use { input ->
                    RandomAccessFile(task.dest, "rw").use { raf ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            ensureActive()
                            raf.write(buffer, 0, read)
                            onBytesRead(read.toLong())
                        }
                    }
                }
                conn.disconnect()

                if (task.sha1 != null) {
                    val actual = sha1(task.dest)
                    if (!actual.equals(task.sha1, ignoreCase = true)) {
                        task.dest.delete()
                        return@repeat
                    }
                }
                return@withContext true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(500L * (attempt + 1))
            }
        }
        false
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { fis ->
            val buf = ByteArray(8192)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
