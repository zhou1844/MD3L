package launcher.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

data class DownloadTask(
    val url: String,
    val dest: File,
    val sha1: String? = null,
    val size: Long = -1L,
    val urls: List<String> = emptyList(),
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

    private const val BUFFER_SIZE = 65536

    private const val STALL_TIMEOUT_MS = 10_000L

    val DEFAULT_CONCURRENCY: Int = minOf(Runtime.getRuntime().availableProcessors() * 4, 64)

    private val GLOBAL_SEMAPHORE = Semaphore(DEFAULT_CONCURRENCY)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(8_000))
        .version(HttpClient.Version.HTTP_2)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private val globalDownloadSpeed = AtomicLong(0L)
    private val speedTimer = Timer("DownloadSpeedRecorder", true)

    init {
        speedTimer.schedule(object : TimerTask() {
            override fun run() {
                val speed = globalDownloadSpeed.getAndSet(0)
                val p = _progress.value
                if (p.isRunning) {
                    _progress.value = p.copy(speedBytesPerSec = speed)
                }
            }
        }, 0, 1000)
    }

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    private var downloadJob: Job? = null

    @Volatile
    var downloadProvider: DownloadProvider = BMCLAPIDownloadProvider


    @Volatile
    var activeMirror: String = "bmclapi"
        set(value) {
            field = value
            if (downloadProvider is BMCLAPIDownloadProvider) {
                (downloadProvider as BMCLAPIDownloadProvider).apiRoot = "https://bmclapi2.bangbang93.com"
            }
        }

    fun mirrorUrl(original: String): String {
        return downloadProvider.injectURL(original)
    }


    private val cacheRoot: File by lazy {
        val dir = File(LauncherDirs.dataDir, "cache")
        dir.mkdirs()
        dir
    }

    private fun lookupCache(sha1: String): File? {
        val cached = getCachedFile(sha1)
        return if (cached.isFile) cached else null
    }

    private fun saveToCache(file: File, sha1: String) {
        try {
            val cached = getCachedFile(sha1)
            if (cached.isFile) return
            cached.parentFile?.mkdirs()
            file.copyTo(cached, overwrite = true)
        } catch (e: Exception) {
            println("[Cache] 缓存文件失败: $sha1 — ${e.message}")
        }
    }

    fun tryCopyFromCache(sha1: String, dest: File): Boolean {
        val cached = lookupCache(sha1) ?: return false
        return try {
            dest.parentFile?.mkdirs()
            cached.copyTo(dest, overwrite = true)
            println("[Cache] 从缓存恢复: ${dest.name}")
            true
        } catch (e: Exception) {
            println("[Cache] 缓存复制失败: ${e.message}")
            false
        }
    }

    private fun getCachedFile(sha1: String): File {
        val prefix = sha1.take(2)
        return File(cacheRoot, "files/$prefix/$sha1")
    }


    private class CounterInputStream(private val input: InputStream) : FilterInputStream(input) {
        var downloaded: Long = 0
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) downloaded++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n >= 0) downloaded += n
            return n
        }
    }


    suspend fun downloadAll(
        tasks: List<DownloadTask>,
        maxConcurrency: Int = DEFAULT_CONCURRENCY,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) {
        if (tasks.isEmpty()) return
        downloadJob?.cancel()

        val sortedTasks = tasks.sortedByDescending { it.size }
        val failed = mutableListOf<String>()
        val totalBytes = sortedTasks.filter { it.size > 0 }.sumOf { it.size }
        val completedFiles = AtomicLong(0)
        val downloadedBytes = AtomicLong(0)

        _progress.value = DownloadProgress(
            totalFiles = sortedTasks.size,
            totalBytes = totalBytes,
            isRunning = true,
        )

        val job = scope.launch {
            try {
                val jobs = sortedTasks.map { task ->
                    launch {
                        GLOBAL_SEMAPHORE.withPermit {
                            _progress.value = _progress.value.copy(currentFile = task.dest.name)
                            val ok = downloadSingleFile(task) { bytes ->
                                if (bytes > 0) {
                                    downloadedBytes.addAndGet(bytes)
                                    globalDownloadSpeed.addAndGet(bytes)
                                }
                            }
                            if (ok) {
                                val completed = completedFiles.incrementAndGet()
                                _progress.value = _progress.value.copy(
                                    completedFiles = completed.toInt(),
                                    downloadedBytes = downloadedBytes.get(),
                                )
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
            val failedCount = finalProgress.failed.size
            val firstUrl = finalProgress.failed.firstOrNull() ?: "?"
            throw RuntimeException("下载失败 ${failedCount}个文件（如 $firstUrl）")
        }
    }

    fun cancel() {
        downloadJob?.cancel(CancellationException("下载已取消"))
        _progress.value = _progress.value.copy(isRunning = false)
    }

    suspend fun downloadAllIsolated(
        tasks: List<DownloadTask>,
        maxConcurrency: Int = DEFAULT_CONCURRENCY,
        onFileComplete: ((completed: Int, total: Int, currentFile: String) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        if (tasks.isEmpty()) return@withContext
        var completedFiles = 0
        val total = tasks.size
        var remaining = tasks.toList()

        val localSem = Semaphore(maxConcurrency.coerceIn(1, DEFAULT_CONCURRENCY))
        val maxRounds = 3
        for (round in 1..maxRounds) {
            val failed = mutableListOf<DownloadTask>()
            coroutineScope {
                remaining.map { task ->
                    launch {
                        localSem.withPermit {
                            val ok = downloadSingleFile(task)
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
            if (round < maxRounds) {
                remaining = failed
                println("[DL] 第 $round 轮有 ${failed.size} 个文件失败，重试...")
                delay(1000)
            } else {
                throw RuntimeException("下载失败 ${failed.size}/${tasks.size} 个文件")
            }
        }
    }

    suspend fun downloadModpackFiles(
        tasks: List<DownloadTask>,
        threadLimit: Int = minOf(Runtime.getRuntime().availableProcessors() * 8, 128),
        onFileComplete: ((completed: Int, total: Int, currentFile: String) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        if (tasks.isEmpty()) return@withContext
        val total = tasks.size
        val limit = threadLimit.coerceIn(4, 256)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val bytes = java.util.concurrent.atomic.AtomicLong(0L)
        val queue = java.util.concurrent.ConcurrentLinkedQueue(tasks)
        val failed = java.util.concurrent.ConcurrentLinkedQueue<DownloadTask>()
        val active = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            fun startWorker() {
                active.incrementAndGet()
                launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            val task = queue.poll() ?: break
                            val ok = downloadSingleFile(task) { b -> if (b > 0) bytes.addAndGet(b) }
                            if (ok) {
                                onFileComplete?.invoke(completed.incrementAndGet(), total, task.dest.name)
                            } else {
                                failed.add(task)
                            }
                        }
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }

            val initial = minOf(limit, total, 16)
            repeat(initial) { startWorker() }

            launch {
                var speedLimitLow = 256L * 1024
                var lastBytes = 0L
                while (queue.isNotEmpty() && active.get() > 0) {
                    delay(150)
                    val now = bytes.get()
                    val speed = (now - lastBytes) * 1000 / 150
                    lastBytes = now
                    val newLimit = (speed * 0.85).toLong()
                    if (newLimit > speedLimitLow) speedLimitLow = newLimit
                    if (speed < speedLimitLow && active.get() < limit && queue.isNotEmpty()) {
                        startWorker()
                    }
                }
            }
        }

        var round = 2
        while (failed.isNotEmpty() && round-- > 0) {
            val retry = failed.toList()
            failed.clear()
            delay(800)
            coroutineScope {
                val sem = Semaphore(minOf(limit, retry.size).coerceAtLeast(1))
                retry.map { task ->
                    launch {
                        sem.withPermit {
                            val ok = downloadSingleFile(task) { b -> if (b > 0) bytes.addAndGet(b) }
                            if (ok) onFileComplete?.invoke(completed.incrementAndGet(), total, task.dest.name)
                            else failed.add(task)
                        }
                    }
                }.joinAll()
            }
        }
        if (failed.isNotEmpty()) {
            throw RuntimeException("下载失败 ${failed.size}/$total 个文件")
        }
    }

    suspend fun downloadSingle(
        task: DownloadTask,
        onProgress: (downloaded: Long, total: Long, speedBytesPerSec: Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        GLOBAL_SEMAPHORE.withPermit {
            val startedAt = System.currentTimeMillis()
            var downloaded = 0L
            var total = task.size
            downloadSingleFile(task, onBytesRead = { bytes ->
                downloaded += bytes
                globalDownloadSpeed.addAndGet(bytes)
                val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                val speed = downloaded * 1000L / elapsed
                onProgress(downloaded, total, speed)
            })
        }
    }


    private suspend fun downloadSingleFile(
        task: DownloadTask,
        onBytesRead: ((bytes: Long) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val candidates: List<String> = if (task.urls.isNotEmpty()) {
            downloadProvider.injectURLsWithCandidates(task.urls)
        } else {
            downloadProvider.injectURLWithCandidates(task.url)
        }

        for ((urlIndex, url) in candidates.withIndex()) {
            val remaining = candidates.size - urlIndex
            val maxAttempts = if (remaining <= 1) 3 else 1

            for (attempt in 1..maxAttempts) {
                try {
                    task.dest.parentFile?.mkdirs()
                    val isMcappx = url.contains("mcappx.com")
                    val ua = if (isMcappx)
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0"
                    else "CraftNova/1.0"

                    var currentURI = URI(url)
                    val redirects = mutableListOf<URI>()

                    while (true) {
                        val requestBuilder = HttpRequest.newBuilder(currentURI)
                            .timeout(Duration.ofMillis(8_000))
                            .header("User-Agent", ua)
                            .header("accept-encoding", "gzip")
                            .GET()
                        if (isMcappx) {
                            requestBuilder.header("Referer", "https://www.mcappx.com/")
                        }

                        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())

                        val code = response.statusCode()
                        if (code in 300..308 && code != 306 && code != 304) {
                            if (redirects.size >= 20) {
                                response.body().close()
                                throw IOException("Too many redirects")
                            }

                            val location = response.headers().firstValue("Location").orElse(null)
                            if (location.isNullOrBlank()) {
                                response.body().close()
                                throw IOException("Redirected to an empty location")
                            }

                            val target = currentURI.resolve(location.replace(" ", "%20"))
                            redirects.add(target)
                            response.body().close()
                            currentURI = target
                            continue
                        }

                        val bmclapiHash = response.headers().firstValue("x-bmclapi-hash").orElse(null)
                        if (bmclapiHash != null && bmclapiHash.length == 40 && bmclapiHash.all { it in "0123456789abcdefABCDEF" }) {
                            if (task.sha1 == null && tryCopyFromCache(bmclapiHash, task.dest)) {
                                response.body().close()
                                return@withContext true
                            }
                        }

                        if (code / 100 == 4) {
                            response.body().close()
                            throw FileNotFoundException(url)
                        } else if (code / 100 != 2) {
                            response.body().close()
                            throw IOException("HTTP $code for $url")
                        }


                        val tempFile: Path = Files.createTempFile(null, null)
                        val algorithm: String?
                        val expectedChecksum: String?
                        if (task.sha1 != null) {
                            algorithm = "SHA-1"
                            expectedChecksum = task.sha1
                        } else if (bmclapiHash != null) {
                            algorithm = "SHA-1"
                            expectedChecksum = bmclapiHash
                        } else {
                            algorithm = null
                            expectedChecksum = null
                        }

                        val digest: MessageDigest? = if (algorithm != null) MessageDigest.getInstance(algorithm) else null
                        val fileChannel: FileChannel = FileChannel.open(
                            tempFile,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.CREATE
                        )

                        var success = false
                        var moved = false
                        var watchdog: Job? = null
                        try {
                            val contentEncoding = response.headers().firstValue("content-encoding")
                                .map { it.lowercase(Locale.ROOT) }.orElse("")
                            val rawInput = response.body()
                            val input: InputStream = if (contentEncoding == "gzip") {
                                GZIPInputStream(rawInput)
                            } else {
                                rawInput
                            }

                            val lastActivity = AtomicLong(System.currentTimeMillis())
                            watchdog = launch(Dispatchers.IO) {
                                while (isActive) {
                                    delay(2_000)
                                    if (System.currentTimeMillis() - lastActivity.get() > STALL_TIMEOUT_MS) {
                                        try { rawInput.close() } catch (_: Throwable) {}
                                        break
                                    }
                                }
                            }

                            CounterInputStream(input).use { counter ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var len: Int
                                var lastDownloaded = 0L

                                while (counter.read(buffer).also { len = it } != -1) {
                                    ensureActive()
                                    lastActivity.set(System.currentTimeMillis())

                                    if (digest != null) {
                                        digest.update(buffer, 0, len)
                                    }
                                    val byteBuffer = ByteBuffer.wrap(buffer, 0, len)
                                    while (byteBuffer.hasRemaining()) {
                                        fileChannel.write(byteBuffer)
                                    }

                                    onBytesRead?.invoke(counter.downloaded - lastDownloaded)
                                    lastDownloaded = counter.downloaded
                                }

                                onBytesRead?.invoke(counter.downloaded - lastDownloaded)
                            }

                            watchdog?.cancel()
                            watchdog = null
                            fileChannel.close()

                            if (expectedChecksum != null && digest != null) {
                                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                                if (!expectedChecksum.equals(actual, ignoreCase = true)) {
                                    throw IOException(
                                        "SHA-1 mismatch: expected $expectedChecksum, got $actual"
                                    )
                                }
                            }

                            Files.createDirectories(task.dest.toPath().toAbsolutePath().parent)
                            Files.move(tempFile, task.dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            moved = true
                            success = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw e
                        } finally {
                            watchdog?.cancel()
                            if (!moved) {
                                try { Files.deleteIfExists(tempFile) } catch (_: IOException) {}
                            }
                            if (!success) {
                                try { fileChannel.close() } catch (_: IOException) {}
                            }
                        }

                        if (expectedChecksum != null) {
                            saveToCache(task.dest, expectedChecksum)
                        }

                        response.body().close()
                        return@withContext true
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: FileNotFoundException) {
                    println("[DL] 文件不存在: $url")
                    break
                } catch (e: Exception) {
                    val isLastAttempt = attempt >= maxAttempts && urlIndex >= candidates.lastIndex
                    if (isLastAttempt) {
                        println("[DL] 所有 URL 均失败: ${task.dest.name} — ${e.message}")
                    } else {
                        println("[DL] URL[$urlIndex] attempt $attempt/$maxAttempts 失败: ${e.message}")
                    }
                    if (attempt < maxAttempts) {
                        delay(200)
                    }
                }
            }
        }
        false
    }

    private class IOException(message: String) : java.io.IOException(message)
}
