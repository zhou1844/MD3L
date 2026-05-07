package launcher.core

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

@Serializable
data class RemoteVersion(
    val id: String,
    val type: String,
    val url: String,
    val releaseTime: String = "",
)

object VersionManifest {

    private const val MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private const val BMCLAPI_MANIFEST = "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"

    /** 根据镜像源设置排列获取顺序 */
    private fun getManifestUrls(): List<String> {
        return if (DownloadManager.activeMirror == "official") {
            listOf(MOJANG_MANIFEST, BMCLAPI_MANIFEST)
        } else {
            listOf(BMCLAPI_MANIFEST, MOJANG_MANIFEST)
        }
    }

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 30_000 }
    }
    private val json = Json { ignoreUnknownKeys = true }

    private val cacheFile: File by lazy {
        File(System.getProperty("user.home"), ".craftlauncher/cache/version_manifest.json").also {
            it.parentFile?.mkdirs()
        }
    }

    /** 最后一次获取版本列表的错误信息（供 UI 展示） */
    @Volatile
    var lastError: String = ""
        private set

    suspend fun fetchVersionList(): List<RemoteVersion> = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()

        // 尝试 Ktor（所有 URL）
        for (url in getManifestUrls()) {
            try {
                val resp = client.get(url) { header("User-Agent", "MD3L/1.1") }
                val text = resp.bodyAsText()
                if (text.length > 1000) {
                    cacheFile.writeText(text)
                    lastError = ""
                    println("[VersionManifest] Ktor 成功: $url")
                    return@withContext parseManifest(text)
                }
            } catch (e: Exception) {
                errors.add("Ktor($url): ${e.message}")
                println("[VersionManifest] Ktor 失败 ($url): ${e.message}")
            }
        }

        // 备选: curl.exe（所有 URL）
        for (url in getManifestUrls()) {
            try {
                println("[VersionManifest] 尝试 curl: $url")
                val proc = ProcessBuilder(
                    "curl.exe", "-sL", "--connect-timeout", "10", "--max-time", "30", url
                ).redirectErrorStream(true).start()
                val text = proc.inputStream.bufferedReader().readText()
                val exited = proc.waitFor(35, java.util.concurrent.TimeUnit.SECONDS)
                if (exited && proc.exitValue() == 0 && text.length > 1000) {
                    cacheFile.writeText(text)
                    lastError = ""
                    println("[VersionManifest] curl 成功: $url")
                    return@withContext parseManifest(text)
                } else {
                    errors.add("curl($url): exit=${if (exited) proc.exitValue() else "timeout"}")
                }
            } catch (e: Exception) {
                errors.add("curl($url): ${e.message}")
                println("[VersionManifest] curl 失败 ($url): ${e.message}")
            }
        }

        // 最后: 本地缓存
        if (cacheFile.exists() && cacheFile.length() > 1000) {
            println("[VersionManifest] 使用本地缓存")
            lastError = "网络不可用，使用本地缓存"
            return@withContext parseManifest(cacheFile.readText())
        }

        lastError = errors.joinToString("; ")
        println("[VersionManifest] 全部失败: $lastError")
        emptyList()
    }

    /** 通用 HTTP GET：先 Ktor，失败走 curl.exe（解决 JVM SSL 问题） */
    private fun httpGet(url: String): String {
        // Ktor
        try {
            val proc = ProcessBuilder(
                "curl.exe", "-sL", "--connect-timeout", "15", "--max-time", "60", url
            ).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor(65, java.util.concurrent.TimeUnit.SECONDS)
            if (ok && proc.exitValue() == 0 && text.isNotBlank()) return text
        } catch (_: Exception) {}

        // fallback Ktor (blocking)
        val resp = kotlinx.coroutines.runBlocking {
            client.get(url) { header("User-Agent", "MD3L/1.1") }.bodyAsText()
        }
        if (resp.isNotBlank()) return resp

        throw RuntimeException("下载失败: $url")
    }

    private fun parseManifest(text: String): List<RemoteVersion> {
        val root = json.parseToJsonElement(text).jsonObject
        val versions = root["versions"]?.jsonArray ?: return emptyList()
        return versions.map { v ->
            val obj = v.jsonObject
            RemoteVersion(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "release",
                url = DownloadManager.mirrorUrl(obj["url"]?.jsonPrimitive?.contentOrNull ?: ""),
                releaseTime = obj["releaseTime"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }

    /**
     * 完整下载一个 Minecraft 版本：
     * 1. version JSON
     * 2. client.jar
     * 3. 所有 libraries (含 natives classifier)
     * 4. asset index JSON
     * 5. 所有 asset objects
     * 可选自定义版本名。
     */
    suspend fun installVersion(
        version: RemoteVersion,
        minecraftDir: String,
        customName: String? = null,
        maxThreads: Int = 64,
        onProgress: (String, Float) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val versionId = customName?.ifBlank { null } ?: version.id
        val versionDir = File(minecraftDir, "versions/$versionId")
        val hadVersionDir = versionDir.exists()
        val hadVersionJson = File(versionDir, "$versionId.json").exists()
        val hadVersionJar = File(versionDir, "$versionId.jar").exists()
        try {
            versionDir.mkdirs()

            // ── Step 1: 下载 version JSON（curl 优先，解决 JVM SSL 问题）────
            onProgress("步骤 1/6：下载版本 JSON", 0.05f)
            val vJsonText = httpGet(version.url)

            // 如果自定义名称，需要修改 JSON 中的 id
            val finalJsonText = if (customName != null && customName != version.id) {
                val parsed = json.parseToJsonElement(vJsonText).jsonObject.toMutableMap()
                parsed["id"] = JsonPrimitive(versionId)
                // 加上 inheritsFrom 指向原始版本（如果没有的话）
                JsonObject(parsed).toString()
            } else {
                vJsonText
            }
            File(versionDir, "$versionId.json").writeText(finalJsonText, Charsets.UTF_8)

            val root = json.parseToJsonElement(vJsonText).jsonObject
            val tasks = mutableListOf<DownloadTask>()
            val librariesDir = File(minecraftDir, "libraries")

            // ── Step 2: client.jar ────────────────────────────────────────────
            val clientDl = root["downloads"]?.jsonObject?.get("client")?.jsonObject
            if (clientDl != null) {
                val url = DownloadManager.mirrorUrl(clientDl["url"]!!.jsonPrimitive.content)
                val sha1 = clientDl["sha1"]?.jsonPrimitive?.contentOrNull
                val size = clientDl["size"]?.jsonPrimitive?.longOrNull ?: -1L
                val dest = File(versionDir, "$versionId.jar")
                if (!dest.exists() || (sha1 != null && !verifyFile(dest, sha1))) {
                    tasks.add(DownloadTask(url, dest, sha1, size))
                }
            }

            // ── Step 3: 所有 libraries ────────────────────────────────────────
            root["libraries"]?.jsonArray?.forEach { libEl ->
                val lib = libEl.jsonObject

                // 检查 rules
                if (!isLibraryAllowed(lib)) return@forEach

                val downloads = lib["downloads"]?.jsonObject

                // artifact (主 jar)
                val artifact = downloads?.get("artifact")?.jsonObject
                if (artifact != null) {
                    val path = artifact["path"]!!.jsonPrimitive.content
                    val url = DownloadManager.mirrorUrl(artifact["url"]!!.jsonPrimitive.content)
                    val sha1 = artifact["sha1"]?.jsonPrimitive?.contentOrNull
                    val size = artifact["size"]?.jsonPrimitive?.longOrNull ?: -1L
                    val dest = File(librariesDir, path)
                    if (!dest.exists() || (sha1 != null && !verifyFile(dest, sha1))) {
                        tasks.add(DownloadTask(url, dest, sha1, size))
                    }
                }

                // natives classifiers
                val classifiers = downloads?.get("classifiers")?.jsonObject
                val nativesKey = getNativesKey(lib)
                if (classifiers != null && nativesKey != null) {
                    val nativeObj = classifiers[nativesKey]?.jsonObject
                    if (nativeObj != null) {
                        val path = nativeObj["path"]!!.jsonPrimitive.content
                        val url = DownloadManager.mirrorUrl(nativeObj["url"]!!.jsonPrimitive.content)
                        val sha1 = nativeObj["sha1"]?.jsonPrimitive?.contentOrNull
                        val size = nativeObj["size"]?.jsonPrimitive?.longOrNull ?: -1L
                        val dest = File(librariesDir, path)
                        if (!dest.exists() || (sha1 != null && !verifyFile(dest, sha1))) {
                            tasks.add(DownloadTask(url, dest, sha1, size))
                        }
                    }
                }
            }

            // ── Step 4: asset index ───────────────────────────────────────────
            val assetIndex = root["assetIndex"]?.jsonObject
            var assetIndexId: String? = null
            if (assetIndex != null) {
                assetIndexId = assetIndex["id"]!!.jsonPrimitive.content
                val indexUrl = DownloadManager.mirrorUrl(assetIndex["url"]!!.jsonPrimitive.content)
                val sha1 = assetIndex["sha1"]?.jsonPrimitive?.contentOrNull
                val size = assetIndex["size"]?.jsonPrimitive?.longOrNull ?: -1L
                val indexFile = File(minecraftDir, "assets/indexes/$assetIndexId.json")
                if (!indexFile.exists() || (sha1 != null && !verifyFile(indexFile, sha1))) {
                    tasks.add(DownloadTask(indexUrl, indexFile, sha1, size))
                }
            }

            // ── 先下载 libraries + client jar + asset index ───────────────────
            if (tasks.isNotEmpty()) {
                onProgress("步骤 2/6：下载客户端与库文件（共 ${tasks.size} 个）", 0.15f)
                kotlinx.coroutines.coroutineScope {
                    val dlJob = launch {
                        DownloadManager.downloadAll(tasks, maxConcurrency = maxThreads)
                    }
                    waitForDownloadCompleteWithProgress(dlJob, 0.15f, 0.55f, onProgress)
                }
            }

            // ── Step 5: 下载 asset objects ────────────────────────────────────
            if (assetIndexId != null) {
                val indexFile = File(minecraftDir, "assets/indexes/$assetIndexId.json")
                if (indexFile.exists()) {
                    val assetTasks = parseAssetObjects(indexFile, minecraftDir)
                    if (assetTasks.isNotEmpty()) {
                        onProgress("步骤 4/6：下载资源文件（共 ${assetTasks.size} 个）", 0.6f)
                        kotlinx.coroutines.coroutineScope {
                            val dlJob = launch {
                                DownloadManager.downloadAll(assetTasks, maxConcurrency = maxThreads)
                            }
                            waitForDownloadCompleteWithProgress(dlJob, 0.6f, 0.95f, onProgress)
                        }
                    }
                }
            }

            // ── Step 6: 解压 natives ──────────────────────────────────────────
            onProgress("步骤 5/6：解压 natives", 0.97f)
            extractNatives(root, minecraftDir, versionDir)
            onProgress("步骤 6/6：原版安装完成", 1f)

            true
        } catch (e: CancellationException) {
            cleanupPartialVersion(versionDir, versionId, hadVersionDir, hadVersionJson, hadVersionJar)
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            cleanupPartialVersion(versionDir, versionId, hadVersionDir, hadVersionJson, hadVersionJar)
            false
        }
    }

    private fun cleanupPartialVersion(
        versionDir: File,
        versionId: String,
        hadVersionDir: Boolean,
        hadVersionJson: Boolean,
        hadVersionJar: Boolean,
    ) {
        runCatching {
            if (!versionDir.exists()) return
            if (!hadVersionDir) {
                versionDir.deleteRecursively()
                return
            }
            if (!hadVersionJson) File(versionDir, "$versionId.json").delete()
            if (!hadVersionJar) File(versionDir, "$versionId.jar").delete()
            if (!hadVersionJson && !hadVersionJar) {
                val nonHiddenFiles = versionDir.listFiles()?.filterNot { it.name.startsWith(".") } ?: emptyList()
                if (nonHiddenFiles.isEmpty()) versionDir.deleteRecursively()
            }
        }
    }

    private fun parseAssetObjects(indexFile: File, minecraftDir: String): List<DownloadTask> {
        val root = Json.parseToJsonElement(indexFile.readText()).jsonObject
        val objects = root["objects"]?.jsonObject ?: return emptyList()
        return objects.entries.mapNotNull { (_, value) ->
            val obj = value.jsonObject
            val hash = obj["hash"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.longOrNull ?: -1L
            val prefix = hash.substring(0, 2)
            val url = "${DownloadManager.mirrorUrl("https://resources.download.minecraft.net")}/$prefix/$hash"
            val dest = File(minecraftDir, "assets/objects/$prefix/$hash")
            if (dest.exists() && dest.length() == size) null
            else DownloadTask(url, dest, hash, size)
        }
    }

    private fun extractNatives(root: JsonObject, minecraftDir: String, versionDir: File) {
        val nativesDir = File(versionDir, "natives")
        nativesDir.mkdirs()
        val librariesDir = File(minecraftDir, "libraries")

        root["libraries"]?.jsonArray?.forEach { libEl ->
            val lib = libEl.jsonObject
            if (!isLibraryAllowed(lib)) return@forEach

            val classifiers = lib["downloads"]?.jsonObject?.get("classifiers")?.jsonObject
            val nativesKey = getNativesKey(lib)
            if (classifiers != null && nativesKey != null) {
                val nativeObj = classifiers[nativesKey]?.jsonObject ?: return@forEach
                val path = nativeObj["path"]!!.jsonPrimitive.content
                val jarFile = File(librariesDir, path)
                if (jarFile.exists()) {
                    try {
                        java.util.jar.JarFile(jarFile).use { jar ->
                            jar.entries().asSequence().forEach entryLoop@{ entry ->
                                if (entry.isDirectory || entry.name.startsWith("META-INF")) return@entryLoop
                                if (entry.name.endsWith(".dll") || entry.name.endsWith(".so") || entry.name.endsWith(".dylib") || entry.name.endsWith(".jnilib")) {
                                    val outFile = File(nativesDir, entry.name)
                                    outFile.parentFile?.mkdirs()
                                    jar.getInputStream(entry).use { input ->
                                        outFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        }
    }

    private fun getNativesKey(lib: JsonObject): String? {
        val natives = lib["natives"]?.jsonObject ?: return null
        val osName = System.getProperty("os.name").lowercase()
        val key = when {
            "win" in osName -> "windows"
            "mac" in osName || "darwin" in osName -> "osx"
            else -> "linux"
        }
        return natives[key]?.jsonPrimitive?.contentOrNull
            ?.replace("\${arch}", System.getProperty("os.arch").let { if ("64" in it) "64" else "32" })
    }

    private fun isLibraryAllowed(lib: JsonObject): Boolean {
        val rules = lib["rules"]?.jsonArray ?: return true
        var allowed = false
        val osName = System.getProperty("os.name").lowercase()
        val currentOs = when {
            "win" in osName -> "windows"
            "mac" in osName || "darwin" in osName -> "osx"
            else -> "linux"
        }
        rules.forEach { ruleEl ->
            val rule = ruleEl.jsonObject
            val action = rule["action"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val os = rule["os"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            if (os == null) {
                allowed = action == "allow"
            } else if (os == currentOs) {
                allowed = action == "allow"
            }
        }
        return allowed
    }

    private fun verifyFile(file: File, sha1: String): Boolean {
        if (!file.exists()) return false
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        file.inputStream().use { fis ->
            val buf = ByteArray(8192)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(sha1, ignoreCase = true)
    }

    internal suspend fun waitForDownloadComplete() {
        // 轮询等待 DownloadManager 完成
        while (true) {
            val prog = DownloadManager.progress.value
            if (!prog.isRunning && prog.totalFiles > 0) break
            if (!prog.isRunning && prog.totalFiles == 0) break
            delay(300)
        }
    }

    internal suspend fun waitForDownloadCompleteWithProgress(
        dlJob: kotlinx.coroutines.Job,
        from: Float,
        to: Float,
        onProgress: (String, Float) -> Unit,
    ) {
        var lastDone = -1
        var lastBytes = -1L
        var lastAdvanceAt = System.currentTimeMillis()
        while (dlJob.isActive) {
            val prog = DownloadManager.progress.value
            if (prog.totalFiles > 0) {
                val frac = prog.fraction.coerceIn(0f, 1f)
                val mapped = (from + (to - from) * frac).coerceIn(from, to)
                val pct = (frac * 100).toInt().coerceIn(0, 100)
                val file = prog.currentFile.ifBlank { "准备中" }.takeLast(48)
                onProgress(
                    "下载 ${prog.completedFiles}/${prog.totalFiles}（$pct%）· ${prog.speedMbps} · $file",
                    mapped,
                )

                val now = System.currentTimeMillis()
                val progressed = prog.completedFiles != lastDone || prog.downloadedBytes != lastBytes
                if (progressed) {
                    lastDone = prog.completedFiles
                    lastBytes = prog.downloadedBytes
                    lastAdvanceAt = now
                } else {
                    val stagnantMs = now - lastAdvanceAt
                    if (stagnantMs > 45_000L) {
                        dlJob.cancel(CancellationException("下载长时间无进展，已自动中断重试"))
                        throw RuntimeException("下载长时间无进展（${stagnantMs / 1000}s）: $file")
                    }
                }
            }
            delay(200)
        }
        dlJob.join() // 确保任何异常都会在这里抛出，或正常结束
    }
}
