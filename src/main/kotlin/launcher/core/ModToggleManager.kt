package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ModToggleManager {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private data class ModCacheEntry(
        val mods: List<ModItem>,
        val fileTimestamps: Map<String, Long>,
    )
    private val javaModCache = ConcurrentHashMap<String, ModCacheEntry>()

    fun clearJavaModCache(versionDir: String) {
        javaModCache.remove(versionDir)
    }

    data class ModItem(
        val name: String,
        val fileName: String,
        val isEnabled: Boolean,
        val isDirectory: Boolean,
        val filePath: String,
    )

    suspend fun scanJavaMods(versionDir: String): List<ModItem> = withContext(Dispatchers.IO) {
        val gameDir = File(versionDir, ".minecraft").takeIf { it.isDirectory } ?: File(versionDir)
        val modsDir = File(gameDir, "mods")
        if (!modsDir.isDirectory) {
            javaModCache.remove(versionDir)
            return@withContext emptyList()
        }

        val currentFiles = modsDir.listFiles()
            ?.filter { it.isFile }
            ?.filter { file ->
                file.name.endsWith(".jar", ignoreCase = true) ||
                file.name.endsWith(".jar.md3ldisabled", ignoreCase = true)
            } ?: emptyList()

        val currentTimestamps = currentFiles.associate { it.name to it.lastModified() }

        val cached = javaModCache[versionDir]
        if (cached != null && cached.fileTimestamps == currentTimestamps) {
            return@withContext cached.mods
        }

        val result = currentFiles.map { file ->
            val isDisabled = file.name.endsWith(".md3ldisabled")
            val displayName = if (isDisabled) {
                file.name.substringBeforeLast(".jar.md3ldisabled") + ".jar"
            } else {
                file.name
            }
            ModItem(
                name = displayName.substringBeforeLast(".jar"),
                fileName = file.name,
                isEnabled = !isDisabled,
                isDirectory = false,
                filePath = file.absolutePath,
            )
        }.sortedBy { it.name }

        javaModCache[versionDir] = ModCacheEntry(result, currentTimestamps)
        result
    }

    suspend fun toggleJavaMod(versionDir: String, modFile: String, enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(modFile)
            if (!file.exists()) return@withContext false

            if (enable) {
                if (file.name.endsWith(".md3ldisabled")) {
                    val newName = file.name.substringBeforeLast(".md3ldisabled")
                    val newFile = File(file.parentFile, newName)
                    file.renameTo(newFile)
                }
            } else {
                if (file.name.endsWith(".jar", ignoreCase = true) && !file.name.endsWith(".md3ldisabled")) {
                    val newFile = File(file.parentFile, "${file.name}.md3ldisabled")
                    file.renameTo(newFile)
                }
            }
            javaModCache.remove(versionDir)
            true
        } catch (e: Exception) {
            println("[ModToggle] Java 模组切换失败: ${e.message}")
            false
        }
    }

    suspend fun scanBedrockPacks(versionDir: String): List<ModItem> = withContext(Dispatchers.IO) {
        val packsDirs = listOf(
            File(versionDir, "behavior_packs"),
            File(versionDir, "resource_packs"),
        )
        val result = mutableListOf<ModItem>()
        for (packsDir in packsDirs) {
            if (!packsDir.isDirectory) continue
            val typeLabel = if (packsDir.name == "behavior_packs") "行为包" else "资源包"
            packsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.filter { dir ->
                    dir.name != "_disabled" &&
                    !dir.name.endsWith("_disabled") &&
                    dir.listFiles()?.any { it.name.equals("manifest.json", ignoreCase = true) } == true
                }
                ?.forEach { dir ->
                    result.add(ModItem(
                        name = "${dir.name} ($typeLabel)",
                        fileName = dir.name,
                        isEnabled = true,
                        isDirectory = true,
                        filePath = dir.absolutePath,
                    ))
                }
            val disabledDir = File(packsDir, "_disabled")
            if (disabledDir.isDirectory) {
                disabledDir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.filter { dir ->
                        dir.listFiles()?.any { it.name.equals("manifest.json", ignoreCase = true) } == true
                    }
                    ?.forEach { dir ->
                        result.add(ModItem(
                            name = "${dir.name} ($typeLabel) [已禁用]",
                            fileName = dir.name,
                            isEnabled = false,
                            isDirectory = true,
                            filePath = dir.absolutePath,
                        ))
                    }
            }
        }
        result.sortedBy { it.name }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun toggleBedrockPack(versionDir: String, packFilePath: String, enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(packFilePath)
            if (!dir.isDirectory) return@withContext false

            val parentDir = dir.parentFile ?: return@withContext false
            val packsRoot = if (parentDir.name == "_disabled") parentDir.parentFile else parentDir
            val disabledDir = File(packsRoot, "_disabled")

            if (enable) {
                if (parentDir.name == "_disabled") {
                    val target = File(packsRoot, dir.name)
                    if (target.exists()) target.deleteRecursively()
                    dir.renameTo(target)
                }
            } else {
                disabledDir.mkdirs()
                val target = File(disabledDir, dir.name)
                if (target.exists()) target.deleteRecursively()
                dir.renameTo(target)
            }
            true
        } catch (e: Exception) {
            println("[ModToggle] 基岩版包切换失败: ${e.message}")
            false
        }
    }
}
