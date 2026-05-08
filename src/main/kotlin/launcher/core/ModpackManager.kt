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
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
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

        val effectivePackFile = resolveEffectivePackFile(packFile, logger)

        try {
            try {
                println("[ModpackImport] 步骤1: 解析整合包定义...")
                val definition = openZipWithFallbackEncoding(effectivePackFile).use { zip ->
                    parsePackDefinition(zip, packFile, logger)
                }
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
                extractPackFilesWithRetry(effectivePackFile, definition, instanceDir, logger)

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
            } catch (e: Exception) {
                logger.error("导入失败: ${e.message}", e)
                println("[ModpackImport] ====== 导入失败: ${e.message} ======")
                e.printStackTrace()
                "整合包导入失败: ${e.message}\n日志: ${logger.path}"
            }
        } finally {
            if (effectivePackFile.absolutePath != packFile.absolutePath) {
                runCatching { effectivePackFile.delete() }
            }
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
        findEntryAtRootOrFirstLevel(zip, "modrinth.index.json")?.let { (indexEntry, baseFolder) ->
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
                overridesRoots = listOf("${baseFolder}overrides/", "${baseFolder}client-overrides/"),
                modrinthIndex = index,
            )
        }

        val manifestPair = findEntryAtRootOrFirstLevel(zip, "manifest.json")
        if (manifestPair != null) {
            val (manifestEntry, baseFolder) = manifestPair
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
                    overridesRoots = listOf("$baseFolder$overrides"),
                )
            }
            logger.warn("检测到 manifest.json 但结构不是 CurseForge，回退通用 ZIP 解析")
        }

        val mcVersion = detectMcVersionForGenericZip(zip, packFile)
            ?: throw RuntimeException("ZIP 整合包缺少可识别的 Minecraft 版本")
        val (loaderType, loaderVersion) = detectLoaderForGenericZip(zip, packFile, mcVersion)
        if (!loaderType.isNullOrBlank() && !loaderVersion.isNullOrBlank()) {
            logger.info("通用 ZIP 推断加载器: $loaderType $loaderVersion")
        }
        val name = packFile.nameWithoutExtension
        return PackDefinition(
            kind = PackKind.GenericZip,
            displayName = name,
            mcVersion = mcVersion,
            loaderType = loaderType,
            loaderVersion = loaderVersion,
            overridesRoots = listOf("overrides/", ".minecraft/", "minecraft/"),
        )
    }

    private fun resolveEffectivePackFile(packFile: File, logger: ImportLogger): File {
        if (!packFile.extension.equals("zip", ignoreCase = true)) return packFile
        return runCatching {
            openZipWithFallbackEncoding(packFile).use { outerZip ->
                val hasDirectDefinition =
                    findEntryAtRootOrFirstLevel(outerZip, "modrinth.index.json") != null ||
                        findEntryAtRootOrFirstLevel(outerZip, "manifest.json") != null
                if (hasDirectDefinition) return@use packFile

                val nestedMrpack = findNestedMrpackEntry(outerZip) ?: return@use packFile
                val cacheDir = File(System.getProperty("java.io.tmpdir"), "md3l-pack-cache")
                cacheDir.mkdirs()
                val extracted = File.createTempFile("md3l-inner-", ".mrpack", cacheDir)
                outerZip.getInputStream(nestedMrpack).use { input ->
                    extracted.outputStream().use { input.copyTo(it) }
                }

                val valid = runCatching {
                    openZipWithFallbackEncoding(extracted).use { innerZip ->
                        findEntryAtRootOrFirstLevel(innerZip, "modrinth.index.json") != null
                    }
                }.getOrDefault(false)
                if (!valid) {
                    extracted.delete()
                    return@use packFile
                }

                logger.info("检测到外层 ZIP 内嵌 mrpack: ${nestedMrpack.name}，切换为 mrpack 导入")
                println("[ModpackImport] 检测到内嵌 mrpack: ${nestedMrpack.name}，切换为 mrpack 导入")
                extracted
            }
        }.getOrElse { ex ->
            logger.warn("内嵌 mrpack 检测失败，按原始 ZIP 继续导入: ${ex.message}")
            packFile
        }
    }

    private fun findNestedMrpackEntry(zip: ZipFile): ZipEntry? {
        findEntryAtRootOrFirstLevel(zip, "modpack.mrpack")?.let { return it.first }
        return zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".mrpack", ignoreCase = true) }
            .filter {
                val normalized = it.name.replace('\\', '/').trim('/').trim()
                normalized.count { ch -> ch == '/' } <= 1
            }
            .sortedBy { it.name.length }
            .firstOrNull()
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

    private fun detectLoaderForGenericZip(zip: ZipFile, packFile: File, mcVersion: String): Pair<String?, String?> {
        detectLoaderFromText(packFile.nameWithoutExtension, mcVersion)?.let { return it }

        zip.entries().asSequence()
            .filter { !it.isDirectory }
            .take(120)
            .forEach { entry ->
                detectLoaderFromText(entry.name, mcVersion)?.let { return it }
            }

        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
            .take(50)
            .forEach { entry ->
                val text = runCatching { zip.getInputStream(entry).bufferedReader().readText() }.getOrNull() ?: return@forEach
                detectLoaderFromText(text, mcVersion)?.let { return it }
            }

        return null to null
    }

    private fun detectLoaderFromText(text: String, mcVersion: String): Pair<String, String>? {
        fun cleanVersion(loader: String, raw: String): String {
            var value = raw.trim().trim('"', '\'', ' ', '-', '_')
            if (loader == "Forge") {
                value = value
                    .removePrefix("$mcVersion-")
                    .removePrefix("${mcVersion}_")
                    .removePrefix(mcVersion)
                    .trimStart('-', '_')
            }
            return value
        }

        val neoPatterns = listOf(
            Regex("""(?i)neoforge[_\-\s]*([0-9][0-9a-zA-Z._+\-]*)"""),
            Regex("""(?i)--fml\.neoForgeVersion[^0-9a-zA-Z]+([0-9][0-9a-zA-Z._+\-]*)"""),
            Regex("""(?i)net\.neoforged:neoforge:([0-9][0-9a-zA-Z._+\-]*)"""),
        )
        neoPatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            ?.let { raw ->
                val ver = cleanVersion("NeoForge", raw)
                if (ver.isNotBlank()) return "NeoForge" to ver
            }

        val forgePatterns = listOf(
            Regex("""(?i)net\.minecraftforge:forge:[0-9.]+-([0-9][0-9a-zA-Z._+\-]*)"""),
            Regex("""(?i)(?:^|[^a-z])forge[_\-\s]*([0-9][0-9a-zA-Z._+\-]*)"""),
        )
        forgePatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            ?.let { raw ->
                val ver = cleanVersion("Forge", raw)
                if (ver.isNotBlank()) return "Forge" to ver
            }

        val fabric = Regex("""(?i)fabric(?:-loader)?[_\-\s:]*([0-9][0-9a-zA-Z._+\-]*)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
        if (!fabric.isNullOrBlank()) {
            val ver = cleanVersion("Fabric", fabric)
            if (ver.isNotBlank()) return "Fabric" to ver
        }

        val quilt = Regex("""(?i)quilt(?:-loader)?[_\-\s:]*([0-9][0-9a-zA-Z._+\-]*)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
        if (!quilt.isNullOrBlank()) {
            val ver = cleanVersion("Quilt", quilt)
            if (ver.isNotBlank()) return "Quilt" to ver
        }

        return null
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

            if (ensureVanillaAliasFromImportedVersions(remote.id, minecraftDir, logger) &&
                deriveVersionFromInstalledVanilla(remote.id, minecraftDir, versionId, logger)
            ) {
                logger.info("已通过非标准原版目录兜底构建导入版本: $versionId <- ${remote.id}")
                onProgress("已识别本地原版并完成兜底", 0.46f)
                return true
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

    private fun ensureVanillaAliasFromImportedVersions(
        mcVersion: String,
        minecraftDir: String,
        logger: ImportLogger,
    ): Boolean {
        val standardDir = File(minecraftDir, "versions/$mcVersion")
        val standardJson = File(standardDir, "$mcVersion.json")
        val standardJar = File(standardDir, "$mcVersion.jar")
        if (standardJson.isFile && standardJar.isFile && standardJar.length() > 0L) return true

        val versionsDir = File(minecraftDir, "versions")
        if (!versionsDir.isDirectory) return false

        val candidate = versionsDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val jsonFile = File(dir, "${dir.name}.json").takeIf { it.isFile }
                    ?: dir.listFiles()?.firstOrNull { it.isFile && it.extension.equals("json", ignoreCase = true) }
                    ?: return@mapNotNull null
                val root = runCatching { json.parseToJsonElement(jsonFile.readText(Charsets.UTF_8)).jsonObject }.getOrNull()
                    ?: return@mapNotNull null
                val id = root["id"]?.jsonPrimitive?.contentOrNull ?: dir.name
                if (id != mcVersion) return@mapNotNull null
                val jar = listOf(
                    File(dir, "$mcVersion.jar"),
                    File(dir, "${dir.name}.jar"),
                ).firstOrNull { it.isFile && it.length() > 0L }
                    ?: dir.listFiles()?.firstOrNull { it.isFile && it.extension.equals("jar", ignoreCase = true) && it.length() > 0L }
                    ?: return@mapNotNull null
                Triple(dir, jsonFile, jar)
            }
            ?.firstOrNull()
            ?: return false

        return runCatching {
            standardDir.mkdirs()
            candidate.second.copyTo(standardJson, overwrite = true)
            candidate.third.copyTo(standardJar, overwrite = true)
            logger.info("已建立原版别名: ${candidate.first.name} -> $mcVersion")
            true
        }.onFailure {
            logger.warn("建立原版别名失败: ${it.message}")
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
                        val nameMatch = ("net.minecraftforge" in text || "forge" in dir.name.lowercase()) && loaderVersion in text && mcVersion in text
                        if (!nameMatch) return@mapNotNull null
                        // 验证 Forge 核心 JAR 存在，防止复用残缺安装
                        val forgeJarCandidates = listOf(
                            File(minecraftDir, "libraries/net/minecraftforge/forge/$mcVersion-$loaderVersion/forge-$mcVersion-$loaderVersion-universal.jar"),
                            File(minecraftDir, "libraries/net/minecraftforge/forge/$mcVersion-$loaderVersion/forge-$mcVersion-$loaderVersion-client.jar"),
                            File(minecraftDir, "libraries/net/minecraftforge/forge/$mcVersion-$loaderVersion/forge-$mcVersion-$loaderVersion.jar"),
                        )
                        if (forgeJarCandidates.any { it.isFile && it.length() > 0 }) dir.name else null
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
            ensureForgeLikeLoaderReadyDuringImport(
                loaderType = loaderType,
                installedVersionId = existingId,
                minecraftDir = minecraftDir,
                javaPath = javaPath,
                logger = logger,
                onLoaderProgress = onLoaderProgress,
            )
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
                        ensureForgeLikeLoaderReadyDuringImport(
                            loaderType = loaderType,
                            installedVersionId = id,
                            minecraftDir = minecraftDir,
                            javaPath = javaPath,
                            logger = logger,
                            onLoaderProgress = onLoaderProgress,
                        )
                        println("[ModpackImport] NeoForge 安装完成, id=$id")
                        LoaderInstallOutcome(installedVersionId = id)
                    }
                    "Forge" -> {
                        println("[ModpackImport] 调用 LoaderInstaller.installForge($mcVersion, $loaderVersion)")
                        val id = runCatching {
                            LoaderInstaller.installForge(
                                mcVersion = mcVersion,
                                loaderVersion = loaderVersion,
                                forgeBuild = 0,
                                minecraftDir = minecraftDir,
                                baseVersionId = baseVersionId,
                                javaPath = javaPath,
                            )
                        }.getOrElse { error ->
                            println("[ModpackImport] Forge 安装异常: ${error.message}")
                            findExistingLoaderVersionId(loaderType, mcVersion, loaderVersion, minecraftDir)
                                ?.also { logger.warn("Forge 安装异常，回收已存在加载器: ${error.message}") }
                                ?: throw error
                        }
                        ensureForgeLikeLoaderReadyDuringImport(
                            loaderType = loaderType,
                            installedVersionId = id,
                            minecraftDir = minecraftDir,
                            javaPath = javaPath,
                            logger = logger,
                            onLoaderProgress = onLoaderProgress,
                        )
                        println("[ModpackImport] Forge 安装完成, id=$id")
                        LoaderInstallOutcome(installedVersionId = id)
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

    private suspend fun ensureForgeLikeLoaderReadyDuringImport(
        loaderType: String,
        installedVersionId: String,
        minecraftDir: String,
        javaPath: String,
        logger: ImportLogger,
        onLoaderProgress: (String, Float) -> Unit,
    ) {
        if (loaderType != "Forge" && loaderType != "NeoForge") return

        val versionJsonFile = File(minecraftDir, "versions/$installedVersionId/$installedVersionId.json")
        if (!versionJsonFile.isFile) {
            throw RuntimeException("$loaderType 安装不完整：缺少 ${versionJsonFile.absolutePath}")
        }

        logger.info("导入阶段校验 $loaderType 完整性: $installedVersionId")
        onLoaderProgress("校验 $loaderType 安装完整性", 0.92f)
        val repaired = LoaderInstaller.repairForgeIfNeeded(
            versionJsonFile = versionJsonFile,
            minecraftDir = minecraftDir,
            javaPath = javaPath,
            onProgress = { msg ->
                logger.info(msg)
                onLoaderProgress(msg, 0.95f)
            },
        )
        if (!repaired) {
            throw RuntimeException("$loaderType 安装不完整且修复失败，请在导入后重新安装该加载器")
        }
        onLoaderProgress("$loaderType 安装校验完成", 1f)
    }

    private fun copyLoaderVersionAs(sourceId: String, targetId: String, minecraftDir: String, mcVersion: String) {
        val sourceDir = File(minecraftDir, "versions/$sourceId")
        val targetDir = File(minecraftDir, "versions/$targetId")
        if (!sourceDir.isDirectory || !targetDir.isDirectory) return
        val sourceJson = File(sourceDir, "$sourceId.json")
        val targetJson = File(targetDir, "$targetId.json")
        if (!sourceJson.isFile) return

        // 覆盖前：确保原版 mcVersion 目录存在，否则 inheritsFrom 链会断裂。
        // 原版 JSON/jar 可能只存在于 targetId 目录下（installBaseVersionRobust 以 customName 安装），
        // 需要在覆盖前将其复制到 versions/<mcVersion>/ 下。
        ensureVanillaVersionExists(minecraftDir, mcVersion, targetId)

        val root = json.parseToJsonElement(sourceJson.readText(Charsets.UTF_8)).jsonObject.toMutableMap()
        root["id"] = JsonPrimitive(targetId)
        root["inheritsFrom"] = JsonPrimitive(mcVersion)
        targetJson.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(root)), Charsets.UTF_8)
    }

    private fun ensureVanillaVersionExists(minecraftDir: String, mcVersion: String, donorVersionId: String) {
        val vanillaDir = File(minecraftDir, "versions/$mcVersion")
        val vanillaJson = File(vanillaDir, "$mcVersion.json")
        val vanillaJar = File(vanillaDir, "$mcVersion.jar")

        // 如果原版目录已完整则无需处理
        if (vanillaJson.isFile && vanillaJar.isFile && vanillaJar.length() > 0L) return

        val donorDir = File(minecraftDir, "versions/$donorVersionId")
        val donorJson = File(donorDir, "$donorVersionId.json")
        val donorJar = File(donorDir, "$donorVersionId.jar")

        vanillaDir.mkdirs()

        // 复制 JSON（改 id 为 mcVersion，去掉 inheritsFrom）
        if (!vanillaJson.isFile && donorJson.isFile) {
            runCatching {
                val root = json.parseToJsonElement(donorJson.readText(Charsets.UTF_8)).jsonObject.toMutableMap()
                root["id"] = JsonPrimitive(mcVersion)
                root.remove("inheritsFrom")
                vanillaJson.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(root)), Charsets.UTF_8)
                println("[ModpackImport] 创建原版 JSON: ${vanillaJson.absolutePath}")
            }
        }

        // 复制或硬链接 jar
        if ((!vanillaJar.isFile || vanillaJar.length() <= 0L) && donorJar.isFile && donorJar.length() > 0L) {
            runCatching {
                donorJar.copyTo(vanillaJar, overwrite = true)
                println("[ModpackImport] 复制原版 JAR: ${vanillaJar.absolutePath}")
            }
        }
    }

    private fun extractPackFilesWithRetry(packFile: File, definition: PackDefinition, instanceDir: File, logger: ImportLogger) {
        var retryCount = 1
        while (true) {
            runCatching {
                openZipWithFallbackEncoding(packFile).use { zip ->
                    when (definition.kind) {
                        PackKind.Modrinth, PackKind.CurseForge -> {
                            extractOverridesByRoots(zip, instanceDir, definition.overridesRoots, logger)
                        }
                        PackKind.GenericZip -> {
                            extractGenericZip(zip, instanceDir, logger)
                        }
                    }
                }
            }.onSuccess {
                return
            }.onFailure { ex ->
                logger.warn("第 $retryCount 次解压尝试失败: ${ex.message}")
                if (retryCount >= 5) {
                    throw RuntimeException("解压整合包文件失败", ex)
                }
                Thread.sleep(retryCount * 2000L)
                retryCount++
            }
        }
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
            val out = safeResolveOutputFile(instanceDir, rel) ?: return@forEach
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
            val out = safeResolveOutputFile(instanceDir, rel) ?: return@forEach
            if (rel.equals("modrinth.index.json", true) || rel.endsWith(".json", true) && "/" !in rel) return@forEach
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
        val failedEntries = java.util.concurrent.ConcurrentLinkedQueue<ModrinthRemoteFile>()
        val sem = kotlinx.coroutines.sync.Semaphore(8)
        coroutineScope {
            entries.map { entry ->
                launch {
                    sem.acquire()
                    try {
                        val ok = downloadModrinthFile(entry, instanceDir, logger)
                        if (!ok) {
                            failedEntries.add(entry)
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

        // 对失败的文件进行第二轮重试（降低并发，增加超时容忍）
        if (failedEntries.isNotEmpty()) {
            logger.warn("第一轮有 ${failedEntries.size} 个资源下载失败，进行重试...")
            onProgress("第一轮完成，正在重试失败资源 0/${failedEntries.size}", 1f)
            val retryList = failedEntries.toList()
            val retryFailed = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val retryDone = AtomicInteger(0)
            val retrySem = kotlinx.coroutines.sync.Semaphore(4)
            coroutineScope {
                retryList.map { entry ->
                    launch {
                        retrySem.acquire()
                        try {
                            val ok = downloadModrinthFile(entry, instanceDir, logger, timeoutMs = 120_000L)
                            if (!ok) retryFailed.add(entry.path)
                        } finally {
                            retrySem.release()
                            val done = retryDone.incrementAndGet()
                            onProgress("重试失败资源 $done/${retryList.size}: ${entry.path.substringAfterLast('/')}", 1f)
                        }
                    }
                }.joinAll()
            }
            if (retryFailed.isNotEmpty()) {
                throw RuntimeException("资源下载失败 ${retryFailed.size} 个: ${retryFailed.joinToString(", ")}")
            }
        }
    }

    private suspend fun downloadModrinthFile(
        entry: ModrinthRemoteFile,
        instanceDir: File,
        logger: ImportLogger,
        timeoutMs: Long = 90_000L,
    ): Boolean {
        if (!isSafeRelativePath(entry.path)) {
            logger.warn("检测到不安全路径，跳过: ${entry.path}")
            return true
        }
        val dest = File(instanceDir, entry.path)
        dest.parentFile?.mkdirs()

        if (dest.isFile && verifyDownloadedFile(dest, entry)) {
            return true
        }

        val allUrls = buildModrinthCandidateUrls(entry)

        for (url in allUrls) {
            for (attempt in 1..2) {
                logger.info("下载资源(尝试 $attempt/2): ${entry.path} <- $url")
                dest.delete()
                val ok = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                        DownloadManager.downloadSingle(DownloadTask(url = url, dest = dest, size = entry.size)) { _, _, _ -> }
                    } ?: run {
                        logger.warn("下载超时 (${timeoutMs / 1000}s): ${entry.path} <- $url")
                        false
                    }
                }.getOrElse {
                    logger.warn("下载异常: ${it.message}")
                    false
                }
                if (ok && verifyDownloadedFile(dest, entry)) {
                    return true
                }
                kotlinx.coroutines.delay(250L * attempt)
            }
        }

        dest.delete()
        logger.error("资源下载失败: ${entry.path}")
        return false
    }

    private fun buildModrinthCandidateUrls(entry: ModrinthRemoteFile): List<String> {
        val candidates = linkedSetOf<String>()
        entry.urls.forEach { raw ->
            if (raw.isBlank()) return@forEach
            candidates.add(raw)

            if ("%2B" in raw || "%2b" in raw) {
                candidates.add(raw.replace("%2B", "+").replace("%2b", "+"))
            }

            if (raw.contains("cdn.modrinth.com", ignoreCase = true)) {
                val suffix = raw.substringAfter("https://cdn.modrinth.com")
                candidates.add("https://bmclapi2.bangbang93.com/modrinth$suffix")
            }
        }

        entry.sha1?.takeIf { it.isNotBlank() }?.let { sha1 ->
            candidates.add("https://api.modrinth.com/v2/version_file/$sha1/download?algorithm=sha1")
            candidates.add("https://bmclapi2.bangbang93.com/modrinth/v2/version_file/$sha1/download?algorithm=sha1")
        }

        return candidates.toList()
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

    private fun findEntryAtRootOrFirstLevel(zip: ZipFile, fileName: String): Pair<java.util.zip.ZipEntry, String>? {
        val normalizedTarget = fileName.replace('\\', '/').trimStart('/').trimEnd('/')

        zip.entries().asSequence().firstOrNull { entry ->
            !entry.isDirectory && entry.name.replace('\\', '/').equals(normalizedTarget, ignoreCase = true)
        }?.let { return it to "" }

        zip.entries().asSequence().forEach { entry ->
            if (entry.isDirectory) return@forEach
            val normalized = entry.name.replace('\\', '/').trimStart('/').trimEnd('/')
            val slashIndex = normalized.indexOf('/')
            if (slashIndex <= 0) return@forEach
            if (normalized.indexOf('/', slashIndex + 1) >= 0) return@forEach

            val first = normalized.substring(0, slashIndex)
            val child = normalized.substring(slashIndex + 1)
            if (child.equals(normalizedTarget, ignoreCase = true)) {
                return entry to "$first/"
            }
        }
        return null
    }

    private fun safeResolveOutputFile(rootDir: File, relativePath: String): File? {
        if (!isSafeRelativePath(relativePath)) return null
        val normalizedRoot = rootDir.toPath().toAbsolutePath().normalize()
        val normalizedTarget = normalizedRoot.resolve(relativePath).normalize()
        if (!normalizedTarget.startsWith(normalizedRoot)) return null
        return normalizedTarget.toFile()
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

    /**
     * 打开 ZIP 文件，自动检测编码。
     * 优先 UTF-8，失败后尝试 GB18030。
     * 对齐 PCL 的整合包解压策略。
     */
    private fun openZipWithFallbackEncoding(file: File): ZipFile {
        fun openAndScan(charset: Charset): ZipFile {
            val zf = ZipFile(file, charset)
            zf.entries().asSequence().forEach { _ -> }
            return zf
        }

        return try {
            openAndScan(StandardCharsets.UTF_8)
        } catch (first: Exception) {
            println("[ModpackImport] UTF-8 解压尝试失败，改用 GB18030 重试: ${first.message}")
            val gb18030 = runCatching { Charset.forName("GB18030") }.getOrElse { throw first }
            try {
                openAndScan(gb18030)
            } catch (second: Exception) {
                second.addSuppressed(first)
                throw second
            }
        }
    }
}
