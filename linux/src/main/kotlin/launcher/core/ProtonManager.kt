package launcher.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
data class ProtonVersion(
    val name: String = "",
    val version: String = "",
    val releaseUrl: String = "",
    val releaseSize: Long = 0L,
    val installPath: String = "",
    val isDefault: Boolean = false,
)

data class ProtonInstallProgress(
    val step: String = "",
    val fraction: Float = 0f,
    val isRunning: Boolean = false,
    val error: String = "",
)

object ProtonManager {

    private const val PROTON_VERSION = "GDK-Proton10-32"
    private const val GH_PROXY = "https://gh-proxy.com/"
    private const val GITHUB_API = "https://api.github.com/repos/LukasPAH/GDK-Proton-Custom/releases"
    private val GITHUB_API_PROXIED = "$GH_PROXY$GITHUB_API"

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(10_000))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val _installProgress = MutableStateFlow(ProtonInstallProgress())
    val installProgress: StateFlow<ProtonInstallProgress> = _installProgress.asStateFlow()

    var installJob: Job? = null
        private set

    private val protonDir: File by lazy { LauncherDirs.protonDir }
    private val protonBinDir: File by lazy { File(protonDir, "bin") }
    private val protonDownloadDir: File by lazy { File(protonDir, "download") }

    init {
        protonDir.mkdirs()
        protonBinDir.mkdirs()
        protonDownloadDir.mkdirs()
    }

    private val configFile: File by lazy { File(protonDir, "proton_config.json") }

    fun isInstalled(): Boolean {
        val versions = getInstalledVersions()
        return versions.isNotEmpty()
    }

    fun getInstalledVersions(): List<ProtonVersion> {
        if (!protonBinDir.isDirectory) return emptyList()
        return protonBinDir.listFiles()?.filter { it.isDirectory }?.map { dir ->
            val versionFile = File(dir, ".bb/version.json")
            if (versionFile.exists()) {
                runCatching {
                    val config = json.decodeFromString<ProtonVersion>(versionFile.readText(Charsets.UTF_8))
                    config.copy(installPath = dir.absolutePath)
                }.getOrDefault(ProtonVersion(installPath = dir.absolutePath))
            } else {
                ProtonVersion(name = dir.name, installPath = dir.absolutePath)
            }
        }?.filter { hasProtonScript(it.installPath) }?.toList() ?: emptyList()
    }

    fun getSelectedProtonPath(): String {
        val custom = runCatching { runBlocking { AppSettings.load() }.bedrockProtonPath }.getOrNull()
        if (!custom.isNullOrBlank()) {
            val customFile = File(custom)
            if (customFile.isDirectory && hasProtonScript(customFile.absolutePath)) {
                return customFile.absolutePath
            }
            if (customFile.isFile && customFile.name == "proton") {
                return customFile.parentFile.absolutePath
            }
        }
        val versions = getInstalledVersions()
        val default = versions.find { it.isDefault }
        if (default != null) return default.installPath
        val first = versions.firstOrNull()
        if (first != null) return first.installPath
        val firstDir = protonBinDir.listFiles()?.find { it.isDirectory && hasProtonScript(it.absolutePath) }
        if (firstDir != null) return firstDir.absolutePath
        return ""
    }

    private fun hasProtonScript(path: String): Boolean {
        return File(path, "proton").isFile
    }

    suspend fun fetchInstallableVersions(): List<ProtonVersion> = withContext(Dispatchers.IO) {
        val urls = listOf(GITHUB_API_PROXIED, GITHUB_API)
        for (apiUrl in urls) {
            try {
                println("[ProtonManager] 尝试获取版本列表: $apiUrl")
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofMillis(15_000))
                    .header("User-Agent", "MD3L/1.4")
                    .header("Accept", "application/vnd.github.v3+json")
                    .GET()
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) {
                    println("[ProtonManager] HTTP ${response.statusCode()} from $apiUrl")
                    continue
                }

                val releases = json.parseToJsonElement(response.body()).jsonArray
                val result = releases.map { release ->
                    val obj = release.jsonObject
                    val assets = obj["assets"]?.jsonArray ?: emptyList()
                    val protonAsset = assets.find {
                        val name = it.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: ""
                        name.contains("Proton", ignoreCase = true)
                    }
                    val assetObj = protonAsset?.jsonObject
                    ProtonVersion(
                        name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        version = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: "",
                        releaseUrl = assetObj?.get("browser_download_url")?.jsonPrimitive?.contentOrNull ?: "",
                        releaseSize = assetObj?.get("size")?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                }.filter { it.releaseUrl.isNotBlank() }
                if (result.isNotEmpty()) {
                    println("[ProtonManager] 成功获取 ${result.size} 个版本 from $apiUrl")
                    return@withContext result
                }
                println("[ProtonManager] 未找到 Proton 版本 from $apiUrl")
            } catch (e: Exception) {
                println("[ProtonManager] 获取版本列表失败 ($apiUrl): ${e.message}")
            }
        }
        emptyList()
    }

    suspend fun installProton(
        version: ProtonVersion? = null,
        isDefault: Boolean = true,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ): String? {
        _installProgress.value = ProtonInstallProgress(step = "准备下载 ProtonGDK...", fraction = 0f, isRunning = true)

        installJob?.cancel()
        var resultPath: String? = null

        val job = scope.launch(Dispatchers.IO) {
            try {
                val targetVersion = version ?: fetchInstallableVersions()
                    .find { it.name == PROTON_VERSION || it.name.contains("Proton", ignoreCase = true) }
                    ?: fetchInstallableVersions().firstOrNull()

                if (targetVersion == null || targetVersion.releaseUrl.isBlank()) {
                    _installProgress.value = ProtonInstallProgress(error = "未找到可安装的 ProtonGDK 版本", isRunning = false)
                    return@launch
                }

                val installName = version?.name ?: targetVersion.name.ifBlank { "ProtonGDK" }
                val installPath = File(protonBinDir, installName)

                if (installPath.exists() && hasProtonScript(installPath.absolutePath)) {
                    _installProgress.value = ProtonInstallProgress(step = "已安装", fraction = 1f, isRunning = false)
                    resultPath = installPath.absolutePath
                    return@launch
                }

                val downloadUrl = targetVersion.releaseUrl
                val fileName = File(downloadUrl).name
                val downloadPath = File(protonDownloadDir, fileName)

                val candidateUrls = if (downloadUrl.contains("github.com"))
                    listOf("$GH_PROXY$downloadUrl", downloadUrl)
                else
                    listOf(downloadUrl)

                if (!downloadPath.exists() || (targetVersion.releaseSize > 0 && downloadPath.length() != targetVersion.releaseSize)) {
                    _installProgress.value = ProtonInstallProgress(step = "下载 ProtonGDK...", fraction = 0.1f, isRunning = true)

                    var lastError = "未知错误"
                    var downloadedOk = false
                    for (candidateUrl in candidateUrls) {
                        try {
                            val dlRequest = HttpRequest.newBuilder()
                                .uri(URI.create(candidateUrl))
                                .timeout(Duration.ofMinutes(10))
                                .header("User-Agent", "MD3L/1.4")
                                .GET()
                                .build()

                            val response = httpClient.send(dlRequest, HttpResponse.BodyHandlers.ofInputStream())
                            if (response.statusCode() !in 200..299) {
                                lastError = "HTTP ${response.statusCode()}"
                                continue
                            }

                            val contentLen = response.headers().firstValue("Content-Length").map { it.toLong() }.orElse(-1L)
                            val totalSize = if (contentLen > 0) contentLen else targetVersion.releaseSize
                            downloadPath.deleteRecursively()
                            response.body().use { input ->
                                downloadPath.outputStream().use { output ->
                                    val buffer = ByteArray(65536)
                                    var len: Int
                                    var downloaded = 0L
                                    var lastPercent = -1
                                    var lastEmit = 0L
                                    while (input.read(buffer).also { len = it } != -1) {
                                        ensureActive()
                                        output.write(buffer, 0, len)
                                        downloaded += len
                                        if (downloaded - lastEmit >= 256 * 1024 || downloaded == totalSize) {
                                            lastEmit = downloaded
                                            val pct = if (totalSize > 0) (downloaded * 100 / totalSize).toInt().coerceIn(0, 100) else -1
                                            if (pct != lastPercent || pct < 0) {
                                                lastPercent = pct
                                                val frac = if (totalSize > 0) (0.1f + (downloaded.toFloat() / totalSize) * 0.5f).coerceIn(0.1f, 0.6f) else 0.3f
                                                val step = if (pct >= 0)
                                                    "下载 ProtonGDK... ${formatSize(downloaded)} / ${formatSize(totalSize)} ($pct%)"
                                                else
                                                    "下载 ProtonGDK... ${formatSize(downloaded)}"
                                                _installProgress.value = ProtonInstallProgress(
                                                    step = step,
                                                    fraction = frac,
                                                    isRunning = true,
                                                )
                                                if (LaunchState.isLaunching.value) {
                                                    LaunchState.updateProgress(
                                                        (40 + (frac - 0.1f) / 0.5f * 50).toInt().coerceIn(40, 90),
                                                        step,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            downloadedOk = true
                            break
                        } catch (e: Exception) {
                            lastError = e.message ?: "未知错误"
                        }
                    }
                    if (!downloadedOk) {
                        _installProgress.value = ProtonInstallProgress(error = "下载失败: $lastError", isRunning = false)
                        return@launch
                    }
                }

                _installProgress.value = ProtonInstallProgress(step = "解压 ProtonGDK...", fraction = 0.65f, isRunning = true)

                val extractDir = File(protonDir, "_extract_${installName}")
                extractDir.deleteRecursively()
                extractDir.mkdirs()

                val tarProcess = ProcessBuilder(
                    "tar", "-xzf", downloadPath.absolutePath, "-C", extractDir.absolutePath,
                ).redirectErrorStream(true).start()

                val outputThread = Thread {
                    try {
                        tarProcess.inputStream.bufferedReader().use { reader ->
                            var lineCount = 0
                            reader.forEachLine {
                                lineCount++
                                if (lineCount % 100 == 0) {
                                    val f = (0.65f + (lineCount / 10000f).coerceAtMost(0.08f)).coerceIn(0.65f, 0.73f)
                                    val step = "解压 ProtonGDK... ($lineCount files)"
                                    _installProgress.value = ProtonInstallProgress(
                                        step = step,
                                        fraction = f,
                                        isRunning = true,
                                    )
                                    if (LaunchState.isLaunching.value) {
                                        LaunchState.updateProgress((90 + (f - 0.65f) / 0.08f * 8).toInt().coerceIn(90, 98), step)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }.also { it.isDaemon = true }
                outputThread.start()

                val tarExit = tarProcess.waitFor()
                outputThread.join(5000)

                if (tarExit != 0) {
                    val err = try { tarProcess.inputStream.bufferedReader().readText().takeLast(500) } catch (_: Exception) { "" }
                    _installProgress.value = ProtonInstallProgress(error = "解压失败 exit=$tarExit: $err", isRunning = false)
                    return@launch
                }

                var extractedRoot = extractDir
                val children = extractDir.listFiles() ?: emptyArray()
                if (children.size == 1 && children[0].isDirectory) {
                    extractedRoot = children[0]
                }

                _installProgress.value = ProtonInstallProgress(step = "复制文件...", fraction = 0.75f, isRunning = true)

                installPath.deleteRecursively()
                installPath.mkdirs()
                extractedRoot.copyRecursively(installPath, overwrite = true)

                ensureProtonExecutable(installPath)

                val confDir = File(installPath, ".bb")
                confDir.mkdirs()
                val savedConfig = targetVersion.copy(installPath = installPath.absolutePath, isDefault = isDefault)
                File(confDir, "version.json").writeText(json.encodeToString(ProtonVersion.serializer(), savedConfig), Charsets.UTF_8)

                extractDir.deleteRecursively()

                if (isDefault) {
                    getInstalledVersions().filter { it.installPath != installPath.absolutePath }.forEach { old ->
                        val oldConfFile = File(File(old.installPath, ".bb"), "version.json")
                        if (oldConfFile.exists()) {
                            val oldConfig = json.decodeFromString<ProtonVersion>(oldConfFile.readText(Charsets.UTF_8))
                            oldConfFile.writeText(
                                json.encodeToString(ProtonVersion.serializer(), oldConfig.copy(isDefault = false)),
                                Charsets.UTF_8,
                            )
                        }
                    }
                }

                _installProgress.value = ProtonInstallProgress(step = "安装完成", fraction = 1f, isRunning = false)
                resultPath = installPath.absolutePath
            } catch (e: CancellationException) {
                _installProgress.value = ProtonInstallProgress(error = "安装已取消", isRunning = false)
            } catch (e: Exception) {
                _installProgress.value = ProtonInstallProgress(error = "安装失败: ${e.message}", isRunning = false)
                println("[ProtonManager] 安装失败: ${e.message}")
            }
        }
        installJob = job
        job.join()
        return resultPath
    }

    suspend fun importProtonFromFile(
        tarGzPath: String,
        installName: String = "ProtonGDK",
        isDefault: Boolean = true,
    ): String? = withContext(Dispatchers.IO) {
        val sourceFile = File(tarGzPath)
        if (!sourceFile.isFile) {
            println("[ProtonManager] 文件不存在: $tarGzPath")
            return@withContext null
        }

        _installProgress.value = ProtonInstallProgress(step = "从本地文件导入 ProtonGDK...", fraction = 0f, isRunning = true)

        val installPath = File(protonBinDir, installName)
        if (installPath.exists() && hasProtonScript(installPath.absolutePath)) {
            _installProgress.value = ProtonInstallProgress(step = "已安装", fraction = 1f, isRunning = false)
            return@withContext installPath.absolutePath
        }

        _installProgress.value = ProtonInstallProgress(step = "解压 ProtonGDK...", fraction = 0.2f, isRunning = true)

        val extractDir = File(protonDir, "_extract_${installName}")
        extractDir.deleteRecursively()
        extractDir.mkdirs()

        val tarProcess = ProcessBuilder(
            "tar", "-xzf", sourceFile.absolutePath, "-C", extractDir.absolutePath,
        ).redirectErrorStream(true).start()

        val outputThread = Thread {
            try {
                tarProcess.inputStream.bufferedReader().use { reader ->
                    var lineCount = 0
                    reader.forEachLine {
                        lineCount++
                        if (lineCount % 100 == 0) {
                            val f = (0.2f + (lineCount / 10000f).coerceAtMost(0.3f)).coerceIn(0.2f, 0.5f)
                            _installProgress.value = ProtonInstallProgress(step = "解压 ProtonGDK... ($lineCount files)", fraction = f, isRunning = true)
                        }
                    }
                }
            } catch (_: Exception) {}
        }.also { it.isDaemon = true }
        outputThread.start()

        val tarExit = tarProcess.waitFor()
        outputThread.join(5000)

        if (tarExit != 0) {
            val err = try { tarProcess.inputStream.bufferedReader().readText().takeLast(500) } catch (_: Exception) { "" }
            _installProgress.value = ProtonInstallProgress(error = "解压失败 exit=$tarExit: $err", isRunning = false)
            return@withContext null
        }

        var extractedRoot = extractDir
        val children = extractDir.listFiles() ?: emptyArray()
        if (children.size == 1 && children[0].isDirectory) extractedRoot = children[0]

        _installProgress.value = ProtonInstallProgress(step = "复制文件...", fraction = 0.6f, isRunning = true)

        installPath.deleteRecursively()
        installPath.mkdirs()
        extractedRoot.copyRecursively(installPath, overwrite = true)

        ensureProtonExecutable(installPath)

        val confDir = File(installPath, ".bb")
        confDir.mkdirs()
        val savedConfig = ProtonVersion(name = installName, installPath = installPath.absolutePath, isDefault = isDefault)
        File(confDir, "version.json").writeText(
            json.encodeToString(ProtonVersion.serializer(), savedConfig), Charsets.UTF_8,
        )

        extractDir.deleteRecursively()

        if (isDefault) {
            getInstalledVersions().filter { it.installPath != installPath.absolutePath }.forEach { old ->
                val oldConfFile = File(File(old.installPath, ".bb"), "version.json")
                if (oldConfFile.exists()) {
                    val oldConfig = json.decodeFromString<ProtonVersion>(oldConfFile.readText(Charsets.UTF_8))
                    oldConfFile.writeText(json.encodeToString(ProtonVersion.serializer(), oldConfig.copy(isDefault = false)), Charsets.UTF_8)
                }
            }
        }

        _installProgress.value = ProtonInstallProgress(step = "导入完成", fraction = 1f, isRunning = false)
        installPath.absolutePath
    }

    fun cancelInstall() {
        installJob?.cancel(CancellationException("安装已取消"))
    }

    private fun ensureProtonExecutable(installPath: File) {
        installPath.walkTopDown().forEach { if (it.isFile) it.setExecutable(true, false) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "${"%.2f".format(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
        bytes >= 1024 -> "${"%.0f".format(bytes / 1024.0)} KB"
        else -> "$bytes B"
    }
}
