package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 贴纸数据模型：支持 PNG/JPG/GIF 图片。
 * 位置以窗口宽高的比例存储（0.0~1.0），适配窗口缩放。
 */
@Serializable
data class StickerData(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val x: Float = 0.05f,          // 窗口宽比例
    val y: Float = 0.75f,          // 窗口高比例（默认左下角）
    val scale: Float = 1.0f,       // 缩放倍数
    val playbackSpeed: Float = 1.0f, // GIF 播放倍速，1.0=原速，0.5=半速，2.0=双倍速
    val zIndex: Int = 0,
)

@Serializable
data class StickerStore(
    val stickers: List<StickerData> = emptyList()
)

/**
 * 贴纸管理器：持久化贴纸元数据到 data/stickers.json，
 * 贴纸图片文件存储在 data/stickers/ 目录。
 */
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

    /** 加载贴纸元数据（启动时调用一次） */
    suspend fun load(): List<StickerData> = withContext(Dispatchers.IO) {
        try {
            if (storeFile.exists()) {
                _store = json.decodeFromString<StickerStore>(storeFile.readText(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            println("[StickerManager] 加载贴纸数据失败: ${e.message}")
            _store = StickerStore()
        }
        // 清理无效条目（文件被手动删除）
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

    /** 添加贴纸：复制图片到 stickers 目录 */
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

    /** 删除贴纸 */
    suspend fun removeSticker(id: String) = withContext(Dispatchers.IO) {
        val sticker = _store.stickers.find { it.id == id } ?: return@withContext
        try {
            File(stickersDir, sticker.fileName).delete()
        } catch (_: Exception) {}
        _store = _store.copy(stickers = _store.stickers.filter { it.id != id })
        save()
    }

    /** 更新贴纸位置、缩放和播放倍速 */
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
