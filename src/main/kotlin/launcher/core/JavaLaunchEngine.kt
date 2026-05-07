package launcher.core

import kotlinx.serialization.json.*
import java.io.File
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

        // 读取父版本 JSON（若存在 inheritsFrom），全局共用
        val inheritsFrom = root["inheritsFrom"]?.jsonPrimitive?.contentOrNull
        val parentRoot: JsonObject? = if (inheritsFrom != null) {
            val parentFile = File(context.minecraftDir, "versions/$inheritsFrom/$inheritsFrom.json")
            if (parentFile.exists()) json.parseToJsonElement(parentFile.readText()).jsonObject else null
        } else null

        // 预计算 assetsIndex：优先自身 → 父版本 assets 字段 → 父版本 ID → 自身 ID
        cachedAssetsIndex = context.version.assetsIndex.ifBlank {
            parentRoot?.get("assets")?.jsonPrimitive?.contentOrNull
                ?: parentRoot?.get("assetIndex")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                ?: inheritsFrom
                ?: context.version.id
        }

        val mainClass = resolveMainClass(root, parentRoot)
        val classPath = buildClassPath(root, context, parentRoot)
        val gameArgs = buildGameArguments(root, context, parentRoot)
        val jvmArgs = buildJvmArguments(root, context, classPath, parentRoot)

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

    private fun resolveMainClass(root: JsonObject, parentRoot: JsonObject?): String {
        return root["mainClass"]?.jsonPrimitive?.contentOrNull
            ?: parentRoot?.get("mainClass")?.jsonPrimitive?.contentOrNull
            ?: "net.minecraft.client.main.Main"
    }

    private fun buildClassPath(root: JsonObject, context: LaunchContext, parentRoot: JsonObject?): String {
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
        if (parentRoot != null) collectLibs(parentRoot)
        collectLibs(root)

        val modulePathEntries = collectModulePathEntries(root, context)
        findNeoForgeUniversalJar(root, context)?.let { libs.add(it.absolutePath) }
        findNeoForgePatchedJar(root, context)?.let { libs.add(it.absolutePath) }
        val versionJar = inheritsFrom ?: context.version.id
        val jarFile = File(context.minecraftDir, "versions/$versionJar/$versionJar.jar")
        if (jarFile.exists()) {
            // NeoForge expects the base game JAR to have this attribute, otherwise it crashes.
            if (usesForgeModuleLauncher(root) || "neoforge" in context.version.id.lowercase() || "forge" in context.version.id.lowercase()) {
                ensureJarManifestAttribute(jarFile, "Minecraft-Dists", "client")
            }
            if (!usesForgeModuleLauncher(root)) libs.add(jarFile.absolutePath)
        }

        return libs.distinct()
            .filterNot { normalizePath(it) in modulePathEntries }
            .joinToString(File.pathSeparator)
    }

    private fun collectModulePathEntries(root: JsonObject, context: LaunchContext): Set<String> {
        val jvmArgs = root["arguments"]?.jsonObject?.get("jvm")?.jsonArray ?: return emptySet()
        val rawArgs = mutableListOf<String>()
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
        val neoForgeVersion = findFmlArg(root, "--fml.neoForgeVersion") ?: return null
        val patched = File(context.librariesDir, "net/neoforged/minecraft-client-patched/$neoForgeVersion/minecraft-client-patched-$neoForgeVersion.jar")
        val file = patched.takeIf { it.isFile && it.length() > 0 }
            ?: throw RuntimeException("NeoForge 安装不完整：缺少 $patched\n请删除该 NeoForge 版本后重新安装，或在版本详情中重新安装 NeoForge。")
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
        parentRoot: JsonObject?,
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
        if (parentRoot != null && !root.containsKey("arguments")) {
            // 父版本有 arguments 但当前版本没有（极少见的旧 Forge），才继承
            collectJvmArgs(parentRoot)
        }
        collectJvmArgs(root)

        if (!root.containsKey("arguments") && parentRoot == null) {
            args.add("-cp")
            args.add(classPath)
        }
        if ("-cp" !in args) {
            args.add("-cp")
            args.add(classPath)
        }

        return args
    }

    private fun buildGameArguments(root: JsonObject, context: LaunchContext, parentRoot: JsonObject?): List<String> {
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

        if (parentRoot != null) collectGameArgs(parentRoot)
        collectGameArgs(root)

        // Legacy minecraftArguments（旧版 1.12.2 及以下）
        val legacySrc = root["minecraftArguments"]?.jsonPrimitive?.contentOrNull
            ?: parentRoot?.get("minecraftArguments")?.jsonPrimitive?.contentOrNull
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

}
