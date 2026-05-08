package launcher.core

import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Java Edition 启动引擎。
 * 从 LaunchManager 中解耦，职责单一：根据 [LaunchContext] 构建命令行并启动进程。
 */
class JavaLaunchEngine : ILaunchEngine {

    private val json = Json { ignoreUnknownKeys = true }

    // 缓存：每次 execute 计算一次，避免 resolveArgTemplate 反复读文件
    private var cachedAssetsIndex: String = ""
    var lastLogFile: File? = null
        private set

    override fun execute(context: LaunchContext): Process {
        val versionJsonFile = File(context.versionDir, "${context.version.id}.json")
        val root = json.parseToJsonElement(versionJsonFile.readText(Charsets.UTF_8)).jsonObject

        // NeoForge 启动前快速校验：检测 processor 产物是否损坏，损坏则自动修复
        preflightNeoForgeIntegrity(root, context, versionJsonFile)

        val inheritanceRoots = resolveInheritanceRoots(root, context.minecraftDir)

        // 预计算 assetsIndex：优先自身 → 继承链最近可用 assets 字段 → 当前版本 ID
        cachedAssetsIndex = context.version.assetsIndex.ifBlank {
            inheritanceRoots.asReversed().firstNotNullOfOrNull { parent ->
                parent["assets"]?.jsonPrimitive?.contentOrNull
                    ?: parent["assetIndex"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: parent["id"]?.jsonPrimitive?.contentOrNull
            }
                ?: context.version.id
        }

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

        val nativesDir = context.nativesDir
        if (!nativesDir.exists()) nativesDir.mkdirs()

        // ── 版本隔离目录创建 ──────────────────────────────────────────────
        val isolatedDir = context.gameDir
        if (!isolatedDir.exists()) isolatedDir.mkdirs()
        File(isolatedDir, "mods").mkdirs()
        File(isolatedDir, "saves").mkdirs()
        File(isolatedDir, "resourcepacks").mkdirs()
        File(isolatedDir, "config").mkdirs()
        File(isolatedDir, "shaderpacks").mkdirs()
        ensureOptions(isolatedDir)
        ensureOfflineSkinResourcePack(context, isolatedDir)

        val pb = ProcessBuilder(command)
            .directory(isolatedDir)
            .redirectErrorStream(true)
        // APPDATA 必须是 .minecraft 的父目录，否则 MC 会创建嵌套 .minecraft\.minecraft 导致黑屏
        pb.environment()["APPDATA"] = File(context.minecraftDir).parent ?: context.rootGameDir.absolutePath

        // ── 启动日志写入文件，方便排查崩溃 ──────────────────────────────────
        val logFile = File(isolatedDir, "logs/md3l-launch-${java.time.LocalDateTime.now().toString().replace(':', '-')}.log")
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

    /**
     * 启动前快速检测 NeoForge processor 产物完整性。
     * 如果 neoforge-client.jar 或 SRG jar 存在但损坏（无 .class），自动触发修复。
     */
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

        // 删除损坏 jar 以强制 repair 重建
        if (clientCorrupt) clientJar.delete()
        if (srgCorrupt) srgJar?.delete()

        // 同步调用修复
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
            optionsFile.writeText("lang:zh_cn\n", Charsets.UTF_8)
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
    }

    private fun ensureOfflineSkinResourcePack(context: LaunchContext, gameDir: File) {
        val skinFile = File(context.skinUri)
        val packFile = File(gameDir, "resourcepacks/MD3L_Offline_Skin.zip")
        if (context.accessToken != "0" || !skinFile.isFile) {
            if (packFile.exists()) packFile.delete()
            removeResourcePackFromOptions(gameDir, packFile.name)
            return
        }
        createSkinResourcePack(packFile, skinFile, context.skinModel)
        enableResourcePack(gameDir, packFile.name)
    }

    private fun createSkinResourcePack(packFile: File, skinFile: File, skinModel: String) {
        packFile.parentFile?.mkdirs()
        ZipOutputStream(packFile.outputStream()).use { zip ->
            fun putText(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            fun putFile(path: String) {
                zip.putNextEntry(ZipEntry(path))
                skinFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            putText("pack.mcmeta", """{"pack":{"pack_format":1,"description":"MD3L Offline Skin"}}""")
            putFile("assets/minecraft/textures/entity/steve.png")
            putFile("assets/minecraft/textures/entity/alex.png")
            putFile("assets/minecraft/textures/entity/player/wide/steve.png")
            putFile("assets/minecraft/textures/entity/player/slim/alex.png")
            val selected = if (skinModel == "slim") "alex" else "steve"
            putText("assets/minecraft/textures/entity/md3l_skin_model.txt", selected)
        }
    }

    private fun enableResourcePack(gameDir: File, packName: String) {
        val optionsFile = File(gameDir, "options.txt")
        val lines = if (optionsFile.exists()) optionsFile.readLines(Charsets.UTF_8).toMutableList() else mutableListOf()
        val packValue = """["file/$packName","$packName"]"""
        val idx = lines.indexOfFirst { it.startsWith("resourcePacks:") }
        if (idx >= 0) lines[idx] = "resourcePacks:$packValue" else lines.add("resourcePacks:$packValue")
        val incompatibleIdx = lines.indexOfFirst { it.startsWith("incompatibleResourcePacks:") }
        if (incompatibleIdx >= 0) lines[incompatibleIdx] = "incompatibleResourcePacks:$packValue" else lines.add("incompatibleResourcePacks:$packValue")
        optionsFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private fun removeResourcePackFromOptions(gameDir: File, packName: String) {
        val optionsFile = File(gameDir, "options.txt")
        if (!optionsFile.exists()) return
        val lines = optionsFile.readLines(Charsets.UTF_8).toMutableList()
        val idx = lines.indexOfFirst { it.startsWith("resourcePacks:") }
        if (idx >= 0 && packName in lines[idx]) {
            lines[idx] = "resourcePacks:[]"
            optionsFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        }
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
            // NeoForge expects the base game JAR to have this attribute, otherwise it crashes.
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
        // Step 1: Read current manifest using JarFile API (the same API NeoForge uses to check)
        val jarF = java.util.jar.JarFile(jarFile)
        val manifest = jarF.manifest ?: java.util.jar.Manifest()
        val mainAttrs = manifest.mainAttributes

        // Already has the attribute — nothing to do
        if (mainAttrs.getValue(key) != null) {
            jarF.close()
            println("[JavaLaunch] $key already present in ${jarFile.name}, skipping")
            return
        }
        println("[JavaLaunch] Injecting $key=$value into ${jarFile.name} (${jarFile.length()} bytes)")

        // Step 2: Ensure Manifest-Version exists (required for manifest to be written)
        if (mainAttrs.getValue("Manifest-Version") == null) {
            mainAttrs.putValue("Manifest-Version", "1.0")
        }
        mainAttrs.putValue(key, value)

        // Step 3: Rewrite JAR preserving original compression methods per entry
        val tempFile = File(jarFile.parentFile, "${jarFile.name}.md3l.tmp")
        tempFile.delete()
        java.util.jar.JarOutputStream(tempFile.outputStream().buffered(), manifest).use { out ->
            val seen = mutableSetOf<String>()
            seen.add("META-INF/MANIFEST.MF") // already written by JarOutputStream constructor
            for (entry in jarF.entries()) {
                if (entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
                if (isJarSignatureEntry(entry.name)) continue
                if (!seen.add(entry.name)) continue
                // Preserve the original compression method and sizes for STORED entries
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
        // Atomic replace
        val backupFile = File(jarFile.parentFile, "${jarFile.name}.md3l.bak")
        backupFile.delete()
        jarFile.renameTo(backupFile)
        if (tempFile.renameTo(jarFile)) {
            backupFile.delete()
            println("[JavaLaunch] Successfully injected $key=$value into ${jarFile.name} (${jarFile.length()} bytes)")
        } else {
            // Fallback: copy
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
            // features 条件（如 has_custom_resolution、is_demo_user）—— 启动器不支持，跳过
            if (rule.containsKey("features")) {
                if (action == "allow") return@forEach   // 需要 feature 才 allow → 不满足 → 跳过
                else { allowed = false; return@forEach } // disallow without feature → 保持
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

        args.add("-Xmx${context.memoryMb}m")
        args.add("-Xms${context.memoryMb / 2}m")
        args.add("-Djava.library.path=$nativesDir")
        args.add("-Dminecraft.launcher.brand=MD3L")
        args.add("-Dminecraft.launcher.version=1.1.0")
        args.add("-Dfile.encoding=UTF-8")
        args.add("-Dsun.stdout.encoding=UTF-8")
        args.add("-Dsun.stderr.encoding=UTF-8")
        args.add("-Duser.language=zh")
        args.add("-Duser.country=CN")

        if (context.customJvmArgs.isNotBlank()) {
            context.customJvmArgs.split("\\s+".toRegex())
                .filter { it.isNotBlank() }
                .forEach { args.add(it) }
        }

        // 收集 JVM 参数（先读父版本、再读当前版本，保证 Forge/NeoForge 继承原版参数）
        fun collectJvmArgs(obj: JsonObject) {
            obj["arguments"]?.jsonObject?.get("jvm")?.jsonArray?.forEach { arg ->
                when {
                    arg is JsonPrimitive -> {
                        args.add(resolveArgTemplate(arg.content, context, classPath, nativesDir))
                    }
                    arg is JsonObject -> {
                        val rules = arg["rules"]?.jsonArray
                        if (rules == null || isRuleAllowed(rules)) {
                            val value = arg["value"]
                            when {
                                value is JsonPrimitive -> args.add(resolveArgTemplate(value.content, context, classPath, nativesDir))
                                value is JsonArray -> value.forEach { v ->
                                    if (v is JsonPrimitive) args.add(resolveArgTemplate(v.content, context, classPath, nativesDir))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 注意：Forge/NeoForge 的 JVM 参数包含模块路径(-p)、--add-modules 等，
        // 如果同时加入父版本的 -cp（全量 classpath），会导致 Java 模块系统冲突。
        // 因此仅在没有 inheritsFrom（纯原版）时继承父版本 JVM 参数。
        if (inheritanceRoots.isNotEmpty() && !root.containsKey("arguments")) {
            // 父版本有 arguments 但当前版本没有（极少见的旧 Forge），才继承
            collectJvmArgs(inheritanceRoots.last())
        }
        collectJvmArgs(root)

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

    private fun buildGameArguments(root: JsonObject, context: LaunchContext, inheritanceRoots: List<JsonObject>): List<String> {
        val args = mutableListOf<String>()
        val nativesDir = context.nativesDir.absolutePath

        // 收集 game 参数（先父版本，再当前版本）
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

        // Legacy minecraftArguments（旧版 1.12.2 及以下）
        val legacySrc = root["minecraftArguments"]?.jsonPrimitive?.contentOrNull
            ?: inheritanceRoots.asReversed().firstNotNullOfOrNull {
                it["minecraftArguments"]?.jsonPrimitive?.contentOrNull
            }
        legacySrc?.split(" ")?.forEach { token ->
            args.add(resolveArgTemplate(token, context, "", nativesDir))
        }

        args.add("--width"); args.add(context.windowWidth.toString())
        args.add("--height"); args.add(context.windowHeight.toString())
        if (context.fullscreen) args.add("--fullscreen")

        // 强制中文
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

            // 兜底：继承目标不存在时，尝试从当前版本目录恢复原版 JSON/jar
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

    /**
     * 当 inheritsFrom 指向的原版目录不存在时，尝试恢复。
     * 策略1：本地扫描（找 id==mcVersion 且 libraries 含 lwjgl 的 JSON）
     * 策略2：从 VersionManifest 缓存/在线获取原版 JSON
     * 策略3：兜底仅复制 donor jar
     */
    private fun repairMissingVanillaVersion(minecraftDir: String, mcVersion: String, donorVersionId: String) {
        val vanillaDir = File(minecraftDir, "versions/$mcVersion")
        val vanillaJson = File(vanillaDir, "$mcVersion.json")
        val vanillaJar = File(vanillaDir, "$mcVersion.jar")
        if (vanillaJson.isFile) return

        println("[Launch] inheritsFrom=$mcVersion 对应的原版 JSON 不存在，尝试恢复…")

        // 策略1：本地扫描已有版本目录
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

        // 策略2：从在线版本清单获取原版 JSON
        if (fetchAndSaveVanillaJson(mcVersion, vanillaDir, vanillaJson)) {
            println("[Launch] 策略2：在线获取原版 $mcVersion JSON 成功")
        }

        // 策略3：兜底复制 donor jar（原版 jar 通常与 modpack jar 相同）
        val donorJar = File(minecraftDir, "versions/$donorVersionId/$donorVersionId.jar")
        if (donorJar.isFile && donorJar.length() > 0L && (!vanillaJar.isFile || vanillaJar.length() <= 0L)) {
            vanillaDir.mkdirs()
            donorJar.copyTo(vanillaJar, overwrite = true)
            println("[Launch] 策略3：复制 donor JAR 作为原版 $mcVersion JAR")
        }
    }

    private fun fetchAndSaveVanillaJson(mcVersion: String, vanillaDir: File, vanillaJson: File): Boolean {
        // 先尝试读取本地缓存的 manifest
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

}
