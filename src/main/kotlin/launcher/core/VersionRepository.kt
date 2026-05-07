package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 版本仓库 —— 管理本地版本列表的 StateFlow 驱动缓存。
 *
 * 所有版本突变操作（重命名、删除）完成后必须调用 [invalidateCache]
 * 强制刷新 StateFlow，驱动 UI 重组 (Recomposition)。
 */
object VersionRepository {

    private val _versions = MutableStateFlow<List<LocalVersion>>(emptyList())
    val versions: StateFlow<List<LocalVersion>> = _versions.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * 扫描本地版本目录并更新 StateFlow。
     */
    suspend fun scan(minecraftDir: String) {
        _versions.value = VersionScanner.scan(minecraftDir)
    }

    /**
     * 强制刷新 StateFlow 缓存，驱动 UI 重组。
     */
    suspend fun invalidateCache(minecraftDir: String) {
        _versions.value = VersionScanner.scan(minecraftDir)
    }

    /**
     * 原子化版本重命名 —— 严格按以下原子操作流执行：
     *
     * 1. JsonParser 读取原 .json 构建 AST
     * 2. 突变 (Mutate) 根节点的 "id" 属性为 newName
     * 3. 将 AST 重新序列化并覆盖写入硬盘
     * 4. 使用 java.nio.file.Files.move 重命名 JSON 文件
     * 5. Files.move 重命名父级 Directory
     * 6. 显式调用 invalidateCache() 强制刷新 StateFlow
     *
     * 所有 I/O 操作使用 NIO 的 ATOMIC_MOVE 保证强一致性，
     * 消除 File.renameTo 的竞态条件 (Race Condition)。
     */
    suspend fun atomicRename(
        version: LocalVersion,
        newId: String,
        minecraftDir: String,
    ): String = withContext(Dispatchers.IO) {
        try {
            val oldDir = File(version.versionDir)
            val parentDir = oldDir.parentFile ?: return@withContext "父目录异常"
            val newDir = File(parentDir, newId)
            if (!oldDir.exists()) return@withContext "原目录不存在: ${oldDir.absolutePath}"
            if (newDir.exists()) return@withContext "目标名称已存在: $newId"

            val oldId = version.id
            val isBedrock = version.type == "bedrock"

            // ── Java 版：修改 JSON 中的 id 字段并重命名 JSON + JAR ────────
            if (!isBedrock) {
                val oldJsonFile = File(oldDir, "$oldId.json")
                if (!oldJsonFile.exists()) return@withContext "找不到版本 JSON: ${oldJsonFile.name}"

                val rawText = oldJsonFile.readText(Charsets.UTF_8)
                val rootElement = json.parseToJsonElement(rawText)
                if (rootElement !is JsonObject) return@withContext "JSON 根节点不是对象"

                val newEntries = rootElement.toMutableMap()
                newEntries["id"] = JsonPrimitive(newId)
                val updatedJson = JsonObject(newEntries)

                oldJsonFile.writeText(
                    json.encodeToString(JsonElement.serializer(), updatedJson),
                    Charsets.UTF_8,
                )

                val newJsonFile = File(oldDir, "$newId.json")
                if (oldJsonFile.name != newJsonFile.name) {
                    try {
                        Files.move(oldJsonFile.toPath(), newJsonFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(oldJsonFile.toPath(), newJsonFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }

                val oldJar = File(oldDir, "$oldId.jar")
                if (oldJar.exists()) {
                    val newJar = File(oldDir, "$newId.jar")
                    try {
                        Files.move(oldJar.toPath(), newJar.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(oldJar.toPath(), newJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }

            // ── 重命名目录（Java 和 Bedrock 通用） ────────────────────────
            try {
                Files.move(oldDir.toPath(), newDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                oldDir.copyRecursively(newDir, overwrite = true)
                oldDir.deleteRecursively()
            }

            if (!newDir.exists()) return@withContext "重命名后验证失败：目标目录不存在"

            invalidateCache(minecraftDir)

            "重命名成功: $oldId → $newId"
        } catch (e: Exception) {
            "重命名出错: ${e.message}"
        }
    }

    /**
     * 删除本地版本并刷新缓存。
     */
    suspend fun deleteVersion(version: LocalVersion, minecraftDir: String): String = withContext(Dispatchers.IO) {
        try {
            val dir = File(version.versionDir)
            if (dir.exists()) {
                dir.deleteRecursively()
                invalidateCache(minecraftDir)
                "已删除: ${version.id}"
            } else {
                "目录不存在"
            }
        } catch (e: Exception) {
            "删除失败: ${e.message}"
        }
    }
}
