package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class StickerData(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val x: Float = 0.05f,
    val y: Float = 0.75f,
    val scale: Float = 1.0f,
    val playbackSpeed: Float = 1.0f,
    val zIndex: Int = 0,
)

@Serializable
data class StickerStore(
    val stickers: List<StickerData> = emptyList()
)

object StickerManager {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    val stickersDir: File by lazy {
        File(LauncherDirs.dataDir, "stickers").also { it.mkdirs() }
    }

    private val storeFile: File by lazy {
        File(LauncherDirs.dataDir, "stickers.json")
    }

    private var _store: StickerStore = StickerStore()

    val stickers: List<StickerData> get() = _store.stickers

    suspend fun load(): List<StickerData> = withContext(Dispatchers.IO) {
        try {
            if (storeFile.exists()) {
                _store = json.decodeFromString<StickerStore>(storeFile.readText(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            println("[StickerManager] 加载贴纸数据失败: ${e.message}")
            _store = StickerStore()
        }
        _store = _store.copy(stickers = _store.stickers.filter { s ->
            File(stickersDir, s.fileName).exists()
        })
        _store.stickers
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        try {
            storeFile.writeText(json.encodeToString(_store), Charsets.UTF_8)
        } catch (e: Exception) {
            println("[StickerManager] 保存贴纸数据失败: ${e.message}")
        }
    }

    suspend fun addSticker(sourceFile: File): StickerData? = withContext(Dispatchers.IO) {
        try {
            val ext = sourceFile.extension.lowercase()
            if (ext !in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp")) return@withContext null

            val newName = "${UUID.randomUUID()}.${sourceFile.extension}"
            val destFile = File(stickersDir, newName)
            sourceFile.copyTo(destFile, overwrite = true)

            val maxZ = _store.stickers.maxOfOrNull { it.zIndex } ?: 0
            val sticker = StickerData(
                fileName = newName,
                zIndex = maxZ + 1,
            )
            _store = _store.copy(stickers = _store.stickers + sticker)
            save()
            sticker
        } catch (e: Exception) {
            println("[StickerManager] 添加贴纸失败: ${e.message}")
            null
        }
    }

    suspend fun removeSticker(id: String) = withContext(Dispatchers.IO) {
        val sticker = _store.stickers.find { it.id == id } ?: return@withContext
        try {
            File(stickersDir, sticker.fileName).delete()
        } catch (_: Exception) {}
        _store = _store.copy(stickers = _store.stickers.filter { it.id != id })
        save()
    }

    suspend fun updateSticker(id: String, x: Float? = null, y: Float? = null, scale: Float? = null, playbackSpeed: Float? = null) {
        _store = _store.copy(stickers = _store.stickers.map { s ->
            if (s.id == id) s.copy(
                x = x ?: s.x,
                y = y ?: s.y,
                scale = scale ?: s.scale,
                playbackSpeed = playbackSpeed ?: s.playbackSpeed,
            ) else s
        })
        save()
    }
}
