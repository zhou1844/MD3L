package launcher.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BedrockExportManager {

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    // ── 1. 导出 Addon（.mcaddon）────────────────────────────────────────────
    /**
     * 将版本 profile 目录内所有行为包和资源包合并打包为标准 .mcaddon ZIP。
     */
    fun exportAddon(versionId: String, versionDir: String, minecraftDir: String, outputFile: File): String {
        return try {
            val engine = BedrockLaunchEngine()
            val resolvedDir = minecraftDir.takeIf { it.isNotBlank() }
                ?: File(versionDir).parentFile?.parentFile?.absolutePath ?: ""
            val profileDir = engine.resolveBedrockVersionComMojang(resolvedDir, versionId)

            val packFolders = listOf(
                "behavior_packs" to profileDir.resolve("behavior_packs"),
                "resource_packs" to profileDir.resolve("resource_packs"),
            )

            var totalPacks = 0
            outputFile.parentFile?.mkdirs()
            ZipOutputStream(outputFile.outputStream().buffered()).use { zos ->
                for ((folderKey, dir) in packFolders) {
                    if (!dir.isDirectory) continue
                    dir.listFiles()?.filter { it.isDirectory }?.forEach { packDir ->
                        totalPacks++
                        packDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            val relative = "$folderKey/${packDir.name}/${packDir.toPath().relativize(file.toPath())}"
                                .replace('\\', '/')
                            zos.putNextEntry(ZipEntry(relative))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }

            if (totalPacks == 0) {
                outputFile.delete()
                "该版本暂无行为包或资源包可导出"
            } else {
                "成功导出 Addon（共 $totalPacks 个包）至: ${outputFile.absolutePath}"
            }
        } catch (e: Exception) {
            runCatching { outputFile.delete() }
            "导出 Addon 失败: ${e.message}"
        }
    }

    // ── 2. 导出 .md3l 整合包────────────────────────────────────────────────
    @Serializable
    data class Md3lPackMeta(
        val formatVersion: Int = 1,
        val versionId: String,
        val exportedAt: Long,
        val behaviorPacks: List<PackInfo> = emptyList(),
        val resourcePacks: List<PackInfo> = emptyList(),
    )

    @Serializable
    data class PackInfo(
        val name: String,
        val displayName: String = "",
        val sizeBytes: Long = 0,
    )

    /**
     * 创建 .md3l 整合包：包含版本信息 JSON + 行为包 + 资源包，不含用户个人设置。
     */
    fun exportMd3lPack(versionId: String, versionDir: String, minecraftDir: String, outputFile: File): String {
        return try {
            val engine = BedrockLaunchEngine()
            val resolvedMinecraftDir = minecraftDir.takeIf { it.isNotBlank() }
                ?: File(versionDir).parentFile?.parentFile?.absolutePath ?: ""
            val profileDir = engine.resolveBedrockVersionComMojang(resolvedMinecraftDir, versionId)

            val behaviorPacksDir = profileDir.resolve("behavior_packs")
            val resourcePacksDir = profileDir.resolve("resource_packs")

            fun collectPacks(dir: File): List<PackInfo> {
                if (!dir.isDirectory) return emptyList()
                return dir.listFiles()?.filter { it.isDirectory }?.map { packDir ->
                    val displayName = readPackDisplayNameFromDir(packDir) ?: packDir.name
                    val size = packDir.walkTopDown().sumOf { if (it.isFile) it.length() else 0L }
                    PackInfo(name = packDir.name, displayName = displayName, sizeBytes = size)
                } ?: emptyList()
            }

            val meta = Md3lPackMeta(
                versionId = versionId,
                exportedAt = System.currentTimeMillis(),
                behaviorPacks = collectPacks(behaviorPacksDir),
                resourcePacks = collectPacks(resourcePacksDir),
            )

            outputFile.parentFile?.mkdirs()
            ZipOutputStream(outputFile.outputStream().buffered()).use { zos ->
                val metaEntry = ZipEntry("md3l_pack.json")
                zos.putNextEntry(metaEntry)
                zos.write(json.encodeToString(meta).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                fun addDir(dir: File, zipPrefix: String) {
                    if (!dir.isDirectory) return
                    dir.listFiles()?.filter { it.isDirectory }?.forEach { packDir ->
                        packDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            val relative = "$zipPrefix/${packDir.name}/${packDir.toPath().relativize(file.toPath())}"
                                .replace('\\', '/')
                            zos.putNextEntry(ZipEntry(relative))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
                addDir(behaviorPacksDir, "behavior_packs")
                addDir(resourcePacksDir, "resource_packs")
            }

            "成功导出 .md3l 整合包至: ${outputFile.absolutePath}"
        } catch (e: Exception) {
            runCatching { outputFile.delete() }
            "导出 .md3l 整合包失败: ${e.message}"
        }
    }

    // ── 3. 备份版本（.md3lbackup）────────────────────────────────────────────
    /**
     * 整个版本目录（含 profile 数据）的完整 ZIP 备份，后缀 .md3lbackup。
     */
    fun backupVersion(versionId: String, versionDir: String, minecraftDir: String, outputFile: File,
                      onProgress: ((String) -> Unit)? = null): String {
        return try {
            val vd = File(versionDir)
            val engine = BedrockLaunchEngine()
            val resolvedMinecraftDir = minecraftDir.takeIf { it.isNotBlank() }
                ?: vd.parentFile?.parentFile?.absolutePath ?: ""
            val profileDir = engine.resolveBedrockVersionComMojang(resolvedMinecraftDir, versionId)

            outputFile.parentFile?.mkdirs()
            ZipOutputStream(outputFile.outputStream().buffered()).use { zos ->
                onProgress?.invoke("正在备份版本文件…")
                fun addDirToZip(dir: File, zipRoot: String) {
                    dir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relative = "$zipRoot/${dir.toPath().relativize(file.toPath())}".replace('\\', '/')
                        zos.putNextEntry(ZipEntry(relative))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                if (vd.isDirectory) addDirToZip(vd, "version")
                onProgress?.invoke("正在备份存档数据…")
                if (profileDir.isDirectory) addDirToZip(profileDir, "profile")
                // 写入备份元信息
                zos.putNextEntry(ZipEntry("backup_meta.json"))
                zos.write("""{"versionId":"$versionId","backedUpAt":${System.currentTimeMillis()},"formatVersion":1}""".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            "成功备份版本 $versionId 至: ${outputFile.absolutePath}"
        } catch (e: Exception) {
            runCatching { outputFile.delete() }
            "备份失败: ${e.message}"
        }
    }

    // ── 4. 导入 .md3l 整合包──────────────────────────────────────────────────
    /**
     * 读取 .md3l 整合包，将行为包/资源包注入到指定版本，并输出元信息描述。
     */
    fun importMd3lPack(packFile: File, versionId: String, versionDir: String, minecraftDir: String): String {
        return try {
            val engine = BedrockLaunchEngine()
            val resolvedMinecraftDir = minecraftDir.takeIf { it.isNotBlank() }
                ?: File(versionDir).parentFile?.parentFile?.absolutePath ?: ""
            val profileDir = engine.resolveBedrockVersionComMojang(resolvedMinecraftDir, versionId)

            var metaJson: String? = null
            ZipInputStream(packFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "md3l_pack.json") {
                        metaJson = zis.readBytes().toString(Charsets.UTF_8)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (metaJson == null) return "无效的 .md3l 文件：缺少 md3l_pack.json 元信息"
            val meta = runCatching { json.decodeFromString<Md3lPackMeta>(metaJson!!) }.getOrNull()
                ?: return "解析 .md3l 元信息失败"

            var bpCount = 0; var rpCount = 0
            ZipInputStream(packFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name != "md3l_pack.json") {
                        val parts = entry.name.replace('\\', '/').split("/")
                        if (parts.size >= 2) {
                            val folder = parts[0]
                            val outFile = profileDir.resolve(entry.name.replace('/', File.separatorChar))
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { zis.copyTo(it) }
                            if (folder == "behavior_packs" && parts.size == 3) bpCount++
                            else if (folder == "resource_packs" && parts.size == 3) rpCount++
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            "成功导入 .md3l 整合包（行为包 ${meta.behaviorPacks.size} 个，资源包 ${meta.resourcePacks.size} 个）至版本 $versionId"
        } catch (e: Exception) {
            "导入 .md3l 整合包失败: ${e.message}"
        }
    }

    // ── 5. 导入 .md3lbackup 备份──────────────────────────────────────────────
    /**
     * 将 .md3lbackup 恢复到目标游戏目录，恢复版本文件和 profile 数据。
     */
    fun restoreBackup(backupFile: File, minecraftDir: String, onProgress: ((String) -> Unit)? = null): String {
        return try {
            var backupMeta: Map<String, String> = emptyMap()
            ZipInputStream(backupFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "backup_meta.json") {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        val obj = Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
                        backupMeta = obj?.entries?.associate { (k, v) ->
                            k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                        } ?: emptyMap()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            val versionId = backupMeta["versionId"] ?: return "无效的 .md3lbackup 文件：缺少版本信息"
            val settings = kotlinx.coroutines.runBlocking { AppSettings.load() }
            val resolvedDir = minecraftDir.takeIf { it.isNotBlank() } ?: settings.minecraftDir

            val versionRootDir = File(resolvedDir, "bedrock_versions/$versionId")
            val engine = BedrockLaunchEngine()
            val profileDir = engine.resolveBedrockVersionComMojang(resolvedDir, versionId)

            onProgress?.invoke("正在恢复版本 $versionId…")
            ZipInputStream(backupFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val parts = entry.name.replace('\\', '/').split("/", limit = 2)
                        if (parts.size == 2) {
                            val destRoot = when (parts[0]) {
                                "version" -> versionRootDir
                                "profile" -> profileDir
                                else -> null
                            }
                            if (destRoot != null) {
                                val outFile = File(destRoot, parts[1].replace('/', File.separatorChar))
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { zis.copyTo(it) }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            "成功恢复备份版本 $versionId 至: ${versionRootDir.absolutePath}"
        } catch (e: Exception) {
            "恢复备份失败: ${e.message}"
        }
    }

    // ── 工具函数 ─────────────────────────────────────────────────────────────
    private fun readPackDisplayNameFromDir(packDir: File): String? {
        return try {
            val manifest = File(packDir, "manifest.json")
            if (!manifest.exists()) return null
            val j = Json { ignoreUnknownKeys = true }
            val root = j.parseToJsonElement(manifest.readText())
                as? kotlinx.serialization.json.JsonObject ?: return null
            val header = root["header"] as? kotlinx.serialization.json.JsonObject ?: return null
            (header["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        } catch (_: Exception) { null }
    }
}
