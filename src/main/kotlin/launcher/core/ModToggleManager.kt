package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 模组启用/禁用管理器。
 *
 * 原理：
 * - 对于 Java 版，扫描版本目录下的 `mods/` 文件夹中的 `.jar` 文件。
 *   禁用时重命名为 `.jar.md3ldisabled`，启用时改回 `.jar`。
 * - 对于基岩版，扫描版本目录下的 `behavior_packs/` 和 `resource_packs/` 文件夹中的子目录。
 *   禁用时将目录重命名为 `.md3ldisabled` 后缀，启用时恢复原名。
 */
object ModToggleManager {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // Java 版模组扫描缓存
    private data class ModCacheEntry(
        val mods: List<ModItem>,
        val fileTimestamps: Map<String, Long>,
    )
    private val javaModCache = ConcurrentHashMap<String, ModCacheEntry>()

    /**
     * 清除指定版本目录的模组扫描缓存。
     */
    fun clearJavaModCache(versionDir: String) {
        javaModCache.remove(versionDir)
    }

    // 数据模型
    data class ModItem(
        val name: String,           // 显示名
        val fileName: String,       // 实际文件名（或目录名）
        val isEnabled: Boolean,     // 当前是否启用
        val isDirectory: Boolean,   // 是否是目录（基岩版包）
        val filePath: String,       // 完整路径
    )

    // Java 版
    suspend fun scanJavaMods(versionDir: String): List<ModItem> = withContext(Dispatchers.IO) {
        val gameDir = File(versionDir, ".minecraft").takeIf { it.isDirectory } ?: File(versionDir)
        val modsDir = File(gameDir, "mods")
        if (!modsDir.isDirectory) {
            javaModCache.remove(versionDir)
            return@withContext emptyList()
        }

        // 获取当前所有模组文件及其最后修改时间
        val currentFiles = modsDir.listFiles()
            ?.filter { it.isFile }
            ?.filter { file ->
                file.name.endsWith(".jar", ignoreCase = true) ||
                file.name.endsWith(".jar.md3ldisabled", ignoreCase = true)
            } ?: emptyList()

        val currentTimestamps = currentFiles.associate { it.name to it.lastModified() }

        // 缓存命中：所有文件均未变化则直接复用，避免重复文件系统 I/O
        val cached = javaModCache[versionDir]
        if (cached != null && cached.fileTimestamps == currentTimestamps) {
            return@withContext cached.mods
        }

        // 缓存未命中，重新扫描并缓存结果
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

    /**
     * 切换 Java 版模组的启用/禁用状态。
     */
    suspend fun toggleJavaMod(versionDir: String, modFile: String, enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(modFile)
            if (!file.exists()) return@withContext false

            if (enable) {
                // .jar.md3ldisabled → .jar
                if (file.name.endsWith(".md3ldisabled")) {
                    val newName = file.name.substringBeforeLast(".md3ldisabled")
                    val newFile = File(file.parentFile, newName)
                    file.renameTo(newFile)
                }
            } else {
                // .jar → .jar.md3ldisabled
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

    // 基岩版
    /**
     * 扫描基岩版版本目录下的 behavior_packs 和 resource_packs 文件夹。
     */
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
            // 扫描已禁用的（_disabled 子目录中的）
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

    /**
     * 切换基岩版包的启用/禁用状态。
     * 启用：从 _disabled 移回父目录；禁用：移到 _disabled 子目录。
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun toggleBedrockPack(versionDir: String, packFilePath: String, enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(packFilePath)
            if (!dir.isDirectory) return@withContext false

            val parentDir = dir.parentFile ?: return@withContext false
            val packsRoot = if (parentDir.name == "_disabled") parentDir.parentFile else parentDir
            val disabledDir = File(packsRoot, "_disabled")

            if (enable) {
                // 从 _disabled 移回父目录
                if (parentDir.name == "_disabled") {
                    val target = File(packsRoot, dir.name)
                    if (target.exists()) target.deleteRecursively()
                    dir.renameTo(target)
                }
            } else {
                // 移到 _disabled
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
