package launcher.core

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object JavaManager {

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 600_000 }
    }
    private val resolvedJavaCache = ConcurrentHashMap<String, String>()
    private val javaMajorCache = ConcurrentHashMap<String, Int>()
    private val javaModuleCache = ConcurrentHashMap<String, Set<String>>()

    /**
     * 根据 Minecraft 版本号判断所需的 Java 大版本。
     * Alpha / Beta / 1.0~1.16.x → Java 8
     * 1.17.x → Java 16
     * 1.18~1.20.4 → Java 17
     * 1.20.5+ / 1.21+ → Java 21
     */
    fun requiredJavaMajor(mcVersionId: String): Int {
        val v = mcVersionId.lowercase().trim()

        // ── 远古版本强制 Java 8 ──────────────────────────────────────────────
        if (v.startsWith("b1.") || v.startsWith("a1.") ||
            v.startsWith("inf-") || v.startsWith("c0.") ||
            v.startsWith("rd-") || v.contains("combat") ||
            v.endsWith("pre-classic")
        ) return 8

        // ── 快照格式 (YYwXXa) 近似映射 ─────────────────────────────────────
        val snapshotMatch = Regex("""^(\d{2})w(\d{2})[a-z]$""").matchEntire(v)
        if (snapshotMatch != null) {
            val year = snapshotMatch.groupValues[1].toIntOrNull() ?: 99
            val week = snapshotMatch.groupValues[2].toIntOrNull() ?: 0
            return when {
                year < 21 -> 8
                year == 21 && week < 19 -> 8
                year == 21 -> 16
                year == 22 || (year == 23 && week < 40) -> 17
                else -> 21
            }
        }

        // ── 正式版 X.Y.Z 解析 ───────────────────────────────────────────────
        val parts = v.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull()
        val minor = parts.getOrNull(1)?.toIntOrNull()
        val patch = parts.getOrNull(2)?.split("-")?.firstOrNull()?.toIntOrNull() ?: 0

        if (major == null || minor == null) return 8  // 无法识别的版本一律 Java 8

        return when {
            major == 1 && minor <= 16 -> 8
            major == 1 && minor == 17 -> 16
            major == 1 && minor in 18..19 -> 17
            major == 1 && minor == 20 && patch <= 4 -> 17
            major == 1 && minor == 20 && patch >= 5 -> 21
            major == 1 && minor >= 21 -> 21
            else -> 17
        }
    }

    /**
     * 从已安装列表中查找匹配大版本的 Java。
     */
    fun findLocalJava(requiredMajor: Int, javaInstallations: List<JavaInstallation>): JavaInstallation? {
        return javaInstallations.firstOrNull { parseJavaMajor(it.version) == requiredMajor }
            ?: javaInstallations
                .filter { requiredMajor >= 17 && parseJavaMajor(it.version) >= requiredMajor }
                .minByOrNull { parseJavaMajor(it.version) }
    }

    /**
     * 一站式启动前 Java 解析：
     * 1. 判断所需 Java 大版本
     * 2. 在本地查找
     * 3. 如缺失，自动下载到 ~/.md3l/java-{ver}
     * 返回 java.exe 的完整路径。
     */
    suspend fun resolveJavaForLaunch(
        mcVersionId: String,
        userJavaPath: String,
        onProgress: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val required = requiredJavaMajor(mcVersionId)
        val cacheKey = "$required|${userJavaPath.trim()}"
        resolvedJavaCache[cacheKey]?.let { cached ->
            if (File(cached).exists()) {
                onProgress("使用已缓存 Java $required ✓")
                return@withContext cached
            }
        }
        onProgress("版本 $mcVersionId 需要 Java $required")

        // 检查用户手动设置的 Java 是否版本匹配
        val userMajor = probeJavaMajor(userJavaPath)
        if (userMajor == required || (required >= 17 && userMajor > required)) {
            val exe = normalizeJavaExe(userJavaPath)
            if (isRuntimeUsable(exe, required)) {
                onProgress("用户 Java ($exe) 版本匹配 ✓")
                resolvedJavaCache[cacheKey] = exe
                return@withContext exe
            }
            onProgress("用户 Java 缺少完整运行时模块，已跳过")
        }

        // 扫描全局 Java
        onProgress("扫描本地 Java $required ...")
        val allJavas = JavaScanner.findAll()
        val match = findLocalJava(required, allJavas)
        if (match != null) {
            val exe = normalizeJavaExe(match.path)
            if (isRuntimeUsable(exe, required)) {
                onProgress("找到本地 Java $required: $exe ✓")
                resolvedJavaCache[cacheKey] = exe
                return@withContext exe
            }
            onProgress("本地 Java $required 运行时不完整，尝试其他来源")
        }

        // 检查之前下载的
        val md3lDir = File(System.getProperty("user.home"), ".md3l")
        val cachedExe = findJavaExeInDir(File(md3lDir, "java-$required"))
        if (cachedExe != null && cachedExe.exists()) {
            val exe = cachedExe.absolutePath
            if (isRuntimeUsable(exe, required)) {
                onProgress("使用已缓存的 Java $required ✓")
                resolvedJavaCache[cacheKey] = exe
                return@withContext exe
            }
            onProgress("缓存 Java $required 运行时不完整，重新下载")
        }

        // 下载
        onProgress("本地未找到 Java $required，正在自动下载...")
        val downloaded = downloadJre(required, md3lDir, onProgress)
        val downloadedPath = downloaded?.absolutePath
            ?: throw Exception("无法下载 Java $required，请手动安装后重试")
        if (!isRuntimeUsable(downloadedPath, required)) {
            throw Exception("自动下载的 Java 运行时不完整（缺少 java.instrument），请手动安装标准 JDK/JRE")
        }
        resolvedJavaCache[cacheKey] = downloadedPath
        downloadedPath
    }

    suspend fun resolveJavaForVersion(
        version: LocalVersion,
        userJavaPath: String,
        onProgress: (String) -> Unit = {},
    ): String {
        val required = resolveRequiredJavaMajor(version)
        val displayId = version.inheritsFrom ?: version.id
        return resolveJavaForRequired(displayId, required, userJavaPath, onProgress)
    }

    private suspend fun resolveJavaForRequired(
        displayId: String,
        required: Int,
        userJavaPath: String,
        onProgress: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val cacheKey = "$required|${userJavaPath.trim()}"
        resolvedJavaCache[cacheKey]?.let { cached ->
            if (File(cached).exists()) {
                onProgress("使用已缓存 Java $required ✓")
                return@withContext cached
            }
        }
        onProgress("版本 $displayId 需要 Java $required")

        val userMajor = probeJavaMajor(userJavaPath)
        if (userMajor == required || (required >= 17 && userMajor > required)) {
            val exe = normalizeJavaExe(userJavaPath)
            if (isRuntimeUsable(exe, required)) {
                onProgress("用户 Java ($exe) 版本匹配 ✓")
                resolvedJavaCache[cacheKey] = exe
                return@withContext exe
            }
            onProgress("用户 Java 缺少完整运行时模块，已跳过")
        }

        onProgress("扫描本地 Java $required ...")
        val match = findLocalJava(required, JavaScanner.findAll())
        if (match != null) {
            val exe = normalizeJavaExe(match.path)
            if (isRuntimeUsable(exe, required)) {
                onProgress("找到本地 Java $required: $exe ✓")
                resolvedJavaCache[cacheKey] = exe
                return@withContext exe
            }
            onProgress("本地 Java $required 运行时不完整，尝试其他来源")
        }

        val md3lDir = File(System.getProperty("user.home"), ".md3l")
        val cachedExe = findJavaExeInDir(File(md3lDir, "java-$required"))
        if (cachedExe != null && cachedExe.exists()) {
            val exe = cachedExe.absolutePath
            if (isRuntimeUsable(exe, required)) {
                onProgress("使用已缓存的 Java $required ✓")
                resolvedJavaCache[cacheKey] = exe
                return@withContext exe
            }
            onProgress("缓存 Java $required 运行时不完整，重新下载")
        }

        onProgress("本地未找到 Java $required，正在自动下载...")
        val downloaded = downloadJre(required, md3lDir, onProgress)
        val downloadedPath = downloaded?.absolutePath
            ?: throw Exception("无法下载 Java $required，请手动安装后重试")
        if (!isRuntimeUsable(downloadedPath, required)) {
            throw Exception("自动下载的 Java 运行时不完整（缺少 java.instrument），请手动安装标准 JDK/JRE")
        }
        resolvedJavaCache[cacheKey] = downloadedPath
        downloadedPath
    }

    fun resolveRequiredJavaMajor(version: LocalVersion): Int {
        var currentId: String? = version.id
        val visited = mutableSetOf<String>()

        while (currentId != null && currentId !in visited) {
            visited.add(currentId)

            val jsonObject = if (currentId == version.id) {
                readVersionJson(version)
            } else {
                readVersionJsonById(version, currentId)
            }

            if (jsonObject != null) {
                jsonObject["javaVersion"]?.jsonObject?.get("majorVersion")?.jsonPrimitive?.intOrNull?.let { return it }
                jsonObject["releaseTarget"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return requiredJavaMajor(it) }
                
                val parentId = jsonObject["inheritsFrom"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                if (parentId != null) {
                    currentId = parentId
                    continue
                }
            }
            break
        }
        
        return requiredJavaMajor(currentId ?: version.id)
    }

    private fun readVersionJson(version: LocalVersion): JsonObject? {
        return try {
            val dir = File(version.versionDir)
            val file = File(dir, "${dir.name}.json").takeIf { it.isFile }
                ?: File(dir, "${version.id}.json").takeIf { it.isFile }
                ?: return null
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun readVersionJsonById(version: LocalVersion, id: String): JsonObject? {
        return try {
            val minecraftDir = File(version.versionDir).parentFile?.parentFile ?: return null
            val file = File(minecraftDir, "versions/$id/$id.json")
            if (!file.isFile) return null
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun probeJavaMajor(path: String): Int {
        try {
            val exe = normalizeJavaExe(path)
            javaMajorCache[exe]?.let { return it }
            val pb = ProcessBuilder(exe, "-version")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return 0
            }
            // Parse: "1.8.0_xxx" → 8, "17.0.x" → 17
            val verLine = output.lines().firstOrNull { "version" in it } ?: return 0
            val verMatch = Regex(""""(\d+)([.\d_]*)"""").find(verLine) ?: return 0
            val first = verMatch.groupValues[1].toIntOrNull() ?: return 0
            val major = if (first == 1) {
                verMatch.groupValues[2].removePrefix(".").split(".").firstOrNull()?.toIntOrNull() ?: 0
            } else first
            if (major > 0) javaMajorCache[exe] = major
            return major
        } catch (_: Exception) {
            return 0
        }
    }

    private fun normalizeJavaExe(path: String): String {
        if (path.isBlank()) return "java"
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        if (path == "java" || path == "java.exe") {
            resolveJavaExeFromPathAlias(isWin)?.let { return it }
            return path
        }
        val f = File(path)
        if (f.isFile && (f.name == "java" || f.name == "java.exe")) return f.absolutePath
        val bin = File(path, "bin")
        val exe = File(bin, if (isWin) "java.exe" else "java")
        if (exe.exists()) return exe.absolutePath
        return if (path.endsWith("java") || path.endsWith("java.exe")) path
        else "${path}${File.separator}bin${File.separator}${if (isWin) "java.exe" else "java"}"
    }

    private fun resolveJavaExeFromPathAlias(isWin: Boolean): String? {
        val command = if (isWin) "where" else "which"
        return runCatching {
            val proc = ProcessBuilder(command, "java")
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor(2, TimeUnit.SECONDS)
            out.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && File(it).isFile }
        }.getOrNull()
    }

    private fun isRuntimeUsable(javaExe: String, requiredMajor: Int): Boolean {
        if (requiredMajor < 9) return true
        return hasJavaModule(javaExe, "java.instrument")
    }

    private fun hasJavaModule(javaPath: String, module: String): Boolean {
        val exe = normalizeJavaExe(javaPath)
        val modules = javaModuleCache[exe] ?: run {
            val detected = runCatching {
                val proc = ProcessBuilder(exe, "--list-modules")
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText()
                if (!proc.waitFor(3, TimeUnit.SECONDS) || proc.exitValue() != 0) {
                    proc.destroyForcibly()
                    return@runCatching emptySet<String>()
                }
                out.lineSequence()
                    .map { it.substringBefore('@').trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
            }.getOrDefault(emptySet())
            javaModuleCache[exe] = detected
            detected
        }
        return module in modules
    }

    private fun parseJavaMajor(version: String): Int {
        if (version.startsWith("1.")) return version.split(".").getOrNull(1)?.toIntOrNull() ?: 0
        return version.split(".").firstOrNull()?.toIntOrNull() ?: 0
    }

    private fun adoptiumOs(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            "win" in os -> "windows"
            "mac" in os || "darwin" in os -> "mac"
            else -> "linux"
        }
    }

    private fun adoptiumArch(): String {
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            "aarch64" in arch || "arm64" in arch -> "aarch64"
            "amd64" in arch || "x86_64" in arch -> "x64"
            "x86" in arch -> "x32"
            else -> "x64"
        }
    }

    suspend fun downloadJre(
        majorVersion: Int,
        targetDir: File,
        onProgress: (String) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        val taskId = "java_runtime_$majorVersion"
        fun update(step: String, fraction: Float) {
            onProgress(step)
            DownloadHub.upsert(DownloadHub.HubTask(
                id = taskId,
                name = "Java $majorVersion 自动下载",
                type = DownloadHub.TaskType.JavaVersion,
                step = step,
                fraction = fraction.coerceIn(0f, 1f),
            ))
        }
        try {
            val os = adoptiumOs()
            val arch = adoptiumArch()
            val javaDir = File(targetDir, "java-$majorVersion")
            javaDir.mkdirs()
            val candidates = javaDownloadCandidates(majorVersion, os, arch)
            var downloadedArchive: File? = null
            candidates.forEachIndexed { index, url ->
                if (downloadedArchive == null) {
                    val cleanUrl = url.substringBefore('?')
                    val defaultName = when {
                        cleanUrl.endsWith(".exe", ignoreCase = true) -> "java.exe"
                        os == "windows" -> "java.zip"
                        else -> "java.tar.gz"
                    }
                    val fileName = cleanUrl.substringAfterLast('/').ifBlank { defaultName }
                    val archive = File(javaDir, fileName)
                    if (archive.exists()) archive.delete()
                    update("尝试下载 Java $majorVersion 源 ${index + 1}/${candidates.size}", 0.03f + index * 0.04f)
                    val ok = downloadJavaArchive(url, archive) { done, total, speed ->
                        val mb = done / 1_048_576.0
                        val totalText = if (total > 0) " / ${"%.1f".format(total / 1_048_576.0)} MB" else ""
                        val speedText = "${"%.1f".format(speed / 1_048_576.0)} MB/s"
                        val fraction = if (total > 0) 0.15f + 0.65f * done.toFloat() / total else 0.15f
                        update("下载 Java $majorVersion ${"%.1f".format(mb)} MB$totalText · $speedText", fraction)
                    }
                    if (ok) {
                        downloadedArchive = archive
                    } else {
                        archive.delete()
                    }
                }
            }
            val archive = downloadedArchive
            if (archive == null) {
                DownloadHub.upsert(DownloadHub.HubTask(
                    id = taskId,
                    name = "Java $majorVersion 自动下载",
                    type = DownloadHub.TaskType.JavaVersion,
                    status = DownloadHub.TaskStatus.Error,
                    step = "Java $majorVersion 下载失败",
                    fraction = 0f,
                    error = "华为云镜像未返回可用安装包",
                ))
                return@withContext null
            }

            update("正在安装 Java $majorVersion...", 0.86f)
            val installed = when {
                archive.extension.equals("zip", ignoreCase = true) -> {
                    unzip(archive, javaDir)
                    true
                }
                archive.name.endsWith(".tar.gz", ignoreCase = true) ||
                    archive.extension.equals("gz", ignoreCase = true) -> {
                    extractTarGz(archive, javaDir)
                    true
                }
                archive.extension.equals("exe", ignoreCase = true) -> {
                    installWindowsJavaExe(archive, javaDir)
                }
                else -> false
            }
            archive.delete()
            if (!installed) {
                update("Java $majorVersion 安装失败: 不支持的安装包格式", 1f)
                return@withContext null
            }

            val javaExe = findJavaExeInDir(javaDir)
            if (javaExe != null) {
                update("Java $majorVersion 安装完成 ✓", 1f)
                DownloadHub.upsert(DownloadHub.HubTask(
                    id = taskId,
                    name = "Java $majorVersion 自动下载",
                    type = DownloadHub.TaskType.JavaVersion,
                    status = DownloadHub.TaskStatus.Done,
                    step = "Java $majorVersion 安装完成",
                    fraction = 1f,
                ))
                javaExe
            } else {
                val fallback = JavaScanner.findAll().firstOrNull { parseJavaMajor(it.version) == majorVersion }
                if (fallback != null) {
                    val exe = normalizeJavaExe(fallback.path)
                    update("Java $majorVersion 安装完成（系统路径）✓", 1f)
                    DownloadHub.upsert(DownloadHub.HubTask(
                        id = taskId,
                        name = "Java $majorVersion 自动下载",
                        type = DownloadHub.TaskType.JavaVersion,
                        status = DownloadHub.TaskStatus.Done,
                        step = "Java $majorVersion 安装完成",
                        fraction = 1f,
                    ))
                    return@withContext File(exe)
                }
                update("Java 安装失败: 未找到 java 可执行文件", 1f)
                DownloadHub.upsert(DownloadHub.HubTask(
                    id = taskId,
                    name = "Java $majorVersion 自动下载",
                    type = DownloadHub.TaskType.JavaVersion,
                    status = DownloadHub.TaskStatus.Error,
                    step = "Java 安装失败",
                    fraction = 1f,
                    error = "未找到 java 可执行文件",
                ))
                null
            }
        } catch (e: Exception) {
            onProgress("Java 下载失败: ${e.message}")
            DownloadHub.upsert(DownloadHub.HubTask(
                id = taskId,
                name = "Java $majorVersion 自动下载",
                type = DownloadHub.TaskType.JavaVersion,
                status = DownloadHub.TaskStatus.Error,
                step = "Java 下载失败: ${e.message}",
                error = e.message ?: "",
            ))
            null
        }
    }

    private fun javaDownloadCandidates(majorVersion: Int, os: String, arch: String): List<String> {
        val normalizedArch = when {
            arch.contains("64") -> "x64"
            else -> "x64"
        }

        if (majorVersion <= 8) {
            return when (os) {
                "windows" -> listOf("https://repo.huaweicloud.com/java/jdk/8u202-b08/jdk-8u202-windows-$normalizedArch.exe")
                "linux" -> listOf("https://repo.huaweicloud.com/java/jdk/8u202-b08/jdk-8u202-linux-$normalizedArch.tar.gz")
                else -> listOf("https://repo.huaweicloud.com/java/jdk/8u202-b08/jdk-8u202-macosx-x64.dmg")
            }
        }

        val osPart = when (os) {
            "windows" -> "windows"
            "linux" -> "linux"
            else -> if (majorVersion <= 16) "osx" else "macos"
        }
        val ext = if (os == "windows") "zip" else "tar.gz"
        return listOf(
            "https://repo.huaweicloud.com/openjdk/$majorVersion/openjdk-${majorVersion}_${osPart}-${normalizedArch}_bin.$ext",
        )
    }

    private fun installWindowsJavaExe(installer: File, targetDir: File): Boolean {
        if (!System.getProperty("os.name").lowercase().contains("win")) return false
        val installHome = File(targetDir, "jdk")
        installHome.mkdirs()

        val argSets = listOf(
            listOf("/s", "INSTALLDIR=${installHome.absolutePath}"),
            listOf("/s", "/INSTALLDIR=${installHome.absolutePath}"),
        )

        argSets.forEach { args ->
            runCatching {
                val pb = ProcessBuilder(listOf(installer.absolutePath) + args)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.inputStream.bufferedReader().use { reader ->
                    while (reader.readLine() != null) {
                        // consume installer output to avoid blocking
                    }
                }
                if (!proc.waitFor(15, TimeUnit.MINUTES)) {
                    proc.destroyForcibly()
                    return@runCatching false
                }
                proc.exitValue() == 0 && findJavaExeInDir(targetDir) != null
            }.getOrDefault(false).let { ok ->
                if (ok) return true
            }
        }
        return findJavaExeInDir(targetDir) != null
    }

    private fun downloadJavaArchive(
        url: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long, speedBytesPerSec: Long) -> Unit,
    ): Boolean {
        repeat(2) {
            var conn: HttpURLConnection? = null
            try {
                dest.parentFile?.mkdirs()
                var currentUrl = url
                var redirects = 0
                while (true) {
                    conn = URL(currentUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 120_000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "MD3L/1.1 (https://github.com/zhou1844/MD3L)")
                    val code = conn.responseCode
                    if (code in 301..308) {
                        val location = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (location.isNullOrBlank() || ++redirects > 8) return false
                        currentUrl = if (location.startsWith("http")) location else URL(URL(currentUrl), location).toString()
                        continue
                    }
                    if (code != 200) {
                        conn.disconnect()
                        return@repeat
                    }
                    break
                }
                val activeConn = conn ?: return@repeat
                val total = activeConn.contentLengthLong
                val startedAt = System.currentTimeMillis()
                var downloaded = 0L
                dest.outputStream().use { output ->
                    activeConn.inputStream.use { input ->
                        val buffer = ByteArray(128 * 1024)
                        var read: Int
                        var lastUpdate = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 300) {
                                val elapsed = (now - startedAt).coerceAtLeast(1L)
                                onProgress(downloaded, total, downloaded * 1000L / elapsed)
                                lastUpdate = now
                            }
                        }
                    }
                }
                val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                onProgress(downloaded, total, downloaded * 1000L / elapsed)
                activeConn.disconnect()
                return dest.isFile && dest.length() > 0
            } catch (_: Exception) {
                conn?.disconnect()
                dest.delete()
            }
        }
        return false
    }

    private fun unzip(zip: File, destDir: File) {
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun extractTarGz(tgz: File, destDir: File) {
        val pb = ProcessBuilder("tar", "xzf", tgz.absolutePath, "-C", destDir.absolutePath)
        pb.redirectErrorStream(true)
        pb.start().waitFor()
    }

    private fun findJavaExeInDir(dir: File): File? {
        if (!dir.exists()) return null
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val exeName = if (isWin) "java.exe" else "java"
        dir.walkTopDown().forEach { f ->
            if (f.isFile && f.name == exeName && f.parentFile?.name == "bin") return f
        }
        return null
    }
}
