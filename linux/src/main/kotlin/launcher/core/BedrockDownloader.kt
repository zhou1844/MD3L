package launcher.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

data class BedrockDownloadProgress(
    val step: String = "",
    val fraction: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val isRunning: Boolean = false,
    val error: String = "",
) {
    val speedMbps: String
        get() = "%.2f MB/s".format(speedBytesPerSec / 1_048_576.0)
}

data class BedrockDownloadUrl(val host: String, val url: String)

object BedrockDownloader {

    private val GAME_FILE_DOWNLOAD_SOURCES = listOf(
        BedrockDownloadUrl("assets1.xboxlive.cn", "http://assets1.xboxlive.cn{router}"),
        BedrockDownloadUrl("assets2.xboxlive.cn", "http://assets2.xboxlive.cn{router}"),
        BedrockDownloadUrl("assets1.xboxlive.com", "http://assets1.xboxlive.com{router}"),
        BedrockDownloadUrl("assets2.xboxlive.com", "http://assets2.xboxlive.com{router}"),
    )

    private fun resolveDownloadUrls(version: McAppxClient.BedrockVersion): List<BedrockDownloadUrl> {
        val metaUrl = version.metaData.firstOrNull() ?: return emptyList()
        val uri = URI.create(metaUrl)
        val router = uri.rawPath
        return GAME_FILE_DOWNLOAD_SOURCES.map { source ->
            BedrockDownloadUrl(
                host = source.host,
                url = source.url.replace("{router}", router),
            )
        }
    }

    private fun verifyGamePackage(filePath: String, expectedMd5: String): Boolean {
        if (expectedMd5.isBlank()) return true
        return try {
            val file = java.io.File(filePath)
            if (!file.exists()) return false
            val digest = java.security.MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    digest.update(buffer, 0, len)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
                .equals(expectedMd5, ignoreCase = true)
        } catch (e: Exception) {
            println("[BedrockDL] MD5 校验异常: ${e.message}")
            false
        }
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(8_000))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val _progress = MutableStateFlow(BedrockDownloadProgress())
    val progress: StateFlow<BedrockDownloadProgress> = _progress.asStateFlow()

    var downloadJob: Job? = null
        private set

    fun cancel() {
        downloadJob?.cancel(CancellationException("下载已取消"))
    }

    suspend fun downloadAndInstall(
        version: McAppxClient.BedrockVersion,
        gameName: String,
        installRoot: String,
        chunkCount: Int = 4,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ): Boolean {
        _progress.value = BedrockDownloadProgress(step = "解析下载地址...", fraction = 0f, isRunning = true)

        val urls: List<BedrockDownloadUrl>
        try {
            urls = resolveDownloadUrls(version)
            println("[BedrockDL] 版本 ${version.version}: metaData=${version.metaData.size}")
            if (urls.isEmpty()) {
                println("[BedrockDL] 无法解析下载地址: metaData=${version.metaData}")
                _progress.value = BedrockDownloadProgress(error = "无法解析下载地址: 版本无下载链接", isRunning = false)
                return false
            }
            println("[BedrockDL] 下载地址(${urls.size}): ${urls[0].url.take(100)}...")
        } catch (e: Exception) {
            println("[BedrockDL] 解析下载地址异常: ${e.message}")
            _progress.value = BedrockDownloadProgress(error = "地址解析异常: ${e.message}", isRunning = false)
            return false
        }

        val versionSaveDir = File(installRoot, "version_save").also { it.mkdirs() }
        val packagePath = File(versionSaveDir, "${version.version}.insPack")

        _progress.value = BedrockDownloadProgress(step = "准备下载...", fraction = 0.05f, isRunning = true)

        val downloadOk = scope.async {
            downloadPackage(urls, packagePath, chunkCount)
        }

        val result = downloadOk.await()
        if (!result) {
            _progress.value = BedrockDownloadProgress(error = "下载失败", isRunning = false)
            return false
        }

        _progress.value = BedrockDownloadProgress(step = "验证包完整性...", fraction = 0.80f, isRunning = true)

        val md5Ok = verifyGamePackage(packagePath.absolutePath, version.md5)
        if (!md5Ok && version.md5.isNotBlank()) {
            _progress.value = BedrockDownloadProgress(error = "MD5 校验失败，请重新下载", isRunning = false)
            return false
        }

        _progress.value = BedrockDownloadProgress(step = "解压安装...", fraction = 0.85f, isRunning = true)

        val installDir = File(installRoot, "bedrock_versions/$gameName")
        if (installDir.exists()) {
            installDir.deleteRecursively()
        }
        installDir.mkdirs()

        val extractOk = withContext(Dispatchers.IO) {
            extractPackage(packagePath, installDir, if (version.isPreview) "preview" else "release")
        }
        if (!extractOk) {
            _progress.value = BedrockDownloadProgress(error = "解压失败", isRunning = false)
            return false
        }

        saveVersionConfig(installDir, version, gameName)

        _progress.value = BedrockDownloadProgress(
            step = "安装完成",
            fraction = 1f,
            isRunning = false,
        )

        return true
    }

    private suspend fun downloadPackage(
        urls: List<BedrockDownloadUrl>,
        dest: File,
        chunkCount: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        for ((idx, source) in urls.withIndex()) {
            try {
                println("[BedrockDL] 尝试下载: ${source.url}")
                val headRequest = HttpRequest.newBuilder()
                    .uri(URI.create(source.url))
                    .timeout(Duration.ofMillis(8_000))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0")
                    .header("Host", source.host)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build()

                val headResponse = try {
                    httpClient.send(headRequest, HttpResponse.BodyHandlers.discarding())
                } catch (e: Exception) {
                    println("[BedrockDL] HEAD 请求失败: ${e.message}，回退单线程下载")
                    null
                }

                val contentLength = headResponse?.headers()?.firstValue("Content-Length")?.map { it.toLong() }?.orElse(-1L) ?: -1L
                val acceptRanges = headResponse?.headers()?.firstValue("Accept-Ranges")?.orElse("").orEmpty()

                if (contentLength > 0 && acceptRanges.contains("bytes", ignoreCase = true) && chunkCount > 1) {
                    multiThreadDownload(source, dest, contentLength, chunkCount, source.host)
                } else {
                    singleThreadDownload(source, dest, source.host)
                }

                if (dest.isFile && dest.length() > 0) {
                    println("[BedrockDL] 下载完成: ${dest.length()} bytes")
                    return@withContext true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("[BedrockDL] 下载失败 ($idx): ${e.message}")
            }
        }
        false
    }

    private fun singleThreadDownload(
        source: BedrockDownloadUrl,
        dest: File,
        host: String,
    ) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(source.url))
            .timeout(Duration.ofMinutes(30))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0")
            .header("Host", host)
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val code = response.statusCode()
        if (code != 200) {
            response.body().close()
            println("[BedrockDL] HTTP $code")
            return
        }

        val contentLength = response.headers().firstValue("Content-Length")
            .map { it.toLong() }.orElse(-1L)

        response.body().use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var len: Int
                var downloaded = 0L
                val startTime = System.currentTimeMillis()
                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                    downloaded += len
                    val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    val speed = downloaded * 1000L / elapsed
                    val frac = if (contentLength > 0) (downloaded.toFloat() / contentLength).coerceIn(0f, 0.75f) else 0f
                    _progress.value = BedrockDownloadProgress(
                        step = "下载中...",
                        fraction = 0.05f + frac,
                        speedBytesPerSec = speed,
                        downloadedBytes = downloaded,
                        totalBytes = contentLength,
                        isRunning = true,
                    )
                }
            }
        }
    }

    private suspend fun multiThreadDownload(
        source: BedrockDownloadUrl,
        dest: File,
        contentLength: Long,
        chunkCount: Int,
        host: String,
    ) = coroutineScope {
        val partSize = (contentLength / chunkCount).coerceAtLeast(5 * 1024 * 1024)
        val actualChunks = ((contentLength + partSize - 1) / partSize).toInt().coerceAtMost(chunkCount)
        val downloadedBytes = AtomicLong(0L)
        val startTime = System.currentTimeMillis()

        val tempDir = Files.createTempDirectory("bedrock_dl_")
        val tempFiles = (0 until actualChunks).map { idx ->
            File(tempDir.toFile(), "part_$idx")
        }

        try {
            val jobs = (0 until actualChunks).map { idx ->
                async(Dispatchers.IO) {
                    val start = idx * partSize
                    val end = ((idx + 1) * partSize - 1).coerceAtMost(contentLength - 1)
                    if (start >= contentLength) return@async

                    var retries = 3
                    while (retries > 0) {
                        try {
                            val request = HttpRequest.newBuilder()
                                .uri(URI.create(source.url))
                                .timeout(Duration.ofMinutes(30))
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0")
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0")
                                .header("Host", host)
                                .header("Range", "bytes=$start-$end")
                                .GET()
                                .build()

                            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
                            val code = response.statusCode()
                            if (code !in 200..299 && code != 206) {
                                response.body().close()
                                throw Exception("HTTP $code")
                            }

                            val raf = RandomAccessFile(tempFiles[idx], "rw")
                            response.body().use { input ->
                                raf.use { file ->
                                    val buffer = ByteArray(65536)
                                    var len: Int
                                    while (input.read(buffer).also { len = it } != -1) {
                                        ensureActive()
                                        file.write(buffer, 0, len)
                                        val dl = downloadedBytes.addAndGet(len.toLong())
                                        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                                        val speed = dl * 1000L / elapsed
                                        val frac = (dl.toFloat() / contentLength).coerceIn(0f, 0.75f)
                                        _progress.value = BedrockDownloadProgress(
                                            step = "下载中...",
                                            fraction = 0.05f + frac,
                                            speedBytesPerSec = speed,
                                            downloadedBytes = dl,
                                            totalBytes = contentLength,
                                            isRunning = true,
                                        )
                                    }
                                }
                            }
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            retries--
                            if (retries == 0) throw e
                            delay(500)
                        }
                    }
                }
            }
            jobs.joinAll()

            dest.parentFile?.mkdirs()
            FileChannel.open(
                dest.toPath(),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { destChannel ->
                for (tf in tempFiles) {
                    if (tf.isFile) {
                        FileChannel.open(tf.toPath(), StandardOpenOption.READ).use { srcChannel ->
                            srcChannel.transferTo(0, srcChannel.size(), destChannel)
                        }
                    }
                }
            }
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    private fun extractPackage(packagePath: File, installDir: File, gameType: String): Boolean {
        return try {
            println("[BedrockDL] GDK 包解压待实现: ${packagePath.absolutePath}")
            // TODO: 实现新的 MSIXVC 解压逻辑
            false
        } catch (e: Exception) {
            println("[BedrockDL] GDK 解压失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun saveVersionConfig(
        installDir: File,
        version: McAppxClient.BedrockVersion,
        gameName: String,
    ) {
        val configDir = File(installDir, "config/BedrockBoot2")
        configDir.mkdirs()

        val bodyFile = installDir.walkTopDown().maxDepth(3).find {
            it.name.equals("Minecraft.Windows.exe", ignoreCase = true)
        }?.relativeTo(installDir)?.path ?: "Minecraft.Windows.exe"

        val configJson = """
{
  "Version": "${version.version}",
  "VersionName": "$gameName",
  "BuildType": "GDK",
  "VersionType": "${if (version.isPreview) "Preview" else "Release"}",
  "BodyFile": "$bodyFile",
  "IsVersionIsolated": false,
  "IsEditModel": false,
  "IsModes": false,
  "OtherCommand": ""
}
        """.trimIndent()
        File(configDir, "config.json").writeText(configJson, Charsets.UTF_8)

        val installedMarker = File(installDir, ".installed")
        installedMarker.writeText("buildType=GDK\nversion=${version.version}\n")
    }
}
