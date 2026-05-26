package launcher.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.io.File

data class LoaderInstallProgress(
    val isRunning: Boolean = false,
    val loaderName: String = "",
    val step: String = "",
    val fraction: Float = 0f,
    val error: String = "",
    val done: Boolean = false,
)

object LoaderInstaller {

    private val json = Json { ignoreUnknownKeys = true }
    private val globalScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private data class ExecResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false,
    )

    private data class ArtifactDownloadCandidate(
        val dest: File,
        val sha1: String?,
        val urls: MutableList<String>,
        val label: String,
    )

    private val _progress = MutableStateFlow(LoaderInstallProgress())
    val progress: StateFlow<LoaderInstallProgress> = _progress.asStateFlow()

    private fun emit(loader: String, step: String, fraction: Float) {
        DownloadHub.registerControls(
            id = "loader_$loader",
            onPause = {
                activeJobs["loader_$loader"]?.cancel(CancellationException("$loader 安装已暂停"))
                DownloadHub.upsert(DownloadHub.HubTask(
                    id = "loader_$loader",
                    name = "$loader 安装",
                    type = DownloadHub.TaskType.LoaderInstall,
                    status = DownloadHub.TaskStatus.Paused,
                    step = "已暂停，可重新点击安装继续",
                    fraction = fraction,
                ))
            },
            onClose = {
                activeJobs["loader_$loader"]?.cancel(CancellationException("$loader 安装已关闭"))
                DownloadHub.remove("loader_$loader")
            },
        )
        _progress.value = LoaderInstallProgress(
            isRunning = true, loaderName = loader, step = step, fraction = fraction
        )
        DownloadHub.upsert(DownloadHub.HubTask(
            id = "loader_$loader", name = "$loader 安装",
            type = DownloadHub.TaskType.LoaderInstall,
            status = DownloadHub.TaskStatus.Running, step = step, fraction = fraction,
        ))
    }
    private fun emitDone(loader: String, msg: String) {
        _progress.value = LoaderInstallProgress(
            isRunning = false, loaderName = loader, step = msg, fraction = 1f, done = true
        )
        DownloadHub.upsert(DownloadHub.HubTask(
            id = "loader_$loader", name = "$loader 安装",
            type = DownloadHub.TaskType.LoaderInstall,
            status = DownloadHub.TaskStatus.Done, step = msg, fraction = 1f,
        ))
    }
    private fun emitError(loader: String, msg: String) {
        _progress.value = LoaderInstallProgress(
            isRunning = false, loaderName = loader, step = msg, fraction = 0f, error = msg
        )
        DownloadHub.upsert(DownloadHub.HubTask(
            id = "loader_$loader", name = "$loader 安装",
            type = DownloadHub.TaskType.LoaderInstall,
            status = DownloadHub.TaskStatus.Error, step = msg, fraction = 0f, error = msg,
        ))
    }

    suspend fun repairForgeIfNeeded(
        versionJsonFile: File,
        minecraftDir: String,
        javaPath: String,
        onProgress: ((String) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val text = versionJsonFile.readText(Charsets.UTF_8)
        val root = json.parseToJsonElement(text).jsonObject

        val isForge = "net.minecraftforge" in text || "forge" in versionJsonFile.nameWithoutExtension.lowercase()
        val isNeoForge = "net.neoforged" in text || "neoforge" in versionJsonFile.nameWithoutExtension.lowercase()
        if (!isForge && !isNeoForge) return@withContext true

        val gameArgs = root["arguments"]?.jsonObject?.get("game")?.jsonArray
        val forgeVersion = gameArgs?.let { args ->
            args.mapIndexedNotNull { i, el ->
                if (el is JsonPrimitive && el.content == "--fml.forgeVersion")
                    (args.getOrNull(i + 1) as? JsonPrimitive)?.content
                else null
            }.firstOrNull()
        }
        val neoForgeVersion = gameArgs?.let { args ->
            args.mapIndexedNotNull { i, el ->
                if (el is JsonPrimitive && el.content == "--fml.neoForgeVersion")
                    (args.getOrNull(i + 1) as? JsonPrimitive)?.content
                else null
            }.firstOrNull()
        }
        val mcVersion = gameArgs?.let { args ->
            args.mapIndexedNotNull { i, el ->
                if (el is JsonPrimitive && el.content == "--fml.mcVersion")
                    (args.getOrNull(i + 1) as? JsonPrimitive)?.content
                else null
            }.firstOrNull()
        } ?: root["inheritsFrom"]?.jsonPrimitive?.contentOrNull ?: return@withContext true

        val loaderVersion = neoForgeVersion ?: forgeVersion ?: return@withContext true
        val loaderName = if (isNeoForge) "NeoForge" else "Forge"
        val neoForgePatchedVersion = if (isNeoForge) {
            gameArgs?.let { args ->
                args.mapIndexedNotNull { i, el ->
                    if (el is JsonPrimitive && el.content == "--fml.neoForgeVersion")
                        (args.getOrNull(i + 1) as? JsonPrimitive)?.content
                    else null
                }.firstOrNull()
            } ?: loaderVersion
        } else loaderVersion

        var installerFile = listOf(
            File(minecraftDir, "cache/forge-$mcVersion-$loaderVersion-installer.jar"),
            File(minecraftDir, "cache/neoforge-$loaderVersion-installer.jar"),
        ).firstOrNull { it.isFile && it.length() > 1000 }

        if (installerFile == null) {
            val cacheDir = File(minecraftDir, "cache")
            cacheDir.mkdirs()
            val fallbackTarget = if (isNeoForge) {
                File(cacheDir, "neoforge-$loaderVersion-installer.jar")
            } else {
                File(cacheDir, "forge-$mcVersion-$loaderVersion-installer.jar")
            }

            val urls = if (isNeoForge) {
                listOf(
                    "https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-installer.jar",
                    "https://maven.neoforged.net/releases/net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-installer.jar",
                    "https://maven.neoforged.net/net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-installer.jar",
                )
            } else {
                listOf(
                    "https://bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/$mcVersion-$loaderVersion/forge-$mcVersion-$loaderVersion-installer.jar",
                    "https://maven.minecraftforge.net/net/minecraftforge/forge/$mcVersion-$loaderVersion/forge-$mcVersion-$loaderVersion-installer.jar",
                )
            }

            var downloaded = false
            for (url in urls) {
                fallbackTarget.delete()
                println("[RepairForge] 下载缺失 installer: $url")
                if (downloadFile(url, fallbackTarget) && fallbackTarget.isFile && fallbackTarget.length() > 1000) {
                    downloaded = true
                    installerFile = fallbackTarget
                    break
                }
            }

            if (!downloaded || installerFile == null) {
                println("[RepairForge] 未找到 $loaderName installer, 无法修复")
                return@withContext false
            }
        }

        val zip = runCatching { java.util.zip.ZipFile(installerFile) }.getOrNull() ?: return@withContext false
        val profileEntry = zip.getEntry("install_profile.json")
        if (profileEntry == null) { zip.close(); return@withContext false }
        val installProfile = json.parseToJsonElement(
            zip.getInputStream(profileEntry).bufferedReader().readText()
        ).jsonObject
        val requiresPatchedClient = isNeoForge && requiresNeoForgePatchedClient(installProfile)
        if (isNeoForge && versionJsonFile.isFile) {
            writeNeoForgePatchedRequirement(versionJsonFile, requiresPatchedClient)
        }

        val processors = installProfile["processors"]?.jsonArray
        if (processors == null || processors.isEmpty()) { zip.close(); return@withContext true }

        val librariesDir = File(minecraftDir, "libraries")
        val currentVersionId = versionJsonFile.nameWithoutExtension
        val preferredBaseVersionId = root["inheritsFrom"]?.jsonPrimitive?.contentOrNull ?: mcVersion
        val baseVersionId = linkedSetOf(preferredBaseVersionId, mcVersion, currentVersionId)
            .firstOrNull { id ->
                val jar = File(minecraftDir, "versions/$id/$id.jar")
                jar.isFile && jar.length() > 0L
            } ?: preferredBaseVersionId
        val minecraftJar = try {
            resolveMinecraftJar(minecraftDir, baseVersionId, mcVersion, currentVersionId)
        } catch (e: RuntimeException) {
            println("[RepairForge] 基础版本 JAR 不存在 ($preferredBaseVersionId / $mcVersion)，当前版本也缺失，跳过修复")
            zip.close()
            return@withContext false
        }

        val vars = mutableMapOf<String, String>()
        vars["{MINECRAFT_JAR}"] = minecraftJar.absolutePath
        vars["{SIDE}"] = "client"
        vars["{MINECRAFT_VERSION}"] = mcVersion
        vars["{ROOT}"] = File(minecraftDir).absolutePath
        vars["{INSTALLER}"] = installerFile.absolutePath
        vars["{LIBRARY_DIR}"] = librariesDir.absolutePath
        vars["{BINPATCH}"] = ""

        val dataMap = installProfile["data"]?.jsonObject ?: JsonObject(emptyMap())
        val tempDir = File(minecraftDir, "cache/forge_temp")
        tempDir.mkdirs()
        for ((key, valEl) in dataMap) {
            val clientVal = valEl.jsonObject["client"]?.jsonPrimitive?.contentOrNull ?: continue
            val resolved = if (clientVal.startsWith("[") && clientVal.endsWith("]")) {
                File(librariesDir, mavenToPath(clientVal.drop(1).dropLast(1))).absolutePath
            } else if (clientVal.startsWith("/")) {
                val entry = zip.getEntry(clientVal.removePrefix("/"))
                if (entry != null) {
                    val tmpFile = File(tempDir, clientVal.removePrefix("/").replace("/", "_"))
                    if (!tmpFile.exists() || tmpFile.length() == 0L) {
                        zip.getInputStream(entry).use { inp -> tmpFile.outputStream().use { out -> inp.copyTo(out) } }
                    }
                    tmpFile.absolutePath
                } else clientVal
            } else if (clientVal.startsWith("'") && clientVal.endsWith("'")) {
                clientVal.drop(1).dropLast(1)
            } else clientVal
            vars["{$key}"] = resolved
        }

        var needsRepair = false
        for (procEl in processors) {
            val proc = procEl.jsonObject
            val sides = proc["sides"]?.jsonArray?.map { it.jsonPrimitive.content }
            if (sides != null && "client" !in sides) continue
            val outputs = resolveProcessorOutputs(proc, vars, librariesDir)
            if (outputs.isNotEmpty() && !verifyProcessorOutputs(outputs)) {
                needsRepair = true
                println("[RepairForge] 处理器 outputs 校验失败")
                break
            }
        }

        if (!needsRepair) {
            val criticalKeys = listOf("PATCHED", "MC_SRG")
            for (key in criticalKeys) {
                val clientVal = dataMap[key]?.jsonObject?.get("client")?.jsonPrimitive?.contentOrNull ?: continue
                if (!clientVal.startsWith("[") || !clientVal.endsWith("]")) continue
                val coord = clientVal.drop(1).dropLast(1)
                val path = mavenToPath(coord)
                val file = File(librariesDir, path)
                if (!file.isFile || file.length() == 0L) {
                    needsRepair = true
                    println("[RepairForge] 关键产物缺失: $key -> $path")
                    break
                }
            }
        }

        if (!needsRepair && requiresPatchedClient) {
            val patchedFile = File(
                librariesDir,
                "net/neoforged/minecraft-client-patched/$neoForgePatchedVersion/minecraft-client-patched-$neoForgePatchedVersion.jar",
            )
            if (!patchedFile.isFile || patchedFile.length() <= 0L) {
                needsRepair = true
                println("[RepairForge] NeoForge patched 客户端缺失: ${patchedFile.absolutePath}")
            }
        }

        if (!needsRepair && isNeoForge) {
            val neoFormVersion = gameArgs?.let { args ->
                args.mapIndexedNotNull { i, el ->
                    if (el is JsonPrimitive && el.content == "--fml.neoFormVersion")
                        (args.getOrNull(i + 1) as? JsonPrimitive)?.content
                    else null
                }.firstOrNull()
            }
            val clientJar = File(librariesDir, "net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-client.jar")
            val srgJar = if (neoFormVersion != null) {
                File(librariesDir, "net/minecraft/client/$mcVersion-$neoFormVersion/client-$mcVersion-$neoFormVersion-srg.jar")
            } else null
            if (clientJar.isFile && !isValidProcessorOutputJar(clientJar)) {
                needsRepair = true
                println("[RepairForge] NeoForge client jar 内容校验失败（无 .class 文件）: ${clientJar.absolutePath}")
            }
            if (!needsRepair && srgJar != null && srgJar.isFile && !isValidProcessorOutputJar(srgJar)) {
                needsRepair = true
                println("[RepairForge] SRG client jar 内容校验失败（无 .class 文件）: ${srgJar.absolutePath}")
            }
        }

        if (!needsRepair) {
            zip.close()
            tempDir.deleteRecursively()
            println("[RepairForge] $loaderName 处理器输出完整，无需修复")
            return@withContext true
        }

        println("[RepairForge] $loaderName 处理器输出不完整，执行修复...")
        onProgress?.invoke("正在修复 $loaderName 安装 (重跑处理器)...")

        zip.close()
        try {
            runForgeProcessors(
                installProfile = installProfile,
                minecraftDir = minecraftDir,
                mcVersion = mcVersion,
                loaderVersion = loaderVersion,
                javaPath = javaPath,
                loaderName = loaderName,
                baseVersionId = baseVersionId,
            )
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            emitError(loaderName, "$loaderName 修复失败: ${e.message ?: "未知错误"}")
            throw e
        }

        if (requiresPatchedClient) {
            val patchedFile = File(
                librariesDir,
                "net/neoforged/minecraft-client-patched/$neoForgePatchedVersion/minecraft-client-patched-$neoForgePatchedVersion.jar",
            )
            if (!patchedFile.isFile || patchedFile.length() <= 0L) {
                tempDir.deleteRecursively()
                println("[RepairForge] NeoForge 修复后仍缺少 patched 客户端: ${patchedFile.absolutePath}")
                emitError(loaderName, "NeoForge 修复后仍缺少 patched 客户端")
                return@withContext false
            }
        }

        tempDir.deleteRecursively()
        onProgress?.invoke("$loaderName 修复完成")
        emitDone(loaderName, "$loaderName 修复完成")
        println("[RepairForge] $loaderName 处理器修复完成")
        true
    }

    fun launchInstall(
        loaderType: String,
        mcVersion: String,
        loaderVersion: String,
        minecraftDir: String,
        javaPath: String = "java",
        maxThreads: Int = 64,
        forgeBuild: Int = 0,
        onDone: (String) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        val taskId = "loader_$loaderType"
        val job = globalScope.launch {
            try {
                val versionId = when (loaderType) {
                    "Fabric" -> installFabric(mcVersion, loaderVersion, minecraftDir, maxThreads)
                    "Forge" -> installForge(
                        mcVersion = mcVersion,
                        loaderVersion = loaderVersion,
                        forgeBuild = forgeBuild,
                        minecraftDir = minecraftDir,
                        javaPath = javaPath,
                    )
                    "NeoForge" -> installNeoForge(
                        mcVersion = mcVersion,
                        loaderVersion = loaderVersion,
                        minecraftDir = minecraftDir,
                        javaPath = javaPath,
                    )
                    else -> throw RuntimeException("未知加载器: $loaderType")
                }
                onDone(versionId)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                emitError(loaderType, e.message ?: "安装失败")
                onError(e.message ?: "安装失败")
            } finally {
                activeJobs.remove(taskId)
            }
        }
        activeJobs[taskId] = job
    }

    suspend fun installFabric(
        mcVersion: String,
        loaderVersion: String,
        minecraftDir: String,
        maxThreads: Int = 64,
    ): String = withContext(Dispatchers.IO) {
        val versionId = "$mcVersion-fabric-$loaderVersion"
        emit("Fabric", "正在获取 Fabric 配置...", 0.1f)
        val versionDir = File(minecraftDir, "versions/$versionId")
        versionDir.mkdirs()

        val urls = listOf(
            "https://bmclapi2.bangbang93.com/fabric-meta/v2/versions/loader/$mcVersion/$loaderVersion/profile/json",
            "https://meta.fabricmc.net/v2/versions/loader/$mcVersion/$loaderVersion/profile/json",
        )
        var profileText = ""
        for (url in urls) {
            try {
                profileText = httpGet(url)
                if (profileText.isNotBlank()) break
            } catch (_: Exception) {}
        }
        if (profileText.isBlank()) throw RuntimeException("无法获取 Fabric profile JSON")
        emit("Fabric", "正在保存版本配置...", 0.3f)

        val root = json.parseToJsonElement(profileText).jsonObject.toMutableMap()
        root["id"] = JsonPrimitive(versionId)
        root["inheritsFrom"] = JsonPrimitive(mcVersion)
        val finalJson = JsonObject(root).toString()
        File(versionDir, "$versionId.json").writeText(finalJson, Charsets.UTF_8)

        val libs = root["libraries"]?.jsonArray ?: JsonArray(emptyList())
        val artifacts = mutableListOf<ArtifactDownloadCandidate>()
        val librariesDir = File(minecraftDir, "libraries")

        for (libEl in libs) {
            val lib = libEl.jsonObject
            val name = lib["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val libUrl = lib["url"]?.jsonPrimitive?.contentOrNull ?: "https://maven.fabricmc.net/"
            val sha1 = lib["sha1"]?.jsonPrimitive?.contentOrNull

            val path = mavenToPath(name)
            val dest = File(librariesDir, path)
            if (dest.exists() && dest.length() > 0 && (sha1 == null || verifyFile(dest, sha1))) continue

            val rawUrl = "${libUrl.trimEnd('/')}/$path"
            val candidates = mutableListOf<String>()
            candidates.add("https://bmclapi2.bangbang93.com/maven/$path")
            candidates.add(rawUrl)
            candidates.add("https://maven.fabricmc.net/$path")
            candidates.add("https://libraries.minecraft.net/$path")
            candidates.add("https://repo1.maven.org/maven2/$path")
            artifacts.add(ArtifactDownloadCandidate(dest, sha1, candidates, path))
        }

        if (artifacts.isNotEmpty()) {
            emit("Fabric", "正在下载 ${artifacts.size} 个库文件...", 0.5f)
            println("[LoaderInstaller] Fabric: 下载 ${artifacts.size} 个库文件")
            ensureArtifactsDownloaded("Fabric", artifacts, 0.5f, 0.9f)
        }

        println("[LoaderInstaller] Fabric $loaderVersion 安装完成 -> $versionId")
        emitDone("Fabric", "Fabric $loaderVersion 安装完成")
        versionId
    }

    suspend fun installForge(
        mcVersion: String,
        loaderVersion: String,
        forgeBuild: Int,
        minecraftDir: String,
        baseVersionId: String = mcVersion,
        javaPath: String,
    ): String = withContext(Dispatchers.IO) {
        emit("Forge", "正在下载 Forge 安装器...", 0.05f)
        val mavenPath = "net/minecraftforge/forge/$mcVersion-$loaderVersion/forge-$mcVersion-$loaderVersion-installer.jar"
        val installerUrls = buildList {
            if (forgeBuild > 0) {
                add("https://bmclapi2.bangbang93.com/forge/download/$forgeBuild")
            }
            add("https://bmclapi2.bangbang93.com/maven/$mavenPath")
            add("https://mirrors.cernet.edu.cn/bmclapi/$mavenPath")
            add("https://maven.minecraftforge.net/$mavenPath")
        }
        val cacheDir = File(minecraftDir, "cache")
        cacheDir.mkdirs()
        val installerFile = File(cacheDir, "forge-$mcVersion-$loaderVersion-installer.jar")

        fun isValidInstallerJar(file: File): Boolean {
            if (!file.isFile || file.length() < 1000) return false
            return runCatching {
                java.util.zip.ZipFile(file).use { z ->
                    z.getEntry("install_profile.json") != null
                }
            }.getOrDefault(false)
        }

        if (!isValidInstallerJar(installerFile)) {
            var ok = false
            installerUrls.forEach { installerUrl ->
                if (ok) return@forEach
                installerFile.delete()
                println("[LoaderInstaller] 下载 Forge installer: $installerUrl")
                ok = downloadFile(installerUrl, installerFile) && isValidInstallerJar(installerFile)
            }
            if (!ok) throw RuntimeException("Forge installer 下载失败")
        }

        if (!isValidInstallerJar(installerFile)) {
            throw RuntimeException("Forge installer 文件已损坏，请重试导入")
        }
        emit("Forge", "正在解析安装器...", 0.15f)

        val zip = runCatching { java.util.zip.ZipFile(installerFile) }
            .getOrElse { throw RuntimeException("Forge installer 无法解析，可能文件损坏: ${installerFile.absolutePath}") }
        val installProfileEntry = zip.getEntry("install_profile.json")
            ?: throw RuntimeException("installer jar 中没有 install_profile.json")
        val installProfileText = zip.getInputStream(installProfileEntry).bufferedReader().readText()
        val installProfile = json.parseToJsonElement(installProfileText).jsonObject

        val versionJsonEntry = zip.getEntry("version.json")
        val isNewFormat = versionJsonEntry != null

        val versionId: String
        val versionJsonText: String
        val librariesDir = File(minecraftDir, "libraries")

        if (isNewFormat) {
            versionJsonText = zip.getInputStream(versionJsonEntry!!).bufferedReader().readText()
            val vJson = json.parseToJsonElement(versionJsonText).jsonObject
            versionId = vJson["id"]?.jsonPrimitive?.contentOrNull ?: "$mcVersion-forge-$loaderVersion"
        } else {
            val vInfo = installProfile["versionInfo"]?.jsonObject
                ?: throw RuntimeException("install_profile.json 缺少 versionInfo")
            versionId = vInfo["id"]?.jsonPrimitive?.contentOrNull ?: "$mcVersion-forge-$loaderVersion"
            versionJsonText = vInfo.toString()
        }
        emit("Forge", "版本: $versionId", 0.2f)

        val versionDir = File(minecraftDir, "versions/$versionId")
        versionDir.mkdirs()
        File(versionDir, "$versionId.json").writeText(versionJsonText, Charsets.UTF_8)
        println("[LoaderInstaller] Forge version.json -> $versionDir")

        emit("Forge", "正在提取 Forge 库文件...", 0.25f)
        val mavenEntries = zip.entries().asSequence().filter { it.name.startsWith("maven/") && !it.isDirectory }
        var extractCount = 0
        for (entry in mavenEntries) {
            val relPath = entry.name.removePrefix("maven/")
            val dest = File(librariesDir, relPath)
            dest.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
            extractCount++
        }
        println("[LoaderInstaller] 从 installer 提取 $extractCount 个 maven 文件")

        emit("Forge", "正在分析依赖库...", 0.3f)
        val versionRoot = json.parseToJsonElement(versionJsonText).jsonObject
        val artifacts = linkedMapOf<String, ArtifactDownloadCandidate>()

        val runtimeLibs = versionRoot["libraries"]?.jsonArray ?: JsonArray(emptyList())
        for (libEl in runtimeLibs) {
            val lib = libEl.jsonObject
            val name = lib["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val path = mavenToPath(name)
            val dest = File(librariesDir, path)
            val artifact = lib["downloads"]?.jsonObject?.get("artifact")?.jsonObject
            val sha1 = artifact?.get("sha1")?.jsonPrimitive?.contentOrNull
            if (dest.exists() && dest.length() > 0L && (sha1 == null || verifyFile(dest, sha1))) continue

            val artUrl = artifact?.get("url")?.jsonPrimitive?.contentOrNull
            val libUrl = lib["url"]?.jsonPrimitive?.contentOrNull

            val rawUrl = when {
                artUrl != null && artUrl.isNotBlank() -> artUrl
                libUrl != null && libUrl.isNotBlank() -> "${libUrl.trimEnd('/')}/$path"
                else -> "https://libraries.minecraft.net/$path"
            }
            val candidates = hmclCandidateUrls(rawUrl, path, preferNeoForge = false)
            upsertArtifactCandidate(artifacts, dest, sha1, candidates, path)
        }

        if (isNewFormat) {
            val profileLibs = installProfile["libraries"]?.jsonArray ?: JsonArray(emptyList())
            for (libEl in profileLibs) {
                val lib = libEl.jsonObject
                val name = lib["name"]?.jsonPrimitive?.contentOrNull ?: continue
                val path = mavenToPath(name)
                val dest = File(librariesDir, path)
                val artifact = lib["downloads"]?.jsonObject?.get("artifact")?.jsonObject
                val sha1 = artifact?.get("sha1")?.jsonPrimitive?.contentOrNull
                if (dest.exists() && dest.length() > 0L && (sha1 == null || verifyFile(dest, sha1))) continue

                val artUrl = artifact?.get("url")?.jsonPrimitive?.contentOrNull
                val rawUrl = when {
                    artUrl != null && artUrl.isNotBlank() -> artUrl
                    else -> "https://libraries.minecraft.net/$path"
                }
                val candidates = hmclCandidateUrls(rawUrl, path, preferNeoForge = false)
                upsertArtifactCandidate(artifacts, dest, sha1, candidates, path)
            }
            addProcessorDownloadTasks(
                installProfile,
                artifacts,
                librariesDir,
                listOf(
                    "https://bmclapi2.bangbang93.com/maven",
                    "https://mirrors.cernet.edu.cn/bmclapi",
                    "https://maven.minecraftforge.net",
                    "https://libraries.minecraft.net",
                ),
            )
        }

        if (artifacts.isNotEmpty()) {
            emit("Forge", "正在下载 ${artifacts.size} 个库文件...", 0.35f)
            println("[LoaderInstaller] Forge: 下载 ${artifacts.size} 个库文件 (多源回退)")
            ensureArtifactsDownloaded("Forge", artifacts.values.toList(), 0.35f, 0.69f)
        }

        if (isNewFormat) {
            emit("Forge", "正在运行处理器...", 0.7f)
            runForgeProcessors(
                installProfile = installProfile,
                minecraftDir = minecraftDir,
                mcVersion = mcVersion,
                loaderVersion = loaderVersion,
                javaPath = javaPath,
                loaderName = "Forge",
                baseVersionId = baseVersionId,
            )
        }

        zip.close()
        println("[LoaderInstaller] Forge $loaderVersion 安装完成 -> $versionId")
        emitDone("Forge", "Forge $loaderVersion 安装完成")
        versionId
    }

    private suspend fun runForgeProcessors(
        installProfile: JsonObject,
        minecraftDir: String,
        mcVersion: String,
        loaderVersion: String,
        javaPath: String,
        loaderName: String = "Forge",
        baseVersionId: String = mcVersion,
    ) = withContext(Dispatchers.IO) {
        val processors = installProfile["processors"]?.jsonArray ?: return@withContext
        val dataMap = installProfile["data"]?.jsonObject ?: JsonObject(emptyMap())
        val librariesDir = File(minecraftDir, "libraries")
        val javaExe = resolveJavaExe(javaPath)
        val minecraftJar = resolveMinecraftJar(minecraftDir, baseVersionId, mcVersion)

        val installerJarPath = listOf(
            File(minecraftDir, "cache/forge-$mcVersion-$loaderVersion-installer.jar"),
            File(minecraftDir, "cache/neoforge-$loaderVersion-installer.jar"),
        ).firstOrNull { it.exists() }
        val installerZip = installerJarPath?.let { runCatching { java.util.zip.ZipFile(it) }.getOrNull() }

        val vars = mutableMapOf<String, String>()
        vars["{MINECRAFT_JAR}"] = minecraftJar.absolutePath
        vars["{SIDE}"] = "client"
        vars["{MINECRAFT_VERSION}"] = mcVersion
        vars["{ROOT}"] = File(minecraftDir).absolutePath
        vars["{INSTALLER}"] = installerJarPath?.absolutePath ?: ""
        vars["{LIBRARY_DIR}"] = librariesDir.absolutePath
        vars["{BINPATCH}"] = ""

        val tempDir = File(minecraftDir, "cache/forge_temp")
        tempDir.mkdirs()

        val dataDownloads = mutableListOf<DownloadTask>()
        val dataDestinations = mutableMapOf<String, String>()
        for ((key, valEl) in dataMap) {
            val clientVal = valEl.jsonObject["client"]?.jsonPrimitive?.contentOrNull ?: continue
            if (clientVal.startsWith("http")) {
                val url = DownloadManager.mirrorUrl(clientVal)
                val fileName = url.substringAfterLast("/").substringBefore("?")
                val hash = java.security.MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
                val dest = File(minecraftDir, "cache/forge_data/${key}_${hash}_$fileName")
                dataDestinations[key] = dest.absolutePath
                if (!dest.exists() || dest.length() == 0L) {
                    dataDownloads.add(DownloadTask(url, dest))
                }
            }
        }
        if (dataDownloads.isNotEmpty()) {
            emit(loaderName, "正在预下载处理器数据文件...", 0.65f)
            DownloadManager.downloadAllIsolated(dataDownloads, maxConcurrency = 64)
        }

        for ((key, valEl) in dataMap) {
            val clientVal = valEl.jsonObject["client"]?.jsonPrimitive?.contentOrNull ?: continue
            val resolved = if (clientVal.startsWith("[") && clientVal.endsWith("]")) {
                File(librariesDir, mavenToPath(clientVal.drop(1).dropLast(1))).absolutePath
            } else if (clientVal.startsWith("/") && installerZip != null) {
                val entryName = clientVal.removePrefix("/")
                val entry = installerZip.getEntry(entryName)
                if (entry != null) {
                    val tmpFile = File(tempDir, entryName.replace("/", "_"))
                    installerZip.getInputStream(entry).use { inp -> tmpFile.outputStream().use { out -> inp.copyTo(out) } }
                    tmpFile.absolutePath
                } else clientVal
            } else if (clientVal.startsWith("http")) {
                val localPath = dataDestinations[key] ?: ""
                if (localPath.isNotBlank() && File(localPath).exists()) {
                    localPath
                } else clientVal
            } else if (clientVal.startsWith("'") && clientVal.endsWith("'")) {
                clientVal.drop(1).dropLast(1)
            } else {
                clientVal
            }
            vars["{$key}"] = resolved
        }

        var idx = 0
        for (procEl in processors) {
            val proc = procEl.jsonObject
            idx++

            val sides = proc["sides"]?.jsonArray?.map { it.jsonPrimitive.content }
            if (sides != null && "client" !in sides) continue

            val jarCoord = proc["jar"]?.jsonPrimitive?.contentOrNull ?: continue
            val jarPath = File(librariesDir, mavenToPath(jarCoord))
            if (!jarPath.isFile || jarPath.length() <= 0) {
                if (!downloadMavenCoordinate(jarCoord, librariesDir, processorRepositories(jarCoord))) {
                    throw RuntimeException("加载器处理器缺少主程序: ${jarPath.absolutePath}")
                }
            }

            val cp = mutableListOf(jarPath.absolutePath)
            proc["classpath"]?.jsonArray?.forEach { cpEl ->
                val coord = cpEl.jsonPrimitive.content
                val cpFile = File(librariesDir, mavenToPath(coord))
                if (!cpFile.isFile || cpFile.length() <= 0) {
                    if (!downloadMavenCoordinate(coord, librariesDir, processorRepositories(coord))) {
                        throw RuntimeException("加载器处理器缺少依赖: ${cpFile.absolutePath}")
                    }
                }
                cp.add(cpFile.absolutePath)
            }

            val mainClass = try {
                val jarZip = java.util.zip.ZipFile(jarPath)
                val manifest = jarZip.getEntry("META-INF/MANIFEST.MF")
                val mainCls = jarZip.getInputStream(manifest).bufferedReader().readLines()
                    .firstOrNull { it.startsWith("Main-Class:") }
                    ?.substringAfter("Main-Class:")?.trim() ?: ""
                jarZip.close()
                mainCls
            } catch (_: Exception) { "" }

            if (mainClass.isBlank()) {
                throw RuntimeException("加载器处理器 #$idx 无法获取 Main-Class: ${jarPath.absolutePath}")
            }

            val expectedOutputs = resolveProcessorOutputs(proc, vars, librariesDir)
            if (expectedOutputs.isNotEmpty() && verifyProcessorOutputs(expectedOutputs)) {
                emit(loaderName, "处理器 $idx/${processors.size}: 输出已存在，跳过", 0.7f + 0.25f * idx / processors.size)
                continue
            }

            val rawArgs = proc["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val resolvedArgs = rawArgs.map { arg ->
                resolveProcessorLiteral(arg, vars, librariesDir)
            }

            val options = parseProcessorOptions(resolvedArgs)
            if (options["task"] == "DOWNLOAD_MOJMAPS" && options["side"] == "client") {
                val mappingsVersion = options["version"].orEmpty().ifBlank { mcVersion }
                val outputPath = options["output"].orEmpty()
                if (outputPath.isNotBlank()) {
                    emit(loaderName, "处理器 $idx/${processors.size}: 下载 Mojang 映射", 0.7f + 0.25f * idx / processors.size)
                    downloadClientMappings(mappingsVersion, File(outputPath))
                    continue
                }
            }

            val shortName = jarCoord.substringAfterLast(":")
            val isHeavy = shortName.contains("binarypatcher", true) ||
                    shortName.contains("ForgeAutoRenamingTool", true) ||
                    shortName.contains("fatjar", true) ||
                    shortName.contains("jarsplitter", true)
            val hint = if (isHeavy) " (可能需要几分钟)" else ""
            emit(loaderName, "处理器 $idx/${processors.size}: $shortName$hint", 0.7f + 0.25f * idx / processors.size)
            println("[LoaderInstaller] Processor #$idx: $mainClass ${resolvedArgs.joinToString(" ")}")

            val cmd = mutableListOf(javaExe, "-cp", cp.joinToString(File.pathSeparator), mainClass)
            cmd.addAll(resolvedArgs)

            val baseFrac = 0.7f + 0.25f * idx / processors.size
            var attempt = 1
            val maxAttempts = 3
            var lastResult: ExecResult? = null
            while (attempt <= maxAttempts) {
                val startTime = System.currentTimeMillis()
                val procRunning = java.util.concurrent.atomic.AtomicBoolean(true)
                val ticker = Thread({
                    while (procRunning.get()) {
                        try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                        if (!procRunning.get()) break
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000
                        emit(loaderName, "处理器 $idx/${processors.size}: $shortName (${elapsed}s)$hint", baseFrac)
                    }
                }, "proc-ticker-$idx-$attempt").apply { isDaemon = true; start() }

                val result = runProcess(cmd, File(minecraftDir), timeoutSeconds = 600)
                procRunning.set(false)
                ticker.interrupt()
                lastResult = result
                if (result.exitCode == 0) {
                    break
                }

                val recovered = recoverProcessorDependenciesFromOutput(result.output, librariesDir)
                if (recovered > 0 && attempt < maxAttempts) {
                    println("[LoaderInstaller] Processor #$idx attempt=$attempt 失败，已补齐 $recovered 个依赖，准备重试")
                    emit(loaderName, "处理器 $idx/${processors.size} 失败，已补齐 $recovered 个依赖，重试中(${attempt + 1}/$maxAttempts)", baseFrac)
                    attempt++
                    continue
                }
                break
            }

            val finalResult = lastResult
            if (finalResult != null && finalResult.exitCode != 0) {
                val msg = finalResult.output.takeLast(1200)
                val reason = if (finalResult.timedOut) "执行超时" else "执行失败 (exit=${finalResult.exitCode})"
                println("[LoaderInstaller] Processor #$idx FAILED: $reason: $msg")
                throw RuntimeException("加载器处理器 #$idx $reason: $msg")
            }

            if (expectedOutputs.isNotEmpty() && !verifyProcessorOutputs(expectedOutputs)) {
                throw RuntimeException("加载器处理器 #$idx 输出文件校验失败")
            }
        }

        installerZip?.close()
        tempDir.deleteRecursively()
    }

    suspend fun installNeoForge(
        mcVersion: String,
        loaderVersion: String,
        minecraftDir: String,
        baseVersionId: String = mcVersion,
        javaPath: String,
    ): String = withContext(Dispatchers.IO) {
        emit("NeoForge", "正在下载 NeoForge 安装器...", 0.05f)
        val installerUrls = listOf(
            "https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-installer.jar",
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-installer.jar",
            "https://maven.neoforged.net/net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-installer.jar",
        )
        val cacheDir = File(minecraftDir, "cache")
        cacheDir.mkdirs()
        val installerFile = File(cacheDir, "neoforge-$loaderVersion-installer.jar")

        if (!installerFile.exists() || installerFile.length() < 1000) {
            var got = false
            for (attempt in 1..3) {
                for (url in installerUrls) {
                    installerFile.delete()
                    println("[LoaderInstaller] 下载 NeoForge installer (尝试 $attempt): $url")
                    if (downloadFile(url, installerFile) && installerFile.length() > 1000) {
                        got = true
                        break
                    }
                }
                if (got) break
            }
            if (!got) throw RuntimeException("NeoForge installer 下载失败")
        }

        emit("NeoForge", "正在解析安装器...", 0.15f)
        val zip = java.util.zip.ZipFile(installerFile)
        val installProfileEntry = zip.getEntry("install_profile.json")
            ?: throw RuntimeException("installer jar 中没有 install_profile.json")
        val installProfileText = zip.getInputStream(installProfileEntry).bufferedReader().readText()
        val installProfile = json.parseToJsonElement(installProfileText).jsonObject
        val versionJsonEntry = zip.getEntry("version.json")
            ?: throw RuntimeException("installer jar 中没有 version.json")
        val versionJsonText = zip.getInputStream(versionJsonEntry).bufferedReader().readText()
        val versionRoot = json.parseToJsonElement(versionJsonText).jsonObject
        val versionId = versionRoot["id"]?.jsonPrimitive?.contentOrNull ?: "$mcVersion-neoforge-$loaderVersion"

        emit("NeoForge", "版本: $versionId", 0.2f)

        emit("NeoForge", "正在提取 NeoForge 库文件...", 0.25f)
        val mavenEntries = zip.entries().asSequence().filter { it.name.startsWith("maven/") && !it.isDirectory }
        for (entry in mavenEntries) {
            val path = entry.name.removePrefix("maven/")
            val out = File(minecraftDir, "libraries/$path")
            out.parentFile?.mkdirs()
            if (!out.exists() || out.length() == 0L) {
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }

        val librariesDir = File(minecraftDir, "libraries")
        val artifacts = linkedMapOf<String, ArtifactDownloadCandidate>()
        val allLibs = mutableListOf<JsonObject>()
        installProfile["libraries"]?.jsonArray?.forEach { allLibs.add(it.jsonObject) }
        versionRoot["libraries"]?.jsonArray?.forEach { allLibs.add(it.jsonObject) }

        allLibs.forEach { lib ->
            val artifact = lib["downloads"]?.jsonObject?.get("artifact")?.jsonObject ?: return@forEach
            val path = artifact["path"]!!.jsonPrimitive.content
            val sha1 = artifact["sha1"]?.jsonPrimitive?.contentOrNull
            val dest = File(librariesDir, path)
            if (!dest.exists() || (sha1 != null && !verifyFile(dest, sha1))) {
                var rawUrl = artifact["url"]?.jsonPrimitive?.contentOrNull ?: ""
                if (rawUrl.isBlank()) rawUrl = "https://libraries.minecraft.net/$path"
                val candidates = hmclCandidateUrls(
                    rawUrl = rawUrl,
                    path = path,
                    preferNeoForge = path.startsWith("net/neoforged/"),
                )
                upsertArtifactCandidate(artifacts, dest, sha1, candidates, path)
            }
        }

        addProcessorDownloadTasks(
            installProfile,
            artifacts,
            librariesDir,
            listOf(
                "https://bmclapi2.bangbang93.com/maven",
                "https://mirrors.cernet.edu.cn/bmclapi",
                "https://maven.neoforged.net/releases",
                "https://maven.minecraftforge.net",
            )
        )

        if (artifacts.isNotEmpty()) {
            emit("NeoForge", "正在下载/校验 ${artifacts.size} 个库文件...", 0.35f)
            ensureArtifactsDownloaded("NeoForge", artifacts.values.toList(), 0.35f, 0.70f)
        }
        
        emit("NeoForge", "正在执行加载器处理器...", 0.70f)
        runForgeProcessors(
            installProfile = installProfile,
            minecraftDir = minecraftDir,
            mcVersion = mcVersion,
            loaderVersion = loaderVersion,
            javaPath = javaPath,
            loaderName = "NeoForge",
            baseVersionId = baseVersionId,
        )
        zip.close()

        val versionDir = File(minecraftDir, "versions/$versionId")
        val versionJsonFile = File(versionDir, "$versionId.json")
        if (!versionJsonFile.isFile) {
            versionDir.mkdirs()
            versionJsonFile.writeText(versionJsonText, Charsets.UTF_8)
        }
        val requiresPatchedClient = requiresNeoForgePatchedClient(installProfile)
        writeNeoForgePatchedRequirement(versionJsonFile, requiresPatchedClient)
        patchNeoForgeBaseVersion(versionJsonFile, mcVersion, baseVersionId)
        ensureNeoForgeUniversalLibrary(versionJsonFile, installProfile, loaderVersion, librariesDir)
        ensureNeoForgePatchedManifest(versionJsonFile, loaderVersion, librariesDir)

        val neoForgeVersionForPatched = runCatching {
            val root = json.parseToJsonElement(versionJsonFile.readText(Charsets.UTF_8)).jsonObject
            val args = root["arguments"]?.jsonObject?.get("game")?.jsonArray ?: JsonArray(emptyList())
            args.forEachIndexed { index, element ->
                if (element is JsonPrimitive && element.content == "--fml.neoForgeVersion") {
                    return@runCatching (args.getOrNull(index + 1) as? JsonPrimitive)?.contentOrNull ?: loaderVersion
                }
            }
            loaderVersion
        }.getOrDefault(loaderVersion)
        val patchedFile = File(
            librariesDir,
            "net/neoforged/minecraft-client-patched/$neoForgeVersionForPatched/minecraft-client-patched-$neoForgeVersionForPatched.jar",
        )
        if (requiresPatchedClient && (!patchedFile.isFile || patchedFile.length() <= 0L)) {
            throw RuntimeException("NeoForge 安装不完整：缺少 ${patchedFile.absolutePath}")
        }

        println("[LoaderInstaller] NeoForge $loaderVersion 安装完成 -> $versionId")
        emitDone("NeoForge", "NeoForge $loaderVersion 安装完成")
        return@withContext versionId
    }

    private fun runProcess(cmd: List<String>, workingDir: File, timeoutSeconds: Long = 300): ExecResult {
        val process = ProcessBuilder(cmd)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val outputBuilder = StringBuilder()
        val drainThread = Thread({
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buf = CharArray(4096)
                    var n: Int
                    while (reader.read(buf).also { n = it } != -1) {
                        synchronized(outputBuilder) { outputBuilder.append(buf, 0, n) }
                    }
                }
            } catch (_: Exception) {}
        }, "proc-drain").apply { isDaemon = true; start() }

        val effectiveTimeout = if (timeoutSeconds <= 0) 300L else timeoutSeconds
        val finished = process.waitFor(effectiveTimeout, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        drainThread.join(5_000)
        val output = synchronized(outputBuilder) { outputBuilder.toString() }
        val exit = if (finished) process.exitValue() else -1
        return ExecResult(exit, output, timedOut = !finished)
    }

    private fun upsertArtifactCandidate(
        artifacts: MutableMap<String, ArtifactDownloadCandidate>,
        dest: File,
        sha1: String?,
        urls: List<String>,
        label: String,
    ) {
        val key = dest.absolutePath.lowercase()
        val mergedUrls = urls.filter { it.isNotBlank() }
        val existing = artifacts[key]
        if (existing == null) {
            artifacts[key] = ArtifactDownloadCandidate(
                dest = dest,
                sha1 = sha1,
                urls = mergedUrls.toMutableList(),
                label = label,
            )
            return
        }
        if (existing.sha1 == null && sha1 != null) {
            artifacts[key] = existing.copy(sha1 = sha1)
        }
        mergedUrls.forEach { url ->
            if (url !in existing.urls) {
                existing.urls.add(url)
            }
        }
    }

    private fun addProcessorDownloadTasks(
        installProfile: JsonObject,
        tasks: MutableList<DownloadTask>,
        librariesDir: File,
        repositories: List<String>,
    ) {
        val artifacts = linkedMapOf<String, ArtifactDownloadCandidate>()
        tasks.forEach { task ->
            upsertArtifactCandidate(
                artifacts = artifacts,
                dest = task.dest,
                sha1 = task.sha1,
                urls = listOf(task.url),
                label = task.dest.name,
            )
        }
        addProcessorDownloadTasks(installProfile, artifacts, librariesDir, repositories)

        tasks.clear()
        artifacts.values.forEach { item ->
            item.urls.distinct().forEach { url ->
                tasks.add(DownloadTask(url = url, dest = item.dest, sha1 = item.sha1))
            }
        }
    }

    private suspend fun ensureArtifactsDownloaded(
        loaderName: String,
        artifacts: List<ArtifactDownloadCandidate>,
        startFraction: Float,
        endFraction: Float,
    ) = withContext(Dispatchers.IO) {
        val total = artifacts.size.coerceAtLeast(1)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val sem = kotlinx.coroutines.sync.Semaphore(10)
        coroutineScope {
            artifacts.map { item ->
                launch {
                    sem.withPermit {
                        if (!(item.dest.exists() && item.dest.length() > 0L && (item.sha1 == null || verifyFile(item.dest, item.sha1)))) {
                            var success = false
                            for (url in item.urls.distinct()) {
                                println("[LoaderInstaller] 下载依赖 (${item.label}): $url")
                                item.dest.delete()
                                if (!downloadFile(url, item.dest) || item.dest.length() <= 0L) {
                                    continue
                                }
                                if (item.sha1 != null && !verifyFile(item.dest, item.sha1)) {
                                    item.dest.delete()
                                    continue
                                }
                                success = true
                                break
                            }
                            if (!success) {
                                throw RuntimeException("下载失败: ${item.dest.name}")
                            }
                        }

                        val current = done.incrementAndGet()
                        val progress = startFraction + (endFraction - startFraction) * (current.toFloat() / total)
                        emit(loaderName, "下载库文件 $current/${artifacts.size}: ${item.dest.name}", progress.coerceIn(startFraction, endFraction))
                    }
                }
            }.joinAll()
        }
    }

    private fun hmclCandidateUrls(
        rawUrl: String,
        path: String,
        preferNeoForge: Boolean,
    ): List<String> {
        val raw = rawUrl.trim()
        val result = linkedSetOf<String>()
        if (raw.isNotBlank()) {
            result.add(DownloadManager.mirrorUrl(raw))
            result.add(raw)
        }

        val pathNormalized = path.removePrefix("/")
        if (preferNeoForge) {
            result.add("https://maven.neoforged.net/releases/$pathNormalized")
            result.add("https://bmclapi2.bangbang93.com/maven/$pathNormalized")
            result.add("https://mirrors.cernet.edu.cn/bmclapi/$pathNormalized")
            result.add("https://maven.minecraftforge.net/$pathNormalized")
        } else {
            result.add("https://bmclapi2.bangbang93.com/maven/$pathNormalized")
            result.add("https://mirrors.cernet.edu.cn/bmclapi/$pathNormalized")
            result.add("https://maven.minecraftforge.net/$pathNormalized")
            result.add("https://maven.neoforged.net/releases/$pathNormalized")
        }

        result.add("https://libraries.minecraft.net/$pathNormalized")
        result.add("https://repo1.maven.org/maven2/$pathNormalized")
        result.add("https://repo.maven.apache.org/maven2/$pathNormalized")
        result.add("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/$pathNormalized")
        return result.filter { it.isNotBlank() }
    }

    private fun parseProcessorOptions(args: List<String>): Map<String, String> {
        val options = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val token = args[i]
            if (token.startsWith("--")) {
                val key = token.removePrefix("--")
                val next = args.getOrNull(i + 1)
                if (next != null && !next.startsWith("--")) {
                    options[key] = next
                    i += 2
                    continue
                }
                options[key] = ""
            }
            i++
        }
        return options
    }

    private fun resolveProcessorLiteral(
        literal: String,
        vars: Map<String, String>,
        librariesDir: File,
    ): String {
        if (literal.startsWith("{") && literal.endsWith("}")) {
            val key = literal.removePrefix("{").removeSuffix("}")
            return vars[literal] ?: vars[key] ?: ""
        }
        if (literal.startsWith("'") && literal.endsWith("'")) {
            return literal.drop(1).dropLast(1)
        }
        if (literal.startsWith("[") && literal.endsWith("]")) {
            return File(librariesDir, mavenToPath(literal.drop(1).dropLast(1))).absolutePath
        }

        var s = literal
        val matcher = Regex("\\[([a-zA-Z0-9_\\-.:@]+)]").findAll(s)
        for (match in matcher) {
            val libPath = File(librariesDir, mavenToPath(match.groupValues[1])).absolutePath
            s = s.replace(match.value, libPath)
        }
        for ((k, v) in vars) s = s.replace(k, v)
        return s
    }

    private fun resolveProcessorOutputs(
        processor: JsonObject,
        vars: Map<String, String>,
        librariesDir: File,
    ): Map<File, String> {
        val outputsEl = processor["outputs"]?.jsonObject ?: return emptyMap()
        val outputs = mutableMapOf<File, String>()
        outputsEl.forEach { (k, v) ->
            val outPath = resolveProcessorLiteral(k, vars, librariesDir)
            val expected = resolveProcessorLiteral(v.jsonPrimitive.content, vars, librariesDir)
            if (outPath.isNotBlank() && expected.isNotBlank()) {
                outputs[File(outPath)] = expected
            }
        }
        return outputs
    }

    private fun isValidProcessorOutputJar(file: File): Boolean {
        if (!file.isFile || file.length() <= 1000) return false
        return runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val entries = zip.entries()
                var hasClass = false
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.endsWith(".class")) {
                        hasClass = true
                        break
                    }
                }
                hasClass
            }
        }.getOrDefault(false)
    }

    private fun verifyProcessorOutputs(outputs: Map<File, String>): Boolean {
        if (outputs.isEmpty()) return false
        var allOk = true
        for ((file, sha1) in outputs) {
            if (!file.exists()) {
                allOk = false
                continue
            }
            if (!verifyFile(file, sha1)) {
                file.delete()
                allOk = false
            }
        }
        return allOk
    }

    private fun recoverProcessorDependenciesFromOutput(output: String, librariesDir: File): Int {
        if (output.isBlank()) return 0
        val coordRegex = Regex("Considering library\\s+([A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+:[A-Za-z0-9_.+\\-]+(?::[A-Za-z0-9_.+\\-]+)?(?:@[A-Za-z0-9_.\\-]+)?)")
        val candidates = linkedSetOf<String>()
        coordRegex.findAll(output).forEach { m ->
            val coord = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (coord.isNotBlank()) candidates.add(coord)
        }
        if (candidates.isEmpty()) return 0

        var recovered = 0
        for (coord in candidates) {
            val path = mavenToPath(coord)
            if (path == coord || path.contains("{")) continue
            val dest = File(librariesDir, path)
            if (dest.isFile && dest.length() > 0L) continue
            if (downloadMavenCoordinate(coord, librariesDir, processorRepositories(coord))) {
                recovered++
            }
        }
        return recovered
    }

    private suspend fun downloadClientMappings(mcVersion: String, outputFile: File) {
        val remote = VersionManifest.fetchVersionList().firstOrNull { it.id == mcVersion }
            ?: throw RuntimeException("无法获取 $mcVersion 的版本清单")
        val versionJsonText = httpGet(remote.url)
        val root = json.parseToJsonElement(versionJsonText).jsonObject
        val mappingUrl = root["downloads"]?.jsonObject
            ?.get("client_mappings")?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull
            ?: throw RuntimeException("$mcVersion 缺少 client_mappings 下载地址")

        outputFile.parentFile?.mkdirs()
        val candidates = linkedSetOf(DownloadManager.mirrorUrl(mappingUrl), mappingUrl)
        for (url in candidates) {
            outputFile.delete()
            if (downloadFile(url, outputFile) && outputFile.length() > 0) {
                return
            }
        }
        throw RuntimeException("下载 client_mappings 失败: $mcVersion")
    }

    private fun patchNeoForgeBaseVersion(versionJsonFile: File, mcVersion: String, baseVersionId: String) {
        if (!versionJsonFile.isFile || baseVersionId == mcVersion) return
        runCatching {
            val root = json.parseToJsonElement(versionJsonFile.readText(Charsets.UTF_8)).jsonObject.toMutableMap()
            val currentBase = root["inheritsFrom"]?.jsonPrimitive?.contentOrNull
            if (currentBase == mcVersion) {
                root["inheritsFrom"] = JsonPrimitive(baseVersionId)
                versionJsonFile.writeText(JsonObject(root).toString(), Charsets.UTF_8)
            }
        }
    }

    private fun requiresNeoForgePatchedClient(installProfile: JsonObject): Boolean {
        val processors = installProfile["processors"]?.jsonArray ?: return false
        return processors.any { procEl ->
            val proc = procEl.jsonObject
            val raw = proc.toString()
            raw.contains("minecraft-client-patched", ignoreCase = true)
        }
    }

    private fun writeNeoForgePatchedRequirement(versionJsonFile: File, requiresPatchedClient: Boolean) {
        if (!versionJsonFile.isFile) return
        runCatching {
            val rootMap = json.parseToJsonElement(versionJsonFile.readText(Charsets.UTF_8)).jsonObject.toMutableMap()
            rootMap["md3lRequiresPatchedClient"] = JsonPrimitive(requiresPatchedClient)
            versionJsonFile.writeText(JsonObject(rootMap).toString(), Charsets.UTF_8)
        }
    }

    private fun ensureNeoForgeUniversalLibrary(
        versionJsonFile: File,
        installProfile: JsonObject,
        loaderVersion: String,
        librariesDir: File,
    ) {
        if (!versionJsonFile.isFile) return
        val universalName = "net.neoforged:neoforge:$loaderVersion:universal"
        val universalPath = "net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-universal.jar"

        val rootMap = json.parseToJsonElement(versionJsonFile.readText(Charsets.UTF_8)).jsonObject.toMutableMap()
        val libs = (rootMap["libraries"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val hasUniversal = libs.any { el ->
            val lib = runCatching { el.jsonObject }.getOrNull() ?: return@any false
            val name = lib["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val path = lib["downloads"]?.jsonObject
                ?.get("artifact")?.jsonObject
                ?.get("path")?.jsonPrimitive?.contentOrNull
            name == universalName || path == universalPath
        }

        if (!hasUniversal) {
            val profileLib = installProfile["libraries"]?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { lib ->
                    val name = lib["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val path = lib["downloads"]?.jsonObject
                        ?.get("artifact")?.jsonObject
                        ?.get("path")?.jsonPrimitive?.contentOrNull
                    name == universalName || path == universalPath
                }

            val artifact = profileLib
                ?.get("downloads")?.jsonObject
                ?.get("artifact")?.jsonObject
            val artifactMap = artifact?.toMutableMap() ?: mutableMapOf()
            artifactMap["path"] = JsonPrimitive(universalPath)
            if (artifactMap["url"] == null) {
                artifactMap["url"] = JsonPrimitive("https://maven.neoforged.net/releases/$universalPath")
            }

            libs.add(
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(universalName),
                        "downloads" to JsonObject(
                            mapOf(
                                "artifact" to JsonObject(artifactMap),
                            ),
                        ),
                    ),
                ),
            )
            rootMap["libraries"] = JsonArray(libs)
            versionJsonFile.writeText(JsonObject(rootMap).toString(), Charsets.UTF_8)
        }

        val universalFile = File(librariesDir, universalPath)
        if (!universalFile.isFile || universalFile.length() <= 0L) {
            val ok = downloadMavenCoordinate(universalName, librariesDir, processorRepositories(universalName))
            if (!ok) throw RuntimeException("NeoForge 核心库下载失败: $universalName")
        }
    }

    private fun ensureNeoForgePatchedManifest(
        versionJsonFile: File,
        loaderVersion: String,
        librariesDir: File,
    ) {
        if (!versionJsonFile.isFile) return
        val neoForgeVersion = runCatching {
            val root = json.parseToJsonElement(versionJsonFile.readText(Charsets.UTF_8)).jsonObject
            val args = root["arguments"]?.jsonObject?.get("game")?.jsonArray ?: JsonArray(emptyList())
            args.forEachIndexed { index, element ->
                if (element is JsonPrimitive && element.content == "--fml.neoForgeVersion") {
                    return@runCatching (args.getOrNull(index + 1) as? JsonPrimitive)?.contentOrNull ?: loaderVersion
                }
            }
            loaderVersion
        }.getOrDefault(loaderVersion)

        val patchedPath = "net/neoforged/minecraft-client-patched/$neoForgeVersion/minecraft-client-patched-$neoForgeVersion.jar"
        val patchedFile = File(librariesDir, patchedPath)
        if (!patchedFile.isFile || patchedFile.length() <= 0L) {
            println("[LoaderInstaller] WARNING: NeoForge patched 客户端尚未生成: $patchedPath (将在启动时由处理器补全)")
            return
        }
        ensureJarManifestAttribute(patchedFile, "Minecraft-Dists", "client")
    }

    private fun ensureJarManifestAttribute(jarFile: File, key: String, value: String) {
        val jarF = java.util.jar.JarFile(jarFile)
        val manifest = jarF.manifest ?: java.util.jar.Manifest()
        val mainAttrs = manifest.mainAttributes
        if (mainAttrs.getValue(key) != null) {
            jarF.close()
            return
        }
        println("[LoaderInstaller] Injecting $key=$value into ${jarFile.name}")
        if (mainAttrs.getValue("Manifest-Version") == null) {
            mainAttrs.putValue("Manifest-Version", "1.0")
        }
        mainAttrs.putValue(key, value)

        val tempFile = File(jarFile.parentFile, "${jarFile.name}.md3l.tmp")
        tempFile.delete()
        java.util.jar.JarOutputStream(tempFile.outputStream().buffered(), manifest).use { out ->
            val seen = mutableSetOf("META-INF/MANIFEST.MF")
            for (entry in jarF.entries()) {
                if (entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
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
            throw RuntimeException("修补 JAR 清单失败: ${jarFile.absolutePath}")
        }
        val backupFile = File(jarFile.parentFile, "${jarFile.name}.md3l.bak")
        backupFile.delete()
        jarFile.renameTo(backupFile)
        if (tempFile.renameTo(jarFile)) {
            backupFile.delete()
        } else {
            tempFile.copyTo(jarFile, overwrite = true)
            tempFile.delete()
            backupFile.delete()
        }
        println("[LoaderInstaller] 已修补 JAR 清单: ${jarFile.name} -> $key=$value")
    }

    private fun ensureLauncherProfilesForInstaller(minecraftDir: String, baseVersionId: String) {
        val root = File(minecraftDir)
        root.mkdirs()
        File(root, "versions").mkdirs()
        File(root, "libraries").mkdirs()

        val launcherProfiles = File(root, "launcher_profiles.json")
        val rootMap = runCatching {
            if (launcherProfiles.exists() && launcherProfiles.length() > 10) {
                json.parseToJsonElement(launcherProfiles.readText(Charsets.UTF_8)).jsonObject.toMutableMap()
            } else {
                mutableMapOf<String, JsonElement>()
            }
        }.getOrElse { mutableMapOf() }

        val profilesMap = (rootMap["profiles"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val md3lProfileMap = (profilesMap["MD3L"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        md3lProfileMap["name"] = JsonPrimitive("MD3L")
        md3lProfileMap["type"] = JsonPrimitive("custom")
        md3lProfileMap["lastVersionId"] = JsonPrimitive(baseVersionId)
        profilesMap["MD3L"] = JsonObject(md3lProfileMap)

        rootMap["profiles"] = JsonObject(profilesMap)
        if (rootMap["selectedProfile"] == null) {
            rootMap["selectedProfile"] = JsonPrimitive("MD3L")
        }
        if (rootMap["settings"] == null) {
            rootMap["settings"] = JsonObject(emptyMap())
        }
        if (rootMap["authenticationDatabase"] == null) {
            rootMap["authenticationDatabase"] = JsonObject(emptyMap())
        }
        if (rootMap["clientToken"] == null) {
            rootMap["clientToken"] = JsonPrimitive("00000000-0000-0000-0000-000000000000")
        }

        launcherProfiles.writeText(
            json.encodeToString(JsonObject.serializer(), JsonObject(rootMap)),
            Charsets.UTF_8,
        )
    }

    private fun verifyFile(file: File, expectedSha1: String): Boolean {
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
        return actual.equals(expectedSha1, ignoreCase = true)
    }

    private fun mavenToPath(coordinate: String): String {
        val atIdx = coordinate.indexOf('@')
        val ext = if (atIdx >= 0) coordinate.substring(atIdx + 1) else "jar"
        val coords = if (atIdx >= 0) coordinate.substring(0, atIdx) else coordinate

        val parts = coords.split(":")
        if (parts.size < 3) return coordinate
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = if (parts.size > 3) "-${parts[3]}" else ""
        return "$group/$artifact/$version/$artifact-$version$classifier.$ext"
    }

    private fun addProcessorDownloadTasks(
        installProfile: JsonObject,
        artifacts: MutableMap<String, ArtifactDownloadCandidate>,
        librariesDir: File,
        repositories: List<String>,
    ) {
        val processors = installProfile["processors"]?.jsonArray ?: return
        fun addCoordinate(coord: String) {
            val path = mavenToPath(coord)
            if (path == coord || path.contains("{")) return
            val dest = File(librariesDir, path)
            val candidates = processorRepositories(coord, repositories)
            val urls = candidates.map { "${it.trimEnd('/')}/$path" }
            upsertArtifactCandidate(artifacts, dest, null, urls, coord)
        }
        processors.forEach { procEl ->
            val proc = procEl.jsonObject
            proc["jar"]?.jsonPrimitive?.contentOrNull?.let(::addCoordinate)
            proc["classpath"]?.jsonArray?.forEach { cpEl ->
                cpEl.jsonPrimitive.contentOrNull?.let(::addCoordinate)
            }
        }
    }

    private fun processorRepositories(coord: String, preferred: List<String> = emptyList()): List<String> {
        val neoForgeFirst = coord.startsWith("net.neoforged")
        val defaults = buildList {
            if (neoForgeFirst) {
                add("https://bmclapi2.bangbang93.com/maven")
                add("https://mirrors.cernet.edu.cn/bmclapi")
                add("https://maven.neoforged.net/releases")
                add("https://maven.minecraftforge.net")
                add("https://repo.maven.apache.org/maven2")
                add("https://repo1.maven.org/maven2")
                add("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
            } else {
                add("https://bmclapi2.bangbang93.com/maven")
                add("https://mirrors.cernet.edu.cn/bmclapi")
                add("https://maven.minecraftforge.net")
                add("https://maven.neoforged.net/releases")
                add("https://repo.maven.apache.org/maven2")
                add("https://repo1.maven.org/maven2")
                add("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
            }
            add("https://libraries.minecraft.net")
        }
        return (preferred + defaults).distinct()
    }


    private fun downloadMavenCoordinate(coord: String, librariesDir: File, repositories: List<String>): Boolean {
        val path = mavenToPath(coord)
        if (path == coord || path.contains("{")) return false
        val dest = File(librariesDir, path)
        if (dest.exists() && dest.length() > 0) return true

        val candidates = linkedSetOf<String>()
        repositories.forEach { repo ->
            val rawUrl = "${repo.trimEnd('/')}/$path"
            candidates.add(rawUrl)
            candidates.add(DownloadManager.mirrorUrl(rawUrl))
            if (rawUrl.startsWith("https://maven.neoforged.net/releases/")) {
                candidates.add(rawUrl.replace("https://maven.neoforged.net/releases/", "https://bmclapi2.bangbang93.com/maven/"))
            }
        }

        for (attempt in 1..2) {
            for (url in candidates) {
                println("[LoaderInstaller] 补下载处理器依赖 (尝试 $attempt): $url")
                dest.delete()
                if (downloadFile(url, dest) && dest.length() > 0) {
                    println("[LoaderInstaller] 补下载成功: ${dest.name} (${dest.length()} bytes)")
                    return true
                }
            }
        }
        println("[LoaderInstaller] 补下载失败: $coord (尝试了 ${candidates.size} 个候选源)")
        return false
    }

    private fun httpGet(url: String): String {
        try {
            val proc = ProcessBuilder(
                "curl.exe", "-sL", "--connect-timeout", "15", "--max-time", "60", url
            ).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor(65, java.util.concurrent.TimeUnit.SECONDS)
            if (ok && proc.exitValue() == 0 && text.isNotBlank()) return text.trim()
        } catch (_: Exception) {}

        val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "MD3L/1.1")
        val code = conn.responseCode
        if (code == 200) {
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            return text.trim()
        }
        conn.disconnect()
        return ""
    }

    private fun downloadFile(url: String, dest: File): Boolean {
        try {
            dest.parentFile?.mkdirs()
            val proc = ProcessBuilder(
                "curl.exe", "-sL", "--connect-timeout", "15", "--max-time", "120",
                "-o", dest.absolutePath, url
            ).redirectErrorStream(true).start()
            val ok = proc.waitFor(130, java.util.concurrent.TimeUnit.SECONDS)
            if (ok && proc.exitValue() == 0 && dest.exists() && dest.length() > 0) return true
        } catch (_: Exception) {}

        try {
            val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("User-Agent", "MD3L/1.1")
            if (conn.responseCode == 200) {
                conn.inputStream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                conn.disconnect()
                return dest.exists() && dest.length() > 0
            }
            conn.disconnect()
        } catch (_: Exception) {}

        return false
    }

    private fun resolveJavaExe(javaPath: String): String {
        if (javaPath == "java" || javaPath.isBlank()) return "java"
        val exe = File(javaPath, "bin/java.exe")
        return if (exe.exists()) exe.absolutePath else javaPath
    }

    private fun resolveMinecraftJar(
        minecraftDir: String,
        baseVersionId: String,
        mcVersion: String,
        fallbackVersionId: String? = null,
    ): File {
        val candidates = linkedSetOf(baseVersionId, mcVersion)
        if (!fallbackVersionId.isNullOrBlank()) candidates.add(fallbackVersionId)
        candidates.forEach { id ->
            val jar = File(minecraftDir, "versions/$id/$id.jar")
            if (jar.isFile && jar.length() > 0) {
                return jar
            }
        }
        throw RuntimeException(
            "未找到基础版本 JAR: " +
                candidates.joinToString(" 或 ") { "versions/$it/$it.jar" },
        )
    }
}

