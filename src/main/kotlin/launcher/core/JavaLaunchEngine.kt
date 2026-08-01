package launcher.core

import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class JavaLaunchEngine : ILaunchEngine {

    private val json = Json { ignoreUnknownKeys = true }

    private var cachedAssetsIndex: String = ""
    var lastLogFile: File? = null
        private set

    var skinServer: OfflineSkinServer? = null
        private set

    override fun execute(context: LaunchContext): Process {
        val versionJsonFile = File(context.versionDir, "${context.version.id}.json")
        val root = json.parseToJsonElement(versionJsonFile.readText(Charsets.UTF_8)).jsonObject

        preflightNeoForgeIntegrity(root, context, versionJsonFile)

        val inheritanceRoots = resolveInheritanceRoots(root, context.minecraftDir)

        cachedAssetsIndex = context.version.assetsIndex.ifBlank {
            inheritanceRoots.asReversed().firstNotNullOfOrNull { parent ->
                parent["assets"]?.jsonPrimitive?.contentOrNull
                    ?: parent["assetIndex"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: parent["id"]?.jsonPrimitive?.contentOrNull
            }
                ?: context.version.id
        }
        if (cachedAssetsIndex.length > 20 || cachedAssetsIndex.contains(" ", ignoreCase = true)) {
            val fallback = inheritanceRoots.asReversed().firstNotNullOfOrNull { parent ->
                parent["assets"]?.jsonPrimitive?.contentOrNull
                    ?: parent["assetIndex"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            } ?: run {
                inheritanceRoots.asReversed().firstNotNullOfOrNull { parent ->
                    parent["id"]?.jsonPrimitive?.contentOrNull?.let { id ->
                        """\d+\.\d+(\.\d+)?""".toRegex().find(id)?.value
                    }
                }
            }
            if (fallback != null) {
                println("[Launch] assetIndex 修正: '$cachedAssetsIndex' -> '$fallback'")
                cachedAssetsIndex = fallback
            }
        }

        val mcVersionStr = resolveMcVersionString(root, inheritanceRoots, cachedAssetsIndex)
        val (mcMajor, mcMinor, mcPatch) = parseMcVersion(mcVersionStr)
        println("[SkinServer] MC版本: $mcVersionStr → ($mcMajor, $mcMinor, $mcPatch), 皮肤模型: ${context.skinModel}")


        val nativesDir = context.nativesDir
        if (!nativesDir.exists()) nativesDir.mkdirs()

        val inheritsFrom = root["inheritsFrom"]?.jsonPrimitive?.contentOrNull
        if (!inheritsFrom.isNullOrBlank()) {
            val parentNatives = File(context.minecraftDir, "versions/$inheritsFrom/natives")
            if (parentNatives.isDirectory) {
                parentNatives.listFiles()?.forEach { f ->
                    val dest = File(nativesDir, f.name)
                    if (!dest.exists() && f.isFile) {
                        runCatching { f.copyTo(dest, overwrite = false) }
                    }
                }
            }
        }

        val isolatedDir = context.gameDir
        if (!isolatedDir.exists()) isolatedDir.mkdirs()
        File(isolatedDir, "mods").mkdirs()
        File(isolatedDir, "saves").mkdirs()
        File(isolatedDir, "resourcepacks").mkdirs()
        File(isolatedDir, "config").mkdirs()
        File(isolatedDir, "shaderpacks").mkdirs()
        ensureOptions(isolatedDir)

        ensureOfflineSkin(context, isolatedDir)

        val mainClass = resolveMainClass(root, inheritanceRoots)
        val classPath = buildClassPath(root, context, inheritanceRoots)
        val gameArgs = buildGameArguments(root, context, inheritanceRoots)
        val jvmArgs = buildJvmArguments(root, context, classPath, inheritanceRoots)

        val command = mutableListOf<String>().apply {
            add(context.javaPath)
            addAll(jvmArgs)
            add(mainClass)
            addAll(gameArgs)
        }

        val pb = ProcessBuilder(command)
            .directory(isolatedDir)
            .redirectErrorStream(true)
        pb.environment()["APPDATA"] = File(context.minecraftDir).parent ?: context.rootGameDir.absolutePath

        val logFile = File(LauncherDirs.javaLogDir, "launch-${context.version.id}-${java.time.LocalDateTime.now().toString().replace(':', '-')}.log")
        logFile.parentFile?.mkdirs()
        lastLogFile = logFile
        val logLines = buildString {
            appendLine("═══ MD3L Launch Debug ═══")
            appendLine("Time: ${java.time.LocalDateTime.now()}")
            appendLine("Version: ${context.version.id}")
            appendLine("Java: ${context.javaPath}")
            appendLine("MainClass: $mainClass")
            appendLine("GameDir: ${isolatedDir.absolutePath}")
            appendLine("APPDATA: ${File(context.minecraftDir).parent ?: context.rootGameDir.absolutePath}")
            appendLine("Memory: ${context.memoryMb}MB")
            appendLine()
            appendLine("── JVM Arguments (${jvmArgs.size}) ──")
            jvmArgs.forEachIndexed { i, a -> appendLine("  [$i] $a") }
            appendLine()
            appendLine("── Game Arguments (${gameArgs.size}) ──")
            gameArgs.forEachIndexed { i, a -> appendLine("  [$i] ${maskSensitiveArg(a, context)}") }
            appendLine()
            appendLine("CommandArgCount: ${command.size}")
            appendLine("ClasspathJarCount: ${classPath.split(File.pathSeparator).count { it.isNotBlank() }}")
        }
        logFile.writeText(logLines, Charsets.UTF_8)
        println("[JavaLaunch] debug log → ${logFile.absolutePath}")
        println("[JavaLaunch] ${context.version.id}: args=${command.size}, cpJars=${classPath.split(File.pathSeparator).count { it.isNotBlank() }}")

        return pb.start()
    }

    private fun preflightNeoForgeIntegrity(root: JsonObject, context: LaunchContext, versionJsonFile: File) {
        val text = versionJsonFile.readText(Charsets.UTF_8)
        if ("net.neoforged" !in text && "neoforge" !in versionJsonFile.nameWithoutExtension.lowercase()) return

        val gameArgs = root["arguments"]?.jsonObject?.get("game")?.jsonArray ?: return
        val neoForgeVersion = gameArgs.mapIndexedNotNull { i, el ->
            if (el is JsonPrimitive && el.content == "--fml.neoForgeVersion")
                (gameArgs.getOrNull(i + 1) as? JsonPrimitive)?.content
            else null
        }.firstOrNull() ?: return
        val mcVersion = gameArgs.mapIndexedNotNull { i, el ->
            if (el is JsonPrimitive && el.content == "--fml.mcVersion")
                (gameArgs.getOrNull(i + 1) as? JsonPrimitive)?.content
            else null
        }.firstOrNull() ?: return
        val neoFormVersion = gameArgs.mapIndexedNotNull { i, el ->
            if (el is JsonPrimitive && el.content == "--fml.neoFormVersion")
                (gameArgs.getOrNull(i + 1) as? JsonPrimitive)?.content
            else null
        }.firstOrNull()

        val librariesDir = context.librariesDir
        val clientJar = File(librariesDir, "net/neoforged/neoforge/$neoForgeVersion/neoforge-$neoForgeVersion-client.jar")
        val srgJar = if (neoFormVersion != null) {
            File(librariesDir, "net/minecraft/client/$mcVersion-$neoFormVersion/client-$mcVersion-$neoFormVersion-srg.jar")
        } else null

        val clientCorrupt = clientJar.isFile && !isJarWithClasses(clientJar)
        val srgCorrupt = srgJar != null && srgJar.isFile && !isJarWithClasses(srgJar)

        if (!clientCorrupt && !srgCorrupt) return

        println("[Launch] NeoForge processor 产物损坏检测：client=${clientCorrupt}, srg=${srgCorrupt}")
        println("[Launch] 自动触发修复...")

        if (clientCorrupt) clientJar.delete()
        if (srgCorrupt) srgJar?.delete()

        val repaired = kotlinx.coroutines.runBlocking {
            LoaderInstaller.repairForgeIfNeeded(
                versionJsonFile = versionJsonFile,
                minecraftDir = context.minecraftDir,
                javaPath = context.javaPath,
                onProgress = { println("[Launch/Repair] $it") },
            )
        }
        if (!repaired) {
            throw RuntimeException(
                "NeoForge 安装损坏且自动修复失败。\n" +
                "请删除该版本后重新导入整合包，或手动重新安装 NeoForge $neoForgeVersion。"
            )
        }
        println("[Launch] NeoForge 自动修复完成")
    }

    private fun isJarWithClasses(file: File): Boolean {
        if (!file.isFile || file.length() <= 1000) return false
        return runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    if (entries.nextElement().name.endsWith(".class")) return@use true
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun ensureOptions(gameDir: File) {
        val optionsFile = File(gameDir, "options.txt")
        if (!optionsFile.exists()) {
            optionsFile.parentFile?.mkdirs()
            optionsFile.writeText("lang:zh_cn\nresourcePacks:[\"vanilla\"]\n", Charsets.UTF_8)
            return
        }
        val lines = optionsFile.readLines(Charsets.UTF_8).toMutableList()
        val langIdx = lines.indexOfFirst { it.startsWith("lang:") }
        if (langIdx >= 0) {
            if (lines[langIdx].lowercase() != "lang:zh_cn") {
                lines[langIdx] = "lang:zh_cn"
                optionsFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
            }
        } else {
            lines.add("lang:zh_cn")
            optionsFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        }
        if (lines.none { it.startsWith("resourcePacks:") }) {
            lines.add("resourcePacks:[\"vanilla\"]")
            optionsFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        }
    }

    private fun ensureOfflineSkin(context: LaunchContext, gameDir: File) {
        val skinFile = File(context.skinUri)

        val oldPackFile = File(gameDir, "resourcepacks/MD3L_Offline_Skin.zip")
        if (oldPackFile.exists()) {
            oldPackFile.delete()
            removeResourcePackFromOptions(gameDir, oldPackFile.name)
            println("[SkinServer] 已清理旧版皮肤资源包: ${oldPackFile.absolutePath}")
        }

        if (context.accessToken != "0" || !skinFile.isFile) {
            stopSkinServer()
            return
        }

        val server = OfflineSkinServer()
        server.start()
        skinServer = server

        val isSlim = context.skinModel == "slim"
        server.addCharacter(
            uuid = context.uuid,
            name = context.playerName,
            skinFile = skinFile,
            isSlim = isSlim
        )
        println("[SkinServer] 离线皮肤服务器已启动: ${server.rootUrl}, 角色=${context.playerName}, slim=$isSlim")
    }

    fun stopSkinServer() {
        skinServer?.stop()
        skinServer = null
    }

    private fun createSkinResourcePack(packFile: File, skinFile: File, isSlim: Boolean, mcMajor: Int, mcMinor: Int, mcPatch: Int) {
        packFile.parentFile?.mkdirs()
        val packFormat = calculatePackFormat(mcMajor, mcMinor, mcPatch)
        val effectiveIsSlim = isSlim && mcMajor >= 8
        val subfolder = if (effectiveIsSlim) "slim" else "wide"
        ZipOutputStream(packFile.outputStream()).use { zip ->
            fun putText(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            fun putFile(path: String) {
                zip.putNextEntry(ZipEntry(path))
                if (mcMajor <= 7) {
                    val cropped = runCatching {
                        val img = javax.imageio.ImageIO.read(skinFile)
                        if (img != null && img.width == 64 && img.height == 64) {
                            val top = img.getSubimage(0, 0, 64, 32)
                            javax.imageio.ImageIO.write(top, "png", zip)
                            true
                        } else false
                    }.getOrDefault(false)
                    if (!cropped) skinFile.inputStream().use { it.copyTo(zip) }
                } else {
                    skinFile.inputStream().use { it.copyTo(zip) }
                }
                zip.closeEntry()
            }
            val sf = if (packFormat >= 46) {
                """{"min_inclusive":$packFormat,"max_inclusive":$packFormat}"""
            } else {
                """[$packFormat,$packFormat]"""
            }
            putText("pack.mcmeta", """{"pack":{"pack_format":$packFormat,"supported_formats":$sf,"description":"MD3L Offline Skin"}}""")
            when {
                mcMajor == 0 || mcMajor > 19 || (mcMajor == 19 && mcMinor >= 3) -> {
                    val playerNames = listOf("alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri")
                    for (name in playerNames) {
                        putFile("assets/minecraft/textures/entity/player/$subfolder/$name.png")
                    }
                }
                mcMajor >= 13 -> {
                    putFile("assets/minecraft/textures/entity/player/$subfolder/steve.png")
                    putFile("assets/minecraft/textures/entity/player/$subfolder/alex.png")
                }
                mcMajor >= 8 -> {
                    putFile("assets/minecraft/textures/entity/steve.png")
                    putFile("assets/minecraft/textures/entity/alex.png")
                }
                mcMajor == 7 -> {
                    putFile("assets/minecraft/textures/entity/steve.png")
                }
                else -> {
                    putFile("assets/minecraft/textures/entity/steve.png")
                }
            }
        }
        val knownPacksFile = File(packFile.parentFile?.parentFile, "known_packs.json")
        if (knownPacksFile.exists()) {
            knownPacksFile.delete()
            println("[SkinPack] 已清理 known_packs.json 缓存: ${knownPacksFile.absolutePath}")
        }
        println("[SkinPack] 已创建离线皮肤资源包 (pack_format=$packFormat, model=$subfolder, mc=$mcMajor.$mcMinor.$mcPatch): ${packFile.absolutePath} (size=${packFile.length()})")
    }

    private fun rebuildResourcePacksLine(gameDir: File, packName: String, mcMajor: Int) {
        val optionsFile = File(gameDir, "options.txt")
        val allLines = if (optionsFile.exists()) optionsFile.readLines(Charsets.UTF_8).toMutableList()
                        else mutableListOf()
        val key = "resourcePacks"
        val useModernFormat = mcMajor == 0 || mcMajor >= 13
        val packEntry = if (useModernFormat) "file/$packName" else packName

        val idx = allLines.indexOfFirst { it.startsWith("$key:") }
        val raw = if (idx >= 0) allLines[idx].substringAfter("$key:") else "[]"

        val items = mutableListOf<String>()
        val entryPattern = Regex("\"([^\"]+)\"")
        entryPattern.findAll(raw).forEach { match ->
            items.add(match.groupValues[1])
        }

        items.removeAll { it == packName || it == "file/$packName" }

        val rebuilt = if (useModernFormat) {
            val hasVanilla = items.any { it == "vanilla" }
            val list = mutableListOf<String>()
            if (!hasVanilla) list.add("vanilla")
            list.addAll(items)
            list.add(packEntry)
            list
        } else {
            items.add(packEntry)
            items
        }

        val jsonList = rebuilt.joinToString(",") { "\"$it\"" }

        if (idx >= 0) {
            allLines[idx] = "$key:[$jsonList]"
        } else {
            allLines.add("$key:[$jsonList]")
        }

        allLines.removeAll { it.startsWith("enabledResourcePacks:") }

        optionsFile.writeText(allLines.joinToString("\n") + "\n", Charsets.UTF_8)
        println("[SkinPack] options.txt 已更新: resourcePacks=[$jsonList], useModernFormat=$useModernFormat, gameDir=${gameDir.absolutePath}")
    }

    private fun removeResourcePackFromOptions(gameDir: File, packName: String) {
        val optionsFile = File(gameDir, "options.txt")
        if (!optionsFile.exists()) return
        val allLines = optionsFile.readLines(Charsets.UTF_8).toMutableList()
        val key = "resourcePacks"
        val idx = allLines.indexOfFirst { it.startsWith("$key:") }
        if (idx < 0) return

        val raw = allLines[idx].substringAfter("$key:")
        val entryPattern = Regex("\"([^\"]+)\"")
        val items = entryPattern.findAll(raw).map { it.groupValues[1] }.toMutableList()

        val removed = items.removeAll { it == packName || it == "file/$packName" }
        if (!removed) return

        if (items.none { it == "vanilla" }) {
            items.add(0, "vanilla")
        }

        val jsonList = items.joinToString(",") { "\"$it\"" }
        allLines[idx] = "$key:[$jsonList]"
        optionsFile.writeText(allLines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private fun resolveMainClass(root: JsonObject, inheritanceRoots: List<JsonObject>): String {
        return root["mainClass"]?.jsonPrimitive?.contentOrNull
            ?: inheritanceRoots.asReversed().firstNotNullOfOrNull { it["mainClass"]?.jsonPrimitive?.contentOrNull }
            ?: "net.minecraft.client.main.Main"
    }

    private fun buildClassPath(root: JsonObject, context: LaunchContext, inheritanceRoots: List<JsonObject>): String {
        val libs = mutableListOf<String>()
        val librariesDir = context.librariesDir

        fun collectLibs(obj: JsonObject) {
            obj["libraries"]?.jsonArray?.forEach { libElement ->
                val lib = libElement.jsonObject
                if (!isLibraryAllowed(lib)) return@forEach

                val artifactPath = lib["downloads"]?.jsonObject
                    ?.get("artifact")?.jsonObject
                    ?.get("path")?.jsonPrimitive?.contentOrNull

                val path = if (artifactPath != null) {
                    artifactPath
                } else {
                    val name = lib["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    mavenNameToPath(name)
                }

                val file = File(librariesDir, path)
                if (file.exists()) {
                    libs.add(file.absolutePath)
                }
            }
        }

        val inheritsFrom = root["inheritsFrom"]?.jsonPrimitive?.contentOrNull
        inheritanceRoots.forEach { collectLibs(it) }
        collectLibs(root)

        val modulePathEntries = collectModulePathEntries(inheritanceRoots + root, context)
        findNeoForgeUniversalJar(root, context)?.let { libs.add(it.absolutePath) }
        findNeoForgePatchedJar(root, context)?.let { libs.add(it.absolutePath) }
        val versionJar = inheritsFrom ?: context.version.id
        val moduleLauncher = usesForgeModuleLauncher(root)
        val inheritedJar = File(context.minecraftDir, "versions/$versionJar/$versionJar.jar")
        val currentJar = File(context.minecraftDir, "versions/${context.version.id}/${context.version.id}.jar")
        val jarFile = when {
            inheritedJar.isFile && inheritedJar.length() > 0L -> inheritedJar
            currentJar.isFile && currentJar.length() > 0L -> {
                println("[Launch] 基础版本 JAR 缺失，回退使用当前版本 JAR: ${currentJar.absolutePath}")
                currentJar
            }
            else -> inheritedJar
        }
        if (jarFile.exists()) {
            if (moduleLauncher) {
                ensureJarManifestAttribute(jarFile, "Minecraft-Dists", "client")
            } else {
                val jarMetaRoot = if (versionJar == context.version.id) {
                    root
                } else {
                    inheritanceRoots.asReversed().firstOrNull {
                        it["id"]?.jsonPrimitive?.contentOrNull == versionJar
                    } ?: inheritanceRoots.lastOrNull() ?: root
                }
                ensureClientJarIntegrityIfNeeded(jarMetaRoot, jarFile, versionJar)
            }
            if (!moduleLauncher) libs.add(jarFile.absolutePath)
        }

        return libs.distinct()
            .filterNot { normalizePath(it) in modulePathEntries }
            .joinToString(File.pathSeparator)
    }

    private fun collectModulePathEntries(roots: List<JsonObject>, context: LaunchContext): Set<String> {
        val rawArgs = mutableListOf<String>()
        roots.forEach { node ->
            val jvmArgs = node["arguments"]?.jsonObject?.get("jvm")?.jsonArray ?: return@forEach
            jvmArgs.forEach { arg ->
                when (arg) {
                    is JsonPrimitive -> rawArgs.add(arg.content)
                    is JsonObject -> {
                        val rules = arg["rules"]?.jsonArray
                        if (rules == null || isRuleAllowed(rules)) {
                            when (val value = arg["value"]) {
                                is JsonPrimitive -> rawArgs.add(value.content)
                                is JsonArray -> value.forEach { if (it is JsonPrimitive) rawArgs.add(it.content) }
                                else -> Unit
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        val result = mutableSetOf<String>()
        rawArgs.forEachIndexed { index, value ->
            if (value == "-p" || value == "--module-path") {
                val modulePath = rawArgs.getOrNull(index + 1).orEmpty()
                    .replace("\${library_directory}", context.librariesDir.absolutePath)
                    .replace("\${classpath_separator}", File.pathSeparator)
                modulePath.split(File.pathSeparator)
                    .filter { it.isNotBlank() }
                    .mapTo(result) { normalizePath(it) }
            }
        }
        return result
    }

    private fun findNeoForgePatchedJar(root: JsonObject, context: LaunchContext): File? {
        val requiresPatched = root["md3lRequiresPatchedClient"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
        val neoForgeVersion = findFmlArg(root, "--fml.neoForgeVersion") ?: return null
        val patched = File(context.librariesDir, "net/neoforged/minecraft-client-patched/$neoForgeVersion/minecraft-client-patched-$neoForgeVersion.jar")
        if (!patched.isFile || patched.length() <= 0L) {
            if (!requiresPatched) {
                return null
            }
            throw RuntimeException("NeoForge 安装不完整：缺少 $patched\n请删除该 NeoForge 版本后重新安装，或在版本详情中重新安装 NeoForge。")
        }
        val file = patched
        ensureJarManifestAttribute(file, "Minecraft-Dists", "client")
        return file
    }

    private fun findNeoForgeUniversalJar(root: JsonObject, context: LaunchContext): File? {
        val neoForgeVersion = findFmlArg(root, "--fml.neoForgeVersion") ?: return null
        val universal = File(
            context.librariesDir,
            "net/neoforged/neoforge/$neoForgeVersion/neoforge-$neoForgeVersion-universal.jar",
        )
        return universal.takeIf { it.isFile && it.length() > 0 }
    }

    private fun findFmlArg(root: JsonObject, key: String): String? {
        val args = root["arguments"]?.jsonObject?.get("game")?.jsonArray ?: return null
        args.forEachIndexed { index, element ->
            if (element is JsonPrimitive && element.content == key) {
                return (args.getOrNull(index + 1) as? JsonPrimitive)?.content
            }
        }
        return null
    }

    private fun ensureJarManifestAttribute(jarFile: File, key: String, value: String) {
        val jarF = java.util.jar.JarFile(jarFile)
        val manifest = jarF.manifest ?: java.util.jar.Manifest()
        val mainAttrs = manifest.mainAttributes

        if (mainAttrs.getValue(key) != null) {
            jarF.close()
            println("[JavaLaunch] $key already present in ${jarFile.name}, skipping")
            return
        }
        println("[JavaLaunch] Injecting $key=$value into ${jarFile.name} (${jarFile.length()} bytes)")

        if (mainAttrs.getValue("Manifest-Version") == null) {
            mainAttrs.putValue("Manifest-Version", "1.0")
        }
        mainAttrs.putValue(key, value)

        val tempFile = File(jarFile.parentFile, "${jarFile.name}.md3l.tmp")
        tempFile.delete()
        java.util.jar.JarOutputStream(tempFile.outputStream().buffered(), manifest).use { out ->
            val seen = mutableSetOf<String>()
            seen.add("META-INF/MANIFEST.MF")
            for (entry in jarF.entries()) {
                if (entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
                if (isJarSignatureEntry(entry.name)) continue
                if (!seen.add(entry.name)) continue
                val newEntry = java.util.zip.ZipEntry(entry.name)
                newEntry.time = entry.time
                newEntry.method = entry.method
                if (entry.method == java.util.zip.ZipEntry.STORED) {
                    newEntry.size = entry.size
                    newEntry.compressedSize = entry.compressedSize
                    newEntry.crc = entry.crc
                }
                out.putNextEntry(newEntry)
                if (!entry.isDirectory) {
                    jarF.getInputStream(entry).use { it.copyTo(out) }
                }
                out.closeEntry()
            }
        }
        jarF.close()

        if (!tempFile.isFile || tempFile.length() <= 0L) {
            tempFile.delete()
            throw RuntimeException("JAR manifest rewrite produced empty file: ${jarFile.name}")
        }
        val backupFile = File(jarFile.parentFile, "${jarFile.name}.md3l.bak")
        backupFile.delete()
        jarFile.renameTo(backupFile)
        if (tempFile.renameTo(jarFile)) {
            backupFile.delete()
            println("[JavaLaunch] Successfully injected $key=$value into ${jarFile.name} (${jarFile.length()} bytes)")
        } else {
            tempFile.copyTo(jarFile, overwrite = true)
            tempFile.delete()
            backupFile.delete()
            println("[JavaLaunch] Injected $key=$value into ${jarFile.name} via copy (${jarFile.length()} bytes)")
        }
    }

    private fun ensureClientJarIntegrityIfNeeded(metaRoot: JsonObject, jarFile: File, versionIdForLog: String) {
        val client = metaRoot["downloads"]?.jsonObject?.get("client")?.jsonObject ?: return
        val expectedSha1 = client["sha1"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (expectedSha1.isBlank()) return

        val currentOk = jarFile.isFile && jarFile.length() > 0L && sha1Hex(jarFile).equals(expectedSha1, ignoreCase = true)
        if (currentOk) return

        val rawUrl = client["url"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val candidates = linkedSetOf<String>()
        if (rawUrl.isNotBlank()) {
            candidates.add(DownloadManager.mirrorUrl(rawUrl))
            candidates.add(rawUrl)
        }
        if (candidates.isEmpty()) return

        println("[Launch] 客户端 JAR 校验失败，尝试修复: $versionIdForLog -> ${jarFile.absolutePath}")
        val tempFile = File(jarFile.parentFile, "${jarFile.name}.md3l.download")
        tempFile.delete()

        for (url in candidates) {
            if (!downloadBinary(url, tempFile)) {
                tempFile.delete()
                continue
            }
            val ok = tempFile.isFile && tempFile.length() > 0L && sha1Hex(tempFile).equals(expectedSha1, ignoreCase = true)
            if (!ok) {
                tempFile.delete()
                continue
            }

            runCatching {
                if (jarFile.exists()) jarFile.delete()
                if (!tempFile.renameTo(jarFile)) {
                    tempFile.copyTo(jarFile, overwrite = true)
                    tempFile.delete()
                }
            }.onSuccess {
                println("[Launch] 客户端 JAR 修复成功: ${jarFile.absolutePath}")
                return
            }.onFailure {
                tempFile.delete()
            }
        }

        println("[Launch] 客户端 JAR 修复失败，继续使用现有文件: ${jarFile.absolutePath}")
    }

    private fun downloadBinary(url: String, dest: File): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("User-Agent", "MD3L/1.1")
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return false
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            dest.isFile && dest.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    private fun sha1Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isJarSignatureEntry(entryName: String): Boolean {
        val n = entryName.replace('\\', '/').uppercase()
        if (!n.startsWith("META-INF/")) return false
        return n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")
    }

    private fun normalizePath(path: String): String =
        File(path.replace('/', File.separatorChar)).absoluteFile.normalize().path.lowercase()

    private fun usesForgeModuleLauncher(root: JsonObject): Boolean {
        val mainClass = root["mainClass"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if ("bootstraplauncher" in mainClass || "modlauncher" in mainClass) return true
        val gameArgs = root["arguments"]?.jsonObject?.get("game")?.jsonArray ?: return false
        return gameArgs.any { arg ->
            when (arg) {
                is JsonPrimitive -> arg.content == "--launchTarget"
                is JsonObject -> {
                    val value = arg["value"]
                    value is JsonPrimitive && value.content == "--launchTarget" ||
                        value is JsonArray && value.any { it is JsonPrimitive && it.content == "--launchTarget" }
                }
                else -> false
            }
        }
    }

    private val currentOs: String by lazy {
        val osName = System.getProperty("os.name").lowercase()
        when {
            "win" in osName -> "windows"
            "mac" in osName || "darwin" in osName -> "osx"
            else -> "linux"
        }
    }

    private fun isLibraryAllowed(lib: JsonObject): Boolean {
        val rules = lib["rules"]?.jsonArray ?: return true
        return isRuleAllowed(rules)
    }

    private fun isRuleAllowed(rules: JsonArray): Boolean {
        var allowed = false
        rules.forEach { ruleEl ->
            val rule = ruleEl.jsonObject
            val action = rule["action"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            if (rule.containsKey("features")) {
                if (action == "allow") return@forEach
                else { allowed = false; return@forEach }
            }
            val os = rule["os"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            if (os == null) {
                allowed = action == "allow"
            } else if (os == currentOs) {
                allowed = action == "allow"
            }
        }
        return allowed
    }

    private fun mavenNameToPath(name: String): String {
        val parts = name.split(":")
        if (parts.size < 3) return name
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = if (parts.size > 3) "-${parts[3]}" else ""
        return "$group/$artifact/$version/$artifact-$version$classifier.jar"
    }

    private fun buildJvmArguments(
        root: JsonObject,
        context: LaunchContext,
        classPath: String,
        inheritanceRoots: List<JsonObject>,
    ): List<String> {
        val args = mutableListOf<String>()
        val nativesDir = context.nativesDir.absolutePath

        val xmx = context.memoryMb
        val xms = if (xmx >= 4096) xmx / 2 else 512.coerceAtMost(xmx)
        args.add("-Xmx${xmx}m")
        args.add("-Xms${xms}m")
        if (context.jvmThreadStackSize > 0) args.add("-Xss${context.jvmThreadStackSize}k")
        args.add("-Djava.library.path=$nativesDir")
        args.add("-Dminecraft.launcher.brand=MD3L")
        args.add("-Dminecraft.launcher.version=1.3.6")
        args.add("-Dfile.encoding=UTF-8")
        args.add("-Dsun.stdout.encoding=UTF-8")
        args.add("-Dsun.stderr.encoding=UTF-8")
        args.add("-Duser.language=zh")
        args.add("-Duser.country=CN")
        if (context.jvmMetaspaceSize > 0)     args.add("-XX:MetaspaceSize=${context.jvmMetaspaceSize}m")
        if (context.jvmReservedCodeCache > 0) args.add("-XX:ReservedCodeCacheSize=${context.jvmReservedCodeCache}m")
        if (!context.jvmTieredCompilation)    args.add("-XX:-TieredCompilation")
        if (context.jvmInlineSize != 325)     args.add("-XX:MaxInlineSize=${context.jvmInlineSize}")
        if (context.jvmFreqInlineSize != 325) args.add("-XX:FreqInlineSize=${context.jvmFreqInlineSize}")
        if (context.jvmLoopUnrollingLimit != 60) args.add("-XX:LoopUnrollingLimit=${context.jvmLoopUnrollingLimit}")
        if (context.jvmEnableIEEE)             args.add("-XX:+UseStrictFP")
        if (context.jvmNativeMemoryTracking)   args.add("-XX:NativeMemoryTracking=summary")
        if (context.jvmUseLargePages)          args.add("-XX:+UseLargePages")

        val userArgs = context.customJvmArgs.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val hasCustomGc = userArgs.any { it.startsWith("-XX:+Use") && it.contains("GC") }
        if (!hasCustomGc) {
            val g1ns = context.jvmG1NewSizePercent
            val g1ms = context.jvmG1MaxNewSizePercent
            val g1rs = context.jvmG1HeapRegionSize
            val g1gt = context.jvmG1GCPauseTarget
            when (context.gcPolicy) {
                "G1GC" -> {
                    args.add("-XX:+UseG1GC")
                    args.add("-XX:+UnlockExperimentalVMOptions")
                    args.add("-XX:G1NewSizePercent=$g1ns")
                    args.add("-XX:G1MaxNewSizePercent=$g1ms")
                    args.add("-XX:G1ReservePercent=20")
                    args.add("-XX:MaxGCPauseMillis=$g1gt")
                    args.add("-XX:G1HeapRegionSize=${g1rs}m")
                    if (context.jvmParallelRefProcEnabled) args.add("-XX:+ParallelRefProcEnabled")
                    if (context.jvmDisableExplicitGC)      args.add("-XX:+DisableExplicitGC")
                    if (context.jvmAlwaysPreTouch)         args.add("-XX:+AlwaysPreTouch")
                    if (context.jvmStringDedup)            args.add("-XX:+UseStringDeduplication")
                    if (xmx >= 4096) {
                        args.add("-XX:G1HeapWastePercent=5")
                        args.add("-XX:G1MixedGCCountTarget=4")
                        args.add("-XX:InitiatingHeapOccupancyPercent=15")
                        args.add("-XX:G1MixedGCLiveThresholdPercent=90")
                        args.add("-XX:SurvivorRatio=32")
                        args.add("-XX:MaxTenuringThreshold=1")
                    }
                }
                "ZGC" -> {
                    args.add("-XX:+UseZGC")
                    args.add("-XX:+UnlockExperimentalVMOptions")
                    if (context.jvmDisableExplicitGC) args.add("-XX:+DisableExplicitGC")
                    if (context.jvmAlwaysPreTouch)    args.add("-XX:+AlwaysPreTouch")
                    args.add("-XX:ZUncommitDelay=${context.jvmZUncommitDelay}")
                    if (context.jvmConcGCThreads > 0) args.add("-XX:ConcGCThreads=${context.jvmConcGCThreads}")
                }
                "ShenandoahGC" -> {
                    args.add("-XX:+UseShenandoahGC")
                    args.add("-XX:+UnlockExperimentalVMOptions")
                    args.add("-XX:ShenandoahGCMode=${context.jvmShenandoahMode}")
                    args.add("-XX:ShenandoahHeapSizePercent=${context.jvmShenandoahHeapSizePercent}")
                    if (context.jvmDisableExplicitGC) args.add("-XX:+DisableExplicitGC")
                    if (context.jvmAlwaysPreTouch)    args.add("-XX:+AlwaysPreTouch")
                }
                "ParallelGC" -> {
                    args.add("-XX:+UseParallelGC")
                    if (context.jvmDisableExplicitGC) args.add("-XX:+DisableExplicitGC")
                    if (context.jvmAlwaysPreTouch)    args.add("-XX:+AlwaysPreTouch")
                    if (context.jvmParallelGCThreads > 0) args.add("-XX:ParallelGCThreads=${context.jvmParallelGCThreads}")
                }
                "SerialGC" -> args.add("-XX:+UseSerialGC")
            }
        }

        if (userArgs.isNotEmpty()) {
            val gcFlags = setOf("-XX:+UseG1GC", "-XX:+UseZGC", "-XX:+UseShenandoahGC", "-XX:+UseParallelGC", "-XX:+UseSerialGC")
            userArgs.filter { it !in gcFlags }.forEach { args.add(it) }
        }

        val needsAuthlibInjector = context.authServerUrl.isNotBlank() || skinServer != null
        if (needsAuthlibInjector) {
            val targetUrl = if (context.authServerUrl.isNotBlank()) {
                context.authServerUrl
            } else {
                skinServer!!.rootUrl
            }

            val authlibInjectorPath = File(context.minecraftDir, "cache/authlib-injector.jar")
            if (!authlibInjectorPath.exists() || authlibInjectorPath.length() == 0L) {
                println("[Launch] Downloading authlib-injector...")
                val url = "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/latest.json"
                try {
                    val proc = ProcessBuilder("curl.exe", "-sL", url).start()
                    val text = proc.inputStream.bufferedReader().readText()
                    val latestJson = Json.parseToJsonElement(text).jsonObject
                    val downloadUrl = latestJson["download_url"]?.jsonPrimitive?.contentOrNull
                        ?: throw RuntimeException("authlib-injector download url not found")

                    authlibInjectorPath.parentFile?.mkdirs()
                    val downloadProc = ProcessBuilder(
                        "curl.exe", "-sL", "-o", authlibInjectorPath.absolutePath, downloadUrl
                    ).start()
                    downloadProc.waitFor()
                    println("[Launch] authlib-injector downloaded.")
                } catch (e: Exception) {
                    println("[Launch] Failed to download authlib-injector: ${e.message}")
                }
            }
            if (authlibInjectorPath.exists() && authlibInjectorPath.length() > 0L) {
                args.add("-javaagent:${authlibInjectorPath.absolutePath}=$targetUrl")
                args.add("-Dauthlibinjector.side=client")
            } else if (context.authServerUrl.isNotBlank()) {
                throw RuntimeException("authlib-injector.jar 不存在且下载失败，无法进行第三方登录")
            } else {
                println("[SkinServer] authlib-injector.jar 不可用，离线皮肤将无法加载")
            }
        }

        val addedArgKeys = mutableSetOf<String>()

        fun collectJvmArgs(obj: JsonObject) {
            obj["arguments"]?.jsonObject?.get("jvm")?.jsonArray?.forEach { arg ->
                when {
                    arg is JsonPrimitive -> {
                        val resolved = resolveArgTemplate(arg.content, context, classPath, nativesDir)
                        val key = argKey(resolved)
                        if (key != null && key in addedArgKeys) return@forEach
                        args.add(resolved)
                        key?.let { addedArgKeys.add(it) }
                    }
                    arg is JsonObject -> {
                        val rules = arg["rules"]?.jsonArray
                        if (rules == null || isRuleAllowed(rules)) {
                            val value = arg["value"]
                            when {
                                value is JsonPrimitive -> {
                                    val resolved = resolveArgTemplate(value.content, context, classPath, nativesDir)
                                    val key = argKey(resolved)
                                    if (key != null && key in addedArgKeys) return@forEach
                                    args.add(resolved)
                                    key?.let { addedArgKeys.add(it) }
                                }
                                value is JsonArray -> value.forEach { v ->
                                    if (v is JsonPrimitive) {
                                        val resolved = resolveArgTemplate(v.content, context, classPath, nativesDir)
                                        val key = argKey(resolved)
                                        if (key != null && key in addedArgKeys) return@forEach
                                        args.add(resolved)
                                        key?.let { addedArgKeys.add(it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        fun registerHardcodedArg(arg: String) {
            argKey(arg)?.let { addedArgKeys.add(it) }
        }
        args.forEach { registerHardcodedArg(it) }

        if (inheritanceRoots.isNotEmpty() && !root.containsKey("arguments")) {
            collectJvmArgs(inheritanceRoots.last())
        }
        collectJvmArgs(root)

        val javaMajor = probeJavaMajorVersion(context.javaPath)
        if (javaMajor >= 22) {
            val compatArgs = listOf(
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/java.time=ALL-UNNAMED",
                "--add-opens=java.base/java.math=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
            )
            for (compatArg in compatArgs) {
                val key = argKey(compatArg)
                if (key == null || compatArg !in args) {
                    args.add(compatArg)
                    key?.let { addedArgKeys.add(it) }
                }
            }
            println("[JavaLaunch] 已添加 Java $javaMajor 兼容性标志 (${compatArgs.size} 个)")
        }

        if (!root.containsKey("arguments") && inheritanceRoots.isEmpty()) {
            args.add("-cp")
            args.add(classPath)
        }
        if ("-cp" !in args) {
            args.add("-cp")
            args.add(classPath)
        }

        return args
    }

    private fun argKey(arg: String): String? {
        return when {
            arg.startsWith("-D") -> {
                val eq = arg.indexOf('=', 2)
                if (eq > 0) arg.substring(0, eq) else arg
            }
            arg.startsWith("-XX:") -> {
                val colon = arg.indexOf(':', 4)
                val eq = arg.indexOf('=', 4)
                if (arg.length > 4 && (arg[4] == '+' || arg[4] == '-')) arg
                else if (eq > 0) arg.substring(0, eq)
                else if (colon > 0) arg.substring(0, colon)
                else arg
            }
            arg.startsWith("--add-opens=") || arg.startsWith("--add-exports=") ||
                arg.startsWith("--add-reads=") || arg.startsWith("--add-modules=") ||
                arg.startsWith("--enable-native-access=") -> {
                val eq = arg.indexOf('=', 2)
                if (eq > 0) arg.substring(0, eq) else arg
            }
            else -> null
        }
    }

    private fun probeJavaMajorVersion(javaPath: String): Int {
        return runCatching {
            val proc = ProcessBuilder(javaPath, "-version")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return@runCatching 0
            }
            val verLine = output.lineSequence().firstOrNull { "version" in it }?.trim() ?: return@runCatching 0
            val quoted = verLine.substringAfter('"').substringBefore('"')
            val major = if (quoted.startsWith("1.")) {
                quoted.split(".").getOrNull(1)?.toIntOrNull() ?: 0
            } else {
                quoted.split(".").firstOrNull()?.toIntOrNull() ?: 0
            }
            println("[JavaLaunch] 检测到 Java 版本: $major ($quoted)")
            major
        }.getOrDefault(0)
    }

    private fun buildGameArguments(root: JsonObject, context: LaunchContext, inheritanceRoots: List<JsonObject>): List<String> {
        val args = mutableListOf<String>()
        val nativesDir = context.nativesDir.absolutePath

        fun collectGameArgs(obj: JsonObject) {
            obj["arguments"]?.jsonObject?.get("game")?.jsonArray?.forEach { arg ->
                when {
                    arg is JsonPrimitive -> {
                        args.add(resolveArgTemplate(arg.content, context, "", nativesDir))
                    }
                    arg is JsonObject -> {
                        val rules = arg["rules"]?.jsonArray
                        if (rules == null || isRuleAllowed(rules)) {
                            val value = arg["value"]
                            when {
                                value is JsonPrimitive -> args.add(resolveArgTemplate(value.content, context, "", nativesDir))
                                value is JsonArray -> value.forEach { v ->
                                    if (v is JsonPrimitive) args.add(resolveArgTemplate(v.content, context, "", nativesDir))
                                }
                            }
                        }
                    }
                }
            }
        }

        inheritanceRoots.forEach { collectGameArgs(it) }
        collectGameArgs(root)

        val legacyTokens = mutableListOf<String>()
        inheritanceRoots.asReversed().firstNotNullOfOrNull {
            it["minecraftArguments"]?.jsonPrimitive?.contentOrNull
        }?.split(" ")?.let { legacyTokens.addAll(it) }
        root["minecraftArguments"]?.jsonPrimitive?.contentOrNull?.split(" ")?.forEach { token ->
            if (token.isNotBlank() && token !in legacyTokens) legacyTokens.add(token)
        }
        legacyTokens.forEach { token ->
            if (token.isNotBlank()) args.add(resolveArgTemplate(token, context, "", nativesDir))
        }

        args.add("--width"); args.add(context.windowWidth.toString())
        args.add("--height"); args.add(context.windowHeight.toString())
        if (context.fullscreen) args.add("--fullscreen")
        if (context.launchDemoMode) args.add("--demo")
        if (context.javaUseNativeGlfw) args.add("--useNativeGlfw")
        if (context.javaUseNativeOpenAl) args.add("--useNativeOpenAL")
        if (context.javaQuickPlaySingleplayer.isNotBlank()) {
            args.add("--quickPlaySingleplayer"); args.add(context.javaQuickPlaySingleplayer)
        }
        if (context.javaQuickPlayMultiplayer.isNotBlank()) {
            args.add("--quickPlayMultiplayer"); args.add(context.javaQuickPlayMultiplayer)
        }
        if (context.javaExtraGameArgs.isNotBlank()) {
            context.javaExtraGameArgs.split("\\s+".toRegex()).filter { it.isNotBlank() }.forEach { args.add(it) }
        }

        if ("--lang" !in args) {
            args.add("--lang"); args.add("zh_CN")
        }

        return args
    }

    private fun resolveArgTemplate(
        template: String,
        context: LaunchContext,
        classPath: String,
        nativesDir: String,
    ): String {
        val assetsDir = context.assetsDir.absolutePath
        val assetsIndex = cachedAssetsIndex
        return template
            .replace("\${auth_player_name}", context.playerName)
            .replace("\${version_name}", context.version.id)
            .replace("\${game_directory}", context.gameDir.absolutePath)
            .replace("\${assets_root}", assetsDir)
            .replace("\${assets_index_name}", assetsIndex)
            .replace("\${auth_uuid}", context.uuid)
            .replace("\${auth_access_token}", context.accessToken)
            .replace("\${clientid}", "0")
            .replace("\${auth_xuid}", "0")
            .replace("\${user_type}", if (context.accessToken.length > 10) "msa" else "legacy")
            .replace("\${version_type}", "MD3L")
            .replace("\${natives_directory}", nativesDir)
            .replace("\${launcher_name}", "MD3L")
            .replace("\${launcher_version}", "1.1.0")
            .replace("\${classpath}", classPath)
            .replace("\${user_properties}", "{}")
            .replace("\${auth_session}", "token:${context.accessToken}")
            .replace("\${game_assets}", assetsDir)
            .replace("\${classpath_separator}", File.pathSeparator)
            .replace("\${library_directory}", context.librariesDir.absolutePath)
            .replace("\${resolution_width}", context.windowWidth.toString())
            .replace("\${resolution_height}", context.windowHeight.toString())
    }

    private fun maskSensitiveArg(arg: String, context: LaunchContext): String {
        if (context.accessToken.isNotBlank() && arg == context.accessToken) return "********"
        return arg
    }

    private fun resolveInheritanceRoots(root: JsonObject, minecraftDir: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val visited = mutableSetOf<String>()
        val currentVersionId = root["id"]?.jsonPrimitive?.contentOrNull

        fun walk(versionId: String) {
            val key = versionId.lowercase()
            if (!visited.add(key)) return

            var file = File(minecraftDir, "versions/$versionId/$versionId.json")

            if (!file.isFile && !currentVersionId.isNullOrBlank() && currentVersionId != versionId) {
                repairMissingVanillaVersion(minecraftDir, versionId, currentVersionId)
                file = File(minecraftDir, "versions/$versionId/$versionId.json")
            }

            if (!file.isFile) return
            val obj = runCatching {
                json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            }.getOrNull() ?: return

            val parentId = obj["inheritsFrom"]?.jsonPrimitive?.contentOrNull
            if (!parentId.isNullOrBlank()) {
                walk(parentId)
            }
            result.add(obj)
        }

        val directParent = root["inheritsFrom"]?.jsonPrimitive?.contentOrNull
        if (!directParent.isNullOrBlank()) {
            walk(directParent)
        }
        return result
    }

    private fun repairMissingVanillaVersion(minecraftDir: String, mcVersion: String, donorVersionId: String) {
        val vanillaDir = File(minecraftDir, "versions/$mcVersion")
        val vanillaJson = File(vanillaDir, "$mcVersion.json")
        val vanillaJar = File(vanillaDir, "$mcVersion.jar")
        if (vanillaJson.isFile) return

        println("[Launch] inheritsFrom=$mcVersion 对应的原版 JSON 不存在，尝试恢复…")

        val versionsRoot = File(minecraftDir, "versions")
        if (versionsRoot.isDirectory) {
            versionsRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val candidate = File(dir, "${dir.name}.json")
                if (!candidate.isFile) return@forEach
                val text = runCatching { candidate.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                if (!text.contains("lwjgl", ignoreCase = true)) return@forEach
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@forEach
                val id = obj["id"]?.jsonPrimitive?.contentOrNull
                val inherits = obj["inheritsFrom"]?.jsonPrimitive?.contentOrNull
                if (id == mcVersion && inherits.isNullOrBlank()) {
                    vanillaDir.mkdirs()
                    candidate.copyTo(vanillaJson, overwrite = true)
                    println("[Launch] 策略1：从 ${candidate.absolutePath} 恢复原版 $mcVersion JSON")
                    val candidateJar = File(dir, "${dir.name}.jar")
                    if (candidateJar.isFile && candidateJar.length() > 0L && (!vanillaJar.isFile || vanillaJar.length() <= 0L)) {
                        candidateJar.copyTo(vanillaJar, overwrite = true)
                    }
                    return
                }
            }
        }

        if (fetchAndSaveVanillaJson(mcVersion, vanillaDir, vanillaJson)) {
            println("[Launch] 策略2：在线获取原版 $mcVersion JSON 成功")
        }

        val donorJar = File(minecraftDir, "versions/$donorVersionId/$donorVersionId.jar")
        if (donorJar.isFile && donorJar.length() > 0L && (!vanillaJar.isFile || vanillaJar.length() <= 0L)) {
            vanillaDir.mkdirs()
            donorJar.copyTo(vanillaJar, overwrite = true)
            println("[Launch] 策略3：复制 donor JAR 作为原版 $mcVersion JAR")
        }
    }

    private fun fetchAndSaveVanillaJson(mcVersion: String, vanillaDir: File, vanillaJson: File): Boolean {
        val manifestCache = File(System.getProperty("user.home"), ".craftlauncher/cache/version_manifest.json")
        val manifestUrls = listOf(
            "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json",
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
        )

        val versionUrl = findVersionUrl(mcVersion, manifestCache)
            ?: manifestUrls.firstNotNullOfOrNull { manifestUrl ->
                val text = curlGet(manifestUrl) ?: return@firstNotNullOfOrNull null
                runCatching { manifestCache.also { it.parentFile?.mkdirs() }.writeText(text) }
                findVersionUrlFromText(mcVersion, text)
            }
            ?: return false

        val vJsonText = curlGet(versionUrl) ?: return false
        if (vJsonText.length < 500 || !vJsonText.contains("libraries", ignoreCase = true)) return false
        vanillaDir.mkdirs()
        vanillaJson.writeText(vJsonText, Charsets.UTF_8)
        return true
    }

    private fun findVersionUrl(mcVersion: String, cacheFile: File): String? {
        if (!cacheFile.isFile) return null
        return runCatching { findVersionUrlFromText(mcVersion, cacheFile.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun findVersionUrlFromText(mcVersion: String, text: String): String? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val versions = root["versions"]?.jsonArray ?: return null
        for (v in versions) {
            val obj = v.jsonObject
            if (obj["id"]?.jsonPrimitive?.contentOrNull == mcVersion) {
                return obj["url"]?.jsonPrimitive?.contentOrNull
            }
        }
        return null
    }

    private fun curlGet(url: String): String? {
        return runCatching {
            val proc = ProcessBuilder("curl.exe", "-sL", "--connect-timeout", "10", "--max-time", "30", url)
                .redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor(35, java.util.concurrent.TimeUnit.SECONDS)
            if (ok && proc.exitValue() == 0 && text.isNotBlank()) text else null
        }.getOrNull()
    }


    private fun parseMcVersion(versionStr: String): Triple<Int, Int, Int> {
        val match = Regex("""1\.(\d+)(?:\.(\d+))?(?:\.(\d+))?""").find(versionStr)
            ?: return Triple(0, 0, 0)
        val major = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return Triple(0, 0, 0)
        val minor = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        val patch = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        return Triple(major, minor, patch)
    }

    private fun resolveMcVersionString(root: JsonObject, inheritanceRoots: List<JsonObject>, fallback: String): String {
        val candidates = buildList {
            add(root["assets"]?.jsonPrimitive?.contentOrNull.orEmpty())
            add(root["assetIndex"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty())
            add(root["inheritsFrom"]?.jsonPrimitive?.contentOrNull.orEmpty())
            add(root["id"]?.jsonPrimitive?.contentOrNull.orEmpty())
            inheritanceRoots.asReversed().forEach { parent ->
                add(parent["assets"]?.jsonPrimitive?.contentOrNull.orEmpty())
                add(parent["assetIndex"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty())
                add(parent["id"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
            add(fallback)
        }
        return candidates.firstNotNullOfOrNull { Regex("""1\.\d+(?:\.\d+)?(?:\.\d+)?""").find(it)?.value }
            ?: fallback
    }

    private fun calculatePackFormat(major: Int, minor: Int, patch: Int): Int {
        return when {
            major == 0 -> 55
            major >= 21 && minor >= 5 -> 55
            major >= 21 && minor >= 4 -> 47
            major >= 21 && minor >= 2 -> 46
            major >= 21 -> 34
            major >= 20 && minor >= 5 -> 32
            major >= 20 && minor >= 3 -> 22
            major >= 20 && minor >= 2 -> 18
            major >= 20 -> 15
            major >= 19 && minor >= 4 -> 13
            major >= 19 && minor >= 3 -> 12
            major >= 19 -> 9
            major >= 18 -> 8
            major >= 17 -> 7
            major >= 16 && minor >= 2 -> 6
            major >= 16 -> 5
            major >= 15 -> 5
            major >= 13 -> 4
            major >= 11 -> 3
            major >= 9 -> 2
            major >= 7 -> 1
            else -> 1
        }
    }

    private fun isSlimUuid(uuid: String): Boolean {
        val u = java.util.UUID.fromString(uuid)
        return (u.hashCode() and 1) == 1
    }

    private fun manipulateUuidForSkinModel(uuid: String, desiredIsSlim: Boolean): String {
        if (isSlimUuid(uuid) == desiredIsSlim) return uuid
        var modified = uuid
        var iterations = 0
        val maxIterations = 2000
        while (iterations < maxIterations) {
            iterations++
            if (modified.endsWith("FFFFF", ignoreCase = true)) {
                modified = modified.dropLast(5) + "00000"
            } else {
                val last5 = modified.takeLast(5)
                val incremented = (last5.toLong(16) + 1)
                    .toString(16)
                    .padStart(5, '0')
                    .takeLast(5)
                modified = modified.dropLast(5) + incremented
            }
            if (isSlimUuid(modified) == desiredIsSlim) return modified
        }
        return uuid
    }
}
