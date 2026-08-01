package launcher.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.ZipFile

private const val GDK_CURL_PKG_URL = "https://mirror.msys2.org/mingw/mingw64/mingw-w64-x86_64-curl-8.17.0-1-any.pkg.tar.zst"
private const val GDK_CACERT_URL = "https://curl.se/ca/cacert.pem"

data class BedrockVersionConfig(
    val versionPath: String = "",
    val versionId: String = "",
    val versionName: String = "",
    val buildType: String = "GDK",
    val versionType: String = "release",
    val bodyFile: String = "Minecraft.Windows.exe",
    val isVersionIsolated: Boolean = false,
    val isEditModel: Boolean = false,
    val isModes: Boolean = false,
    val otherCommand: String = "",
    val gameInputInstalled: Boolean = false,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun loadFromDir(versionDir: String): BedrockVersionConfig? {
            return try {
                val configFile = File(versionDir, "config/BedrockBoot2/config.json")
                if (!configFile.exists()) {
                    val fallbackExe = findExeInDir(versionDir)
                    if (fallbackExe != null) {
                        val name = File(versionDir).name
                        return BedrockVersionConfig(
                            versionPath = versionDir,
                            versionId = name,
                            versionName = name,
                            bodyFile = fallbackExe,
                        )
                    }
                    return null
                }

                val root = json.parseToJsonElement(configFile.readText(Charsets.UTF_8)).jsonObject
                BedrockVersionConfig(
                    versionPath = versionDir,
                    versionId = root["Version"]?.jsonPrimitive?.contentOrNull ?: File(versionDir).name,
                    versionName = root["VersionName"]?.jsonPrimitive?.contentOrNull ?: File(versionDir).name,
                    buildType = root["BuildType"]?.jsonPrimitive?.contentOrNull ?: "GDK",
                    versionType = root["VersionType"]?.jsonPrimitive?.contentOrNull ?: "release",
                    bodyFile = root["BodyFile"]?.jsonPrimitive?.contentOrNull ?: "Minecraft.Windows.exe",
                    isVersionIsolated = root["IsVersionIsolated"]?.jsonPrimitive?.booleanOrNull ?: false,
                    isEditModel = root["IsEditModel"]?.jsonPrimitive?.booleanOrNull ?: false,
                    isModes = root["IsModes"]?.jsonPrimitive?.booleanOrNull ?: false,
                    otherCommand = root["OtherCommand"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            } catch (e: Exception) {
                null
            }
        }

        fun findExeInDir(dir: String): String? {
            val d = File(dir)
            if (!d.isDirectory) return null
            val exe = d.walkTopDown().maxDepth(3).find {
                it.name.equals("Minecraft.Windows.exe", ignoreCase = true)
            }
            return exe?.relativeTo(d)?.path
        }
    }
}

class BedrockLaunchEngine {

    data class ProtonInfo(
        val prefixPath: String = "",
        val protonPath: String = "",
    )

    suspend fun launch(
        versionConfig: BedrockVersionConfig,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ): Process? {
        val protonPath = ProtonManager.getSelectedProtonPath()
        if (protonPath.isBlank() || !ProtonManager.isInstalled()) {
            println("[BedrockLaunch] Proton 未安装")
            return null
        }

        val prefixPath = File(LauncherDirs.protonDir, "game_prefix").also { it.mkdirs() }
        val protonInfo = ProtonInfo(prefixPath = prefixPath.absolutePath, protonPath = protonPath)

        val exePath = File(versionConfig.versionPath, versionConfig.bodyFile)
        if (!exePath.exists()) {
            println("[BedrockLaunch] 找不到游戏可执行文件: ${exePath.absolutePath}")
            return null
        }

        installGameInputIfNeeded(versionConfig, protonInfo)
        ensureOnlineComponents(versionConfig)

        return launchWithProton(exePath.absolutePath, versionConfig, protonInfo)
    }

    private fun installGameInputIfNeeded(
        versionConfig: BedrockVersionConfig,
        protonInfo: ProtonInfo,
    ) {
        if (versionConfig.gameInputInstalled) return

        val gameInputMsi = File(versionConfig.versionPath, "Installers/GameInputRedist.msi")
        if (!gameInputMsi.exists()) return

        println("[BedrockLaunch] 安装 GameInputRedist...")
        val process = launchWithProton(gameInputMsi.absolutePath, versionConfig, protonInfo, useWaitForExit = false)
        if (process == null) {
            println("[BedrockLaunch] GameInputRedist 安装失败: Proton 进程未能启动")
            return
        }
        process.waitFor()
        println("[BedrockLaunch] GameInputRedist 安装完成")
    }

    private fun ensureOnlineComponents(versionConfig: BedrockVersionConfig) {
        val versionDir = File(versionConfig.versionPath)
        if (!versionDir.isDirectory) return
        val xcurl = File(versionDir, "XCurl.dll")
        val caBundle = File(versionDir, "etc/ssl/certs/ca-bundle.crt")
        val needXcurl = !xcurl.exists() || xcurl.length() < 500_000
        val needCa = !caBundle.exists()
        if (!needXcurl && !needCa) return
        println("[BedrockLaunch] 配置 GDK 联网组件...")
        val tmp = File(System.getProperty("java.io.tmpdir"), "md3l-gdk-online")
        tmp.mkdirs()
        if (needXcurl) {
            val pkg = File(tmp, "mingw-w64-x86_64-curl.pkg.tar.zst")
            if (downloadToFile(GDK_CURL_PKG_URL, pkg)) {
                val extractDir = File(tmp, "extract")
                if (extractZstPackage(pkg, extractDir)) {
                    val libcurl = File(extractDir, "mingw64/bin/libcurl-4.dll")
                    if (libcurl.exists()) {
                        libcurl.copyTo(xcurl, overwrite = true)
                        println("[BedrockLaunch] XCurl.dll 已替换为真实 libcurl")
                    }
                }
            }
        }
        if (needCa) {
            val cacert = File(tmp, "cacert.pem")
            if (downloadToFile(GDK_CACERT_URL, cacert)) {
                val certDir = File(versionDir, "etc/ssl/certs")
                certDir.mkdirs()
                cacert.copyTo(File(certDir, "ca-bundle.crt"), overwrite = true)
                println("[BedrockLaunch] ca-bundle.crt 已配置")
            }
        }
    }

    private fun downloadToFile(url: String, target: File): Boolean {
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target.toPath()))
            response.statusCode() in 200..299
        } catch (e: Exception) {
            println("[BedrockLaunch] 下载失败 $url: ${e.message}")
            false
        }
    }

    private fun extractZstPackage(pkg: File, extractDir: File): Boolean {
        return try {
            extractDir.mkdirs()
            val pb = ProcessBuilder("tar", "--zstd", "-xf", pkg.absolutePath, "-C", extractDir.absolutePath)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.waitFor() == 0
        } catch (e: Exception) {
            println("[BedrockLaunch] 解压失败: ${e.message}")
            false
        }
    }

    private fun launchWithProton(
        filePath: String,
        versionConfig: BedrockVersionConfig,
        protonInfo: ProtonInfo,
        useWaitForExit: Boolean = true,
    ): Process? {
        return try {
            val protonScript = File(protonInfo.protonPath, "proton")
            if (!protonScript.exists()) {
                println("[BedrockLaunch] 找不到 proton 脚本: ${protonScript.absolutePath}")
                return null
            }
            if (!protonScript.canExecute()) {
                protonScript.setExecutable(true, false)
            }

            // 使用 waitforexitandrun 触发 protonfixes；非 GDK 安装 MSI 用 run
            val verb = if (useWaitForExit) "waitforexitandrun" else "run"
            val pb = ProcessBuilder(
                protonScript.absolutePath,
                verb, filePath,
            )

            val env = pb.environment()
            env["STEAM_COMPAT_DATA_PATH"] = protonInfo.prefixPath
            val steamInstall = System.getenv("STEAM_COMPAT_CLIENT_INSTALL_PATH")?.takeIf { it.isNotBlank() }
                ?: listOf(
                    "${System.getProperty("user.home")}/.local/share/Steam",
                    "${System.getProperty("user.home")}/.steam/steam",
                    "${System.getProperty("user.home")}/.steam/root",
                ).firstOrNull { File(it).exists() }
                ?: "${System.getProperty("user.home")}/.local/share/Steam"
            env["STEAM_COMPAT_CLIENT_INSTALL_PATH"] = steamInstall
            // UMU_ID 防止 proton 尝试启动 steam.exe
            env["UMU_ID"] = "md3l-minecraft-gdk"

            val lib64 = "${protonInfo.protonPath}/files/lib64"
            val lib32 = "${protonInfo.protonPath}/files/lib"
            val currentLd = env["LD_LIBRARY_PATH"] ?: ""
            env["LD_LIBRARY_PATH"] = "$lib64:$lib32${if (currentLd.isNotBlank()) ":$currentLd" else ""}"

            env["WINEDLLOVERRIDES"] = "dxgi,d3d11,d3d10core,d3d9=b"

            val extraEnv = versionConfig.otherCommand.lines()
                .filter { it.contains("=") && !it.startsWith("#") }
            for (line in extraEnv) {
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) env[parts[0].trim()] = parts[1].trim()
            }

            pb.redirectErrorStream(false)
            val process = pb.start()

            Thread {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        println("[MC] $line")
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()

            Thread {
                try {
                    process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        println("[MC:ERR] $line")
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()

            val verbLabel = if (useWaitForExit) "waitforexitandrun" else "run"
            println("[BedrockLaunch] 游戏启动成功 PID: ${process.pid()} (verb=$verbLabel)")
            process
        } catch (e: Exception) {
            println("[BedrockLaunch] 启动失败: ${e.message}")
            null
        }
    }

    fun resolveVersionProfilePublic(minecraftDir: String, versionId: String): File =
        resolveVersionProfile(minecraftDir, versionId)

    fun resolveBedrockVersionComMojang(minecraftDir: String, versionId: String): File {
        return resolveVersionProfile(minecraftDir, versionId)
    }

    fun resolveActiveComMojangPublic(minecraftDir: String, versionId: String): File {
        return resolveVersionProfile(minecraftDir, versionId)
    }

    fun resolveActiveJunctionTarget(): File? {
        return null
    }

    private fun resolveVersionProfile(minecraftDir: String, versionId: String): File {
        val customProfilesDir = runCatching { runBlocking { AppSettings.load() }.bedrockProfilesDir }.getOrNull()
        val base = if (!customProfilesDir.isNullOrBlank()) {
            File(customProfilesDir, "md3l_profiles")
        } else {
            File(minecraftDir.takeIf { it.isNotBlank() } ?: File("mc").absolutePath, "bedrock_profiles")
        }
        return File(base, "$versionId/com.mojang").apply { mkdirs() }
    }

    fun extractAppxBundle(
        bundlePath: String,
        targetDir: File,
        targetArch: String = "x64",
        onProgress: ((current: Int, total: Int, fileName: String) -> Unit)? = null,
    ) {
        targetDir.mkdirs()
        val bundleFile = File(bundlePath)
        val ext = bundleFile.extension.lowercase()

        when (ext) {
            "appx", "msix" -> {
                extractZipToDir(bundleFile, targetDir, onProgress)
            }
            "msixvc" -> {
                println("[Bedrock] 检测到 MSIXVC 格式，尝试纯 Kotlin XVD 解码器...")
                val xvdProgress = if (onProgress != null) GdkXvdExtractor.ExtractProgress { cur, total, name ->
                    onProgress(cur, total, name)
                } else null
                val ok = GdkXvdExtractor.extract(bundleFile, targetDir, GDK_CIK_GUID, GDK_CIK_KEY, xvdProgress)
                if (!ok) {
                    throw RuntimeException("XVD 解码器解压 .msixvc 失败")
                }
            }
            "msixbundle", "appxbundle" -> {
                ZipFile(bundleFile).use { bundle ->
                    val allEntries = bundle.entries().asSequence().toList()
                    println("[Bedrock] bundle 内容: ${allEntries.map { it.name }}")
                    val targetEntry = allEntries.find { entry ->
                        isTargetArchPayload(entry.name, targetArch)
                    } ?: throw RuntimeException(
                        "未在 bundle 中找到 $targetArch 架构的 .appx 包体，所有条目: ${allEntries.map { it.name }}"
                    )

                    val tempAppx = File.createTempFile("mc_bedrock_", ".appx")
                    try {
                        bundle.getInputStream(targetEntry).use { input ->
                            BufferedInputStream(input).use { bufferedInput ->
                                BufferedOutputStream(tempAppx.outputStream()).use { output ->
                                    copyStream(bufferedInput, output)
                                }
                            }
                        }
                        extractZipToDir(tempAppx, targetDir, onProgress)
                    } finally {
                        tempAppx.delete()
                    }
                }
            }
            else -> throw IllegalArgumentException("不支持的包格式: $ext")
        }
        File(targetDir, "AppxSignature.p7x").takeIf { it.exists() }?.delete()
    }

    private fun extractZipToDir(zipFile: File, targetDir: File, onProgress: ((current: Int, total: Int, fileName: String) -> Unit)? = null) {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .toList()
            val total = entries.size
            entries.forEachIndexed { idx, entry ->
                if (entry.isDirectory) return@forEachIndexed
                val outFile = File(targetDir, entry.name)
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    return@forEachIndexed
                }
                onProgress?.invoke(idx + 1, total, entry.name)
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    BufferedInputStream(input).use { bufferedInput ->
                        BufferedOutputStream(outFile.outputStream()).use { output ->
                            copyStream(bufferedInput, output)
                        }
                    }
                }
            }
        }
    }

    private fun copyStream(input: InputStream, output: BufferedOutputStream) {
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    private fun isTargetArchPayload(entryName: String, targetArch: String): Boolean {
        val name = entryName.substringAfterLast('/').substringAfterLast('\\').lowercase()
        val arch = targetArch.lowercase()
        if (!name.endsWith(".appx") && !name.endsWith(".msix")) return false
        if (name.endsWith(".msixbundle") || name.endsWith(".appxbundle")) return false
        return name.endsWith("_$arch.appx") ||
            name.endsWith("_$arch.msix") ||
            "_${arch}_" in name ||
            "_${arch}__" in name ||
            name.contains(arch)
    }

    fun injectAddon(addonPath: String, bedrockDataDir: File) {
        val addonFile = File(addonPath)
        val ext = addonFile.extension.lowercase()

        when (ext) {
            "mcpack" -> injectSinglePack(addonFile, bedrockDataDir)
            "mcaddon", "zip" -> {
                val tempDir = File(System.getProperty("java.io.tmpdir"), "md3l_addon_${System.nanoTime()}")
                tempDir.mkdirs()
                try {
                    extractZipToDir(addonFile, tempDir)
                    val mcpacks = tempDir.walkTopDown().filter {
                        it.isFile && it.extension.lowercase() == "mcpack"
                    }.toList()

                    val rootManifest = File(tempDir, "manifest.json")
                    when {
                        mcpacks.isNotEmpty() -> {
                            mcpacks.forEach { pack -> injectSinglePack(pack, bedrockDataDir) }
                        }
                        rootManifest.exists() -> {
                            val packType = detectPackType(rootManifest)
                            val uuid = readManifestUuid(rootManifest)
                            val packName = uuid?.replace("-", "") ?: addonFile.nameWithoutExtension
                            val targetBase = resolvePackTargetDir(packType, bedrockDataDir)
                            val destDir = File(targetBase, packName)
                            destDir.mkdirs()
                            tempDir.copyRecursively(destDir, overwrite = true)
                        }
                        else -> {
                            tempDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                                val manifest = File(subDir, "manifest.json")
                                if (manifest.exists()) {
                                    val packType = detectPackType(manifest)
                                    val uuid = readManifestUuid(manifest)
                                    val packName = uuid?.replace("-", "") ?: subDir.name
                                    val targetBase = resolvePackTargetDir(packType, bedrockDataDir)
                                    val destDir = File(targetBase, packName)
                                    subDir.copyRecursively(destDir, overwrite = true)
                                }
                            }
                        }
                    }
                } finally {
                    tempDir.deleteRecursively()
                }
            }
            else -> throw IllegalArgumentException("不支持的 Addon 格式: $ext (仅支持 .mcpack / .mcaddon)")
        }
    }

    private fun injectSinglePack(packFile: File, bedrockDataDir: File) {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "md3l_pack_${System.nanoTime()}")
        tempDir.mkdirs()
        try {
            extractZipToDir(packFile, tempDir)
            val manifest = tempDir.walkTopDown().find { it.name == "manifest.json" }
                ?: throw RuntimeException("Pack 中未找到 manifest.json: ${packFile.name}")

            val packType = detectPackType(manifest)
            val packRootDir = manifest.parentFile!!

            val uuid = readManifestUuid(manifest)
            val packName = uuid?.replace("-", "")
                ?: packFile.nameWithoutExtension

            val targetBase = resolvePackTargetDir(packType, bedrockDataDir)
            val destDir = File(targetBase, packName)
            destDir.mkdirs()
            packRootDir.copyRecursively(destDir, overwrite = true)
            println("[AddonInject] ${packFile.name} -> ${destDir.absolutePath}")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun detectPackType(manifestFile: File): PackType {
        return try {
            val root = Json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)).jsonObject
            val modules = root["modules"]?.jsonArray
            val firstModuleType = modules?.firstOrNull()?.jsonObject
                ?.get("type")?.jsonPrimitive?.contentOrNull?.lowercase()

            when (firstModuleType) {
                "resources" -> PackType.ResourcePack
                else -> PackType.BehaviorPack
            }
        } catch (e: Exception) {
            PackType.BehaviorPack
        }
    }

    private fun readManifestUuid(manifestFile: File): String? {
        return try {
            val root = Json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)).jsonObject
            root["header"]?.jsonObject?.get("uuid")?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) { null }
    }

    private fun resolvePackTargetDir(type: PackType, bedrockDataDir: File): File {
        val dirName = when (type) {
            PackType.BehaviorPack -> "behavior_packs"
            PackType.ResourcePack -> "resource_packs"
        }
        val dir = File(bedrockDataDir, dirName)
        dir.mkdirs()
        return dir
    }

    enum class PackType { BehaviorPack, ResourcePack }
}

object BedrockVersionCatalog {
    data class BedrockVersionEntry(
        val version: String,
        val downloadUrl: String,
        val fileName: String,
        val fileSize: Long,
        val arch: String,
    )

    suspend fun fetchAvailableVersions(): List<BedrockVersionEntry> {
        // Linux 版本不提供 UWP Store 目录查询
        return emptyList()
    }
}
