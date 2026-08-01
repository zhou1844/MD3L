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

object VersionRepository {

    private val _versions = MutableStateFlow<List<LocalVersion>>(emptyList())
    val versions: StateFlow<List<LocalVersion>> = _versions.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun notifyChanged() {
        _revision.value = _revision.value + 1
    }

    suspend fun scan(minecraftDir: String) {
        _versions.value = VersionScanner.scan(minecraftDir)
    }

    suspend fun invalidateCache(minecraftDir: String) {
        _versions.value = VersionScanner.scan(minecraftDir)
        _revision.value = _revision.value + 1
    }

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
