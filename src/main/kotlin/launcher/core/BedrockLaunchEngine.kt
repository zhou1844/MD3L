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

/**
 * Bedrock Edition 启动引擎。
 *
 * 核心架构：
 * 1. 免 Appx 提取协议：从 .appx / .msixbundle 中流式解压至 .minecraft/bedrock_versions/
 * 2. UWP 绕过执行：通过 JNA 调用 Windows COM 接口
 *    IApplicationActivationManager::ActivateApplication 挂载 UWP 包体执行，
 *    绕过常规 UWP 容器限制。
 * 3. Addon 注入：拦截 .mcpack / .mcaddon，解压至 development_behavior_packs / development_resource_packs。
 */
class BedrockLaunchEngine : ILaunchEngine {

    companion object {
        // CLSID_ApplicationActivationManager = {45BA127D-10A8-46EA-8AB7-56EA9078943C}
        private val CLSID_AAM = Guid.CLSID("{45BA127D-10A8-46EA-8AB7-56EA9078943C}")

        // IID_IApplicationActivationManager = {2E941141-7F97-4756-BA1D-9DECDE894A3D}
        private val IID_IAAM = Guid.IID("{2E941141-7F97-4756-BA1D-9DECDE894A3D}")

        private var runtimeCheckPassedAt = 0L
    }

    private var runtimeChecked = false

    private fun resolveBedrockDataBackupDir(context: LaunchContext): File {
        val root = context.minecraftDir.takeIf { it.isNotBlank() }
            ?: context.version.versionDir.takeIf { it.isNotBlank() }?.let { File(it).parentFile?.parentFile?.absolutePath }
            ?: File("mc").absolutePath
        return File(root, "bedrock_data_backup/com.mojang").apply { mkdirs() }
    }

    private fun getLatestModifiedTime(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.lastModified()
        var latest = dir.lastModified()
        dir.walkTopDown().forEach { file ->
            val mod = file.lastModified()
            if (mod > latest) latest = mod
        }
        return latest
    }

    private fun syncBedrockDataFromInstalledPackages(backupDir: File) {
        val packagesDir = File(System.getenv("LOCALAPPDATA") ?: return, "Packages")
        if (!packagesDir.isDirectory) return
        val packageRoots = packagesDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("Microsoft.MinecraftUWP", ignoreCase = true)
        }.orEmpty()

        val mojangDirs = packageRoots.map { root ->
            File(root, "LocalState/games/com.mojang")
        }.filter { it.isDirectory }

        // 1. Backup from LocalState to backupDir
        mojangDirs.forEach { mojangDir ->
            mojangDir.listFiles()?.forEach { item ->
                val backupItem = File(backupDir, item.name)
                val itemTime = getLatestModifiedTime(item)
                val backupTime = getLatestModifiedTime(backupItem)
                if (!backupItem.exists() || itemTime > backupTime) {
                    println("[Bedrock] 备份基岩版数据: ${item.name}")
                    item.copyRecursively(backupItem, overwrite = true)
                }
            }
        }

        // 2. Restore from backupDir to LocalState
        val hasBackups = backupDir.listFiles()?.isNotEmpty() == true
        if (hasBackups) {
            val targetMojangDirs = packageRoots.map { root ->
                File(root, "LocalState/games/com.mojang").also { it.mkdirs() }
            }
            targetMojangDirs.forEach { mojangDir ->
                backupDir.listFiles()?.forEach { backupItem ->
                    val targetItem = File(mojangDir, backupItem.name)
                    if (!targetItem.exists()) {
                        println("[Bedrock] 恢复基岩版数据: ${backupItem.name}")
                        backupItem.copyRecursively(targetItem, overwrite = true)
                    }
                }
            }
        }
    }

    /**
     * 公开的运行库检查方法，供 UI 在启动前调用。
     * 检查通过返回 true，缺失组件则打开浏览器并抛异常。
     */
    fun checkRuntime() {
        ensureVCLibsInstalled()
        runtimeChecked = true
    }

    private fun injectSkinAndUsername(context: LaunchContext) {
        val packagesDir = File(System.getenv("LOCALAPPDATA") ?: return, "Packages")
        if (!packagesDir.isDirectory) return
        val packageRoots = packagesDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("Microsoft.MinecraftUWP", ignoreCase = true)
        }.orEmpty()

        packageRoots.forEach { root ->
            val mojangDir = File(root, "LocalState/games/com.mojang")
            if (!mojangDir.exists()) mojangDir.mkdirs()
            
            // 1. Inject Username
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

            // 2. Inject Skin Pack if skinUri is valid
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
                        // 绝对不能有 BOM (EF BB BF)
                        fos.write(manifestStr.toByteArray(Charsets.UTF_8))
                    }
                    val texturesDir = File(packDir, "textures/entity")
                    texturesDir.mkdirs()
                    
                    File(texturesDir, "steve.png").delete()
                    File(texturesDir, "alex.png").delete()
                    
                    // 为了保证不论玩家默认是哪种，都能看到自定义皮肤，我们将 Steve 和 Alex 均替换
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

    override fun execute(context: LaunchContext): Process {
        if (!runtimeChecked) ensureVCLibsInstalled()

        val bedrockDataBackupDir = resolveBedrockDataBackupDir(context)
        syncBedrockDataFromInstalledPackages(bedrockDataBackupDir)
        var launched = false
        if (context.version.type == "bedrock" && context.version.versionDir.isNotBlank()) {
            val versionDir = File(context.version.versionDir)
            val packageFile = resolveSelectedVersionPackage(versionDir)
                ?: throw RuntimeException(
                    "基岩版 ${context.version.id} 没有可安装的原始 Appx 包。\n" +
                        "目录: ${versionDir.absolutePath}\n" +
                        "请重新下载该版本，确保 cache/Minecraft-${versionDir.name}.Appx 存在。"
                )
            val slot = resolveInstalledSelectedVersionSlot(versionDir, packageFile)
                ?: installSelectedVersionPackageSlot(versionDir, packageFile)
            syncBedrockDataFromInstalledPackages(bedrockDataBackupDir)
            try {
                val pid = activateUwpApplication(slot.aumid)
                println("[Bedrock] COM 激活成功: PID=$pid, AUMID=${slot.aumid}")
                launched = true
            } catch (e: Exception) {
                println("[Bedrock] COM 激活失败: ${e.message}，尝试 shell 启动")
                val script = "Start-Process 'shell:appsFolder\\${slot.aumid.replace("'", "''")}'"
                val ps = ProcessBuilder("powershell", "-NoProfile", "-Command", script)
                    .redirectErrorStream(true).start()
                val shellOutput = ps.inputStream.bufferedReader().readText()
                ps.waitFor()
                if (ps.exitValue() == 0) {
                    launched = true
                } else {
                    throw RuntimeException(
                        "基岩版 ${context.version.id} 安装成功但激活失败。\n" +
                            "AUMID: ${slot.aumid}\n" +
                            "COM: ${e.message}\n" +
                            "Shell: $shellOutput\n" +
                            "安装日志:\n${slot.log}"
                    )
                }
            }
        } else if (context.version.versionDir.isBlank()) {
            val detectedAumid = detectInstalledMinecraft()
            if (detectedAumid != null) {
                try {
                    val pid = activateUwpApplication(detectedAumid)
                    println("[Bedrock] COM 系统包启动成功: PID=$pid")
                    launched = true
                } catch (e: Exception) {
                    println("[Bedrock] COM 系统包启动失败: ${e.message}")
                    // 回退 shell 启动
                    try {
                        val script = "Start-Process 'shell:appsFolder\\${detectedAumid.replace("'", "''")}'"
                        val ps = ProcessBuilder("powershell", "-NoProfile", "-Command", script)
                            .redirectErrorStream(true).start()
                        ps.waitFor()
                        if (ps.exitValue() == 0) launched = true
                    } catch (_: Exception) {}
                }
            }
        }

        if (!launched && context.version.type == "bedrock" && context.version.versionDir.isNotBlank()) {
            throw RuntimeException(
                "基岩版 ${context.version.id} 已执行官方 Appx 安装槽位替换，但未能激活。\n" +
                    "启动器没有回退到 C 盘系统包，以避免高版本覆盖低版本。"
            )
        }

        // ── 策略 3：使用 minecraft: URI 协议启动 ─────────────────────
        if (!launched) {
            try {
                println("[Bedrock] 尝试 minecraft: URI")
                ProcessBuilder("cmd", "/c", "start", "minecraft:")
                    .redirectErrorStream(true).start()
                launched = true
                println("[Bedrock] URI 协议启动成功")
            } catch (e: Exception) {
                throw RuntimeException(
                    "基岩版启动失败: 未检测到已安装的 Minecraft 基岩版。\n" +
                    "请确保已通过下载安装页完成安装。\n" +
                    "原因: ${e.message}"
                )
            }
        }
        // ── 返回持续监控 sentinel ─────────────────────────────────────
        val monitorScript = """
            ${'$'}proc = ${'$'}null
            for (${'$'}i = 0; ${'$'}i -lt 30; ${'$'}i++) {
                ${'$'}proc = Get-Process -Name 'Minecraft.Windows' -ErrorAction SilentlyContinue | Select-Object -First 1
                if (${'$'}proc) { break }
                Start-Sleep -Seconds 1
            }
            if (${'$'}proc) {
                Start-Sleep -Seconds 10
                ${'$'}still = Get-Process -Id ${'$'}proc.Id -ErrorAction SilentlyContinue
                if (-not ${'$'}still) { Write-Output 'Minecraft.Windows exited during startup'; exit 1 }
                Wait-Process -Id ${'$'}proc.Id -ErrorAction SilentlyContinue
            } else {
                Write-Output 'Minecraft.Windows process was not found after activation'
                exit 1
            }
            ${bedrockDataBackupDir.absolutePath.replace("'", "''").let { backup ->
                """
            ${'$'}backup = '$backup'
            ${'$'}roots = Get-ChildItem -Path (Join-Path ${'$'}env:LOCALAPPDATA 'Packages') -Directory -ErrorAction SilentlyContinue |
                Where-Object { ${'$'}_.Name -like 'Microsoft.MinecraftUWP*' }
            foreach (${'$'}root in ${'$'}roots) {
                ${'$'}mojang = Join-Path ${'$'}root.FullName 'LocalState\games\com.mojang'
                if (Test-Path ${'$'}mojang) {
                    New-Item -ItemType Directory -Force -Path ${'$'}backup | Out-Null
                    Copy-Item -Path (Join-Path ${'$'}mojang '*') -Destination ${'$'}backup -Recurse -Force -ErrorAction SilentlyContinue
                }
            }
                """.trimIndent()
            }}
        """.trimIndent()

        return ProcessBuilder(
            "powershell", "-NoProfile", "-Command", monitorScript
        ).redirectErrorStream(true).start()
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

    private data class RegisteredSlot(
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
        if (markerSource != packageFile.absolutePath) return null

        // 旧 marker 没有 packageFullName，视为不可信，强制重装到选中版本
        if (markerAumid == null || markerPackageFullName == null) return null

        if (!isAumidInstalled(markerAumid)) return null
        val installedPackageFullName = queryInstalledPackageFullNameByAumid(markerAumid) ?: return null
        if (!installedPackageFullName.equals(markerPackageFullName, ignoreCase = true)) {
            println(
                "[Bedrock] 检测到外部更新/替换：" +
                    "marker=$markerPackageFullName, installed=$installedPackageFullName，强制重装选中版本槽位"
            )
            return null
        }

        return RegisteredSlot(markerAumid, markerPackageFullName, "FAST_PATH marker aumid+package")
    }

    private fun queryInstalledPackageFullNameByAumid(aumid: String): String? {
        return try {
            val family = aumid.substringBefore("!").replace("'", "''")
            val script = "\$pkg = Get-AppxPackage | Where-Object { \$_.PackageFamilyName -ieq '$family' } | Select-Object -First 1; if (\$pkg) { Write-Output \$pkg.PackageFullName }"
            val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", script)
                .redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            output.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isAumidInstalled(aumid: String): Boolean {
        return try {
            val family = aumid.substringBefore("!").replace("'", "''")
            val script = "if (Get-AppxPackage | Where-Object { \$_.PackageFamilyName -ieq '$family' }) { Write-Output 'YES' }"
            val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", script)
                .redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output.contains("YES")
        } catch (_: Exception) {
            false
        }
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

    private fun installSelectedVersionPackageSlot(versionDir: File, packageFile: File): RegisteredSlot {
        val scriptFile = File.createTempFile("md3l-bedrock-install-slot-", ".ps1")
        val script = """
            param([Parameter(Mandatory=${'$'}true)][string]${'$'}PackagePath)
            ${'$'}ProgressPreference = 'SilentlyContinue'
            ${'$'}ErrorActionPreference = 'Stop'
            try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
            ${'$'}log = New-Object System.Collections.Generic.List[string]
            try { Get-Process -Name 'Minecraft.Windows' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue; ${'$'}log.Add('STOP_PROCESS_OK') } catch { ${'$'}log.Add(('STOP_PROCESS_SKIP {0}' -f ${'$'}_.Exception.Message)) }
            ${'$'}existing = Get-AppxPackage -Name 'Microsoft.MinecraftUWP' -ErrorAction SilentlyContinue
            foreach (${'$'}pkg in ${'$'}existing) {
                ${'$'}log.Add(('REMOVE {0} @ {1}' -f ${'$'}pkg.PackageFullName, ${'$'}pkg.InstallLocation))
                try { Remove-AppxPackage -Package ${'$'}pkg.PackageFullName -ErrorAction Stop } catch { ${'$'}log.Add(('REMOVE_FAIL {0}' -f ${'$'}_.Exception.Message)) }
            }
            ${'$'}packageDir = Split-Path -Parent ${'$'}PackagePath
            ${'$'}dependencyPaths = @()
            if (${'$'}packageDir -and (Test-Path ${'$'}packageDir)) {
                ${'$'}dependencyPaths = Get-ChildItem -Path ${'$'}packageDir -File -ErrorAction SilentlyContinue |
                    Where-Object {
                        ${'$'}_.Name -like 'Microsoft.Services.Store.Engagement*.appx' -or
                        ${'$'}_.Name -like 'Microsoft.VCLibs*.appx' -or
                        ${'$'}_.Name -like 'Microsoft.NET.Native*.appx'
                    } |
                    Select-Object -ExpandProperty FullName
            }
            try {
                if (${'$'}dependencyPaths.Count -gt 0) {
                    ${'$'}log.Add(('DEPENDENCIES {0}' -f (${'$'}dependencyPaths -join '; ')))
                    Add-AppxPackage -Path ${'$'}PackagePath -DependencyPath ${'$'}dependencyPaths -ForceApplicationShutdown -ForceUpdateFromAnyVersion -ErrorAction Stop
                } else {
                    Add-AppxPackage -Path ${'$'}PackagePath -ForceApplicationShutdown -ForceUpdateFromAnyVersion -ErrorAction Stop
                }
                ${'$'}log.Add(('INSTALL_OK {0}' -f ${'$'}PackagePath))
            } catch {
                ${'$'}log.Add(('INSTALL_FAIL {0}' -f ${'$'}_.Exception.Message))
                ${'$'}log | ForEach-Object { Write-Output ${'$'}_ }
                exit 31
            }
            ${'$'}pkg = ${'$'}null
            for (${'$'}i = 0; ${'$'}i -lt 40; ${'$'}i++) {
                ${'$'}pkg = Get-AppxPackage -Name 'Microsoft.MinecraftUWP' -ErrorAction SilentlyContinue | Select-Object -First 1
                if (${'$'}pkg) { break }
                Start-Sleep -Milliseconds 250
            }
            if (-not ${'$'}pkg) {
                ${'$'}log.Add('VERIFY_FAIL Microsoft.MinecraftUWP not found after install')
                ${'$'}log | ForEach-Object { Write-Output ${'$'}_ }
                exit 32
            }
            ${'$'}appId = (Get-AppxPackageManifest ${'$'}pkg).Package.Applications.Application.Id
            ${'$'}aumid = ('{0}!{1}' -f ${'$'}pkg.PackageFamilyName, ${'$'}appId)
            ${'$'}log.Add(('INSTALLED {0} @ {1}' -f ${'$'}pkg.PackageFullName, ${'$'}pkg.InstallLocation))
            ${'$'}log.Add(('AUMID {0}' -f ${'$'}aumid))
            ${'$'}log | ForEach-Object { Write-Output ${'$'}_ }
        """.trimIndent()
        scriptFile.writeText(script, Charsets.UTF_8)
        try {
            val proc = ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptFile.absolutePath, "-PackagePath", packageFile.absolutePath)
                .redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            println("[Bedrock] 官方安装槽位日志:\n$output")
            if (proc.exitValue() != 0) throw RuntimeException("基岩版官方 Appx 安装槽位失败 (exit ${proc.exitValue()}):\n$output")
            val aumid = output.lineSequence().firstOrNull { it.startsWith("AUMID ") }?.substringAfter("AUMID ")?.trim()?.takeIf { it.contains("!") }
                ?: throw RuntimeException("基岩版官方 Appx 安装成功但未解析到 AUMID:\n$output")
            val packageFullName = output.lineSequence().firstOrNull { it.startsWith("INSTALLED ") }
                ?.substringAfter("INSTALLED ")
                ?.substringBefore(" @ ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw RuntimeException("基岩版官方 Appx 安装成功但未解析到 PackageFullName:\n$output")
            appendInstallMarkerSlot(versionDir, packageFile, aumid, packageFullName)
            return RegisteredSlot(aumid, packageFullName, output)
        } finally {
            scriptFile.delete()
        }
    }
    /**
     * 逐个检查基岩版所需的全部运行库组件。
     * 缺失的直接打开浏览器下载页面并抛异常阻止启动。
     */
    private fun ensureVCLibsInstalled() {
        val now = System.currentTimeMillis()
        if (now - runtimeCheckPassedAt < 5 * 60 * 1000L) {
            println("[Bedrock] 运行库检查使用缓存")
            return
        }
        // 组件名 → (PowerShell 包名通配符, 下载链接, 安装说明)
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

        // 一次性查询所有已安装的 Appx 包名
        val installedPackages: Set<String> = try {
            val script = "Get-AppxPackage | ForEach-Object { Write-Output \$_.Name } "
            val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", script)
                .redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            output.lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        } catch (e: Exception) {
            println("[Bedrock] 获取已安装包列表失败: ${e.message}")
            return // 检查失败不阻止启动
        }

        println("[Bedrock] 已安装 ${installedPackages.size} 个 Appx 包")

        // 检查 VC++ Redistributable（桌面版）
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

        // 检查 UWP 包依赖
        val dedupUrls = mutableSetOf<String>()
        for ((displayName, pkgPrefix, urlAndTip) in components) {
            val cached = hasCachedDependencyPackage(pkgPrefix)
            val found = installedPackages.any { it.startsWith(pkgPrefix, ignoreCase = true) } || cached
            if (found) {
                println("[Bedrock] ✓ $displayName ${if (cached) "已在缓存中" else "已安装"}")
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

        // 打开浏览器
        println("[Bedrock] 缺失 ${missing.size} 个组件，打开浏览器下载")
        missing.forEach { item ->
            try {
                // 用 rundll32 打开 URL 避免 cmd start 转义问题
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

    /**
     * 通过 PowerShell 动态检测已安装的 Minecraft 基岩版 AUMID。
     */
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  UWP COM 激活 —— IApplicationActivationManager::ActivateApplication
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 通过 Windows COM 接口 IApplicationActivationManager 激活 UWP 应用。
     *
     * 调用链：
     *   CoInitializeEx → CoCreateInstance(CLSID_AAM) → QueryInterface(IID_IAAM)
     *   → ActivateApplication(aumid, args, options) → 返回 PID
     *
     * vtable 布局 (继承自 IUnknown):
     *   [0] QueryInterface
     *   [1] AddRef
     *   [2] Release
     *   [3] ActivateApplication(LPCWSTR appUserModelId, LPCWSTR arguments, DWORD options, DWORD* processId)
     *   [4] ActivateForFile
     *   [5] ActivateForProtocol
     */
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

            // 读取 vtable 指针
            val vtablePtr = pAAM.getPointer(0)
            // ActivateApplication 是 vtable[3] (IUnknown 占 0-2)
            val activateAppAddr = vtablePtr.getPointer(3L * Native.POINTER_SIZE.toLong())

            val activateApp = com.sun.jna.Function.getFunction(activateAppAddr)

            val pidRef = IntByReference()
            val aumidWStr = WString(aumid)

            // ACTIVATEOPTIONS.AO_NONE = 0
            // int ActivateApplication(LPCWSTR, LPCWSTR, DWORD, DWORD*)
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

            // Release COM 对象
            val releaseAddr = vtablePtr.getPointer(2L * Native.POINTER_SIZE.toLong())
            val release = com.sun.jna.Function.getFunction(releaseAddr)
            release.invokeInt(arrayOf(pAAM))

            return resultPid
        } finally {
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  免 Appx 提取协议 (Appx Extraction Protocol)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 从 .appx 或 .msixbundle 中流式解压 Bedrock 版本文件至
     * .minecraft/bedrock_versions/{versionId}/
     *
     * .appx 本质是 ZIP 格式，直接用 java.util.zip.ZipFile 解压。
     * .msixbundle 是包含多个 .appx 的 ZIP，需先提取目标架构的 .appx 再解压。
     */
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
            "msixbundle", "appxbundle" -> {
                ZipFile(bundleFile).use { bundle ->
                    val targetEntry = bundle.entries().asSequence().find { entry ->
                        isTargetArchPayload(entry.name, targetArch)
                    } ?: throw RuntimeException(
                        "未在 bundle 中找到 $targetArch 架构的 .appx 包体"
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
                // 防止 Zip Slip 攻击
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    return@forEach
                }
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input: InputStream ->
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
        return name.endsWith("_$arch.appx") ||
            name.endsWith("_$arch.msix") ||
            "_${arch}_" in name ||
            "_${arch}__" in name
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  VCLibs 依赖环境映射
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 为脱离 UWP 容器的 Minecraft.Windows.exe 映射 VCLibs 依赖。
     * 设置 PATH 环境变量包含 VCLibs DLL 目录。
     */
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

    /**
     * 尝试直接启动解压后的 Minecraft.Windows.exe（脱离 UWP 容器）。
     * 需先调用 configureVcLibsEnvironment 映射依赖。
     * 注意：此方式可能因缺少 UWP 容器上下文而失败，
     *      建议优先使用 COM 激活方式。
     */
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  Bedrock Addon 注入 (Behavior Pack / Resource Pack)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 拦截 .mcpack 和 .mcaddon 文件，提取并路由解压至对应版本的
     * development_behavior_packs 或 development_resource_packs 文件夹。
     *
     * .mcpack：单个 Pack（可能是 BP 或 RP），通过检查 manifest.json 判断类型。
     * .mcaddon：包含多个 .mcpack 的 ZIP 容器。
     */
    fun injectAddon(addonPath: String, bedrockDataDir: File) {
        val addonFile = File(addonPath)
        val ext = addonFile.extension.lowercase()

        when (ext) {
            "mcpack" -> injectSinglePack(addonFile, bedrockDataDir)
            "mcaddon" -> {
                // .mcaddon 是 ZIP，内含多个 .mcpack
                val tempDir = File(System.getProperty("java.io.tmpdir"), "md3l_addon_${System.nanoTime()}")
                tempDir.mkdirs()
                try {
                    extractZipToDir(addonFile, tempDir)
                    // 查找解压后的子 .mcpack 文件
                    val mcpacks = tempDir.walkTopDown().filter {
                        it.isFile && it.extension.lowercase() == "mcpack"
                    }.toList()

                    if (mcpacks.isNotEmpty()) {
                        // .mcaddon 内含独立的 .mcpack 文件
                        mcpacks.forEach { pack -> injectSinglePack(pack, bedrockDataDir) }
                    } else {
                        // .mcaddon 直接就是多个 pack 的合并 ZIP
                        // 检查各子目录是否包含 manifest.json
                        tempDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                            val manifest = File(subDir, "manifest.json")
                            if (manifest.exists()) {
                                val packType = detectPackType(manifest)
                                val targetBase = resolvePackTargetDir(packType, bedrockDataDir)
                                val destDir = File(targetBase, subDir.name)
                                subDir.copyRecursively(destDir, overwrite = true)
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
            val packName = packRootDir.name.ifBlank { packFile.nameWithoutExtension }
            val targetBase = resolvePackTargetDir(packType, bedrockDataDir)
            val destDir = File(targetBase, packName)
            packRootDir.copyRecursively(destDir, overwrite = true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 解析 manifest.json 中的 modules[0].type 判断 Pack 类型：
     * "data" / "script" → Behavior Pack
     * "resources" → Resource Pack
     */
    private fun detectPackType(manifestFile: File): PackType {
        return try {
            val root = Json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)).jsonObject
            val modules = root["modules"]?.jsonArray
            val firstModuleType = modules?.firstOrNull()?.jsonObject
                ?.get("type")?.jsonPrimitive?.contentOrNull?.lowercase()

            when (firstModuleType) {
                "data", "script", "client_data" -> PackType.BehaviorPack
                "resources" -> PackType.ResourcePack
                else -> PackType.BehaviorPack // 默认当 BP 处理
            }
        } catch (e: Exception) {
            PackType.BehaviorPack
        }
    }

    private fun resolvePackTargetDir(type: PackType, bedrockDataDir: File): File {
        val dirName = when (type) {
            PackType.BehaviorPack -> "development_behavior_packs"
            PackType.ResourcePack -> "development_resource_packs"
        }
        val dir = File(bedrockDataDir, dirName)
        dir.mkdirs()
        return dir
    }

    enum class PackType { BehaviorPack, ResourcePack }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Microsoft Store Update Catalog 下载框架
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Bedrock 版本下载引擎。
 * 通过爬取 Microsoft Store Update Catalog (rg-adguard / store.rg-adguard.net)
 * 获取 .appx 或 .msixbundle 下载链接。
 *
 * 工作流程：
 * 1. 向 Store Catalog API 发送 productId 查询
 * 2. 从响应中筛选出目标架构和版本的下载 URL
 * 3. 使用 DownloadManager 下载至 .minecraft/bedrock_versions/cache/
 * 4. 调用 BedrockLaunchEngine.extractAppxBundle 解压
 */
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

    /**
     * 查询可用的 Bedrock 版本列表。
     * 通过 POST 请求 Store Catalog 获取文件列表，
     * 筛选出 .appx 和 .msixbundle 格式的 Minecraft 包体。
     */
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

    /**
     * 解析 Store Catalog 响应 HTML，提取下载链接。
     * 响应格式中包含 <a> 标签指向 .appx / .msixbundle / .appxbundle 文件。
     */
    private fun parseStoreResponse(html: String): List<BedrockVersionEntry> {
        val entries = mutableListOf<BedrockVersionEntry>()
        // 匹配所有 href 链接指向 .appx/.msixbundle/.appxbundle 的下载 URL
        val regex = Regex("""href="(https?://[^"]+\.(appx|msixbundle|appxbundle))"[^>]*>([^<]+)<""")
        regex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val ext = match.groupValues[2]
            val fileName = match.groupValues[3].trim()

            // 过滤只保留 Minecraft 相关包体
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
        // 版本号格式: X.XX.XXXX.X 或 X.XX.XX.X
        val versionRegex = Regex("""(\d+\.\d+\.\d+(?:\.\d+)?)""")
        return versionRegex.find(fileName)?.value ?: "unknown"
    }

    /**
     * 下载并安装指定的 Bedrock 版本。
     */
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
