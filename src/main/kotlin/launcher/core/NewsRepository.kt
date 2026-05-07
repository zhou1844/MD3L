package launcher.core

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Minecraft 官方资讯仓库。
 *
 * 数据源：https://launchercontent.mojang.com/news.json
 * 解析 entries 数组，提取 title, text, playPageImage.url, readMoreLink。
 * 绝不抛异常 —— 所有错误静默降级为空列表。
 */
object NewsRepository {

    private const val NEWS_URL = "https://launchercontent.mojang.com/news.json"
    private const val IMAGE_BASE = "https://launchercontent.mojang.com/"

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 15_000 }
    }

    private val json = Json { ignoreUnknownKeys = true }

    data class NewsEntry(
        val title: String,
        val text: String,
        val imageUrl: String,
        val readMoreLink: String,
        val tag: String = "",
        val date: String = "",
    )

    private val _news = MutableStateFlow<List<NewsEntry>>(emptyList())
    val news = _news.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow("")
    val error = _error.asStateFlow()

    /**
     * 从 Mojang launchercontent API 抓取资讯列表。
     * IO 线程执行，绝不阻塞主线程。
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        _isLoading.value = true
        _error.value = ""
        try {
            val body = client.get(NEWS_URL) {
                header("User-Agent", "MD3L/1.1.0")
                header("Accept", "application/json")
            }.bodyAsText()

            val root = json.parseToJsonElement(body).jsonObject
            val entries = root["entries"]?.jsonArray ?: JsonArray(emptyList())

            val newsList = entries.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    val tag = obj["tag"]?.jsonPrimitive?.contentOrNull ?: ""
                    val date = obj["date"]?.jsonPrimitive?.contentOrNull ?: ""

                    // 封面图：playPageImage.url（相对路径需要拼接 base）
                    val imageUrl = obj["playPageImage"]?.jsonObject
                        ?.get("url")?.jsonPrimitive?.contentOrNull?.let { url ->
                            if (url.startsWith("http")) url else "$IMAGE_BASE${url.trimStart('/')}"
                        } ?: ""

                    // 阅读更多链接
                    val readMoreLink = obj["readMoreLink"]?.jsonPrimitive?.contentOrNull ?: ""

                    NewsEntry(
                        title = title,
                        text = text,
                        imageUrl = imageUrl,
                        readMoreLink = readMoreLink,
                        tag = tag,
                        date = date,
                    )
                } catch (_: Exception) {
                    null
                }
            }

            _news.value = newsList
            if (newsList.isEmpty()) {
                _error.value = "未获取到任何资讯"
            }
        } catch (e: Exception) {
            println("[NewsRepository] 资讯抓取失败: ${e.message}")
            _error.value = "资讯加载失败: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }
}
