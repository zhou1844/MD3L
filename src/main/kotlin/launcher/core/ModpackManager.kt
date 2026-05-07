package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ModpackManager {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private enum class PackKind { Modrinth, CurseForge, GenericZip }

    private data class PackDefinition(
        val kind: PackKind,
        val displayName: String,
        val mcVersion: String,
        val loaderType: String? = null,
        val loaderVersion: String? = null,
        val overridesRoots: List<String> = emptyList(),
        val modrinthIndex: JsonObject? = null,
    )

    private data class LoaderInstallOutcome(
        val installedVersionId: String? = null,
        val warning: String? = null,
    )

    private data class ModrinthRemoteFile(
        val path: String,
        val urls: List<String>,
        val size: Long,
        val sha1: String? = null,
        val sha512: String? = null,
    )

    private class ImportLogger(private val logFile: File) {
        private val lock = Any()
        val path: String get() = logFile.absolutePath

        fun info(message: String) {
            append("INFO", message)
        }

        fun warn(message: String) {
            append("WARN", message)
        }

        fun error(message: String, throwable: Throwable? = null) {
            append("ERROR", message)
            if (throwable != null) {
                append("ERROR", throwable.stackTraceStringSafe())
            }
        }

        private fun append(level: String, message: String) {
            synchronized(lock) {
                val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                logFile.appendText("[$time][$level] $message\n", Charsets.UTF_8)
            }
        }
    }

    suspend fun importMrpack(
        packFile: File,
        minecraftDir: String,
        onProgress: (String, Float) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        if (!packFile.isFile) return@withContext "整合包文件不存在"
        val logger = createImportLogger(minecraftDir, packFile)
        logger.info("开始导入整合包: ${packFile.absolutePath}")
        println("[ModpackImport] ====== 开始导入整合包 ======")
        println("[ModpackImport] 文件: ${packFile.absolutePath} (${packFile.length()} bytes)")
        fun report(step: String, fraction: Float) {
            val clamped = fraction.coerceIn(0f, 1f)
            onProgress(formatImportProgress(step, clamped), clamped)
        }
        report("检查整合包文件", 0.02f)

        try {
            ZipFile(packFile).use { zip ->
                println("[ModpackImport] 步骤1: 解析整合包定义...")
                val definition = parsePackDefinition(zip, packFile, logger)
                logger.info("识别整合包类型=${definition.kind}, mc=${definition.mcVersion}, loader=${definition.loaderType}:${definition.loaderVersion}")
                println("[ModpackImport] 解析完成: kind=${definition.kind}, mc=${definition.mcVersion}, loader=${definition.loaderType}:${definition.loaderVersion}")
                val versionId = uniqueVersionId(minecraftDir, definition.displayName.sanitizeVersionName())
                val settings = AppSettings.load()

                report("查找原版 ${definition.mcVersion} 清单", 0.1f)
                println("[ModpackImport] 步骤2: 查找原版 ${definition.mcVersion} 版本清单...")
                val remote = VersionManifest.fetchVersionList().firstOrNull { it.id == definition.mcVersion }
                println("[ModpackImport] 版本清单查找结果: ${if (remote != null) "找到" else "未找到"}")

                val baseInstalled = if (remote != null) {
                    report("安装原版 ${definition.mcVersion} 到 $versionId", 0.16f)
                    println("[ModpackImport] 步骤2: 安装原版 ${definition.mcVersion} -> $versionId")
                    installBaseVersionRobust(
                        remote = remote,
                        minecraftDir = minecraftDir,
                        versionId = versionId,
                        preferredMirror = settings.downloadMirror,
                        maxThreads = settings.maxDownloadThreads,
                        logger = logger,
                    ) { step, frac ->
                        report("安装原版 ${definition.mcVersion}: $step", frac)
                    }
                } else {
                    logger.warn("未从版本清单获取到 ${definition.mcVersion}，尝试本地原版兜底")
                    report("版本清单不可用，尝试本地原版兜底", 0.22f)
                    deriveVersionFromInstalledVanilla(definition.mcVersion, minecraftDir, versionId, logger)
                }
                if (!baseInstalled) {
                    throw RuntimeException("原版 ${definition.mcVersion} 安装失败")
                }
                println("[ModpackImport] 步骤2完成: 原版安装成功")

                report("安装整合包加载器", 0.5f)
                println("[ModpackImport] 步骤3: 安装加载器 ${definition.loaderType}:${definition.loaderVersion}...")
                val loaderOutcome = installLoaderIfNeeded(
                    definition = definition,
                    mcVersion = definition.mcVersion,
                    minecraftDir = minecraftDir,
                    baseVersionId = versionId,
                    javaPath = settings.javaPath,
                    logger = logger,
                    onLoaderProgress = { step, frac ->
                        val mapped = (0.46f + 0.16f * frac.coerceIn(0f, 1f)).coerceIn(0.46f, 0.62f)
                        report("安装整合包加载器: $step", mapped)
                    },
                )
                println("[ModpackImport] 步骤3完成: 加载器安装结果 id=${loaderOutcome.installedVersionId}, warning=${loaderOutcome.warning}")
                if (loaderOutcome.installedVersionId != null && loaderOutcome.installedVersionId != versionId) {
                    println("[ModpackImport] 迁移加载器版本 ${loaderOutcome.installedVersionId} -> $versionId")
                    copyLoaderVersionAs(loaderOutcome.installedVersionId, versionId, minecraftDir, definition.mcVersion)
                    logger.info("已将加载器版本 ${loaderOutcome.installedVersionId} 迁移到导入版本 $versionId")
                }

                val instanceDir = File(minecraftDir, "versions/$versionId/.minecraft")
                instanceDir.mkdirs()
                report("释放整合包文件", 0.62f)
                println("[ModpackImport] 步骤4: 释放整合包文件到 ${instanceDir.absolutePath}")
                when (definition.kind) {
                    PackKind.Modrinth, PackKind.CurseForge -> {
                        extractOverridesByRoots(zip, instanceDir, definition.overridesRoots, logger)
                    }
                    PackKind.GenericZip -> {
                        extractGenericZip(zip, instanceDir, logger)
                    }
                }

                println("[ModpackImport] 步骤4完成: 文件释放完毕")
                if (definition.kind == PackKind.Modrinth && definition.modrinthIndex != null) {
                    println("[ModpackImport] 步骤5: 下载 Modrinth 资源文件...")
                    downloadModrinthFiles(definition.modrinthIndex, instanceDir, logger) { step, frac ->
                        val mapped = (0.70f + 0.24f * frac).coerceIn(0.70f, 0.94f)
                        report(step, mapped)
                    }
                }

                println("[ModpackImport] 步骤6: 写入整合包标记并刷新版本列表")
                report("写入整合包标记并刷新版本列表", 0.96f)
                writeMarker(versionId, packFile, definition.mcVersion, instanceDir)
                VersionRepository.invalidateCache(minecraftDir)
                report("整合包导入完成: $versionId", 1f)

                val warning = loaderOutcome.warning?.let { "\n$it" } ?: ""
                logger.info("导入成功: $versionId")
                println("[ModpackImport] ====== 导入成功: $versionId ======")
                "整合包导入成功: $versionId$warning\n日志: ${logger.path}"
            }
        } catch (e: Exception) {
            logger.error("导入失败: ${e.message}", e)
            println("[ModpackImport] ====== 导入失败: ${e.message} ======")
            e.printStackTrace()
            "整合包导入失败: ${e.message}\n日志: ${logger.path}"
        }
    }

    private fun createImportLogger(minecraftDir: String, packFile: File): ImportLogger {
        val logsDir = File(minecraftDir, "logs")
        logsDir.mkdirs()
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val logFile = File(logsDir, "md3l-modpack-import-$ts.log")
        logFile.writeText("MD3L Modpack Import Log\npack=${packFile.absolutePath}\n\n", Charsets.UTF_8)
        return ImportLogger(logFile)
    }

    private fun parsePackDefinition(zip: ZipFile, packFile: File, logger: ImportLogger): PackDefinition {
        zip.getEntry("modrinth.index.json")?.let { indexEntry ->
            val text = zip.getInputStream(indexEntry).bufferedReader().readText()
            val index = json.parseToJsonElement(text).jsonObject
            val dependencies = index["dependencies"]?.jsonObject ?: JsonObject(emptyMap())
            val mcVersion = dependencies["minecraft"]?.jsonPrimitive?.contentOrNull
                ?: throw RuntimeException("Modrinth 整合包缺少 dependencies.minecraft")
            val (loaderType, loaderVersion) = parseLoaderFromModrinthDependencies(dependencies)
            val name = index["name"]?.jsonPrimitive?.contentOrNull
                ?: packFile.nameWithoutExtension
            return PackDefinition(
                kind = PackKind.Modrinth,
                displayName = name,
                mcVersion = mcVersion,
                loaderType = loaderType,
                loaderVersion = loaderVersion,
                overridesRoots = listOf("overrides/", "client-overrides/"),
                modrinthIndex = index,
            )
        }

        val manifestEntry = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.replace('\\', '/').endsWith("manifest.json", ignoreCase = true) }
            .minByOrNull { it.name.count { ch -> ch == '/' || ch == '\\' } }
        if (manifestEntry != null) {
            val text = zip.getInputStream(manifestEntry).bufferedReader().readText()
            val manifest = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (manifest != null && manifest["minecraft"] is JsonObject) {
                val mc = manifest["minecraft"]!!.jsonObject
                val mcVersion = mc["version"]?.jsonPrimitive?.contentOrNull
                    ?: throw RuntimeException("CurseForge manifest 缺少 minecraft.version")
                val (loaderType, loaderVersion) = parseLoaderFromCurseManifest(mc)
                val name = manifest["name"]?.jsonPrimitive?.contentOrNull ?: packFile.nameWithoutExtension
                val overrides = manifest["overrides"]?.jsonPrimitive?.contentOrNull?.normalizeRootPrefix()
                    ?: "overrides/"
                return PackDefinition(
                    kind = PackKind.CurseForge,
                    displayName = name,
                    mcVersion = mcVersion,
                    loaderType = loaderType,
                    loaderVersion = loaderVersion,
                    overridesRoots = listOf(overrides),
                )
            }
            logger.warn("检测到 manifest.json 但结构不是 CurseForge，回退通用 ZIP 解析")
        }

        val mcVersion = detectMcVersionForGenericZip(zip, packFile)
            ?: throw RuntimeException("ZIP 整合包缺少可识别的 Minecraft 版本")
        val name = packFile.nameWithoutExtension
        return PackDefinition(
            kind = PackKind.GenericZip,
            displayName = name,
            mcVersion = mcVersion,
            overridesRoots = listOf("overrides/", ".minecraft/", "minecraft/"),
        )
    }

    private fun parseLoaderFromModrinthDependencies(dependencies: JsonObject): Pair<String?, String?> {
        fun value(key: String): String? = dependencies[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return when {
            value("neoforge") != null -> "NeoForge" to value("neoforge")
            value("fabric-loader") != null -> "Fabric" to value("fabric-loader")
            value("forge") != null -> "Forge" to value("forge")
            value("quilt-loader") != null -> "Quilt" to value("quilt-loader")
            else -> null to null
        }
    }

    private fun parseLoaderFromCurseManifest(minecraftObj: JsonObject): Pair<String?, String?> {
        val loaders = minecraftObj["modLoaders"]?.jsonArray ?: return null to null
        val selected = loaders
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it["primary"]?.jsonPrimitive?.booleanOrNull == true }
            ?: loaders.firstOrNull()?.jsonObject
            ?: return null to null
        val id = selected["id"]?.jsonPrimitive?.contentOrNull ?: return null to null
        val lowered = id.lowercase()
        return when {
            lowered.startsWith("neoforge-") -> "NeoForge" to id.substringAfter('-')
            lowered.startsWith("fabric-") -> "Fabric" to id.substringAfter('-')
            lowered.startsWith("forge-") -> "Forge" to id.substringAfter('-')
            lowered.startsWith("quilt-") -> "Quilt" to id.substringAfter('-')
            else -> null to null
        }
    }

    private fun detectMcVersionForGenericZip(zip: ZipFile, packFile: File): String? {
        val regexes = listOf(
            Regex(""""minecraft(?:Version|_version)?"\s*:\s*"([^"]+)""", RegexOption.IGNORE_CASE),
            Regex(""""gameVersion"\s*:\s*"([^"]+)""", RegexOption.IGNORE_CASE),
            Regex(""""mcVersion"\s*:\s*"([^"]+)""", RegexOption.IGNORE_CASE),
            Regex(""""version"\s*:\s*"(1\.\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
        )
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
            .take(50)
            .forEach { entry ->
                val text = runCatching { zip.getInputStream(entry).bufferedReader().readText() }.getOrNull() ?: return@forEach
                regexes.forEach { reg ->
                    reg.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        return Regex("""(?<!\d)(1\.\d+(?:\.\d+)?|2\d\.\d+(?:\.\d+)?)(?!\d)""")
            .find(packFile.name)
            ?.value
    }

    suspend fun exportMrpack(version: LocalVersion, targetFile: File): String = withContext(Dispatchers.IO) {
        try {
            val versionDir = File(version.versionDir)
            if (!versionDir.isDirectory) return@withContext "版本目录不存在"
            targetFile.parentFile?.mkdirs()
            val instanceDir = File(versionDir, ".minecraft")
            ZipOutputStream(targetFile.outputStream()).use { zip ->
                val index = JsonObject(mapOf(
                    "formatVersion" to JsonPrimitive(1),
                    "game" to JsonPrimitive("minecraft"),
                    "versionId" to JsonPrimitive(version.id),
                    "name" to JsonPrimitive(version.id),
                    "summary" to JsonPrimitive("Exported by MD3L"),
                    "files" to JsonArray(emptyList()),
                    "dependencies" to JsonObject(mapOf("minecraft" to JsonPrimitive(version.inheritsFrom ?: version.id))),
                ))
                putText(zip, "modrinth.index.json", json.encodeToString(JsonObject.serializer(), index))
                if (instanceDir.isDirectory) {
                    listOf("mods", "config", "resourcepacks", "shaderpacks", "saves").forEach { folder ->
                        val root = File(instanceDir, folder)
                        if (root.exists()) putDir(zip, root, "overrides/$folder")
                    }
                }
            }
            "整合包导出成功: ${targetFile.absolutePath}"
        } catch (e: Exception) {
            "整合包导出失败: ${e.message}"
        }
    }

    private suspend fun installBaseVersionRobust(
        remote: RemoteVersion,
        minecraftDir: String,
        versionId: String,
        preferredMirror: String,
        maxThreads: Int,
        logger: ImportLogger,
        onProgress: (String, Float) -> Unit,
    ): Boolean {
        val originalMirror = DownloadManager.activeMirror
        val primaryMirror = preferredMirror.ifBlank { originalMirror.ifBlank { "bmclapi" } }
        val fallbackMirror = if (primaryMirror == "official") "bmclapi" else "official"
        val attempts = listOf(primaryMirror, fallbackMirror).distinct()

        try {
            attempts.forEachIndexed { index, mirror ->
                val maxThreadsThisAttempt = if (index == 0) {
                    maxThreads.coerceIn(4, 64)
                } else {
                    maxThreads.coerceIn(4, 64).coerceAtMost(16)
                }
                DownloadManager.activeMirror = mirror
                logger.info("安装原版尝试 ${index + 1}/${attempts.size}，镜像=$mirror，并发=$maxThreadsThisAttempt")

                val ok = runCatching {
                    VersionManifest.installVersion(
                        version = remote,
                        minecraftDir = minecraftDir,
                        customName = versionId,
                        maxThreads = maxThreadsThisAttempt,
                    ) { step, frac ->
                        val mapped = (0.16f + (0.46f - 0.16f) * frac).coerceIn(0.16f, 0.46f)
                        onProgress("尝试 ${index + 1}/${attempts.size} · $step", mapped)
                    }
                }.getOrElse {
                    logger.warn("安装原版尝试 ${index + 1} 失败: ${it.message}")
                    false
                }
                if (ok) return true
            }

            if (deriveVersionFromInstalledVanilla(remote.id, minecraftDir, versionId, logger)) {
                logger.info("已通过本地原版兜底构建导入版本: $versionId <- ${remote.id}")
                onProgress("已使用本地原版兜底完成", 0.46f)
                return true
            }

            logger.warn("自定义原版安装失败，尝试先安装父原版 ${remote.id} 再派生整合包版本")
            attempts.forEachIndexed { index, mirror ->
                val maxThreadsThisAttempt = maxThreads.coerceIn(4, 64).coerceAtMost(24)
                DownloadManager.activeMirror = mirror
                val okParent = runCatching {
                    VersionManifest.installVersion(
                        version = remote,
                        minecraftDir = minecraftDir,
                        customName = null,
                        maxThreads = maxThreadsThisAttempt,
                    ) { step, frac ->
                        val mapped = (0.16f + (0.46f - 0.16f) * frac).coerceIn(0.16f, 0.46f)
                        onProgress("父原版尝试 ${index + 1}/${attempts.size} · $step", mapped)
                    }
                }.getOrElse {
                    logger.warn("父原版安装尝试 ${index + 1} 失败: ${it.message}")
                    false
                }

                if (okParent && deriveVersionFromInstalledVanilla(remote.id, minecraftDir, versionId, logger)) {
                    logger.info("父原版安装成功并派生导入版本: $versionId")
                    onProgress("父原版安装成功并已派生版本", 0.46f)
                    return true
                }
            }
        } finally {
            DownloadManager.activeMirror = originalMirror
        }

        return false
    }

    private fun deriveVersionFromInstalledVanilla(
        mcVersion: String,
        minecraftDir: String,
        targetVersionId: String,
        logger: ImportLogger,
    ): Boolean {
        return runCatching {
            val parentDir = File(minecraftDir, "versions/$mcVersion")
            val parentJson = File(parentDir, "$mcVersion.json")
            val parentJar = File(parentDir, "$mcVersion.jar")
            if (!parentJson.isFile || !parentJar.isFile || parentJar.length() <= 0L) {
                return false
            }

            val targetDir = File(minecraftDir, "versions/$targetVersionId")
            targetDir.mkdirs()
            val targetJson = File(targetDir, "$targetVersionId.json")
            val targetJar = File(targetDir, "$targetVersionId.jar")

            val root = json.parseToJsonElement(parentJson.readText(Charsets.UTF_8)).jsonObject.toMutableMap()
            root["id"] = JsonPrimitive(targetVersionId)
            root["inheritsFrom"] = JsonPrimitive(mcVersion)
            targetJson.writeText(
                json.encodeToString(JsonObject.serializer(), JsonObject(root)),
                Charsets.UTF_8,
            )

            if (!targetJar.isFile || targetJar.length() <= 0L) {
                runCatching { parentJar.copyTo(targetJar, overwrite = true) }
            }
            true
        }.onFailure {
            logger.warn("派生本地原版失败: ${it.message}")
        }.getOrDefault(false)
    }

    private fun findExistingLoaderVersionId(
        loaderType: String,
        mcVersion: String,
        loaderVersion: String,
        minecraftDir: String,
    ): String? {
        val versionsDir = File(minecraftDir, "versions")
        if (!versionsDir.isDirectory) return null
        return versionsDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val jsonFile = File(dir, "${dir.name}.json")
                if (!jsonFile.isFile) return@mapNotNull null
                val text = runCatching { jsonFile.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
                when (loaderType) {
                    "Fabric" -> {
                        val nameMatch = dir.name == "$mcVersion-fabric-$loaderVersion" ||
                            ("net.fabricmc" in text && loaderVersion in text && mcVersion in text)
                        if (!nameMatch) return@mapNotNull null
                        // 验证 fabric-loader JAR 存在，防止复用残缺安装
                        val loaderJar = File(minecraftDir, "libraries/net/fabricmc/fabric-loader/$loaderVersion/fabric-loader-$loaderVersion.jar")
                        if (loaderJar.isFile && loaderJar.length() > 0) dir.name else null
                    }
                    "NeoForge" -> {
                        val nameMatch = ("net.neoforged" in text || "neoforge" in dir.name.lowercase()) && loaderVersion in text
                        if (!nameMatch) return@mapNotNull null
                        // 验证 universal JAR 存在
                        val uniJar = File(minecraftDir, "libraries/net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-universal.jar")
                        if (uniJar.isFile && uniJar.length() > 0) dir.name else null
                    }
                    "Forge" -> {
                        if (("net.minecraftforge" in text || "forge" in dir.name.lowercase()) && loaderVersion in text && mcVersion in text) dir.name else null
                    }
                    "Quilt" -> {
                        if (("org.quiltmc" in text || "quilt" in dir.name.lowercase()) && loaderVersion in text) dir.name else null
                    }
                    else -> null
                }
            }
            ?.firstOrNull()
    }

    private suspend fun installLoaderIfNeeded(
        definition: PackDefinition,
        mcVersion: String,
        minecraftDir: String,
        baseVersionId: String,
        javaPath: String,
        logger: ImportLogger,
        onLoaderProgress: (String, Float) -> Unit = { _, _ -> },
    ): LoaderInstallOutcome {
        val loaderType = definition.loaderType
        val loaderVersion = definition.loaderVersion
        if (loaderType.isNullOrBlank() || loaderVersion.isNullOrBlank()) {
            logger.info("整合包未声明可自动安装的加载器，跳过加载器安装")
            println("[ModpackImport] installLoaderIfNeeded: 无加载器声明，跳过")
            return LoaderInstallOutcome()
        }

        println("[ModpackImport] installLoaderIfNeeded: 查找已安装的 $loaderType $loaderVersion...")
        findExistingLoaderVersionId(loaderType, mcVersion, loaderVersion, minecraftDir)?.let { existingId ->
            logger.info("复用已安装加载器: $loaderType $loaderVersion -> $existingId")
            println("[ModpackImport] installLoaderIfNeeded: 复用已安装 $existingId")
            return LoaderInstallOutcome(installedVersionId = existingId)
        }

        logger.info("安装加载器: $loaderType $loaderVersion")
        println("[ModpackImport] installLoaderIfNeeded: 未找到已安装，开始全新安装 $loaderType $loaderVersion")
        return kotlinx.coroutines.coroutineScope {
            val progressJob = launch {
                LoaderInstaller.progress.collect { p ->
                    if (p.loaderName == loaderType && (p.isRunning || p.done || p.error.isNotBlank())) {
                        onLoaderProgress(p.step.ifBlank { "处理中" }, p.fraction)
                    }
                }
            }
            try {
                when (loaderType) {
                    "Fabric" -> {
                        println("[ModpackImport] 调用 LoaderInstaller.installFabric($mcVersion, $loaderVersion)")
                        val id = runCatching {
                            LoaderInstaller.installFabric(mcVersion, loaderVersion, minecraftDir)
                        }.getOrElse { error ->
                            println("[ModpackImport] Fabric 安装异常: ${error.message}")
                            findExistingLoaderVersionId(loaderType, mcVersion, loaderVersion, minecraftDir)
                                ?.also { logger.warn("Fabric 安装异常，回收已存在加载器: ${error.message}") }
                                ?: throw error
                        }
                        LoaderInstallOutcome(installedVersionId = id)
                    }
                    "NeoForge" -> {
                        println("[ModpackImport] 调用 LoaderInstaller.installNeoForge($mcVersion, $loaderVersion)")
                        val id = runCatching {
                            LoaderInstaller.installNeoForge(
                                mcVersion = mcVersion,
                                loaderVersion = loaderVersion,
                                minecraftDir = minecraftDir,
                                baseVersionId = baseVersionId,
                                javaPath = javaPath,
                            )
                        }.getOrElse { error ->
                            println("[ModpackImport] NeoForge 安装异常: ${error.message}")
                            findExistingLoaderVersionId(loaderType, mcVersion, loaderVersion, minecraftDir)
                                ?.also { logger.warn("NeoForge 安装异常，回收已存在加载器: ${error.message}") }
                                ?: throw error
                        }
                        println("[ModpackImport] NeoForge 安装完成, id=$id")
                        LoaderInstallOutcome(installedVersionId = id)
                    }
                    "Forge" -> {
                        logger.warn("Forge 自动安装在整合包导入中仍不稳定，跳过自动安装")
                        LoaderInstallOutcome(warning = "提示：该整合包声明 Forge $loaderVersion，已完成文件导入；请在版本详情中安装对应 Forge。")
                    }
                    "Quilt" -> {
                        logger.warn("Quilt 自动安装尚未实现，跳过")
                        LoaderInstallOutcome(warning = "提示：该整合包声明 Quilt $loaderVersion，已完成文件导入；请在版本详情中安装对应 Quilt。")
                    }
                    else -> {
                        logger.warn("未知加载器类型: $loaderType，跳过")
                        LoaderInstallOutcome(warning = "提示：检测到未知加载器 $loaderType，已完成文件导入。")
                    }
                }
            } finally {
                progressJob.cancel()
            }
        }
    }

    private fun copyLoaderVersionAs(sourceId: String, targetId: String, minecraftDir: String, mcVersion: String) {
        val sourceDir = File(minecraftDir, "versions/$sourceId")
        val targetDir = File(minecraftDir, "versions/$targetId")
        if (!sourceDir.isDirectory || !targetDir.isDirectory) return
        val sourceJson = File(sourceDir, "$sourceId.json")
        val targetJson = File(targetDir, "$targetId.json")
        if (!sourceJson.isFile) return
        val root = json.parseToJsonElement(sourceJson.readText(Charsets.UTF_8)).jsonObject.toMutableMap()
        root["id"] = JsonPrimitive(targetId)
        root["inheritsFrom"] = JsonPrimitive(mcVersion)
        targetJson.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(root)), Charsets.UTF_8)
    }

    private fun extractOverridesByRoots(zip: ZipFile, instanceDir: File, roots: List<String>, logger: ImportLogger) {
        val normalizedRoots = roots
            .map { it.normalizeRootPrefix() }
            .filter { it.isNotBlank() }
            .distinct()
        var extracted = 0
        zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
            val normalized = entry.name.replace('\\', '/')
            val rel = normalizedRoots.firstNotNullOfOrNull { root ->
                if (normalized.startsWith(root, ignoreCase = true)) normalized.substring(root.length) else null
            } ?: return@forEach
            if (!isSafeRelativePath(rel)) return@forEach
            val out = File(instanceDir, rel)
            out.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            extracted++
        }
        logger.info("释放 overrides 文件完成: $extracted 个")
    }

    private fun extractGenericZip(zip: ZipFile, instanceDir: File, logger: ImportLogger) {
        val knownRoots = listOf(".minecraft/", "minecraft/", "overrides/")
        var extracted = 0
        zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
            val normalized = entry.name.replace('\\', '/')
            val rel = knownRoots.firstOrNull { normalized.startsWith(it, ignoreCase = true) }
                ?.let { normalized.substring(it.length) }
                ?: normalized
            if (!isSafeRelativePath(rel)) return@forEach
            if (rel.equals("modrinth.index.json", true) || rel.endsWith(".json", true) && "/" !in rel) return@forEach
            val out = File(instanceDir, rel)
            out.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            extracted++
        }
        logger.info("释放通用 ZIP 文件完成: $extracted 个")
    }

    private suspend fun downloadModrinthFiles(
        index: JsonObject,
        instanceDir: File,
        logger: ImportLogger,
        onProgress: (String, Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val files = index["files"]?.jsonArray ?: JsonArray(emptyList())
        val entries = files.mapNotNull { el ->
            val obj = el.jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val env = obj["env"]?.jsonObject
            if (env?.get("client")?.jsonPrimitive?.contentOrNull == "unsupported") return@mapNotNull null
            val urls = obj["downloads"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            if (urls.isEmpty()) return@mapNotNull null
            val hashes = obj["hashes"]?.jsonObject
            ModrinthRemoteFile(
                path = path,
                urls = urls,
                size = obj["fileSize"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                sha1 = hashes?.get("sha1")?.jsonPrimitive?.contentOrNull,
                sha512 = hashes?.get("sha512")?.jsonPrimitive?.contentOrNull,
            )
        }
        if (entries.isEmpty()) {
            logger.info("整合包未包含远程下载文件，跳过文件下载阶段")
            onProgress("整合包资源已就绪", 0.94f)
            return@withContext
        }

        onProgress("并发下载整合包资源 0/${entries.size}", 0f)
        val total = entries.size
        val finished = AtomicInteger(0)
        val sem = kotlinx.coroutines.sync.Semaphore(8)
        coroutineScope {
            entries.map { entry ->
                launch {
                    sem.acquire()
                    try {
                        val ok = downloadModrinthFile(entry, instanceDir, logger)
                        if (!ok) {
                            throw RuntimeException("资源下载失败: ${entry.path}")
                        }
                    } finally {
                        sem.release()
                        val done = finished.incrementAndGet()
                        val frac = done.toFloat() / total
                        onProgress("并发下载整合包资源 $done/$total: ${entry.path.substringAfterLast('/')}", frac.coerceIn(0f, 1f))
                    }
                }
            }.joinAll()
        }
    }

    private suspend fun downloadModrinthFile(entry: ModrinthRemoteFile, instanceDir: File, logger: ImportLogger): Boolean {
        if (!isSafeRelativePath(entry.path)) {
            logger.warn("检测到不安全路径，跳过: ${entry.path}")
            return true
        }
        val dest = File(instanceDir, entry.path)
        dest.parentFile?.mkdirs()

        if (dest.isFile && verifyDownloadedFile(dest, entry)) {
            return true
        }

        entry.urls.forEach { url ->
            logger.info("下载资源: ${entry.path} <- $url")
            dest.delete()
            val ok = runCatching {
                DownloadManager.downloadSingle(DownloadTask(url = url, dest = dest, size = entry.size)) { _, _, _ -> }
            }.getOrElse {
                logger.warn("下载异常: ${it.message}")
                false
            }
            if (ok && verifyDownloadedFile(dest, entry)) {
                return true
            }
        }

        dest.delete()
        logger.error("资源下载失败: ${entry.path}")
        return false
    }

    private fun verifyDownloadedFile(file: File, entry: ModrinthRemoteFile): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (entry.size > 0 && file.length() != entry.size) return false
        entry.sha512?.takeIf { it.isNotBlank() }?.let { expect ->
            return digest(file, "SHA-512").equals(expect, ignoreCase = true)
        }
        entry.sha1?.takeIf { it.isNotBlank() }?.let { expect ->
            return digest(file, "SHA-1").equals(expect, ignoreCase = true)
        }
        return true
    }

    private fun digest(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeMarker(versionId: String, packFile: File, mcVersion: String, instanceDir: File) {
        File(instanceDir.parentFile, ".modpack").writeText("name=$versionId\nsource=${packFile.absolutePath}\nminecraft=$mcVersion\n", Charsets.UTF_8)
    }

    private fun uniqueVersionId(minecraftDir: String, base: String): String {
        val versions = File(minecraftDir, "versions")
        versions.mkdirs()
        var id = base.ifBlank { "Imported Modpack" }
        var i = 2
        while (File(versions, id).exists()) {
            id = "$base $i"
            i++
        }
        return id
    }

    private fun String.sanitizeVersionName(): String {
        return replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Imported Modpack" }
    }

    private fun formatImportProgress(step: String, fraction: Float): String {
        val clamped = fraction.coerceIn(0f, 1f)
        val stages = listOf(
            "解析整合包" to 0.10f,
            "安装原版" to 0.46f,
            "安装加载器" to 0.62f,
            "释放整合包文件" to 0.70f,
            "下载整合包资源" to 0.94f,
            "收尾与刷新" to 1f,
        )
        val currentIndex = stages.indexOfFirst { clamped <= it.second }
            .let { if (it < 0) stages.lastIndex else it }
        val totalPercent = (clamped * 100).toInt().coerceIn(0, 100)

        val lines = stages.mapIndexed { index, (name, stageEnd) ->
            when {
                index < currentIndex -> "✓ ${index + 1}. $name"
                index > currentIndex -> "○ ${index + 1}. $name"
                else -> {
                    val stageStart = if (index == 0) 0f else stages[index - 1].second
                    val stageRange = (stageEnd - stageStart).coerceAtLeast(0.0001f)
                    val stagePercent = ((clamped - stageStart) / stageRange * 100f).toInt().coerceIn(0, 100)
                    "● ${index + 1}. $name $stagePercent%"
                }
            }
        }

        return buildString {
            append("总进度 $totalPercent% · $step")
            append('\n')
            append(lines.joinToString("\n"))
        }
    }

    private fun String.normalizeRootPrefix(): String {
        val clean = replace('\\', '/').trim().trimStart('/').trimEnd('/')
        return if (clean.isBlank()) "" else "$clean/"
    }

    private fun isSafeRelativePath(rel: String): Boolean {
        val normalized = rel.replace('\\', '/').trim()
        if (normalized.isBlank()) return false
        if (normalized.startsWith("/")) return false
        if (normalized.contains("../") || normalized.contains("..\\")) return false
        return true
    }

    private fun Throwable.stackTraceStringSafe(): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        this.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }

    private fun putText(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putDir(zip: ZipOutputStream, dir: File, basePath: String) {
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(dir).invariantSeparatorsPath
            zip.putNextEntry(ZipEntry("$basePath/$rel"))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}
