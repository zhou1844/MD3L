package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * 基岩版安装包获取器 —— 基于 MCAPPX 版本库 (mcappx.com)。
 *
 * 架构:
 * 1. fetchVersions() — 单次 GET search_index.json 获取全部 386+ 版本列表（<1秒）
 * 2. resolveDownloadUrl() — 点击下载时按需 GET 版本详情页，提取 dl.mcappx.com CDN 链接
 *
 * 零第三方 HTTP 依赖：纯 java.net.HttpURLConnection。
 */
object McAppxClient {

    private const val SEARCH_INDEX = "https://www.mcappx.com/search/search_index.json"
    private const val MCAPPX_BASE = "https://www.mcappx.com/"
    private const val PREFERRED_REGION = "jp"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000

    private val json = Json { ignoreUnknownKeys = true }

    data class BedrockVersion(
        val version: String,
        val downloadUrl: String = "",
        val fileName: String = "",
        val arch: String = "x64",
        val isPreview: Boolean = false,
        val fileSize: Long = -1L,
        val detailPath: String = "",
    )

    // ═══════════════════════════════════════════════════════════════════════
    //  公共 API — 版本列表（瞬间返回）
    // ═══════════════════════════════════════════════════════════════════════

    suspend fun fetchVersions(): List<BedrockVersion> = withContext(Dispatchers.IO) {
        println("[MCAPPX] 正在获取版本索引...")
        val body = httpGet(SEARCH_INDEX)
        val root = json.parseToJsonElement(body).jsonObject
        val docs = root["docs"]?.jsonArray ?: return@withContext emptyList()

        val titleRegex = Regex("""^(Release|Preview)\s+(.+?)(?:¶.*)?$""")
        val versions = mutableListOf<BedrockVersion>()

        for (doc in docs) {
            val obj = doc.jsonObject
            val location = obj["location"]?.jsonPrimitive?.contentOrNull ?: continue
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: continue

            if (!location.startsWith("bedrock/")) continue
            val match = titleRegex.find(title) ?: continue

            val type = match.groupValues[1]
            val ver = match.groupValues[2].trim()
            val isPreview = type == "Preview"
            val groupPath = location.substringBefore("#")
            val detailPath = "$groupPath$ver/"

            versions.add(
                BedrockVersion(
                    version = ver,
                    isPreview = isPreview,
                    detailPath = detailPath,
                )
            )
        }

        println("[MCAPPX] 索引解析完成: ${versions.size} 个版本")

        versions.distinctBy { "${it.version}_${it.isPreview}" }
            .sortedWith(compareByDescending<BedrockVersion> { versionSortKey(it.version) }
                .thenBy { it.isPreview })
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  按需解析下载链接（点击下载时调用）
    // ═══════════════════════════════════════════════════════════════════════

    suspend fun resolveDownloadUrl(version: BedrockVersion): BedrockVersion = withContext(Dispatchers.IO) {
        if (version.downloadUrl.isNotBlank()) return@withContext version

        val pageUrl = "$MCAPPX_BASE${version.detailPath}"
        println("[MCAPPX] 解析下载链接: $pageUrl")
        val html = httpGet(pageUrl)

        // 提取 dl.mcappx.com 下载链接
        val linkRegex = Regex("""https://dl\.mcappx\.com/[^"]+""")
        val allLinks = linkRegex.findAll(html).map { it.value }.toList()

        if (allLinks.isEmpty()) {
            throw RuntimeException("版本页未找到下载链接: ${version.version}")
        }

        // 优先亚太 CDN 节点
        val preferred = allLinks.find { it.endsWith("-$PREFERRED_REGION") }
            ?: allLinks.find { it.endsWith("-tw") }
            ?: allLinks.first()

        // 提取文件大小
        val sizeRegex = Regex("""([\d.,]+)\s*(GB|MB)""")
        val sizeMatch = sizeRegex.find(html)
        val fileSize = if (sizeMatch != null) {
            val v = sizeMatch.groupValues[1].replace(",", "").toDouble()
            when (sizeMatch.groupValues[2]) {
                "GB" -> (v * 1_073_741_824).toLong()
                "MB" -> (v * 1_048_576).toLong()
                else -> -1L
            }
        } else -1L

        // 检测包类型 (exe/appx)
        val typeRegex = Regex("""/\s*(exe|appx|msixbundle|appxbundle)""", RegexOption.IGNORE_CASE)
        val pkgExt = typeRegex.find(html)?.groupValues?.get(1)?.lowercase() ?: "exe"
        val fileName = "Minecraft-Bedrock-${version.version}-x64.$pkgExt"

        println("[MCAPPX] 已解析: $fileName ($fileSize bytes) => $preferred")

        version.copy(
            downloadUrl = preferred,
            fileName = fileName,
            fileSize = fileSize,
        )
    }

    fun toDownloadEntry(version: BedrockVersion): BedrockVersionCatalog.BedrockVersionEntry {
        return BedrockVersionCatalog.BedrockVersionEntry(
            version = version.version,
            downloadUrl = version.downloadUrl,
            fileName = version.fileName,
            fileSize = version.fileSize,
            arch = version.arch,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  版本号排序键 — 数值化比较
    // ═══════════════════════════════════════════════════════════════════════

    private fun versionSortKey(version: String): String {
        return version.split(".")
            .joinToString(".") { it.toIntOrNull()?.toString()?.padStart(6, '0') ?: it }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HTTP 工具
    // ═══════════════════════════════════════════════════════════════════════

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0")
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code for $url")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
