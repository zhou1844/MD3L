package launcher.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Desktop
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * 基岩版静默安装管理器。
 *
 * 完整流水线：
 *   1. 直接 HTTP 下载 .appx 文件（不经过 DownloadManager，带完整日志）
 *   2. 通过 PowerShell Add-AppxPackage 安装到系统
 *   3. 安装完成后即可通过 COM 激活启动
 *
 * 通过 [installProgress] StateFlow 实时推送阶段+进度至 UI。
 */
object BedrockDownloadManager {

    enum class WUDownloadSource(val label: String) {
        Auto("自动选择（推荐）"),
        WuOfficial("Windows Update 官方源"),
        McAppxMirror("MCAPPX 镜像源"),
        XboxCnAssets1("assets1.xboxlive.cn"),
        XboxCnAssets2("assets2.xboxlive.cn"),
        XboxComAssets1("assets1.xboxlive.com"),
        XboxComAssets2("assets2.xboxlive.com"),
    }

    data class WuDownloadTask(
        val version: WUDownloadClient.WUVersion,
        val source: WUDownloadSource = WUDownloadSource.Auto,
        val installVersionName: String = version.name,
    )

    fun availableSources(ver: WUDownloadClient.WUVersion): List<WUDownloadSource> {
        return if (ver.isGdk) {
            listOf(
                WUDownloadSource.Auto,
                WUDownloadSource.XboxCnAssets1,
                WUDownloadSource.XboxCnAssets2,
                WUDownloadSource.XboxComAssets1,
                WUDownloadSource.XboxComAssets2,
            )
        } else {
            listOf(
                WUDownloadSource.Auto,
                WUDownloadSource.WuOfficial,
                WUDownloadSource.McAppxMirror,
            )
        }
    }

    data class InstallProgress(
        val phase: String = "",          // "downloading" | "installing" | "done" | "error"
        val message: String = "",        // 人类可读状态
        val fraction: Float = 0f,        // 0..1
        val versionKey: String = "",     // "${version}_WU"
    )

    private val _installProgress = MutableStateFlow(InstallProgress())
    val installProgress: StateFlow<InstallProgress> = _installProgress.asStateFlow()

    // 全局下载状态（切换页面不丢失）
    private val _downloadingVersions = MutableStateFlow<Set<String>>(emptySet())
    val downloadingVersions: StateFlow<Set<String>> = _downloadingVersions.asStateFlow()

    private val _downloadResults = MutableStateFlow<Map<String, String>>(emptyMap())
    val downloadResults: StateFlow<Map<String, String>> = _downloadResults.asStateFlow()

    // 全局协程作用域 — 下载不随页面切换取消
    private val globalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 保存 Job 引用用于取消
    private val activeJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val pausedVersions = ConcurrentHashMap.newKeySet<String>()
    private val closedVersions = ConcurrentHashMap.newKeySet<String>()
    private val resumableEntries = ConcurrentHashMap<String, BedrockVersionCatalog.BedrockVersionEntry>()
    private val resumableWuEntries = ConcurrentHashMap<String, WuDownloadTask>()
    private val lastEmitTimeMs = ConcurrentHashMap<String, Long>()
    private val lastEmitProgress = ConcurrentHashMap<String, InstallProgress>()

    fun markDownloading(key: String) {
        closedVersions.remove(key)
        _downloadingVersions.value = _downloadingVersions.value + key
        _downloadResults.value = _downloadResults.value - key
    }

    fun markDone(key: String, result: String) {
        if (key in closedVersions) {
            activeJobs.remove(key)
            return
        }
        _downloadingVersions.value = _downloadingVersions.value - key
        _downloadResults.value = _downloadResults.value + (key to result)
        activeJobs.remove(key)
        lastEmitTimeMs.remove(key)
        lastEmitProgress.remove(key)
    }

    /**
     * 取消下载任务。
     */
    fun cancelDownload(versionKey: String) {
        closedVersions.add(versionKey)
        pausedVersions.remove(versionKey)
        resumableEntries.remove(versionKey)
        resumableWuEntries.remove(versionKey)
        activeJobs[versionKey]?.cancel()
        activeJobs.remove(versionKey)
        deletePartialDownload(versionKey)
        _downloadingVersions.value = _downloadingVersions.value - versionKey
        _downloadResults.value = _downloadResults.value - versionKey
        DownloadHub.remove("bedrock_$versionKey")
        println("[BDM] 取消下载: $versionKey")
    }

    fun pauseDownload(versionKey: String) {
        pausedVersions.add(versionKey)
        activeJobs[versionKey]?.cancel(CancellationException("基岩版下载已暂停"))
        activeJobs.remove(versionKey)
        _downloadingVersions.value = _downloadingVersions.value - versionKey
        _downloadResults.value = _downloadResults.value + (versionKey to "已暂停")
        DownloadHub.upsert(DownloadHub.HubTask(
            id = "bedrock_$versionKey",
            name = "基岩版 $versionKey",
            type = DownloadHub.TaskType.BedrockVersion,
            status = DownloadHub.TaskStatus.Paused,
            step = "已暂停，点击右侧继续按钮恢复下载",
            fraction = _installProgress.value.fraction,
        ))
    }

    fun resumeDownload(versionKey: String) {
        resumableEntries[versionKey]?.let {
            launchDownload(it)
            return
        }
        resumableWuEntries[versionKey]?.let {
            launchDownloadWU(it.version, it.source, it.installVersionName)
        }
    }

    private fun deletePartialDownload(versionKey: String) {
        runCatching {
            val version = versionKey.substringBefore("_")
            val settings = runBlocking { AppSettings.load() }
            val cacheDir = File(settings.minecraftDir, "bedrock_versions/cache")
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.contains(version) && (file.name.endsWith(".filepart") || file.name.endsWith(".filepart.parts") || file.name.endsWith(".chunk"))) {
                    file.delete()
                }
            }
        }
    }

    /**
     * 在全局作用域中启动下载+安装，切换页面也不会中断。
     */
    fun launchDownload(entry: BedrockVersionCatalog.BedrockVersionEntry) {
        val versionKey = "${entry.version}_${entry.arch}"
        if (_downloadingVersions.value.contains(versionKey)) return // 防重复
        if (_downloadingVersions.value.isNotEmpty()) {
            _downloadResults.value = _downloadResults.value + (versionKey to "已有基岩下载任务进行中，请先完成或取消")
            return
        }
        closedVersions.remove(versionKey)
        pausedVersions.remove(versionKey)
        resumableEntries[versionKey] = entry
        markDownloading(versionKey)
        DownloadHub.registerControls(
            id = "bedrock_$versionKey",
            onPause = { pauseDownload(versionKey) },
            onResume = { resumeDownload(versionKey) },
            onClose = { cancelDownload(versionKey) },
        )
        val job = globalScope.launch {
            try {
                val msg = download(entry)
                markDone(versionKey, msg)
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!pausedVersions.contains(versionKey)) {
                    markDone(versionKey, "已取消")
                }
            } catch (e: Exception) {
                markDone(versionKey, "失败: ${e.message}")
            }
        }
        activeJobs[versionKey] = job
    }

    fun launchDownloadWU(
        ver: WUDownloadClient.WUVersion,
        source: WUDownloadSource = WUDownloadSource.Auto,
        installVersionName: String = ver.name,
    ) {
        val effectiveInstallName = installVersionName.trim().ifBlank { ver.name }
        val versionKey = "${effectiveInstallName}_${ver.packageType}"
        if (_downloadingVersions.value.contains(versionKey)) return
        if (_downloadingVersions.value.isNotEmpty()) {
            _downloadResults.value = _downloadResults.value + (versionKey to "已有基岩下载任务进行中，请先完成或取消")
            return
        }
        closedVersions.remove(versionKey)
        pausedVersions.remove(versionKey)
        resumableWuEntries[versionKey] = WuDownloadTask(ver, source, effectiveInstallName)
        markDownloading(versionKey)
        DownloadHub.registerControls(
            id = "bedrock_$versionKey",
            onPause = { pauseDownload(versionKey) },
            onResume = { resumeDownload(versionKey) },
            onClose = { cancelDownload(versionKey) },
        )
        val job = globalScope.launch {
            try {
                val msg = downloadWU(ver, source, effectiveInstallName)
                markDone(versionKey, msg)
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!pausedVersions.contains(versionKey)) {
                    markDone(versionKey, "已取消")
                }
            } catch (e: Exception) {
                markDone(versionKey, "失败: ${e.message}")
            }
        }
        activeJobs[versionKey] = job
    }

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0"

    private fun bedrockVersionKey(ver: WUDownloadClient.WUVersion): String = "${ver.name}_${ver.packageType}"

    suspend fun downloadWU(
        ver: WUDownloadClient.WUVersion,
        source: WUDownloadSource = WUDownloadSource.Auto,
        installVersionName: String = ver.name,
    ): String = withContext(Dispatchers.IO) {
        val effectiveInstallName = installVersionName.trim().ifBlank { ver.name }
        val versionKey = "${effectiveInstallName}_${ver.packageType}"
        try {
            val settings = AppSettings.load()
            val cacheDir = File(settings.minecraftDir, "bedrock_versions/cache")
            cacheDir.mkdirs()

            var resolvedFileName = "Minecraft-${ver.name}-${ver.packageType}.${if (ver.isGdk) "msixvc" else "Appx"}"
            var resolvedUrl = ""
            var fileSize = -1L

            if (ver.isGdk) {
                emit(versionKey, "downloading", "正在选择 GDK 下载源...", 0.02f)
                resolvedUrl = chooseGdkDownloadUrl(ver.downloadUrls, source)
                    ?: run {
                        // GdkLinks 数据库尚未收录此版本，尝试 Xbox 商店目录 API 动态解析
                        emit(versionKey, "downloading", "GdkLinks 未收录，正在查询 Xbox 商店...", 0.03f)
                        resolveGdkUrlFromXboxCatalog(ver.name, ver.isPreview)
                    }
                    ?: return@withContext "下载失败: GDK 无下载链接（GdkLinks 和 Xbox 商店均未找到 ${ver.name}）"
            } else {
                when (source) {
                    WUDownloadSource.Auto,
                    WUDownloadSource.McAppxMirror -> {
                        emit(versionKey, "downloading", "正在解析 MCAPPX 镜像链接...", 0.02f)
                        val mcResolved = resolveWuVersionFromMcAppx(ver)
                        if (mcResolved != null) {
                            resolvedUrl = mcResolved.downloadUrl
                            fileSize = mcResolved.fileSize
                            resolvedFileName = mcResolved.fileName.ifBlank { resolvedFileName }
                        } else {
                            emit(versionKey, "downloading", "MCAPPX 无匹配，回退官方源...", 0.04f)
                        }
                    }
                    else -> Unit
                }

                if (resolvedUrl.isBlank()) {
                    if (ver.uuid.isBlank()) {
                        emit(versionKey, "error", "该版本未提供 WU UUID，且镜像源未命中，请切换下载源或刷新版本库", 0f)
                        return@withContext "下载失败: 缺少 WU UUID"
                    }
                    emit(versionKey, "downloading", "正在解析 Appx/UWP 下载链接...", 0.02f)
                    val resolved = WUDownloadClient.resolveDownloadUrl(ver.uuid)
                    resolvedUrl = resolved.first
                    fileSize = resolved.second
                }
            }

            val targetFile = File(cacheDir, resolvedFileName)

            if (targetFile.exists() && targetFile.length() > 10_000_000) {
                emit(versionKey, "downloading", "缓存命中", 0.95f)
            } else {
                emit(versionKey, "downloading", "正在下载 ${ver.name}...", 0.05f)
                var ok = WUDownloadClient.downloadFile(resolvedUrl, targetFile, fileSize) { downloaded, total, speedBps ->
                    val frac = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 0.95f) else 0.05f
                    val mb = downloaded / (1024.0 * 1024.0)
                    val totalMb = if (total > 0) " / ${"%.0f".format(total / (1024.0 * 1024.0))} MB" else ""
                    val speed = "${"%.1f".format(speedBps / (1024.0 * 1024.0))} MB/s"
                    emit(versionKey, "downloading", "下载中 ${"%.1f".format(mb)} MB$totalMb · $speed", frac)
                }
                if (!ok && ver.isGdk && source != WUDownloadSource.Auto) {
                    val autoUrl = chooseGdkDownloadUrl(ver.downloadUrls, WUDownloadSource.Auto)
                    if (!autoUrl.isNullOrBlank() && autoUrl != resolvedUrl) {
                        emit(versionKey, "downloading", "当前源不可用，回退自动源重试...", 0.08f)
                        ok = WUDownloadClient.downloadFile(autoUrl, targetFile, fileSize) { downloaded, total, speedBps ->
                            val frac = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 0.95f) else 0.05f
                            val mb = downloaded / (1024.0 * 1024.0)
                            val totalMb = if (total > 0) " / ${"%.0f".format(total / (1024.0 * 1024.0))} MB" else ""
                            val speed = "${"%.1f".format(speedBps / (1024.0 * 1024.0))} MB/s"
                            emit(versionKey, "downloading", "重试下载 ${"%.1f".format(mb)} MB$totalMb · $speed", frac)
                        }
                    }
                }
                if (!isActive) throw CancellationException("基岩版下载已暂停")
                if (!ok) {
                    emit(versionKey, "error", "下载失败", 0f)
                    return@withContext "下载失败"
                }
            }

            if (ver.isGdk) {
                return@withContext installGdk(targetFile, effectiveInstallName, versionKey, settings)
            }

            installLocalFile(targetFile, effectiveInstallName, versionKey, settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            emit(versionKey, "error", "失败: ${e.message}", 0f)
            "失败: ${e.message}"
        }
    }

    private suspend fun resolveWuVersionFromMcAppx(ver: WUDownloadClient.WUVersion): McAppxClient.BedrockVersion? {
        return runCatching {
            val list = McAppxClient.fetchVersions()
            val matched = list.firstOrNull { it.version == ver.name && it.isPreview == ver.isPreview }
                ?: list.firstOrNull { it.version == ver.name }
                ?: return null
            McAppxClient.resolveDownloadUrl(matched)
        }.getOrNull()
    }

    private fun chooseGdkDownloadUrl(urls: List<String>, source: WUDownloadSource): String? {
        fun pickByHost(host: String): String? {
            val exact = urls.firstOrNull { host in it }
            if (exact != null) return exact
            val seed = urls.firstOrNull { "xboxlive." in it } ?: return null
            return runCatching {
                val uri = URI(seed)
                URI(uri.scheme, uri.userInfo, host, uri.port, uri.path, uri.query, uri.fragment).toString()
            }.getOrNull() ?: urls.firstOrNull()
        }

        return when (source) {
            WUDownloadSource.XboxCnAssets1 -> pickByHost("assets1.xboxlive.cn")
            WUDownloadSource.XboxCnAssets2 -> pickByHost("assets2.xboxlive.cn")
            WUDownloadSource.XboxComAssets1 -> pickByHost("assets1.xboxlive.com")
            WUDownloadSource.XboxComAssets2 -> pickByHost("assets2.xboxlive.com")
            WUDownloadSource.WuOfficial,
            WUDownloadSource.McAppxMirror,
            WUDownloadSource.Auto -> pickFastestGdkUrl(urls)
        }
    }

    /**
     * 并行探测所有候选 URL 的响应延迟，返回最快的。
     * 优先 .cn 源，同等延迟时 cn 优先。
     */
    private fun pickFastestGdkUrl(urls: List<String>): String? {
        if (urls.isEmpty()) return null
        if (urls.size == 1) return urls.first()

        // 构造候选：先 cn 后 com，去重
        val candidates = (
            urls.filter { "xboxlive.cn" in it } +
            urls.filter { "xboxlive.com" in it } +
            urls
        ).distinct().take(4)

        data class Probe(val url: String, val ms: Long)
        val results = java.util.concurrent.CopyOnWriteArrayList<Probe>()
        val threads = candidates.map { url ->
            Thread {
                val start = System.currentTimeMillis()
                runCatching {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "HEAD"
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.instanceFollowRedirects = true
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..299 || code == 206) {
                        results.add(Probe(url, System.currentTimeMillis() - start))
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        }
        threads.forEach { it.join(6_000) }

        return results.minByOrNull { it.ms }?.url
            ?: candidates.firstOrNull { "xboxlive.cn" in it }
            ?: candidates.firstOrNull()
    }

    /**
     * 通过 Xbox Marketplace 目录 API 动态解析 GDK msixvc 下载链接。
     * 用于 GdkLinks 数据库尚未收录的新版本（如刚发布的 26.x）。
     */
    private suspend fun resolveGdkUrlFromXboxCatalog(versionName: String, isPreview: Boolean): String? = withContext(Dispatchers.IO) {
        // Minecraft Windows (GDK) 的商店 product ID
        val productId = if (isPreview) "9p5x4qvlc2xr" else "9nblggh2jhxj"
        val candidates = listOf(
            "https://displaycatalog.mp.microsoft.com/v7.0/products/$productId?ms-cv=0&fieldsTemplate=Details&market=US&languages=en-US",
            "https://displaycatalog.mp.microsoft.com/v7.0/products/$productId?ms-cv=0&fieldsTemplate=Details&market=CN&languages=zh-CN",
        )
        for (apiUrl in candidates) {
            runCatching {
                val proc = ProcessBuilder(
                    "curl.exe", "-sL",
                    "--connect-timeout", "15", "--max-time", "30",
                    "-H", "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                    apiUrl,
                ).redirectErrorStream(true).start()
                val json = proc.inputStream.bufferedReader().readText()
                proc.waitFor(35, java.util.concurrent.TimeUnit.SECONDS)
                if (json.length < 100) return@runCatching null

                // 从 JSON 里找 PackageDownloadUris 下的 msixvc/msixbundle URL
                // 优先匹配版本号，再 fallback 到任意 msixvc
                val urlRegex = Regex(""""Uri"\s*:\s*"(https?://[^"]+\.msixvc[^"]*)"""", RegexOption.IGNORE_CASE)
                val allUrls = urlRegex.findAll(json).map { it.groupValues[1] }.toList()
                // 尝试精确匹配版本号
                val versionMatch = allUrls.firstOrNull { versionName in it }
                if (versionMatch != null) return@withContext versionMatch
                // fallback: 任意 msixvc，优先 cn 源
                allUrls.firstOrNull { "xboxlive.cn" in it }
                    ?: allUrls.firstOrNull { "xboxlive.com" in it }
                    ?: allUrls.firstOrNull()
            }.getOrNull()?.let { return@withContext it }
        }
        null
    }

    suspend fun download(entry: BedrockVersionCatalog.BedrockVersionEntry): String = withContext(Dispatchers.IO) {
        val versionKey = "${entry.version}_${entry.arch}"
        try {
            val settings = AppSettings.load()
            val cacheDir = File(settings.minecraftDir, "bedrock_versions/cache")
            cacheDir.mkdirs()
            val targetFile = File(cacheDir, entry.fileName)

            // ── Phase 1: 尝试直接 HTTP 下载 ─────────────────────────────
            if (targetFile.exists() && targetFile.length() > 0 &&
                (entry.fileSize <= 0 || targetFile.length() >= entry.fileSize * 0.95)) {
                println("[BDM] 缓存命中: ${entry.fileName} (${targetFile.length()} bytes)")
            } else {
                val downloadUrl = entry.downloadUrl
                if (downloadUrl.isBlank()) {
                    emit(versionKey, "error", "URL 为空", 0f)
                    return@withContext "安装失败: URL 为空"
                }

                emit(versionKey, "downloading", "正在下载 ${entry.version}...", 0f)
                println("[BDM] 开始下载: $downloadUrl")

                val result = directDownload(downloadUrl, targetFile, entry.fileSize, isCancelled = { !isActive }) { downloaded, total ->
                    val frac = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                    val mb = downloaded / (1024.0 * 1024.0)
                    emit(versionKey, "downloading", "下载中 ${"%.1f".format(mb)} MB...", frac)
                }
                if (!isActive) throw CancellationException("基岩版下载已暂停")

                when (result) {
                    DownloadResult.CLOUDFLARE_BLOCKED, DownloadResult.FAILED -> {
                        // 直接下载失败（Cloudflare 或网络问题），使用后台 Edge 下载
                        println("[BDM] 直接下载失败，启用后台 Edge 下载")
                        emit(versionKey, "downloading", "正在启动后台下载引擎...", 0.05f)

                        val downloaded = SilentEdgeDownloader.download(
                            url = downloadUrl,
                            downloadDir = cacheDir,
                            timeoutMs = 10 * 60 * 1000L,
                            isCancelled = { !isActive },
                        ) { status ->
                            emit(versionKey, "downloading", status, 0.3f)
                        }
                        if (!isActive) throw CancellationException("基岩版下载已暂停")

                        if (downloaded == null) {
                            emit(versionKey, "error", "下载失败: 后台下载超时", 0f)
                            return@withContext "安装失败: 下载超时"
                        }

                        // 如果下载的文件名和预期不同，重命名
                        if (downloaded.name != targetFile.name) {
                            downloaded.copyTo(targetFile, overwrite = true)
                            downloaded.delete()
                        }
                        println("[BDM] Edge 下载完成: ${targetFile.length()} bytes")
                    }
                    DownloadResult.SUCCESS -> {
                        println("[BDM] 直接下载完成: ${targetFile.length()} bytes")
                    }
                }
            }

            // ── Phase 2: 安装 ────────────────────────────────────────────
            installLocalFile(targetFile, entry.version, versionKey, settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            println("[BDM] 安装异常: ${e.message}")
            emit(versionKey, "error", "安装失败: ${e.message}", 0f)
            "安装失败: ${e.message}"
        }
    }

    /**
     * GDK (MSIXVC) 安装流程：
     *
     *  优先路径（无需 Store 授权）：
     *    内置 GdkExtractor.exe（打包在 resources/runtime/）基于 BedrockLauncher.Core (MIT)，
     *    使用纯 C# 实现的 XTS-AES 解密器直接解密解压 MSIXVC，
     *    无需系统已安装游戏，无需 Xbox DRM license。
     *
     *  回退路径（需要 Store 已安装过一次）：
     *    Add-AppxPackage 让系统解密安装到 C:\XboxGames，再 robocopy 复制。
     */
    private fun installGdk(
        file: File, version: String, versionKey: String, settings: AppSettings,
    ): String {
        val versionDir = File(settings.minecraftDir, "bedrock_versions/$version")

        // ── 优先：用内置 GdkExtractor.exe 直接解密解压（无需 Store 授权）────────
        emit(versionKey, "installing", "正在查找解压组件...", 0.52f)
        val gdkExe = findGdkExtractorExe()
        if (gdkExe != null) {
            println("[BDM-GDK] 找到 GdkExtractor.exe: ${gdkExe.absolutePath}")
            emit(versionKey, "installing", "正在解密解压 GDK 包（直接模式）...", 0.55f)

            if (versionDir.exists()) versionDir.deleteRecursively()
            versionDir.mkdirs()

            val rc = callGdkExtractor(gdkExe, file.absolutePath, versionDir.absolutePath, version, versionKey)
            if (rc == 0) {
                File(versionDir, "AppxSignature.p7x").takeIf { it.exists() }?.delete()
                val manifest = File(versionDir, "AppxManifest.xml")
                val exe = File(versionDir, "Minecraft.Windows.exe")
                if (manifest.exists() && exe.exists()) {
                    emit(versionKey, "installing", "正在安装基岩运行依赖...", 0.95f)
                    ensureFrameworkDeps(versionKey)
                    markInstalledVersion(settings, version, file)
                    emit(versionKey, "done", "GDK 安装完成: $version", 1f)
                    return "安装完成: $version"
                }
                println("[BDM-GDK] GdkExtractor 解压后目录不完整，回退到 Add-AppxPackage")
            } else {
                println("[BDM-GDK] GdkExtractor.exe 返回 rc=$rc，回退到 Add-AppxPackage")
            }
            versionDir.deleteRecursively()
            versionDir.mkdirs()
        }

        // ── 回退：Add-AppxPackage（需要系统已有 Xbox DRM license）──────────────
        emit(versionKey, "installing", "正在通过系统安装 GDK 包（需要 Xbox 授权）...", 0.58f)
        val filePath = file.absolutePath.replace("'", "''")

        val installScript = """
            ${'$'}ErrorActionPreference = 'Stop'
            try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
            try {
                Add-AppxPackage -Path '$filePath' -ForceApplicationShutdown -ForceUpdateFromAnyVersion -ErrorAction Stop
                Write-Output 'INSTALL_OK'
            } catch {
                Write-Output "INSTALL_FAIL: ${'$'}(${'$'}_.Exception.Message)"
                exit 1
            }
        """.trimIndent()

        val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", installScript)
            .redirectErrorStream(true).start()
        val installOut = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        println("[BDM-GDK] Add-AppxPackage exit=${proc.exitValue()}: $installOut")

        if (proc.exitValue() != 0) {
            val hint = "\n\n提示：GDK 版本安装需要 Xbox 解密授权。\n" +
                "方案A：安装 LeviLauncher（免费开源）后重试，本启动器会自动借用其解压组件。\n" +
                "方案B：先从 Microsoft Store 安装一次正版 Minecraft，再重试。"
            emit(versionKey, "error", "GDK 安装失败: ${installOut.take(150)}$hint", 0f)
            return "安装失败: ${installOut.take(150)}$hint"
        }

        // 从系统安装位置复制到版本目录
        emit(versionKey, "installing", "正在查找并复制游戏文件...", 0.75f)
        val locScript = """
            ${'$'}pkg = Get-AppxPackage | Where-Object { ${'$'}_.Name -like '*Minecraft*' -and ${'$'}_.Name -notlike '*Education*' -and ${'$'}_.Name -notlike '*Creator*' } | Sort-Object -Property Version -Descending | Select-Object -First 1
            if (${'$'}pkg) { Write-Output ${'$'}pkg.InstallLocation }
        """.trimIndent()
        val locProc = ProcessBuilder("powershell", "-NoProfile", "-Command", locScript)
            .redirectErrorStream(true).start()
        val locOut = locProc.inputStream.bufferedReader().readText().trim()
        locProc.waitFor()

        val installLocation = listOf(
            "C:\\XboxGames\\Minecraft Bedrock Edition\\Content",
            "C:\\XboxGames\\Minecraft\\Content",
            "C:\\XboxGames\\Minecraft",
        ).firstOrNull { File(it, "Minecraft.Windows.exe").exists() }
            ?: locOut.lines().firstOrNull { it.isNotBlank() && File(it.trim(), "Minecraft.Windows.exe").exists() }?.trim()

        if (installLocation == null) {
            emit(versionKey, "error", "GDK 安装完成但未找到游戏目录（$locOut）", 0f)
            return "安装失败: 找不到游戏目录"
        }

        val copyProc = ProcessBuilder(
            "robocopy", installLocation, versionDir.absolutePath,
            "/E", "/R:0", "/W:0", "/MT:16", "/NFL", "/NDL", "/NJH", "/NJS", "/NP"
        ).redirectErrorStream(true).start()
        copyProc.inputStream.bufferedReader().readText()
        copyProc.waitFor()
        if (copyProc.exitValue() > 7) {
            emit(versionKey, "error", "GDK 文件复制失败 (robocopy exit=${copyProc.exitValue()})", 0f)
            return "安装失败: 文件复制失败"
        }

        val manifest = File(versionDir, "AppxManifest.xml")
        val exe = File(versionDir, "Minecraft.Windows.exe")
        if (!manifest.exists() || !exe.exists()) {
            emit(versionKey, "error", "版本目录不完整", 0f)
            return "安装失败: 版本目录不完整"
        }

        emit(versionKey, "installing", "正在安装基岩运行依赖...", 0.95f)
        ensureFrameworkDeps(versionKey)
        markInstalledVersion(settings, version, file)
        emit(versionKey, "done", "GDK 安装完成: $version", 1f)
        return "安装完成: $version"
    }

    /**
     * 查找内置的 GdkExtractor.exe（打包在 resources/runtime/ 里）。
     * 运行时从 jar 解压到临时目录使用。
     */
    private fun findGdkExtractorExe(): File? {
        // 先看是否已解压到缓存目录
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "md3l1_runtime")
        val cached = File(cacheDir, "GdkExtractor.exe")
        if (cached.isFile && cached.length() > 1_000_000) return cached

        // 从 classpath 资源解压
        val stream = javaClass.classLoader.getResourceAsStream("runtime/GdkExtractor.exe") ?: return null
        cacheDir.mkdirs()
        try {
            stream.use { s -> cached.outputStream().use { s.copyTo(it) } }
        } catch (e: Exception) {
            println("[BDM-GDK] 解压 GdkExtractor.exe 失败: ${e.message}")
            return null
        }
        return if (cached.isFile && cached.length() > 1_000_000) cached else null
    }

    /**
     * 调用 GdkExtractor.exe 解密解压 MSIXVC。
     * 返回 exit code（0 = 成功）。
     * GdkExtractor.exe 使用 BedrockLauncher.Core（MIT）内置 XTS-AES 解密，无需 Store 授权。
     */
    private fun callGdkExtractor(
        exe: File, msixvcPath: String, outDir: String, version: String, versionKey: String,
    ): Int {
        val gameType = if (version.contains("preview", ignoreCase = true) ||
            version.contains("beta", ignoreCase = true)) "preview" else "release"
        return try {
            val proc = ProcessBuilder(exe.absolutePath, msixvcPath, outDir, gameType)
                .redirectErrorStream(true).start()

            // 实时读输出并更新进度
            val reader = proc.inputStream.bufferedReader()
            var lastLine = ""
            reader.forEachLine { line ->
                lastLine = line
                println("[GdkExtractor] $line")
                if (line.startsWith("PROGRESS:")) {
                    val pct = line.removePrefix("PROGRESS:").trim().toFloatOrNull()
                    if (pct != null) emit(versionKey, "installing", "解压中 ${pct.toInt()}%...", 0.55f + pct / 100f * 0.35f)
                } else if (line.startsWith("STATE:")) {
                    emit(versionKey, "installing", "${line.removePrefix("STATE:")}", 0.8f)
                }
            }
            proc.waitFor(15 * 60, java.util.concurrent.TimeUnit.SECONDS)
            val exit = proc.exitValue()
            println("[BDM-GDK] GdkExtractor exit=$exit, last=$lastLine")
            exit
        } catch (e: Exception) {
            println("[BDM-GDK] GdkExtractor 调用异常: ${e.message}")
            1
        }
    }

    /**
     * 安装 .appx / .msixbundle（UWP 非加密包）：直接解压到版本目录。
     */
    private fun installAppx(
        file: File, version: String, versionKey: String, settings: AppSettings,
    ): String {
        // ── Phase 1: 解压到启动器自己的版本目录，确保多版本隔离 ───────────────
        emit(versionKey, "installing", "正在解压到版本目录 $version...", 0.5f)
        val extractDir = File(settings.minecraftDir, "bedrock_versions/$version")
        if (extractDir.exists()) extractDir.deleteRecursively()
        extractDir.mkdirs()

        val extracted = try {
            BedrockLaunchEngine().extractAppxBundle(file.absolutePath, extractDir, "x64")
            true
        } catch (e: Exception) {
            if (file.extension.lowercase() == "appxbundle" || file.extension.lowercase() == "msixbundle") {
                println("[BDM] Bundle 严格 x64 解包失败: ${e.message}")
                false
            } else {
                println("[BDM] 标准 Appx 解包失败: ${e.message}, 尝试通用解压")
                safeExtract(file, extractDir, versionKey)
            }
        }
        if (!extracted) {
            emit(versionKey, "error", "解压失败: $version", 0f)
            return "安装失败: 解压失败"
        }

        val manifest = File(extractDir, "AppxManifest.xml")
        val blockMap = File(extractDir, "AppxBlockMap.xml")
        val contentTypes = File(extractDir, "[Content_Types].xml")
        val exe = File(extractDir, "Minecraft.Windows.exe")
        val missing = listOf(
            "AppxManifest.xml" to manifest,
            "AppxBlockMap.xml" to blockMap,
            "[Content_Types].xml" to contentTypes,
            "Minecraft.Windows.exe" to exe,
        ).firstOrNull { !it.second.exists() }?.first
        if (missing != null) {
            emit(versionKey, "error", "安装包不是完整基岩版 Appx，缺少 $missing", 0f)
            return "安装失败: 安装包不完整，无法启动"
        }

        emit(versionKey, "installing", "正在安装基岩运行依赖...", 0.9f)
        ensureFrameworkDeps(versionKey)

        markInstalledVersion(settings, version, file)
        emit(versionKey, "done", "安装完成，启动时将注册隔离版本: $version", 1f)
        return "安装完成: $version"
    }

    private fun markInstalledVersion(settings: AppSettings, version: String, file: File) {
        val versionDir = File(settings.minecraftDir, "bedrock_versions/$version")
        versionDir.mkdirs()
        File(versionDir, ".installed").writeText(
            "version=$version\nsource=${file.absolutePath}\ninstalledAt=${System.currentTimeMillis()}\n",
            Charsets.UTF_8,
        )
    }

    /**
     * 自动下载安装 UWP 框架依赖（VCLibs + .NET Native Runtime/Framework）。
     * 这些是 Bedrock 注册/运行必需的框架包。
     */
    private fun ensureFrameworkDeps(versionKey: String) {
        // 需要安装的框架 appx 包（仅 x64）
        data class Dep(val name: String, val pkgPrefix: String, val url: String)
        val deps = listOf(
            Dep("VCLibs 14.00 UWPDesktop",
                "Microsoft.VCLibs.140.00.UWPDesktop",
                "https://aka.ms/Microsoft.VCLibs.x64.14.00.Desktop.appx"),
            Dep("VCLibs 14.00",
                "Microsoft.VCLibs.140.00",
                "https://aka.ms/Microsoft.VCLibs.x64.14.00.Desktop.appx"),
        )

        // 获取已安装包名
        val installed: Set<String> = try {
            val proc = ProcessBuilder("powershell", "-NoProfile", "-Command",
                "Get-AppxPackage | ForEach-Object { Write-Output \$_.Name }")
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            out.lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        } catch (_: Exception) { emptySet() }

        for (dep in deps) {
            val found = installed.any { it.startsWith(dep.pkgPrefix, ignoreCase = true) }
            if (found) {
                println("[BDM] ✓ ${dep.name} 已安装")
                continue
            }
            println("[BDM] ✗ ${dep.name} 缺失，正在自动安装...")
            emit(versionKey, "installing", "正在安装依赖: ${dep.name}...", 0.92f)
            try {
                val script = """
                    ${'$'}ProgressPreference = 'SilentlyContinue'
                    ${'$'}tmp = Join-Path ${'$'}env:TEMP '${dep.pkgPrefix}.appx'
                    try {
                        Invoke-WebRequest -Uri '${dep.url}' -OutFile ${'$'}tmp -UseBasicParsing
                        Add-AppxPackage -Path ${'$'}tmp -ErrorAction Stop
                        Remove-Item ${'$'}tmp -Force -ErrorAction SilentlyContinue
                        Write-Output 'DEP_OK'
                    } catch {
                        Write-Output "DEP_FAIL: ${'$'}(${'$'}_.Exception.Message)"
                    }
                """.trimIndent()
                val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", script)
                    .redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                println("[BDM] 依赖安装 ${dep.name}: $out")
            } catch (e: Exception) {
                println("[BDM] 依赖安装异常 ${dep.name}: ${e.message}")
            }
        }
    }

    /**
     * 安全解压：Java ZipFile 优先，失败则 fallback 到 Windows 自带 tar.exe
     */
    private fun safeExtract(zipFile: File, targetDir: File, versionKey: String): Boolean {
        try {
            emit(versionKey, "installing", "正在高速解压...", 0.6f)
            val proc = ProcessBuilder(
                "tar", "-xf", zipFile.absolutePath, "-C", targetDir.absolutePath
            ).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor() == 0
            println("[BDM] tar> exit=${proc.exitValue()}, output=$output")
            if (ok) return true
        } catch (e: Exception) {
            println("[BDM] tar 解压失败: ${e.message}, 尝试 Java ZIP")
        }

        try {
            extractZipWithProgress(zipFile, targetDir, versionKey)
            return true
        } catch (e: Exception) {
            println("[BDM] Java ZIP 解压失败: ${e.message}, 尝试 PowerShell 备选")
        }

        try {
            emit(versionKey, "installing", "使用 PowerShell 解压...", 0.6f)
            val src = zipFile.absolutePath.replace("'", "''")
            val dst = targetDir.absolutePath.replace("'", "''")
            val proc = ProcessBuilder(
                "powershell", "-NoProfile", "-Command",
                "Expand-Archive -Path '$src' -DestinationPath '$dst' -Force"
            ).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor() == 0
            println("[BDM] Expand-Archive> exit=${proc.exitValue()}, output=$output")
            if (ok) return true
        } catch (e: Exception) {
            println("[BDM] PowerShell 解压也失败: ${e.message}")
        }

        return false
    }

    /**
     * 安装本地 .appx / .exe 文件。
     * 可从 download() 调用，也可直接传入已下载的文件。
     */
    private fun installLocalFile(
        file: File, version: String, versionKey: String, settings: AppSettings,
    ): String {
        val ext = file.extension.lowercase()
        when (ext) {
            "appx", "msixbundle", "appxbundle", "msix", "msixvc" -> {
                return installAppx(file, version, versionKey, settings)
            }
            "exe" -> {
                // MCAPPX 的 exe 是安装器壳，内含 appx —— 提取出来静默安装
                emit(versionKey, "installing", "正在从安装器中提取 .appx...", 0.3f)
                val extractDir = File(file.parentFile, "extract_${System.currentTimeMillis()}")
                extractDir.mkdirs()
                val appxFile = extractAppxFromExe(file, extractDir, versionKey)
                if (appxFile != null) {
                    println("[BDM] 提取到: ${appxFile.absolutePath} (${appxFile.length()} bytes)")
                    emit(versionKey, "installing", "正在安装 $version（Add-AppxPackage）...", 0.6f)
                    val proc = ProcessBuilder(
                        "powershell", "-NoProfile", "-Command",
                        "Add-AppxPackage -Path '${appxFile.absolutePath}' -ForceApplicationShutdown -ErrorAction Stop"
                    ).redirectErrorStream(true).start()
                    val output = proc.inputStream.bufferedReader().readText()
                    val exitCode = proc.waitFor()
                    // 清理临时解压目录
                    try { extractDir.deleteRecursively() } catch (_: Exception) {}
                    if (exitCode != 0) {
                        println("[BDM] Add-AppxPackage 失败 (exit=$exitCode): $output")
                        emit(versionKey, "error", "安装失败: ${output.take(200)}", 0f)
                        return "安装失败: ${output.take(200)}"
                    }
                    println("[BDM] exe → appx → Add-AppxPackage 成功")
                } else {
                    // 提取失败，最后手段：静默运行 exe
                    println("[BDM] 无法提取 appx，尝试静默运行 exe")
                    try { extractDir.deleteRecursively() } catch (_: Exception) {}
                    emit(versionKey, "installing", "正在静默运行安装器...", 0.5f)
                    val proc = ProcessBuilder(file.absolutePath, "/S", "/VERYSILENT")
                        .directory(file.parentFile)
                        .redirectErrorStream(true)
                        .start()
                    proc.waitFor()
                }
            }
            else -> {
                emit(versionKey, "error", "不支持的格式: $ext", 0f)
                return "安装失败: 不支持的格式 $ext"
            }
        }
        emit(versionKey, "done", "安装完成: $version", 1f)
        println("[BDM] 安装完成: $version")
        return "安装完成: $version"
    }

    /**
     * 浏览器下载 + 自动监控方案。
     * 1) 记录 Downloads 文件夹现有文件
     * 2) 用浏览器打开下载页
     * 3) 轮询 Downloads 文件夹检测新 .appx 文件
     * 4) 等待文件大小稳定（下载完成）
     * 5) 自动调用 Add-AppxPackage 安装
     */
    suspend fun downloadViaBrowser(
        detailPageUrl: String,
        version: String,
        versionKey: String,
    ): String = withContext(Dispatchers.IO) {
        try {
            val downloadsDir = File(System.getProperty("user.home"), "Downloads")
            val appxExts = setOf("appx", "msixbundle", "appxbundle")

            // 记录现有文件
            val existingFiles = downloadsDir.listFiles()
                ?.filter { it.extension.lowercase() in appxExts }
                ?.map { it.absolutePath }
                ?.toSet() ?: emptySet()

            // 打开浏览器
            emit(versionKey, "downloading", "正在打开浏览器下载页...", 0f)
            openInBrowser(detailPageUrl)

            // 轮询检测新文件（最多等 10 分钟）
            emit(versionKey, "downloading", "等待浏览器下载中... 请在浏览器中点击下载", 0.05f)
            var newFile: File? = null
            val deadline = System.currentTimeMillis() + 10 * 60 * 1000L // 10 min

            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2000)
                val currentFiles = downloadsDir.listFiles()
                    ?.filter { it.extension.lowercase() in appxExts && it.absolutePath !in existingFiles }
                    ?: emptyList()

                // 也检测 .crdownload / .partial / .tmp 表示下载进行中
                val downloading = downloadsDir.listFiles()
                    ?.any { it.name.contains("Minecraft", ignoreCase = true) &&
                            (it.extension == "crdownload" || it.extension == "partial" || it.extension == "tmp") }
                    ?: false

                if (downloading) {
                    emit(versionKey, "downloading", "浏览器正在下载中...", 0.2f)
                }

                val mcFile = currentFiles.find {
                    it.name.contains("Minecraft", ignoreCase = true) ||
                    it.name.contains("be-", ignoreCase = true)
                } ?: currentFiles.firstOrNull()

                if (mcFile != null && mcFile.length() > 1024 * 1024) {
                    // 等待文件大小稳定（下载完成）
                    val size1 = mcFile.length()
                    emit(versionKey, "downloading", "检测到文件: ${mcFile.name} (${"%.1f".format(size1 / 1048576.0)} MB)...", 0.5f)
                    Thread.sleep(3000)
                    val size2 = mcFile.length()
                    if (size1 == size2) {
                        // 大小稳定，下载完成
                        newFile = mcFile
                        break
                    }
                    emit(versionKey, "downloading", "下载中 ${"%.1f".format(size2 / 1048576.0)} MB...", 0.6f)
                }
            }

            if (newFile == null) {
                emit(versionKey, "error", "未检测到下载文件（超时）", 0f)
                return@withContext "安装失败: 未检测到浏览器下载的文件"
            }

            println("[BDM] 检测到下载文件: ${newFile.absolutePath} (${newFile.length()} bytes)")

            // 复制到缓存目录
            val settings = AppSettings.load()
            val cacheDir = File(settings.minecraftDir, "bedrock_versions/cache")
            cacheDir.mkdirs()
            val cachedFile = File(cacheDir, newFile.name)
            emit(versionKey, "installing", "正在复制文件到缓存...", 0.7f)
            newFile.copyTo(cachedFile, overwrite = true)

            // 安装
            installLocalFile(cachedFile, version, versionKey, settings)
        } catch (e: Exception) {
            e.printStackTrace()
            emit(versionKey, "error", "安装失败: ${e.message}", 0f)
            "安装失败: ${e.message}"
        }
    }

    /**
     * 手动导入本地 .appx 文件并安装。
     */
    suspend fun installFromFile(file: File, version: String): String = withContext(Dispatchers.IO) {
        val versionKey = "${version}_import"
        try {
            val settings = AppSettings.load()
            installLocalFile(file, version, versionKey, settings)
        } catch (e: Exception) {
            emit(versionKey, "error", "安装失败: ${e.message}", 0f)
            "安装失败: ${e.message}"
        }
    }

    /**
     * 用系统浏览器打开 URL（绕过 Cloudflare）。
     */
    fun openInBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                ProcessBuilder("cmd", "/c", "start", url).start()
            }
        } catch (e: Exception) {
            println("[BDM] 无法打开浏览器: ${e.message}")
            try { ProcessBuilder("cmd", "/c", "start", url).start() } catch (_: Exception) {}
        }
    }

    /**
     * 打开 Microsoft Store 中的 Minecraft 页面。
     */
    fun openMicrosoftStore() {
        try {
            ProcessBuilder("cmd", "/c", "start", "ms-windows-store://pdp/?ProductId=9NBLGGH2JHXJ").start()
        } catch (_: Exception) {}
    }

    enum class DownloadResult { SUCCESS, FAILED, CLOUDFLARE_BLOCKED }

    // ═══════════════════════════════════════════════════════════════════════
    //  直接 HTTP 下载 — 不经过 DownloadManager，带完整错误日志
    // ═══════════════════════════════════════════════════════════════════════

    private fun directDownload(
        url: String,
        dest: File,
        expectedSize: Long,
        isCancelled: () -> Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadResult {
        dest.parentFile?.mkdirs()
        var attempt = 0
        while (attempt < 3) {
            if (isCancelled()) throw CancellationException("基岩版下载已暂停")
            attempt++
            try {
                // 手动跟随重定向
                var currentUrl = url
                var conn: HttpURLConnection
                var redirects = 0
                while (true) {
                    if (isCancelled()) throw CancellationException("基岩版下载已暂停")
                    println("[BDM-DL] 请求 ($attempt/3): $currentUrl")
                    conn = URL(currentUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 120_000
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", UA)
                    conn.setRequestProperty("Referer", "https://www.mcappx.com/")
                    conn.setRequestProperty("Accept", "*/*")

                    val code = conn.responseCode
                    println("[BDM-DL] 响应: HTTP $code")

                    if (code in 301..308) {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc.isNullOrBlank() || ++redirects > 10) {
                            println("[BDM-DL] 重定向失败: redirects=$redirects, location=$loc")
                            return DownloadResult.FAILED
                        }
                        currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                        println("[BDM-DL] 重定向到: $currentUrl")
                        continue
                    }
                    break
                }

                if (conn.responseCode == 403) {
                    // 检测 Cloudflare JS 挑战
                    val errBody = try { conn.errorStream?.bufferedReader()?.readText()?.take(500) } catch (_: Exception) { null }
                    conn.disconnect()
                    if (errBody != null && (errBody.contains("Just a moment") || errBody.contains("cloudflare"))) {
                        println("[BDM-DL] 检测到 Cloudflare JS 挑战，无法直接下载")
                        return DownloadResult.CLOUDFLARE_BLOCKED
                    }
                    println("[BDM-DL] HTTP 403 (非 Cloudflare): $errBody")
                    return DownloadResult.CLOUDFLARE_BLOCKED // 403 都当作需要浏览器
                }

                if (conn.responseCode != 200) {
                    val errBody = try { conn.errorStream?.bufferedReader()?.readText()?.take(500) } catch (_: Exception) { null }
                    println("[BDM-DL] HTTP ${conn.responseCode}: $errBody")
                    conn.disconnect()
                    Thread.sleep(1000L * attempt)
                    continue
                }

                val contentLength = conn.contentLengthLong.let { if (it > 0) it else expectedSize }
                println("[BDM-DL] Content-Length: ${conn.contentLengthLong}, Content-Type: ${conn.contentType}")

                conn.inputStream.use { input ->
                    RandomAccessFile(dest, "rw").use { raf ->
                        val buffer = ByteArray(65536)
                        var totalRead = 0L
                        var read: Int
                        var lastReport = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            if (isCancelled()) throw CancellationException("基岩版下载已暂停")
                            raf.write(buffer, 0, read)
                            totalRead += read
                            if (totalRead - lastReport > 512 * 1024) {
                                onProgress(totalRead, contentLength)
                                lastReport = totalRead
                            }
                        }
                        onProgress(totalRead, contentLength)
                        println("[BDM-DL] 下载完成: $totalRead bytes")
                    }
                }
                conn.disconnect()
                return DownloadResult.SUCCESS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("[BDM-DL] 下载异常 ($attempt/3): ${e.javaClass.simpleName}: ${e.message}")
                if (attempt < 3) Thread.sleep(2000L * attempt)
            }
        }
        return DownloadResult.FAILED
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  从 exe 安装器中提取 .appx
    // ═══════════════════════════════════════════════════════════════════════

    private val APPX_EXTS = setOf("appx", "msixbundle", "appxbundle", "msix")

    private fun extractAppxFromExe(exeFile: File, extractDir: File, versionKey: String): File? {
        // 策略1: 当作 ZIP 打开（自解压 ZIP）
        try {
            ZipFile(exeFile).use { zip ->
                val appx = zip.entries().asSequence().firstOrNull { e ->
                    !e.isDirectory && e.name.substringAfterLast('.').lowercase() in APPX_EXTS
                }
                if (appx != null) {
                    val out = File(extractDir, File(appx.name).name)
                    zip.getInputStream(appx).use { i -> out.outputStream().use { o -> i.copyTo(o, 65536) } }
                    println("[BDM] ZIP提取成功: ${out.name}")
                    return out
                }
            }
        } catch (_: Exception) {}

        // 策略2: 7z 提取（支持 NSIS/Inno/7z SFX）
        try {
            val proc = ProcessBuilder(
                "powershell", "-NoProfile", "-Command",
                "& 7z x '${exeFile.absolutePath}' -o'${extractDir.absolutePath}' -y 2>&1"
            ).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() == 0) {
                val found = findAppxInDir(extractDir)
                if (found != null) { println("[BDM] 7z提取成功: ${found.name}"); return found }
            }
        } catch (_: Exception) {}

        // 策略3: Inno Setup 静默解压
        try {
            val proc = ProcessBuilder(
                exeFile.absolutePath, "/VERYSILENT", "/SUPPRESSMSGBOXES",
                "/DIR=${extractDir.absolutePath}", "/SP-"
            ).redirectErrorStream(true).start()
            proc.waitFor()
            val found = findAppxInDir(extractDir)
            if (found != null) { println("[BDM] Inno提取成功: ${found.name}"); return found }
        } catch (_: Exception) {}

        return null
    }

    private fun findAppxInDir(dir: File): File? {
        return dir.walkTopDown().filter { f ->
            f.isFile && f.extension.lowercase() in APPX_EXTS && f.length() > 1_000_000
        }.maxByOrNull { it.length() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ZIP 解压回退
    // ═══════════════════════════════════════════════════════════════════════

    private fun extractZipWithProgress(zipFile: File, targetDir: File, versionKey: String) {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
                .filter { e ->
                    !e.name.startsWith("AppxMetadata/") &&
                    !e.name.startsWith("[Content_Types]") &&
                    e.name != "AppxBlockMap.xml" &&
                    e.name != "AppxSignature.p7x"
                }
            val total = entries.size
            entries.forEachIndexed { idx, entry ->
                val outFile = File(targetDir, entry.name)
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) return@forEachIndexed
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input: InputStream ->
                    outFile.outputStream().use { output -> input.copyTo(output, 65536) }
                }
                if (idx % 50 == 0 || idx == total - 1) {
                    val frac = (idx + 1).toFloat() / total
                    emit(versionKey, "extracting", "解压中 ${idx + 1}/$total ...", frac)
                }
            }
        }
    }

    private fun emit(versionKey: String, phase: String, message: String, fraction: Float) {
        if (versionKey in closedVersions) return
        val normalizedFraction = fraction.coerceIn(0f, 1f)
        val current = InstallProgress(phase, message, normalizedFraction, versionKey)
        val now = System.currentTimeMillis()
        val last = lastEmitProgress[versionKey]
        val lastTs = lastEmitTimeMs[versionKey] ?: 0L
        val force = phase == "done" || phase == "error" || phase == "installing" || phase == "extracting"
        val shouldEmit = force ||
            last == null ||
            phase != last.phase ||
            now - lastTs >= 110L ||
            kotlin.math.abs(normalizedFraction - last.fraction) >= 0.004f
        if (!shouldEmit) return

        lastEmitProgress[versionKey] = current
        lastEmitTimeMs[versionKey] = now
        _installProgress.value = current
        val status = when (phase) {
            "done" -> DownloadHub.TaskStatus.Done
            "error" -> DownloadHub.TaskStatus.Error
            else -> DownloadHub.TaskStatus.Running
        }
        DownloadHub.upsert(DownloadHub.HubTask(
            id = "bedrock_$versionKey", name = "基岩版 $versionKey",
            type = DownloadHub.TaskType.BedrockVersion,
            status = status, step = message, fraction = normalizedFraction,
            error = if (phase == "error") message else "",
        ))
    }
}
