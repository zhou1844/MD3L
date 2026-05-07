package launcher.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

object BedrockResourceApi {
    private const val BASE = "https://api.modrinth.com/v2"

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 20_000 }
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, contentType: String, limit: Int = 30): List<ModrinthProject> = withContext(Dispatchers.IO) {
        val curated = curatedBedrockBrowse(query, contentType)
        val scraped = runCatching { searchMcpedl(query, contentType, limit) }.getOrDefault(emptyList())
        (curated + scraped).distinctBy { it.slug }.take(limit)
    }

    private fun curatedBedrockBrowse(query: String, contentType: String): List<ModrinthProject> {
        val q = query.trim()
        val encoded = if (q.isNotBlank()) URLEncoder.encode(q, "UTF-8") else ""
        fun entry(slug: String, title: String, desc: String) = ModrinthProject(
            slug = slug,
            title = title,
            description = desc,
            iconUrl = "",
            downloads = 0,
            categories = listOf("bedrock", "browse"),
            projectType = contentType,
            author = "在浏览器打开",
        )
        return when (contentType) {
            "bedrock_resourcepack" -> listOf(
                entry(
                    if (q.isBlank()) "https://mcpedl.com/category/texture-packs/" else "https://mcpedl.com/?s=$encoded",
                    if (q.isBlank()) "MCPEDL · 材质包目录" else "MCPEDL · 搜索 “$q”",
                    "点击下载按钮在浏览器中打开 MCPEDL，挑选材质包后会得到 .mcpack",
                ),
                entry("https://mcbedrock.com/texturepacks", "MCBedrock · 材质包", "MCBedrock 的基岩材质包合集，国内可访问"),
                entry("https://www.9minecraft.net/category/minecraft-bedrock-resource-packs/", "9Minecraft · 基岩材质", "9Minecraft 基岩材质包列表"),
            )
            "bedrock_modpack" -> listOf(
                entry(
                    if (q.isBlank()) "https://mcpedl.com/category/mods/addons/" else "https://mcpedl.com/?s=$encoded",
                    if (q.isBlank()) "MCPEDL · 整合/模组目录" else "MCPEDL · 搜索 “$q”",
                    "MCPEDL Mods/Addons 分类，提供 .mcaddon / .mcpack",
                ),
                entry("https://mcbedrock.com/mods", "MCBedrock · 模组/整合", "国内可访问的基岩 Addons 站点"),
                entry("https://www.9minecraft.net/category/minecraft-pe-mods/", "9Minecraft · PE Mods", "9Minecraft 基岩 PE Mods 合集"),
            )
            else -> listOf(
                entry(
                    if (q.isBlank()) "https://mcpedl.com/category/mods/addons/" else "https://mcpedl.com/?s=$encoded",
                    if (q.isBlank()) "MCPEDL · 模组/Addon" else "MCPEDL · 搜索 “$q”",
                    "MCPEDL Mods/Addons，下载 .mcaddon 后双击导入即可",
                ),
                entry("https://mcbedrock.com/mods", "MCBedrock · 模组/整合", "国内可访问"),
                entry("https://www.9minecraft.net/category/minecraft-pe-mods/", "9Minecraft · PE Mods", "9Minecraft 基岩 PE Mods 合集"),
            )
        }
    }

    suspend fun getProjectVersions(projectUrl: String, fallbackTitle: String): List<ModrinthVersion> = withContext(Dispatchers.IO) {
        if (projectUrl.startsWith("http://") || projectUrl.startsWith("https://")) {
            return@withContext getWebProjectVersions(projectUrl, fallbackTitle)
        }
        try {
            val resp = client.get("$BASE/project/$projectUrl/version") {
                header("User-Agent", "MD3L/1.1 (https://github.com/zhou1844/MD3L)")
            }
            val versions = json.parseToJsonElement(resp.bodyAsText()).jsonArray.map { el ->
                val obj = el.jsonObject
                ModrinthVersion(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    versionNumber = obj["version_number"]?.jsonPrimitive?.contentOrNull ?: "",
                    gameVersions = obj["game_versions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    loaders = (obj["loaders"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()).ifEmpty { listOf("bedrock") },
                    files = obj["files"]?.jsonArray?.mapNotNull { fEl ->
                        val fObj = fEl.jsonObject
                        val url = fObj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val filename = fObj["filename"]?.jsonPrimitive?.contentOrNull ?: url.substringBefore('?').substringAfterLast('/')
                        if (!isBedrockFile(filename)) return@mapNotNull null
                        ModrinthFile(
                            url = url,
                            filename = filename,
                            size = fObj["size"]?.jsonPrimitive?.longOrNull ?: 0,
                            primary = fObj["primary"]?.jsonPrimitive?.booleanOrNull ?: false,
                        )
                    } ?: emptyList(),
                )
            }.filter { it.files.isNotEmpty() }
            versions.ifEmpty {
                listOf(
                    ModrinthVersion(
                        id = projectUrl,
                        name = fallbackTitle,
                        versionNumber = "Bedrock",
                        gameVersions = emptyList(),
                        loaders = listOf("bedrock"),
                        files = emptyList(),
                    )
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun searchOnce(query: String, projectType: String, contentType: String, limit: Int): List<ModrinthProject> {
        val facetGroups = buildList {
            add(listOf("project_type:$projectType"))
        }
        val facets = facetGroups.joinToString(prefix = "[", postfix = "]") { group ->
            group.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        }
        val resp = client.get("$BASE/search") {
            if (query.isNotBlank()) parameter("query", query)
            parameter("facets", facets)
            parameter("limit", limit)
            header("User-Agent", "MD3L/1.1 (https://github.com/zhou1844/MD3L)")
        }
        val hits = json.parseToJsonElement(resp.bodyAsText()).jsonObject["hits"]?.jsonArray ?: return emptyList()
        return hits.map { hit ->
            val obj = hit.jsonObject
            ModrinthProject(
                slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: obj["project_id"]?.jsonPrimitive?.contentOrNull ?: "",
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "Modrinth Bedrock 资源",
                iconUrl = obj["icon_url"]?.jsonPrimitive?.contentOrNull ?: "",
                downloads = obj["downloads"]?.jsonPrimitive?.longOrNull ?: 0,
                categories = (obj["categories"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()) + "bedrock",
                projectType = contentType,
                author = obj["author"]?.jsonPrimitive?.contentOrNull ?: "Modrinth",
            )
        }.filter { it.slug.isNotBlank() }
    }

    private suspend fun searchMcpedl(query: String, contentType: String, limit: Int): List<ModrinthProject> {
        val url = if (query.isBlank()) {
            when (contentType) {
                "bedrock_resourcepack" -> "https://mcpedl.com/category/texture-packs/"
                "bedrock_modpack" -> "https://mcpedl.com/category/mods/addons/"
                else -> "https://mcpedl.com/category/mods/addons/"
            }
        } else {
            "https://mcpedl.com/?s=${URLEncoder.encode(query, "UTF-8")}"
        }
        val html = fetchWeb(url)
        val doc = Jsoup.parse(html, "https://mcpedl.com")
        return doc.select("article a[href], .post a[href], .entry-title a[href], h2 a[href], h3 a[href]")
            .mapNotNull { link ->
                val href = link.absUrl("href").substringBefore("#")
                if (!href.startsWith("https://mcpedl.com/") || href.contains("/category/") || href.contains("/tag/")) return@mapNotNull null
                val title = link.text().trim().ifBlank { href.trimEnd('/').substringAfterLast('/').replace("-", " ") }
                if (title.length < 3) return@mapNotNull null
                ModrinthProject(
                    slug = href,
                    title = title,
                    description = "MCPEDL · Minecraft Bedrock 资源",
                    iconUrl = link.parents().firstOrNull()?.selectFirst("img[src],img[data-src]")?.let { img ->
                        img.absUrl("data-src").ifBlank { img.absUrl("src") }
                    }.orEmpty(),
                    downloads = 0,
                    categories = listOf("bedrock", "mcpedl"),
                    projectType = contentType,
                    author = "MCPEDL",
                )
            }
            .distinctBy { it.slug }
            .take(limit)
    }

    private suspend fun getWebProjectVersions(projectUrl: String, fallbackTitle: String): List<ModrinthVersion> {
        return try {
            val html = fetchWeb(projectUrl)
            val directFiles = parseBedrockFiles(html)
            val doc = Jsoup.parse(html, projectUrl)
            val anchorFiles = doc.select("a[href]").mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { return@mapNotNull null }
                if (!isBedrockFile(href.substringBefore('?'))) return@mapNotNull null
                ModrinthFile(
                    url = href,
                    filename = fileNameFromUrl(href).ifBlank { link.text().ifBlank { "bedrock-pack.mcpack" } },
                    size = 0,
                    primary = false,
                )
            }
            val files = (directFiles + anchorFiles).distinctBy { it.url }.ifEmpty {
                listOf(ModrinthFile(url = projectUrl, filename = "打开 MCPEDL 下载页", size = 0, primary = true))
            }.mapIndexed { index, file -> file.copy(primary = index == 0) }
            listOf(
                ModrinthVersion(
                    id = projectUrl,
                    name = doc.selectFirst("h1,.entry-title")?.text()?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                    versionNumber = "Bedrock",
                    gameVersions = emptyList(),
                    loaders = listOf("bedrock"),
                    files = files,
                )
            )
        } catch (_: Exception) {
            listOf(
                ModrinthVersion(
                    id = projectUrl,
                    name = fallbackTitle,
                    versionNumber = "Bedrock",
                    gameVersions = emptyList(),
                    loaders = listOf("bedrock"),
                    files = listOf(ModrinthFile(url = projectUrl, filename = "打开下载页面", size = 0, primary = true)),
                )
            )
        }
    }

    private suspend fun fetchWeb(url: String): String {
        return client.get(url) {
            header("User-Agent", "Mozilla/5.0 MD3L/1.1")
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }.bodyAsText()
    }

    private fun parseBedrockFiles(html: String): List<ModrinthFile> {
        val regex = Regex("""https?://[^"'\s<>()]+?\.(?:mcpack|mcaddon|mcworld|mctemplate|zip)(?:\?[^"'\s<>()]*)?""", RegexOption.IGNORE_CASE)
        return regex.findAll(html).mapIndexed { index, match ->
            val url = htmlDecode(match.value)
            ModrinthFile(
                url = url,
                filename = fileNameFromUrl(url).ifBlank { "bedrock-pack-${index + 1}.mcpack" },
                size = 0,
                primary = index == 0,
            )
        }.toList()
    }

    private fun fileNameFromUrl(url: String): String {
        return URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8")
    }

    private fun htmlDecode(value: String): String {
        return value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun isBedrockFile(filename: String): Boolean {
        val clean = filename.substringBefore('?').lowercase()
        return clean.endsWith(".mcpack") ||
            clean.endsWith(".mcaddon") ||
            clean.endsWith(".mcworld") ||
            clean.endsWith(".mctemplate") ||
            clean.endsWith(".zip")
    }
}
