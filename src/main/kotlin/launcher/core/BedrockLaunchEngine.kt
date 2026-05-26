package launcher.core

import com.sun.jna.*
import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

class BedrockLaunchEngine : ILaunchEngine {

    companion object {
        private val CLSID_AAM = Guid.CLSID("{45BA127D-10A8-46EA-8AB7-56EA9078943C}")
        private val IID_IAAM = Guid.IID("{2E941141-7F97-4756-BA1D-9DECDE894A3D}")

        private var runtimeCheckPassedAt = 0L
        private var packageFamilyCacheAt = 0L
        private var packageFullNameByFamily = emptyMap<String, String>()
        private const val PACKAGE_FAMILY_CACHE_TTL_MS = 20_000L

        // 预热缓存：key = versionDir 绝对路径，value = 已验证的注册槽
        private val prewarmedSlots = java.util.concurrent.ConcurrentHashMap<String, RegisteredSlotCache>()
        private const val PREWARM_TTL_MS = 5 * 60 * 1000L // 5 分钟有效

        internal data class RegisteredSlotCache(
            val slot: RegisteredSlot,
            val cachedAt: Long,
        )

        suspend fun preheat(versionDir: File, minecraftDir: String = "", versionId: String = versionDir.name) = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val key = versionDir.absolutePath
            val existing = prewarmedSlots[key]
            if (existing != null && System.currentTimeMillis() - existing.cachedAt < PREWARM_TTL_MS) {
                println("[Bedrock] 预热命中缓存: $key")
                return@withContext
            }
            println("[Bedrock] 开始预热: $key")
            runCatching {
                val engine = BedrockLaunchEngine()
                // 1. 预热 Get-AppxPackage 缓存（冷启动 PS 最慢的一步）
                engine.getInstalledPackageFullNameByFamily(forceRefresh = true)
                // 2. 提前切换版本存档 junction（版本切换最耗时步骤提前到后台）
                val resolvedDir = minecraftDir.takeIf { it.isNotBlank() }
                    ?: versionDir.parentFile?.parentFile?.absolutePath ?: ""
                runCatching {
                    val targetProfile = engine.resolveBedrockVersionComMojang(resolvedDir, versionId)
                    engine.switchProfileJunction(targetProfile)
                    println("[Bedrock] 预热：存档 Junction 已提前切换至 ${targetProfile.absolutePath}")
                }.onFailure { println("[Bedrock] 预热：Junction 切换失败（不影响启动）: ${it.message}") }
                // 3. 若已有 .installed marker，验证注册状态并缓存结果
                val manifestFile = File(versionDir, "AppxManifest.xml")
                if (manifestFile.isFile) {
                    val packageFile = engine.resolveSelectedVersionPackage(versionDir) ?: manifestFile
                    val slot = engine.resolveInstalledSelectedVersionSlot(versionDir, packageFile)
                    if (slot != null) {
                        prewarmedSlots[key] = RegisteredSlotCache(slot, System.currentTimeMillis())
                        println("[Bedrock] 预热完成，槽位已缓存: ${slot.aumid}")
                    } else {
                        println("[Bedrock] 预热完成，无已注册槽位（首次启动将注册）")
                    }
                }
            }.onFailure { println("[Bedrock] 预热失败（不影响正常启动）: ${it.message}") }
        }

        internal fun getPrewarmedSlot(versionDir: File): RegisteredSlot? {
            val entry = prewarmedSlots[versionDir.absolutePath] ?: return null
            if (System.currentTimeMillis() - entry.cachedAt > PREWARM_TTL_MS) {
                prewarmedSlots.remove(versionDir.absolutePath)
                return null
            }
            return entry.slot
        }

        internal fun invalidatePrewarm(versionDir: File) {
            prewarmedSlots.remove(versionDir.absolutePath)
        }
    }

    private var runtimeChecked = false

    var onProgress: ((Int, String) -> Unit)? = null

    private fun resolveBedrockDataBackupDir(context: LaunchContext): File {
        val root = context.minecraftDir.takeIf { it.isNotBlank() }
            ?: context.version.versionDir.takeIf { it.isNotBlank() }
                ?.let { File(it).parentFile?.parentFile?.absolutePath }
            ?: File("mc").absolutePath
        return File(root, "bedrock_data_backup/com.mojang").apply { mkdirs() }
    }

    private fun findUwpPackageRoots(): Array<out File> {
        val localAppData = System.getenv("LOCALAPPDATA") ?: return emptyArray()
        val packagesDir = File(localAppData, "Packages")
        if (!packagesDir.isDirectory) return emptyArray()
        return packagesDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("Microsoft.MinecraftUWP", ignoreCase = true)
        }.orEmpty()
    }

    private fun findInstalledMinecraftPackageRoots(): Array<out File> {
        val localAppData = System.getenv("LOCALAPPDATA") ?: return emptyArray()
        val packagesDir = File(localAppData, "Packages")
        if (!packagesDir.isDirectory) return emptyArray()
        return packagesDir.listFiles { file ->
            file.isDirectory && (
                file.name.startsWith("Microsoft.MinecraftUWP", ignoreCase = true) ||
                file.name.startsWith("Microsoft.MinecraftWindows", ignoreCase = true)
            )
        }.orEmpty()
    }

    private fun resolveGdkComMojang(versionId: String): File {
        val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
        val folderName = if (versionId.contains("preview", ignoreCase = true) ||
            versionId.contains("beta", ignoreCase = true)
        ) "Minecraft Bedrock Preview" else "Minecraft Bedrock"
        return File(appData, "$folderName/users/shared/games/com.mojang").apply { mkdirs() }
    }

    fun resolveVersionProfilePublic(minecraftDir: String, versionId: String): File =
        resolveVersionProfile(minecraftDir, versionId)

    /**
     * 返回版本的 com.mojang 目录，统一使用 bedrock_profiles/<versionId>/com.mojang。
     * Pack/World 管理、导出/导入、启动 junction 均走此路径。
     */
    fun resolveBedrockVersionComMojang(minecraftDir: String, versionId: String): File {
        return resolveVersionProfile(minecraftDir, versionId)
    }

    /**
     * 返回当前活跃的 com.mojang 目录（Mod 下载安装用），统一走 bedrock_profiles。
     */
    fun resolveActiveComMojangPublic(minecraftDir: String, versionId: String): File {
        if (isGdkVersion(versionId.removePrefix("Bedrock ").trim())) {
            return resolveGdkComMojang(versionId)
        }
        return resolveVersionProfile(minecraftDir, versionId)
    }

    private fun resolveVersionProfile(minecraftDir: String, versionId: String): File {
        if (isGdkVersion(versionId.removePrefix("Bedrock ").trim())) {
            return resolveGdkComMojang(versionId)
        }
        val uwpRoot = findUwpPackageRoots().firstOrNull()
        val base = if (uwpRoot != null) {
            File(uwpRoot, "LocalState/md3l_profiles")
        } else {
            File(minecraftDir.takeIf { it.isNotBlank() } ?: File("mc").absolutePath, "bedrock_profiles")
        }
        return File(base, "$versionId/com.mojang").apply { mkdirs() }
    }

    private fun readJunctionTarget(dir: File): File? {
        if (!dir.exists()) return null
        return try {
            val proc = ProcessBuilder("fsutil", "reparsepoint", "query", dir.absolutePath)
                .redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (proc.exitValue() != 0) return null
            val target = text.lineSequence()
                .firstOrNull { it.trimStart().startsWith("Print Name:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
                ?: text.lineSequence()
                    .firstOrNull { it.trimStart().startsWith("Substitute Name:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
                    ?.removePrefix("\\??\\")
            target?.let { File(it).canonicalFile }
        } catch (_: Exception) {
            null
        }
    }

    private fun switchProfileJunction(targetProfile: File): Boolean {
        val packageRoots = findInstalledMinecraftPackageRoots()
        if (packageRoots.isEmpty()) {
            println("[Bedrock] 未找到 UWP 包目录，跳过 junction 设置")
            return false
        }

        targetProfile.mkdirs()

        var allOk = true
        packageRoots.forEach { root ->
            val gamesDir = File(root, "LocalState/games")
            gamesDir.mkdirs()
            val comMojang = File(gamesDir, "com.mojang")

            val currentTarget = readJunctionTarget(comMojang)
            if (currentTarget != null && currentTarget == targetProfile.canonicalFile) {
                println("[Bedrock] Junction 已正确指向 ${targetProfile.canonicalFile}，零切换")
                return@forEach
            }

            if (comMojang.exists() && currentTarget == null) {
                println("[Bedrock] 首次迁移：从真实目录迁移数据到版本 profile")
                val migrated = tryRobocopyIncremental(comMojang, targetProfile)
                if (migrated) {
                    // 验证迁移结果：目标 profile 必须非空才能删除源目录
                    val profileHasData = targetProfile.exists() && (targetProfile.listFiles()?.isNotEmpty() == true)
                    if (profileHasData) {
                        comMojang.deleteRecursively()
                        println("[Bedrock] 数据迁移完成，共迁移到 ${targetProfile.absolutePath}")
                    } else {
                        println("[Bedrock] 警告：迁移后 profile 目录为空，放弃删除源目录以保护数据")
                        return@forEach
                    }
                } else {
                    println("[Bedrock] 数据迁移失败，保留原始 com.mojang 目录以防止数据丢失")
                    return@forEach
                }
            }

            if (comMojang.exists() || currentTarget != null) {
                val rm = ProcessBuilder("cmd", "/c", "rmdir", comMojang.absolutePath)
                    .redirectErrorStream(true).start()
                rm.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (comMojang.exists() && comMojang.isDirectory && comMojang.listFiles()?.isEmpty() == true) {
                    comMojang.delete()
                }
                if (comMojang.exists()) {
                    throw RuntimeException(
                        "无法移除旧的 com.mojang 目录/junction: ${comMojang.absolutePath}\n" +
                        "请手动删除该目录后重试，或以管理员身份运行启动器。"
                    )
                }
            }

            // AppContainer 沙箱无法跟随 SymbolicLink，必须用 Junction
            val mk = ProcessBuilder(
                "cmd", "/c", "mklink", "/J",
                comMojang.absolutePath,
                targetProfile.canonicalPath
            ).redirectErrorStream(true).start()
            val mkOut = mk.inputStream.bufferedReader().readText().trim()
            mk.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (mk.exitValue() != 0 && !comMojang.exists()) {
                throw RuntimeException(
                    "版本存档 Junction 建立失败，请以管理员身份运行启动器。\n" +
                    "路径: ${comMojang.absolutePath}\n错误: $mkOut"
                )
            }
            println("[Bedrock] Junction 建立成功: ${comMojang.absolutePath} → ${targetProfile.canonicalPath}")
        }
        return allOk
    }

    private fun tryRobocopyIncremental(sourceDir: File, targetDir: File): Boolean {
        return try {
            val proc = ProcessBuilder(
                "robocopy",
                sourceDir.absolutePath,
                targetDir.absolutePath,
                "/E", "/R:0", "/W:0", "/MT:16",
                "/NFL", "/NDL", "/NJH", "/NJS", "/NP",
            ).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            proc.exitValue() <= 7
        } catch (_: Exception) {
            false
        }
    }

    private fun backupBedrockDataFromInstalledPackages(backupDir: File) {}
    private fun restoreBedrockDataToInstalledPackages(backupDir: File) {}

    fun checkRuntime() {
        runtimeChecked = true
    }

    private fun injectSkinAndUsername(context: LaunchContext) {
        val packagesDir = File(System.getenv("LOCALAPPDATA") ?: return, "Packages")
        if (!packagesDir.isDirectory) return
        val packageRoots = packagesDir.listFiles { file ->
            file.isDirectory && (
                file.name.startsWith("Microsoft.MinecraftUWP", ignoreCase = true) ||
                file.name.startsWith("Microsoft.MinecraftWindows", ignoreCase = true)
            )
        }.orEmpty()

        packageRoots.forEach { root ->
            val mojangDir = File(root, "LocalState/games/com.mojang")
            if (!mojangDir.exists()) mojangDir.mkdirs()
            
            val optionsFile = File(mojangDir, "minecraftpe/options.txt")
            if (optionsFile.exists()) {
                val lines = optionsFile.readLines().toMutableList()
                val idx = lines.indexOfFirst { it.startsWith("mp_username:") }
                if (idx >= 0) {
                    lines[idx] = "mp_username:${context.playerName}"
                } else {
                    lines.add("mp_username:${context.playerName}")
                }
                optionsFile.outputStream().use { fos ->
                    fos.write((lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
                }
            } else {
                optionsFile.parentFile?.mkdirs()
                optionsFile.outputStream().use { fos ->
                    fos.write("mp_username:${context.playerName}\n".toByteArray(Charsets.UTF_8))
                }
            }

            if (context.skinUri.isNotBlank()) {
                val skinFile = File(context.skinUri)
                if (skinFile.exists()) {
                    val packDir = File(mojangDir, "development_resource_packs/MD3LSkinPack")
                    packDir.mkdirs()
                    val manifestObj = buildJsonObject {
                        put("format_version", 2)
                        put("header", buildJsonObject {
                            put("description", "MD3L Auto-injected Skin Pack")
                            put("name", "MD3L Skin Sync")
                            put("uuid", "ee35fa32-0268-45e0-9bc7-60e0fb2eecbe")
                            put("version", buildJsonArray { add(1); add(0); add(0) })
                            put("min_engine_version", buildJsonArray { add(1); add(16); add(0) })
                        })
                        put("modules", buildJsonArray {
                            add(buildJsonObject {
                                put("description", "MD3L Auto-injected Skin Pack")
                                put("type", "resources")
                                put("uuid", "b6a84f3e-52f1-4328-9418-971ef28baeb1")
                                put("version", buildJsonArray { add(1); add(0); add(0) })
                            })
                        })
                    }
                    val manifestStr = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), manifestObj)
                    val manifestFile = File(packDir, "manifest.json")
                    manifestFile.outputStream().use { fos ->
                        fos.write(manifestStr.toByteArray(Charsets.UTF_8))
                    }
                    val texturesDir = File(packDir, "textures/entity")
                    texturesDir.mkdirs()
                    
                    File(texturesDir, "steve.png").delete()
                    File(texturesDir, "alex.png").delete()
                    
                    skinFile.copyTo(File(texturesDir, "steve.png"), overwrite = true)
                    skinFile.copyTo(File(texturesDir, "alex.png"), overwrite = true)

                    val globalPacksFile = File(mojangDir, "minecraftpe/global_resource_packs.json")
                    try {
                        val packsArray = if (globalPacksFile.exists()) {
                            Json.parseToJsonElement(globalPacksFile.readText()).jsonArray.toMutableList()
                        } else {
                            mutableListOf()
                        }
                        
                        val exists = packsArray.any { it.jsonObject["pack_id"]?.jsonPrimitive?.content == "ee35fa32-0268-45e0-9bc7-60e0fb2eecbe" }
                        if (!exists) {
                            val newPack = buildJsonObject {
                                put("pack_id", "ee35fa32-0268-45e0-9bc7-60e0fb2eecbe")
                                put("version", buildJsonArray { add(1); add(0); add(0) })
                                put("subpack", "")
                            }
                            packsArray.add(newPack)
                            val newGlobalStr = Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(packsArray))
                            globalPacksFile.outputStream().use { fos ->
                                fos.write(newGlobalStr.toByteArray(Charsets.UTF_8))
                            }
                        }
                    } catch (e: Exception) {
                        println("[Bedrock] 更新 global_resource_packs.json 失败: ${e.message}")
                    }
                }
            } else {
                val globalPacksFile = File(mojangDir, "minecraftpe/global_resource_packs.json")
                if (globalPacksFile.exists()) {
                    try {
                        val packsArray = Json.parseToJsonElement(globalPacksFile.readText()).jsonArray
                        val filtered = packsArray.filterNot { it.jsonObject["pack_id"]?.jsonPrimitive?.content == "ee35fa32-0268-45e0-9bc7-60e0fb2eecbe" }
                        if (filtered.size != packsArray.size) {
                            val filteredStr = Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(filtered))
                            globalPacksFile.outputStream().use { fos ->
                                fos.write(filteredStr.toByteArray(Charsets.UTF_8))
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun injectBedrockGameOptions(comMojangDir: File, context: LaunchContext) {
        runCatching {
            val settings = runBlocking { AppSettings.load() }
            val injections = mutableMapOf<String, String>()
            if (settings.bedrockFpsLimit > 0)           injections["max_framerate"] = settings.bedrockFpsLimit.toString()
            if (settings.bedrockRenderDistance > 0)     injections["gfx_viewdistance"] = (settings.bedrockRenderDistance * 16).toString()
            if (settings.bedrockSimulationDistance > 0) injections["gfx_simulation_distance"] = (settings.bedrockSimulationDistance * 16).toString()
            if (settings.bedrockShowCoordinates)        injections["show_coordinates"] = "1"
            if (settings.bedrockHideHud)                injections["show_hud"] = "0"
            if (injections.isEmpty()) return@runCatching
            val optionsFile = File(comMojangDir, "minecraftpe/options.txt")
            optionsFile.parentFile?.mkdirs()
            val existing = if (optionsFile.exists()) {
                optionsFile.readLines(Charsets.UTF_8).associate { line ->
                    val eq = line.indexOf('=')
                    if (eq > 0) line.substring(0, eq).trim() to line.substring(eq + 1).trim()
                    else line to ""
                }.toMutableMap()
            } else mutableMapOf()
            injections.forEach { (k, v) -> existing[k] = v }
            optionsFile.writeText(existing.entries.joinToString("\n") { (k, v) -> "$k=$v" }, Charsets.UTF_8)
            println("[MD3L] 已注入基岩版游戏选项: ${injections.keys.joinToString()}")
        }.onFailure { println("[MD3L] 注入游戏选项失败: ${it.message}") }
    }

    private fun isGdkVersion(versionId: String): Boolean {
        val GDK_THRESHOLD = listOf(1, 21, 120, 21)
        val parts = versionId.trim().split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 4) return false
        for (i in 0 until 4) {
            val a = parts.getOrElse(i) { 0 }
            val b = GDK_THRESHOLD.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true // 完全相等也算 GDK
    }

    override fun execute(context: LaunchContext): Process {
        runtimeChecked = true

        if (context.version.type == "bedrock" && context.version.versionDir.isNotBlank()) {
            val versionDir = File(context.version.versionDir)

            val versionId = versionDir.name

            if (isGdkVersion(versionId)) {
                val isMsaAccount = context.accountType == AccountType.MSA

                if (isMsaAccount) {
                    // 正版 MSA 用户本机持有 Xbox/GamePass license，直接跑 EXE 不触发试用版
                    onProgress?.invoke(30, "正版账户，GDK 快速启动…")
                    val exeFile = File(versionDir, "Minecraft.Windows.exe")
                    if (!exeFile.isFile) throw RuntimeException(
                        "GDK 版本 $versionId 目录中未找到 Minecraft.Windows.exe。\n目录: ${versionDir.absolutePath}"
                    )
                    onProgress?.invoke(80, "正在启动 Minecraft.Windows.exe…")
                    println("[MD3L] GDK 正版快速启动: ${exeFile.absolutePath}")
                    val beforePids = ProcessHandle.allProcesses()
                        .filter { it.info().command().orElse("").endsWith("Minecraft.Windows.exe") }
                        .map { it.pid() }.toList()
                    val pb = ProcessBuilder(exeFile.absolutePath).directory(versionDir)
                    configureVcLibsEnvironment(pb)
                    pb.start()
                    onProgress?.invoke(95, "已启动，等待游戏进程…")
                    return waitForMinecraftProcess(beforePids, timeoutMs = 30_000)
                }

                // 非正版（离线/第三方）：走 -Register + COM 激活，绕过 license 检查
                onProgress?.invoke(30, "GDK 版本，切换 com.mojang 存档…")
                val gdkProfile = resolveBedrockVersionComMojang(context.minecraftDir, context.version.id)
                runCatching { switchProfileJunction(gdkProfile) }

                val manifestFile = File(versionDir, "AppxManifest.xml")
                if (!manifestFile.isFile) throw RuntimeException(
                    "GDK 版本 $versionId 目录中未找到 AppxManifest.xml。\n目录: ${versionDir.absolutePath}"
                )
                val packageFile = resolveSelectedVersionPackage(versionDir) ?: manifestFile
                val prewarmed = getPrewarmedSlot(versionDir)
                val fastSlot = prewarmed ?: resolveInstalledSelectedVersionSlot(versionDir, packageFile)
                val slot = if (fastSlot != null) {
                    onProgress?.invoke(85, if (prewarmed != null) "预热命中，直接激活…" else "命中已注册槽位，准备激活…")
                    fastSlot
                } else {
                    onProgress?.invoke(50, "正在注册 GDK 应用包…")
                    installSelectedVersionPackageSlot(versionDir, packageFile, onProgress)
                }
                onProgress?.invoke(90, "正在激活 GDK 游戏进程…")
                try {
                    val pid = activateUwpApplication(slot.aumid)
                    println("[MD3L] GDK COM 激活成功: PID=$pid, AUMID=${slot.aumid}")
                } catch (e: Exception) {
                    println("[MD3L] GDK COM 激活失败，清除 marker 重注册: ${e.message}")
                    File(versionDir, ".installed").delete()
                    invalidateInstalledPackageFamilyCache()
                    invalidatePrewarm(versionDir)
                    onProgress?.invoke(50, "GDK 槽位失效，重新注册…")
                    val newSlot = installSelectedVersionPackageSlot(versionDir, packageFile, onProgress)
                    onProgress?.invoke(90, "重注册完成，激活中…")
                    val pid2 = activateUwpApplication(newSlot.aumid)
                    println("[MD3L] GDK 重注册激活成功: PID=$pid2")
                }
                onProgress?.invoke(95, "已激活，等待游戏进程…")
                return uwpMonitorProcess()
            }

            onProgress?.invoke(30, "正在切换版本存档…")
            val targetProfile = resolveBedrockVersionComMojang(context.minecraftDir, context.version.id)
            switchProfileJunction(targetProfile)
            println("[MD3L] 版本存档已隔离: ${targetProfile.absolutePath}")
            injectBedrockGameOptions(targetProfile, context)

            val manifestFile = File(versionDir, "AppxManifest.xml")
            if (!manifestFile.isFile) throw RuntimeException(
                "基岩版 ${context.version.id} 目录中未找到 AppxManifest.xml。\n" +
                "目录: ${versionDir.absolutePath}\n请重新下载并安装该版本。"
            )
            val packageFile = resolveSelectedVersionPackage(versionDir) ?: manifestFile
            val prewarmed = getPrewarmedSlot(versionDir)
            val fastSlot = prewarmed ?: resolveInstalledSelectedVersionSlot(versionDir, packageFile)
            val slot = if (fastSlot != null) {
                onProgress?.invoke(85, if (prewarmed != null) "预热命中，直接激活…" else "命中已注册槽位，准备激活…")
                fastSlot
            } else {
                onProgress?.invoke(50, "正在注册 UWP 应用包…")
                installSelectedVersionPackageSlot(versionDir, packageFile, onProgress)
            }
            onProgress?.invoke(90, "正在激活游戏进程…")
            try {
                val pid = activateUwpApplication(slot.aumid)
                println("[MD3L] UWP COM 激活成功: PID=$pid, AUMID=${slot.aumid}")
            } catch (e: Exception) {
                println("[MD3L] UWP COM 激活失败，清除 marker 重注册: ${e.message}")
                File(versionDir, ".installed").delete()
                invalidateInstalledPackageFamilyCache()
                invalidatePrewarm(versionDir)
                onProgress?.invoke(50, "槽位失效，重新注册…")
                val newSlot = installSelectedVersionPackageSlot(versionDir, packageFile, onProgress)
                onProgress?.invoke(90, "重注册完成，激活中…")
                val pid2 = activateUwpApplication(newSlot.aumid)
                println("[MD3L] UWP 重注册激活成功: PID=$pid2")
            }
            onProgress?.invoke(95, "已激活，等待游戏进程…")
            return uwpMonitorProcess()

        } else {
            val detectedAumid = detectInstalledMinecraft()
                ?: throw RuntimeException(
                    "基岩版启动失败：未检测到已安装的 Minecraft 基岩版。\n" +
                    "请确保已通过 Microsoft Store 或下载安装页完成安装。"
                )
            activateUwpApplication(detectedAumid)
            println("[MD3L] COM 系统包激活成功 AUMID=$detectedAumid")
            onProgress?.invoke(95, "已激活，等待游戏进程…")
            return uwpMonitorProcess()
        }
    }

    private fun uwpMonitorProcess(): Process {
        val s = "\$"
        val script = """
            ${"\$"}proc = ${"\$"}null
            for (${"\$"}i = 0; ${"\$"}i -lt 120; ${"\$"}i++) {
                ${"\$"}proc = Get-Process -Name 'Minecraft.Windows' -ErrorAction SilentlyContinue | Select-Object -First 1
                if (${"\$"}proc) { break }
                Start-Sleep -Milliseconds 500
            }
            if (${"\$"}proc) {
                Wait-Process -Id ${"\$"}proc.Id -ErrorAction SilentlyContinue
            }
        """.trimIndent()
        return ProcessBuilder("powershell", "-NoProfile", "-Command", script)
            .redirectErrorStream(true).start()
    }

    private fun waitForMinecraftProcess(beforePids: List<Long>, timeoutMs: Long): Process {
        val deadline = System.currentTimeMillis() + timeoutMs
        var mcHandle: ProcessHandle? = null
        while (System.currentTimeMillis() < deadline) {
            mcHandle = ProcessHandle.allProcesses()
                .filter { it.info().command().orElse("").endsWith("Minecraft.Windows.exe") }
                .filter { !beforePids.contains(it.pid()) }
                .findFirst().orElse(null)
            if (mcHandle != null) break
            Thread.sleep(300)
        }
        val foundHandle = mcHandle
            ?: throw RuntimeException("等待 Minecraft.Windows.exe 超时（${timeoutMs / 1000}s），游戏未能启动。")
        println("[MD3L] 游戏进程已就绪: PID=${foundHandle.pid()}")
        onProgress?.invoke(100, "游戏已启动")
        val sentinelScript = """
            ${'$'}deadline = (Get-Date).AddSeconds(10)
            do {
                Start-Sleep -Milliseconds 500
                ${'$'}mc = Get-Process -Name 'Minecraft.Windows' -ErrorAction SilentlyContinue
            } while (-not ${'$'}mc -and (Get-Date) -lt ${'$'}deadline)
            while (${'$'}true) {
                ${'$'}mc = Get-Process -Name 'Minecraft.Windows' -ErrorAction SilentlyContinue
                if (-not ${'$'}mc) { exit 0 }
                Start-Sleep -Milliseconds 500
            }
        """.trimIndent()
        val sentinel = ProcessBuilder("powershell", "-NoProfile", "-Command", sentinelScript)
            .redirectErrorStream(true).start()
        return sentinel
    }

    suspend fun installBedrockVersion(bundleFile: File, version: String) = withContext(Dispatchers.IO) {
        try {
            val settings = AppSettings.load()
            val targetSlotDir = File(settings.minecraftDir, "bedrock_versions/$version")
            if (targetSlotDir.exists()) targetSlotDir.deleteRecursively()
            targetSlotDir.mkdirs()
            val tempAppxFile = File(targetSlotDir, "temp_payload_x64.appx")
            try {
                val ext = bundleFile.extension.lowercase()
                if (ext == "appxbundle" || ext == "msixbundle") {
                    ZipFile(bundleFile).use { bundleZip ->
                        val payloadEntry = bundleZip.entries().asSequence().firstOrNull { entry ->
                            isTargetArchPayload(entry.name, "x64")
                        } ?: throw RuntimeException("未在 Bundle 中找到 _x64.appx 核心负载: ${bundleFile.absolutePath}")
                        bundleZip.getInputStream(payloadEntry).use { input ->
                            BufferedInputStream(input).use { bufferedInput ->
                                BufferedOutputStream(tempAppxFile.outputStream()).use { output ->
                                    copyStream(bufferedInput, output)
                                }
                            }
                        }
                    }
                    extractZipToDir(tempAppxFile, targetSlotDir)
                } else {
                    extractZipToDir(bundleFile, targetSlotDir)
                }
                deleteAppxSignature(targetSlotDir)
            } finally {
                tempAppxFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    private fun deleteAppxSignature(dir: File) {
        val signature = File(dir, "AppxSignature.p7x")
        if (signature.exists() && !signature.delete()) {
            println("[Bedrock] AppxSignature.p7x 删除失败: ${signature.absolutePath}")
        }
    }

    private fun resolveSelectedVersionPackage(versionDir: File): File? {
        val marker = File(versionDir, ".installed")
        val sourceFromMarker = if (marker.exists()) marker.readLines().firstOrNull { it.startsWith("source=") }
            ?.substringAfter("source=")
            ?.let(::File)
        else null
        val source = sourceFromMarker ?: File(versionDir.parentFile, "cache/Minecraft-${versionDir.name}.Appx")
        if (!source.isFile) return null
        val ext = source.extension.lowercase()
        return if (ext == "appx" || ext == "appxbundle" || ext == "msixbundle") source else null
    }

    internal data class RegisteredSlot(
        val aumid: String,
        val packageFullName: String,
        val log: String,
    )

    private fun resolveInstalledSelectedVersionSlot(versionDir: File, packageFile: File): RegisteredSlot? {
        val marker = File(versionDir, ".installed")
        if (!marker.isFile) return null
        val lines = runCatching { marker.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        val markerSource = lines.firstOrNull { it.startsWith("source=") }?.substringAfter("source=")
        val markerAumid = lines.firstOrNull { it.startsWith("aumid=") }?.substringAfter("aumid=")?.takeIf { it.contains("!") }
        val markerPackageFullName = lines.firstOrNull { it.startsWith("packageFullName=") }
            ?.substringAfter("packageFullName=")
            ?.takeIf { it.isNotBlank() }
        val expectedManifest = File(versionDir, "AppxManifest.xml").absolutePath
        val sourceMatches = markerSource == packageFile.absolutePath ||
            markerSource == expectedManifest ||
            (markerSource != null && File(markerSource).parentFile?.absolutePath == versionDir.absolutePath)
        if (!sourceMatches) return null
        if (markerAumid == null || markerPackageFullName == null) return null

        val currentPkg = runCatching {
            val escapedPath = versionDir.absolutePath.trimEnd('\\', '/').replace("'", "''")
            val script = "Get-AppxPackage | Where-Object { \$_.InstallLocation -ieq '$escapedPath' } | Select-Object -First 1 | ForEach-Object { Write-Output \$_.InstallLocation }"
            val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", script)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out
        }.getOrDefault("")
        val expectedPath = versionDir.absolutePath.trimEnd('\\', '/')
        val actualPath = currentPkg.trimEnd('\\', '/')
        if (actualPath.isBlank() || !actualPath.equals(expectedPath, ignoreCase = true)) {
            println("[MD3L] 安装位置不匹配（当前=$actualPath，期望=$expectedPath），强制重注册")
            marker.delete()
            return null
        }
        println("[MD3L] 快速路径命中: $markerAumid @ $actualPath")
        return RegisteredSlot(markerAumid, markerPackageFullName, "FAST_PATH")
    }

    private fun invalidateInstalledPackageFamilyCache() {
        packageFamilyCacheAt = 0L
        packageFullNameByFamily = emptyMap()
    }

    private fun getInstalledPackageFullNameByFamily(forceRefresh: Boolean = false): Map<String, String> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - packageFamilyCacheAt < PACKAGE_FAMILY_CACHE_TTL_MS && packageFullNameByFamily.isNotEmpty()) {
            return packageFullNameByFamily
        }
        return try {
                val script = "Get-AppxPackage -Name 'Microsoft.Minecraft*' | ForEach-Object { Write-Output (\"{0}|{1}\" -f \$_.PackageFamilyName, \$_.PackageFullName) }"
            val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", script)
                .redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val map = output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && "|" in it }
                .mapNotNull { line ->
                    val sep = line.indexOf('|')
                    if (sep <= 0 || sep >= line.lastIndex) return@mapNotNull null
                    val family = line.substring(0, sep).trim().lowercase()
                    val fullName = line.substring(sep + 1).trim()
                    if (family.isBlank() || fullName.isBlank()) null else family to fullName
                }
                .toMap()
            if (map.isNotEmpty()) {
                packageFullNameByFamily = map
                packageFamilyCacheAt = now
                map
            } else {
                packageFullNameByFamily
            }
        } catch (_: Exception) {
            packageFullNameByFamily
        }
    }

    private fun queryInstalledPackageFullNameByAumid(aumid: String): String? {
        val family = aumid.substringBefore("!").trim().lowercase()
        if (family.isBlank()) return null
        val cached = packageFullNameByFamily[family]
        if (cached != null) return cached
        return getInstalledPackageFullNameByFamily(forceRefresh = true)[family]
    }

    private fun appendInstallMarkerSlot(versionDir: File, packageFile: File, aumid: String, packageFullName: String) {
        val marker = File(versionDir, ".installed")
        val lines = if (marker.exists()) marker.readLines(Charsets.UTF_8).filterNot {
            it.startsWith("source=") ||
                it.startsWith("aumid=") ||
                it.startsWith("packageFullName=") ||
                it.startsWith("slotUpdatedAt=")
        } else emptyList()
        marker.writeText(
            (lines + listOf(
                "source=${packageFile.absolutePath}",
                "aumid=$aumid",
                "packageFullName=$packageFullName",
                "slotUpdatedAt=${System.currentTimeMillis()}",
            )).joinToString("\n") + "\n",
            Charsets.UTF_8,
        )
    }

    private fun installSelectedVersionPackageSlot(versionDir: File, packageFile: File, onProgress: ((Int, String) -> Unit)? = null): RegisteredSlot {
        invalidateInstalledPackageFamilyCache()

        val manifestFile = File(versionDir, "AppxManifest.xml")
        if (!manifestFile.isFile) {
            throw RuntimeException(
                "基岩版 ${versionDir.name} 目录中未找到 AppxManifest.xml。\n" +
                "目录: ${versionDir.absolutePath}\n" +
                "请重新下载并安装该版本。"
            )
        }

        val workingManifest = sanitizeManifestForRegister(manifestFile)
        val manifestPath = workingManifest.absolutePath.replace("'", "''")
        val versionDirPath = versionDir.absolutePath.trimEnd('\\', '/').replace("'", "''")
        onProgress?.invoke(55, "正在准备 Appx 清单…")
        val s = "\$"
        val script = """
            ${s}ProgressPreference = 'SilentlyContinue'
            ${s}ErrorActionPreference = 'Stop'
            try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
            ${s}log = New-Object System.Collections.Generic.List[string]
            try { Get-Process -Name 'Minecraft.Windows' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue; ${s}log.Add('STOP_PROCESS_OK') } catch { ${s}log.Add(('STOP_PROCESS_SKIP {0}' -f ${s}_.Exception.Message)) }
            try {
                Add-AppxPackage -Register '$manifestPath' -ForceApplicationShutdown -ForceUpdateFromAnyVersion -ErrorAction Stop
                ${s}log.Add('REGISTER_OK')
            } catch {
                ${s}log.Add(('REGISTER_FAIL {0}' -f ${s}_.Exception.Message))
                ${s}log | ForEach-Object { Write-Output ${s}_ }
                exit 31
            }
            ${s}pkg = Get-AppxPackage | Where-Object { ${s}_.InstallLocation -ieq '$versionDirPath' } | Select-Object -First 1
            if (-not ${s}pkg) {
                ${s}pkg = Get-AppxPackage -Name 'Microsoft.Minecraft*' -ErrorAction SilentlyContinue | Where-Object { ${s}_.InstallLocation -ieq '$versionDirPath' } | Select-Object -First 1
            }
            if (-not ${s}pkg) {
                ${s}log.Add('VERIFY_FAIL no Minecraft package found at $versionDirPath')
                ${s}log | ForEach-Object { Write-Output ${s}_ }
                exit 32
            }
            ${s}log.Add(('INSTALLED {0} @ {1}' -f ${s}pkg.PackageFullName, ${s}pkg.InstallLocation))
            ${s}log.Add(('FAMILY {0}' -f ${s}pkg.PackageFamilyName))
            ${s}log | ForEach-Object { Write-Output ${s}_ }
        """.trimIndent()

        val scriptFile = File.createTempFile("md3l-bedrock-register-", ".ps1")
        scriptFile.writeText(script, Charsets.UTF_8)
        try {
            onProgress?.invoke(60, "正在注册应用包…")
            val proc = ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptFile.absolutePath)
                .redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            onProgress?.invoke(80, "注册完成，正在验证…")
            println("[Bedrock] -Register 注册日志:\n$output")
            if (proc.exitValue() != 0) throw RuntimeException("基岩版 -Register 注册失败 (exit ${proc.exitValue()}):\n$output")
            val packageFullName = output.lineSequence().firstOrNull { it.startsWith("INSTALLED ") }
                ?.substringAfter("INSTALLED ")?.substringBefore(" @ ")?.trim()?.takeIf { it.isNotBlank() }
                ?: throw RuntimeException("基岩版注册成功但未解析到 PackageFullName:\n$output")
            val family = output.lineSequence().firstOrNull { it.startsWith("FAMILY ") }
                ?.substringAfter("FAMILY ")?.trim()?.takeIf { it.isNotBlank() }
                ?: throw RuntimeException("基岩版注册成功但未解析到 PackageFamilyName:\n$output")
            // AUMID = PackageFamilyName!AppId，AppId 直接从 manifest XML 解析，无需再跑 PowerShell
            val appId = parseAppIdFromManifest(manifestFile)
                ?: throw RuntimeException("基岩版注册成功但无法从 manifest 解析 Application Id:\n$output")
            val aumid = "$family!$appId"
            appendInstallMarkerSlot(versionDir, packageFile, aumid, packageFullName)
            invalidateInstalledPackageFamilyCache()
            return RegisteredSlot(aumid, packageFullName, output)
        } finally {
            scriptFile.delete()
            val backup = File(manifestFile.parentFile, "AppxManifest.xml.md3l_bak")
            if (backup.exists()) {
                try { backup.copyTo(manifestFile, overwrite = true) } catch (_: Exception) {}
                backup.delete()
            }
        }
    }

    private fun parseAppIdFromManifest(manifestFile: File): String? {
        return try {
            val content = manifestFile.readText(Charsets.UTF_8)
            Regex("""<Application\s[^>]*\bId="([^"]+)"""").find(content)?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    private fun sanitizeManifestForRegister(manifestFile: File): File {
        val content = manifestFile.readText(Charsets.UTF_8)
        // -Register -DevelopmentMode 不兼容这两个 Extension，会报 0x80080204
        val badCategories = listOf(
            "windows.customInstall",
            "windows.loopbackAccessRules",
        )
        var cleaned = content
        for (cat in badCategories) {
            cleaned = cleaned.replace(
                Regex("""<[\w:]+Extension\s[^>]*Category="${Regex.escape(cat)}"[^>]*>.*?</[\w:]+Extension>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            cleaned = cleaned.replace(
                Regex("""<[\w:]+Extension\s[^>]*Category="${Regex.escape(cat)}"[^/]*/>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        }
        if (cleaned == content) return manifestFile
        val backup = File(manifestFile.parentFile, "AppxManifest.xml.md3l_bak")
        manifestFile.copyTo(backup, overwrite = true)
        manifestFile.writeText(cleaned, Charsets.UTF_8)
        println("[Bedrock] sanitized manifest in-place: removed incompatible extensions")
        return manifestFile  // 路径不变，但内容已清理
    }

    private fun ensureVCLibsInstalled() {
        val now = System.currentTimeMillis()
        if (now - runtimeCheckPassedAt < 24 * 60 * 60 * 1000L) {
            println("[Bedrock] 运行库检查使用缓存")
            return
        }
        val components = listOf(
            Triple(
                "Microsoft.VCLibs.140.00 (UWP 框架)",
                "Microsoft.VCLibs.140.00",
                "https://aka.ms/Microsoft.VCLibs.x64.14.00.Desktop.appx" to "下载 .appx 文件后双击安装"
            ),
            Triple(
                "Microsoft.VCLibs.140.00.UWPDesktop",
                "Microsoft.VCLibs.140.00.UWPDesktop",
                "https://aka.ms/Microsoft.VCLibs.x64.14.00.Desktop.appx" to "下载 .appx 文件后双击安装 (同上)"
            ),
            Triple(
                "Microsoft.Services.Store.Engagement",
                "Microsoft.Services.Store.Engagement",
                "https://store.rg-adguard.net/" to "选择 PackageFamilyName，输入 Microsoft.Services.Store.Engagement_8wekyb3d8bbwe，下载 x64 .appx 后放入 bedrock_versions/cache 或双击安装"
            ),
            Triple(
                "Microsoft.NET.Native.Runtime (UWP)",
                "Microsoft.NET.Native.Runtime",
                "https://www.microsoft.com/store/productId/9NBLGGH4R315" to "从 Microsoft Store 页面点击获取/安装"
            ),
            Triple(
                "Microsoft.NET.Native.Framework (UWP)",
                "Microsoft.NET.Native.Framework",
                "https://www.microsoft.com/store/productId/9NBLGGH4R315" to "从 Microsoft Store 页面点击获取/安装"
            ),
        )

        val targetNames = components.map { it.second }
        val nameFilter = targetNames.joinToString(",") { "'$it'" }
        val installedPackages: Set<String> = try {
            val script = "Get-AppxPackage -Name @($nameFilter) | ForEach-Object { Write-Output \$_.Name }"
            val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", script)
                .redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            output.lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        } catch (e: Exception) {
            println("[Bedrock] 获取已安装包列表失败: ${e.message}")
            return // 检查失败不阻止启动
        }

        println("[Bedrock] 已安装 ${installedPackages.size} 个 Appx 包")
        val hasDesktopVCRedist = try {
            val dll = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32/vcruntime140.dll")
            dll.exists()
        } catch (_: Exception) { true }

        data class MissingItem(val name: String, val url: String, val tip: String)
        val missing = mutableListOf<MissingItem>()

        if (!hasDesktopVCRedist) {
            missing.add(MissingItem(
                "Visual C++ 2015-2022 Redistributable (x64)",
                "https://aka.ms/vs/17/release/vc_redist.x64.exe",
                "下载并运行 vc_redist.x64.exe 安装"
            ))
        } else {
            println("[Bedrock] ✓ vcruntime140.dll 存在")
        }

        val dedupUrls = mutableSetOf<String>()
        for ((displayName, pkgPrefix, urlAndTip) in components) {
            val found = installedPackages.any { it.startsWith(pkgPrefix, ignoreCase = true) }
            if (found) {
                println("[Bedrock] ✓ $displayName 已安装")
            } else {
                println("[Bedrock] ✗ $displayName 缺失")
                if (urlAndTip.first !in dedupUrls) {
                    dedupUrls.add(urlAndTip.first)
                    missing.add(MissingItem(displayName, urlAndTip.first, urlAndTip.second))
                }
            }
        }

        if (missing.isEmpty()) {
            println("[Bedrock] 所有运行库检查通过")
            runtimeCheckPassedAt = System.currentTimeMillis()
            return
        }

        println("[Bedrock] 缺失 ${missing.size} 个组件，打开浏览器下载")
        missing.forEach { item ->
            try {
                ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", item.url)
                    .redirectErrorStream(true).start()
                Thread.sleep(800)
            } catch (e: Exception) {
                println("[Bedrock] 打开失败: ${item.url} - ${e.message}")
            }
        }

        val detail = missing.mapIndexed { i, item ->
            "${i + 1}. ${item.name}\n   → ${item.tip}"
        }.joinToString("\n\n")

        throw RuntimeException(
            "基岩版缺少 ${missing.size} 个运行库组件，已打开浏览器引导安装：\n\n" +
            detail + "\n\n" +
            "全部安装完成后，请重新点击启动游戏。"
        )
    }

    private fun hasCachedDependencyPackage(packageNamePrefix: String): Boolean {
        return try {
            val settings = runBlocking { AppSettings.load() }
            val cacheDir = File(settings.minecraftDir, "bedrock_versions/cache")
            cacheDir.listFiles()?.any { file ->
                file.isFile &&
                    file.extension.equals("appx", ignoreCase = true) &&
                    file.name.startsWith(packageNamePrefix, ignoreCase = true)
            } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun detectInstalledMinecraft(): String? {
        return try {
            val script = """
                ${'$'}pkg = Get-AppxPackage | Where-Object { ${'$'}_.Name -like '*Minecraft*' -and ${'$'}_.Name -notlike '*Education*' -and ${'$'}_.Name -notlike '*Creator*' } | Select-Object -First 1
                if (${'$'}pkg) {
                    ${'$'}appId = (Get-AppxPackageManifest ${'$'}pkg).Package.Applications.Application.Id
                    Write-Output "${'$'}(${'$'}pkg.PackageFamilyName)!${'$'}appId"
                }
            """.trimIndent()
            val proc = ProcessBuilder(
                "powershell", "-NoProfile", "-Command", script
            ).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (output.isNotBlank() && output.contains("!") && !output.contains("error", ignoreCase = true)) {
                println("[Bedrock] 检测到已安装包: $output")
                output
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun detectMinecraftFromInstallLocation(installDir: File): String? {
        return try {
            val loc = installDir.absolutePath.replace("'", "''")
            val script = """
                ${'$'}pkg = Get-AppxPackage | Where-Object { ${'$'}_.InstallLocation -ieq '$loc' } | Select-Object -First 1
                if (${'$'}pkg) {
                    ${'$'}appId = (Get-AppxPackageManifest ${'$'}pkg).Package.Applications.Application.Id
                    Write-Output "${'$'}(${'$'}pkg.PackageFamilyName)!${'$'}appId"
                }
            """.trimIndent()
            val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", script)
                .redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (output.isNotBlank() && output.contains("!") && !output.contains("error", ignoreCase = true)) {
                println("[Bedrock] 本地包 AUMID: $output")
                output
            } else null
        } catch (_: Exception) {
            null
        }
    }

    // vtable: [0]QueryInterface [1]AddRef [2]Release [3]ActivateApplication [4]ActivateForFile [5]ActivateForProtocol
    private fun activateUwpApplication(aumid: String): Int {
        Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED)
        try {
            val pUnknown = PointerByReference()
            var hr: HRESULT = Ole32.INSTANCE.CoCreateInstance(
                CLSID_AAM,
                null,
                WTypes.CLSCTX_LOCAL_SERVER,
                IID_IAAM,
                pUnknown,
            )
            COMUtils.checkRC(hr)

            val pAAM = pUnknown.value
                ?: throw RuntimeException("CoCreateInstance 返回 null 指针")

            val vtablePtr = pAAM.getPointer(0)
            val activateAppAddr = vtablePtr.getPointer(3L * Native.POINTER_SIZE.toLong())

            val activateApp = com.sun.jna.Function.getFunction(activateAppAddr)

            val pidRef = IntByReference()
            val aumidWStr = WString(aumid)

            hr = HRESULT(
                activateApp.invokeInt(
                    arrayOf(
                        pAAM,           // this 指针
                        aumidWStr,      // appUserModelId
                        WString(""),    // arguments (空)
                        0,              // ACTIVATEOPTIONS = AO_NONE
                        pidRef.pointer, // out processId
                    )
                )
            )
            COMUtils.checkRC(hr)

            val resultPid = pidRef.value
            if (resultPid <= 0) {
                throw RuntimeException("ActivateApplication 返回无效 PID: $resultPid")
            }

            val releaseAddr = vtablePtr.getPointer(2L * Native.POINTER_SIZE.toLong())
            val release = com.sun.jna.Function.getFunction(releaseAddr)
            release.invokeInt(arrayOf(pAAM))

            return resultPid
        } finally {
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    fun extractAppxBundle(
        bundlePath: String,
        targetDir: File,
        targetArch: String = "x64",
    ) {
        targetDir.mkdirs()
        val bundleFile = File(bundlePath)
        val ext = bundleFile.extension.lowercase()

        when (ext) {
            "appx", "msix" -> {
                extractZipToDir(bundleFile, targetDir)
            }
            "msixvc", "msixbundle", "appxbundle" -> {
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
                        extractZipToDir(tempAppx, targetDir)
                    } finally {
                        tempAppx.delete()
                    }
                }
            }
            else -> throw IllegalArgumentException("不支持的包格式: $ext")
        }
        File(targetDir, "AppxSignature.p7x").takeIf { it.exists() }?.delete()
    }

    private fun extractZipToDir(zipFile: File, targetDir: File) {
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val outFile = File(targetDir, entry.name)
                        if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    return@forEach
                }
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

    fun configureVcLibsEnvironment(processBuilder: ProcessBuilder) {
        val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val windowsApps = File("$programFiles\\WindowsApps")

        val dependencyDirs = if (windowsApps.exists()) {
            windowsApps.listFiles()?.filter { dir ->
                if (!dir.isDirectory || !dir.name.contains("x64", ignoreCase = true)) return@filter false
                val name = dir.name.lowercase()
                name.startsWith("microsoft.vclibs") ||
                    name.startsWith("microsoft.net.native.runtime") ||
                    name.startsWith("microsoft.net.native.framework") ||
                    name.startsWith("microsoft.services.store.engagement") ||
                    name.startsWith("microsoft.xbox")
            }?.sortedByDescending { it.name } ?: emptyList()
        } else {
            emptyList()
        }

        val existingPath = processBuilder.environment()["PATH"] ?: ""
        val dependencyPaths = dependencyDirs.joinToString(File.pathSeparator) { it.absolutePath }
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        val systemPaths = listOf(
            File(systemRoot, "System32").absolutePath,
            File(systemRoot, "SysWOW64").absolutePath,
        ).joinToString(File.pathSeparator)
        processBuilder.environment()["PATH"] = listOf(
            processBuilder.directory()?.absolutePath.orEmpty(),
            dependencyPaths,
            systemPaths,
            existingPath,
        ).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
        processBuilder.environment()["MD3L_BEDROCK_EXE_ISOLATED"] = "1"
        processBuilder.environment()["__COMPAT_LAYER"] = "RunAsInvoker"
    }

    fun executeDetachedBedrock(exePath: String): Process {
        val exeFile = File(exePath)
        if (!exeFile.exists()) {
            throw RuntimeException("Minecraft.Windows.exe 不存在: $exePath")
        }

        val pb = ProcessBuilder(exeFile.absolutePath)
            .directory(exeFile.parentFile)
            .redirectErrorStream(true)

        configureVcLibsEnvironment(pb)
        println("[Bedrock] EXE 隔离启动: ${exeFile.absolutePath}")
        return pb.start()
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

            // 优先用 manifest header.uuid 作目录名（LeviLauncher 同款），避免临时目录名污染
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

    private const val STORE_CATALOG_URL = "https://store.rg-adguard.net/api/GetFiles"
    private const val MINECRAFT_PRODUCT_ID = "9NBLGGH2JHXJ"

    data class BedrockVersionEntry(
        val version: String,
        val downloadUrl: String,
        val fileName: String,
        val fileSize: Long,
        val arch: String,
    )

    suspend fun fetchAvailableVersions(): List<BedrockVersionEntry> {
        return try {
            val client = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
                engine { requestTimeout = 30_000 }
            }
            val resp = client.post(STORE_CATALOG_URL) {
                contentType(io.ktor.http.ContentType.Application.FormUrlEncoded)
                setBody("type=ProductId&url=$MINECRAFT_PRODUCT_ID&ring=Retail&lang=en-US")
            }
            val html = resp.bodyAsText()
            parseStoreResponse(html)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseStoreResponse(html: String): List<BedrockVersionEntry> {
        val entries = mutableListOf<BedrockVersionEntry>()
        val regex = Regex("""href="(https?://[^"]+\.(appx|msixbundle|appxbundle))"[^>]*>([^<]+)<""")
        regex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val ext = match.groupValues[2]
            val fileName = match.groupValues[3].trim()

            if (fileName.contains("MinecraftUWP", ignoreCase = true) ||
                fileName.contains("Minecraft", ignoreCase = true)
            ) {
                val arch = when {
                    fileName.contains("x64", ignoreCase = true) -> "x64"
                    fileName.contains("x86", ignoreCase = true) -> "x86"
                    fileName.contains("arm", ignoreCase = true) -> "arm64"
                    else -> "x64"
                }

                val version = extractVersionFromFileName(fileName)

                entries.add(
                    BedrockVersionEntry(
                        version = version,
                        downloadUrl = url,
                        fileName = fileName,
                        fileSize = -1,
                        arch = arch,
                    )
                )
            }
        }
        return entries
    }

    private fun extractVersionFromFileName(fileName: String): String {
        val versionRegex = Regex("""(\d+\.\d+\.\d+(?:\.\d+)?)""")
        return versionRegex.find(fileName)?.value ?: "unknown"
    }

    suspend fun downloadAndInstall(
        entry: BedrockVersionEntry,
        minecraftDir: String,
        maxThreads: Int = 64,
    ): File {
        val cacheDir = File(minecraftDir, "bedrock_versions/cache")
        cacheDir.mkdirs()
        val destFile = File(cacheDir, entry.fileName)

        if (!destFile.exists()) {
            val task = DownloadTask(
                url = entry.downloadUrl,
                dest = destFile,
                size = entry.fileSize,
            )
            DownloadManager.downloadAll(listOf(task), maxConcurrency = maxThreads)
        }

        val versionDir = File(minecraftDir, "bedrock_versions/${entry.version}")
        val engine = BedrockLaunchEngine()
        engine.extractAppxBundle(destFile.absolutePath, versionDir, entry.arch)

        return versionDir
    }
}
