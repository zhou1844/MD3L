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

    private const val NEWS_URL = "https://launchercontent.mojang.com/v2/news.json"
    private const val NEWS_URL_FALLBACK = "https://launchercontent.mojang.com/news.json"
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
     * 主源：launchercontent.mojang.com JSON API
     * 备用：minecraft.net RSS feed（保证网络正常时必有内容）
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        _isLoading.value = true
        _error.value = ""
        try {
            val result = runCatching { fetchFromJson(NEWS_URL) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: runCatching { fetchFromJson(NEWS_URL_FALLBACK) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                ?: runCatching { fetchFromRss() }.getOrNull()
                ?: emptyList()

            _news.value = result
            if (result.isEmpty()) _error.value = "未获取到任何资讯"
        } catch (e: Exception) {
            _error.value = "资讯加载失败: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun fetchFromJson(url: String): List<NewsEntry> {
        val body = client.get(url) {
            header("User-Agent", "MD3L/1.1.0")
            header("Accept", "application/json")
        }.bodyAsText()
        val root = json.parseToJsonElement(body).jsonObject
        // v2 格式: { "version": 1, "entries": [...] }，兼容 v1 同结构
        val entries = root["entries"]?.jsonArray ?: JsonArray(emptyList())
        return entries.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val text  = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val tag   = obj["tag"]?.jsonPrimitive?.contentOrNull ?: ""
                val date  = obj["date"]?.jsonPrimitive?.contentOrNull ?: ""
                val imageUrl = obj["playPageImage"]?.jsonObject
                    ?.get("url")?.jsonPrimitive?.contentOrNull?.let { imgUrl ->
                        if (imgUrl.startsWith("http")) imgUrl else "$IMAGE_BASE${imgUrl.trimStart('/')}"
                    } ?: obj["cardImage"]?.jsonObject
                    ?.get("url")?.jsonPrimitive?.contentOrNull?.let { imgUrl ->
                        if (imgUrl.startsWith("http")) imgUrl else "$IMAGE_BASE${imgUrl.trimStart('/')}"
                    } ?: ""
                val readMoreLink = obj["readMoreLink"]?.jsonPrimitive?.contentOrNull ?: ""
                NewsEntry(title = title, text = text, imageUrl = imageUrl,
                    readMoreLink = readMoreLink, tag = tag, date = date)
            }.getOrNull()
        }
    }

    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        MicrosoftTranslate.toChinese(text)
    }

    suspend fun fetchArticleBody(url: String): String = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext ""
        runCatching {
            val html = client.get(url) {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                header("Accept-Language", "en-US,en;q=0.9")
                header("Accept", "text/html,application/xhtml+xml")
            }.bodyAsText()

            // 去除 script/style/nav/header/footer 干扰
            val cleaned = html
                .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<nav[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<header[\\s\\S]*?</header>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<footer[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), "")

            // 尝试提取 article/main 主体
            val body = Regex("<article[^>]*>(.*?)</article>", RegexOption.DOT_MATCHES_ALL)
                .find(cleaned)?.groupValues?.getOrNull(1)
                ?: Regex("<main[^>]*>(.*?)</main>", RegexOption.DOT_MATCHES_ALL)
                    .find(cleaned)?.groupValues?.getOrNull(1)
                ?: Regex("""<div[^>]*class="[^"]*(?:article|content|body)[^"]*"[^>]*>(.*?)</div>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .find(cleaned)?.groupValues?.getOrNull(1)
                ?: cleaned

            // 把 <h1>~<h4> 转成带换行的标题，<p> 转成段落
            val paragraphs = StringBuilder()
            Regex("<(h[1-4]|p)[^>]*>(.*?)</(h[1-4]|p)>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(body)
                .forEach { m ->
                    val tag = m.groupValues[1].lowercase()
                    val inner = m.groupValues[2]
                        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&nbsp;", " ").replace("&amp;", "&")
                        .replace("&quot;", "\"").replace("&#39;", "'")
                        .replace("&lt;", "<").replace("&gt;", ">")
                        .replace(Regex("[ \t]{2,}"), " ").trim()
                    if (inner.isNotBlank()) {
                        if (tag.startsWith("h")) paragraphs.append("\n【$inner】\n")
                        else paragraphs.append(inner).append("\n\n")
                    }
                }

            val result = paragraphs.toString().replace(Regex("\n{3,}"), "\n\n").trim()
            // 如果 <p> 提取失败（某些页面纯 div 结构），退回全文去标签
            if (result.length < 100) {
                body.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("</(?:p|div|section)>", RegexOption.IGNORE_CASE), "\n\n")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace("&nbsp;", " ").replace("&amp;", "&")
                    .replace("&quot;", "\"").replace("&#39;", "'")
                    .replace(Regex("\\n{3,}"), "\n\n")
                    .replace(Regex("[ \t]{2,}"), " ").trim()
            } else result
        }.getOrDefault("")
    }

    private suspend fun fetchFromRss(): List<NewsEntry> {
        val rssUrl = "https://www.minecraft.net/en-us/feeds/community-content/articles.xml"
        val body = client.get(rssUrl) {
            header("User-Agent", "MD3L/1.1.0")
        }.bodyAsText()
        // 简单 XML 解析（不引入 DOM 库，regex 抽取 item 字段）
        val items = "<item>(.*?)</item>".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(body)
        return items.mapNotNull { match ->
            runCatching {
                val item = match.groupValues[1]
                val title = "<title><!\\[CDATA\\[(.*?)\\]\\]></title>|<title>(.*?)</title>".toRegex()
                    .find(item)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } } ?: return@runCatching null
                val link  = "<link>(.*?)</link>".toRegex().find(item)?.groupValues?.get(1) ?: ""
                val desc  = "<description><!\\[CDATA\\[(.*?)\\]\\]></description>".toRegex()
                    .find(item)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                val date  = "<pubDate>(.*?)</pubDate>".toRegex().find(item)?.groupValues?.get(1) ?: ""
                NewsEntry(title = title.trim(), text = desc, imageUrl = "",
                    readMoreLink = link.trim(), tag = "官网", date = date.trim())
            }.getOrNull()
        }.take(20).toList()
    }
}
