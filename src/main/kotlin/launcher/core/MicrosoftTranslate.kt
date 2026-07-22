package launcher.core

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object MicrosoftTranslate {

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 8_000 }
    }
    private val json = Json { ignoreUnknownKeys = true }

    private val translationCache = ConcurrentHashMap<String, String>()

    private val apiMutex = Mutex()

    @Volatile
    private var lastApiCallMs = 0L

    suspend fun toChinese(text: String): String = text

    suspend fun toEnglish(text: String): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        val zhChars = text.count { it.code in 0x4E00..0x9FFF }
        if (zhChars == 0) return@withContext text

        translationCache[text]?.let { return@withContext it }

        val lower = text.trim().lowercase()
        MINECRAFT_TERMS[lower]?.let {
            translationCache[text] = it
            return@withContext it
        }

        val words = text.trim().split(Regex("[\\s,，、;；]+")).filter { it.isNotBlank() }
        val allInDict = words.all { MINECRAFT_TERMS[it.lowercase()] != null }
        if (allInDict && words.isNotEmpty()) {
            val combined = words.map { MINECRAFT_TERMS[it.lowercase()]!! }.joinToString(" ")
            translationCache[text] = combined
            return@withContext combined
        }

        try {
            apiMutex.withLock {
                // 保证每次请求间隔至少 300ms
                val elapsed = System.currentTimeMillis() - lastApiCallMs
                if (elapsed < 300) {
                    kotlinx.coroutines.delay(300 - elapsed)
                }
                val translated = callMyMemory(text)
                if (translated != null && translated.isNotBlank() && !translated.equals(text, ignoreCase = true)) {
                    translationCache[text] = translated
                    return@withContext translated
                }
                lastApiCallMs = System.currentTimeMillis()
            }
        } catch (_: Exception) {
            // fall through
        }

        val fallback = words.map { w ->
            MINECRAFT_TERMS[w.lowercase()] ?: w
        }.joinToString(" ")
        if (fallback.isNotBlank() && fallback != text) {
            translationCache[text] = fallback
            return@withContext fallback
        }

        null
    }

    /**
     * 调用 MyMemory 免费翻译 API。
     * 文档：https://mymemory.translated.net/doc/spec.php
     */
    private suspend fun callMyMemory(text: String): String? {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=zh-CN|en-US"
        val resp = client.get(url) {
            header("User-Agent", "MD3L/1.1 (https://github.com/yunoniaodudu)")
        }
        if (resp.status != HttpStatusCode.OK) return null
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val responseData = body["responseData"]?.jsonObject ?: return null
        val translatedText = responseData["translatedText"]?.jsonPrimitive?.contentOrNull ?: return null
        val matchQuality = responseData["match"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
        // 只接受质量 >= 40% 的结果
        if (matchQuality < 0.4f) return null
        return translatedText
    }

    private val MINECRAFT_TERMS = mapOf(
        "地图" to "map", "皮肤" to "skin", "材质包" to "texture pack",
        "资源包" to "resource pack", "行为包" to "behavior pack",
        "模组" to "mod", "模块" to "mod", "插件" to "plugin",
        "武器" to "weapon", "装备" to "armor", "盔甲" to "armor",
        "工具" to "tools", "建筑" to "building", "科技" to "tech",
        "魔法" to "magic", "冒险" to "adventure", "生存" to "survival",
        "优化" to "optimization", "性能" to "performance",
        "光影" to "shader", "光追" to "ray tracing",
        "生物" to "mob", "怪物" to "monster", "动物" to "animal",
        "农业" to "farming", "食物" to "food", "烹饪" to "cooking",
        "存储" to "storage", "背包" to "backpack", "箱子" to "chest",
        "传送" to "teleport", "领地" to "land claim", "权限" to "permission",
        "经济" to "economy", "商店" to "shop", "交易" to "trade",
        "小游戏" to "minigame", "跑酷" to "parkour", "pvp" to "pvp",
        "家具" to "furniture", "装饰" to "decoration", "家居" to "furniture",
        "机械" to "machine", "自动化" to "automation", "红石" to "redstone",
        "附魔" to "enchant", "药水" to "potion", "酿造" to "brewing",
        "维度" to "dimension", "空岛" to "skyblock", "地牢" to "dungeon",
        "怪物" to "monster", "矿石" to "ore", "护甲" to "armor",
        "食物" to "food", "树木" to "tree", "经验" to "experience",
        "世界" to "world", "液体" to "liquid", "能源" to "energy",
        "管道" to "pipe", "物流" to "logistics", "种植" to "farming",
        "发电机" to "generator", "电脑" to "computer", "网络" to "network",
        "远程" to "remote", "信息" to "information", "显示" to "display",
        "区块" to "chunk", "地形" to "terrain",
        "生物群系" to "biome", "结构" to "structure", "村庄" to "village",
        "下界" to "nether", "末地" to "end", "主世界" to "overworld",
        "地狱" to "nether", "末影" to "ender", "海洋" to "ocean",
        "沙漠" to "desert", "丛林" to "jungle", "森林" to "forest",
        "山地" to "mountain", "雪地" to "snow", "沼泽" to "swamp",
        "河流" to "river", "洞穴" to "cave", "矿井" to "mine",
        "刷怪" to "spawn", "经验" to "exp", "飞行" to "fly",
        "速度" to "speed", "跳跃" to "jump", "力量" to "strength",
        "隐身" to "invisibility", "夜视" to "night vision",
        "迅捷" to "swiftness", "再生" to "regeneration",
        "方块" to "block", "物品" to "item", "实体" to "entity",
    )
}
