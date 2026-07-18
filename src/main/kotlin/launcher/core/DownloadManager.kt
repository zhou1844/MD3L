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
    val urls: List<String> = emptyList(),  // 原始 URL 列表（会被 injectURLsWithCandidates 处理）
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

/**
 * HMCL 风格下载管理器 — 精确移植自 HMCL FetchTask + FileDownloadTask
 *
 * 核心架构：
 * 1. 全局信号量 — DEFAULT_CONCURRENCY = min(cores*4, 64)（与 HMCL 完全一致）
 * 2. 全局速度统计 — Timer 每秒触发（与 HMCL 完全一致）
 * 3. CounterInputStream — 精确字节计数（与 HMCL 完全一致）
 * 4. gzip Content-Encoding 解包（与 HMCL 完全一致）
 * 5. 临时文件 + FileChannel + MessageDigest 流式哈希（与 HMCL FileDownloadTask 完全一致）
 * 6. SHA-1 校验 + 原子移动 temp→dest（与 HMCL 完全一致）
 * 7. 多 URL fallback + 3 次重试 + 200ms 延迟（与 HMCL 完全一致）
 * 8. SHA-1 文件缓存（类似 HMCL CacheRepository）
 */
object DownloadManager {

    /** 64KB — 与 HMCL IOUtils.DEFAULT_BUFFER_SIZE 一致 */
    private const val BUFFER_SIZE = 65536

    /** body 传输停滞超时：10 秒内无任何新增字节则判定连接假死，强制中断并切换候选源（PCL 风格，慢源快速失败） */
    private const val STALL_TIMEOUT_MS = 10_000L

    /** 全局默认并发数 — 与 HMCL FetchTask.DEFAULT_CONCURRENCY 完全一致 */
    val DEFAULT_CONCURRENCY: Int = minOf(Runtime.getRuntime().availableProcessors() * 4, 64)

    /** 全局信号量 — 与 HMCL FetchTask.SEMAPHORE 完全一致 */
    private val GLOBAL_SEMAPHORE = Semaphore(DEFAULT_CONCURRENCY)

    /** 全局 HTTP 客户端 — HTTP/2，与 HMCL 一致 */
    /** 连接超时 — 与 HMCL NetworkUtils.TIME_OUT 完全一致（8 秒，慢源快速失败切镜像） */
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(8_000))
        .version(HttpClient.Version.HTTP_2)
        .followRedirects(HttpClient.Redirect.NEVER)  // 手动处理重定向，与 HMCL 一致
        .build()

    /** 全局下载速度追踪 — 与 HMCL FetchTask.downloadSpeed + Timer 完全一致 */
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

    /** 当前下载源 */
    @Volatile
    var downloadProvider: DownloadProvider = BMCLAPIDownloadProvider

    // ========== 向后兼容：mirrorUrl / activeMirror ==========
    // 旧代码大量引用这些 API，这里委托给 BMCLAPIDownloadProvider.injectURL()

    /** 当前镜像名称（向后兼容） */
    @Volatile
    var activeMirror: String = "bmclapi"
        set(value) {
            field = value
            if (downloadProvider is BMCLAPIDownloadProvider) {
                // 彻底禁用官方源：无论传入何值（含旧配置遗留的 "official"），
                // apiRoot 恒为 BMCLAPI 镜像，杜绝把 Mojang 域名替换成空前缀导致的坏 URL 与卡死。
                (downloadProvider as BMCLAPIDownloadProvider).apiRoot = "https://bmclapi2.bangbang93.com"
            }
        }

    /** 镜像 URL 替换（向后兼容，委托给 BMCLAPIDownloadProvider.injectURL） */
    fun mirrorUrl(original: String): String {
        return downloadProvider.injectURL(original)
    }

    // ========== 文件缓存（类似 HMCL CacheRepository） ==========

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

    // ========== CounterInputStream — 与 HMCL FetchTask.CounterInputStream 完全一致 ==========

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

    // ========== 多文件并发下载 ==========

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

        // 局部并发限制：整合包资源大量来自单一镜像，过高并发（64）易触发镜像限流 → body 挂起。
        // 用较温和的并发上限，并配合 downloadSingleFile 的进度看门狗，彻底避免卡死。
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

    /**
     * PCL 风格整合包批量下载引擎 —— 移植自 PCL2 NetManager 的调度思想。
     *
     * 核心机制：
     * 1. 全局 worker 池 + 速度自适应动态扩容：初始少量 worker，管理协程每 150ms 测速，
     *    只要当前总速度仍低于「历史峰值 × 0.85」（速度下限，只增不减）且任务未取完，
     *    就持续追加 worker，直到带宽饱和或达到 threadLimit —— 自动榨满带宽，
     *    避免固定并发在慢源下吞吐骤降。
     * 2. 每个 worker 复用 downloadSingleFile：多候选源快速失败切换 + gzip 解包 +
     *    SHA-1 流式校验 + 文件缓存 + 停滞看门狗（慢源自动掐断切下一源）。
     * 3. 失败文件多轮重试（容错个别抽风的镜像源）。
     */
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

            // 初始并发：PCL 先为等待文件开少量线程，再按速度增长逐步扩容
            val initial = minOf(limit, total, 16)
            repeat(initial) { startWorker() }

            // 速度自适应扩容管理协程（对应 PCL NetManager.StartManager）
            launch {
                var speedLimitLow = 256L * 1024        // 初始速度下限 256 K/s，与 PCL 一致
                var lastBytes = 0L
                while (queue.isNotEmpty() && active.get() > 0) {
                    delay(150)
                    val now = bytes.get()
                    val speed = (now - lastBytes) * 1000 / 150   // B/s
                    lastBytes = now
                    val newLimit = (speed * 0.85).toLong()
                    if (newLimit > speedLimitLow) speedLimitLow = newLimit   // 只增不减
                    // 速度未达下限（仍有提速空间）且未达线程上限 → 追加 worker
                    if (speed < speedLimitLow && active.get() < limit && queue.isNotEmpty()) {
                        startWorker()
                    }
                }
            }
        }

        // 失败文件多轮重试（降并发，容错个别抽风的源）
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

    // ========== 核心单文件下载 — 精确移植 HMCL FetchTask.downloadHttp ==========

    /**
     * 核心单文件下载入口
     *
     * 架构（精确移植 HMCL）：
     * 1. 通过 DownloadProvider.injectURLsWithCandidates 生成候选 URL 列表
     * 2. 按优先级尝试每个 URL
     * 3. 每个 URL 最多重试 3 次，失败后等待 200ms
     * 4. 手动处理 HTTP 重定向（最多 20 次）
     * 5. 使用 accept-encoding: gzip
     * 6. CounterInputStream 精确字节计数
     * 7. 临时文件 + FileChannel + MessageDigest 流式哈希
     * 8. SHA-1 校验 → 原子移动 temp→dest → 缓存
     */
    private suspend fun downloadSingleFile(
        task: DownloadTask,
        onBytesRead: ((bytes: Long) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        // 步骤 1: 生成候选 URL（使用 DownloadProvider.injectURLsWithCandidates — 与 HMCL 完全一致）
        val candidates: List<String> = if (task.urls.isNotEmpty()) {
            downloadProvider.injectURLsWithCandidates(task.urls)
        } else {
            downloadProvider.injectURLWithCandidates(task.url)
        }

        // 步骤 2: 遍历候选 URL
        // 非末尾候选源只试 1 次 → 慢源（如国内直连 modrinth 官方）8 秒超时后立即切换镜像
        // 仅最后一个候选源重试 3 次（无其他源可用时的兜底）— 显著加速整合包资源下载
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

                    // 手动重定向处理 — 与 HMCL 完全一致（最多 20 次）
                    var currentURI = URI(url)
                    val redirects = mutableListOf<URI>()

                    while (true) {
                        // 请求超时 8 秒 — 与 HMCL FetchTask 完全一致。
                        // 注意：ofInputStream 下该超时仅作用于「接收响应头」阶段，
                        // 不会中断正在流式传输的大文件 body，因此缩短超时对大文件完全安全。
                        val requestBuilder = HttpRequest.newBuilder(currentURI)
                            .timeout(Duration.ofMillis(8_000))
                            .header("User-Agent", ua)
                            .header("accept-encoding", "gzip")  // 与 HMCL 完全一致
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

                        // 检查 x-bmclapi-hash — 与 HMCL 完全一致
                        val bmclapiHash = response.headers().firstValue("x-bmclapi-hash").orElse(null)
                        if (bmclapiHash != null && bmclapiHash.length == 40 && bmclapiHash.all { it in "0123456789abcdefABCDEF" }) {
                            // 尝试从缓存恢复
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

                        // === 下载数据 — 与 HMCL FileDownloadTask.getContext + download 完全一致 ===

                        // 创建临时文件 + MessageDigest
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
                            // 处理 Content-Encoding — gzip 解包（与 HMCL 完全一致）
                            val contentEncoding = response.headers().firstValue("content-encoding")
                                .map { it.lowercase(Locale.ROOT) }.orElse("")
                            val rawInput = response.body()
                            val input: InputStream = if (contentEncoding == "gzip") {
                                GZIPInputStream(rawInput)
                            } else {
                                rawInput
                            }

                            // === 进度看门狗（关键：防止 body 挂起导致的永久卡死）===
                            // ofInputStream 的 8 秒超时只保护「接收响应头」，body 流式读取无任何超时。
                            // 若 body 传输中途挂起（镜像限流/连接假死），counter.read 会永久阻塞，
                            // 该协程永不释放并发许可，最终许可耗尽 → 整体下载卡住。
                            // 看门狗监控读取进度，STALL 秒内无新增字节则强制关闭底层流，
                            // 使阻塞的 read 抛异常 → 失败重试 / 切换下一候选源 → 释放许可。
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
                                    lastActivity.set(System.currentTimeMillis())  // 标记有进展

                                    // 更新 digest + 写入 FileChannel — 与 HMCL 完全一致
                                    if (digest != null) {
                                        digest.update(buffer, 0, len)
                                    }
                                    val byteBuffer = ByteBuffer.wrap(buffer, 0, len)
                                    while (byteBuffer.hasRemaining()) {
                                        fileChannel.write(byteBuffer)
                                    }

                                    // 速度追踪（每秒更新）— 与 HMCL 完全一致
                                    onBytesRead?.invoke(counter.downloaded - lastDownloaded)
                                    lastDownloaded = counter.downloaded
                                }

                                onBytesRead?.invoke(counter.downloaded - lastDownloaded)
                            }

                            watchdog?.cancel()
                            watchdog = null
                            fileChannel.close()

                            // SHA-1 校验 — 与 HMCL FileDownloadTask.close 完全一致
                            if (expectedChecksum != null && digest != null) {
                                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                                if (!expectedChecksum.equals(actual, ignoreCase = true)) {
                                    throw IOException(
                                        "SHA-1 mismatch: expected $expectedChecksum, got $actual"
                                    )
                                }
                            }

                            // 原子移动 temp → dest — 与 HMCL 完全一致
                            Files.createDirectories(task.dest.toPath().toAbsolutePath().parent)
                            Files.move(tempFile, task.dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            moved = true
                            success = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 重新抛出，让外层重试逻辑处理
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

                        // 下载成功，缓存 — 与 HMCL 完全一致
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
                    // 文件不存在，不重试此 URL，直接尝试下一个
                    break
                } catch (e: Exception) {
                    val isLastAttempt = attempt >= maxAttempts && urlIndex >= candidates.lastIndex
                    if (isLastAttempt) {
                        println("[DL] 所有 URL 均失败: ${task.dest.name} — ${e.message}")
                    } else {
                        println("[DL] URL[$urlIndex] attempt $attempt/$maxAttempts 失败: ${e.message}")
                    }
                    // 200ms 延迟后重试 — 与 HMCL 完全一致
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
