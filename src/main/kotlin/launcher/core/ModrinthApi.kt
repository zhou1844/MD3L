package launcher.core

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder

@Serializable
data class CurseForgeProject(
    val modId: Int = 0,
    val slug: String = "",
    val title: String = "",
    val summary: String = "",
    val iconUrl: String = "",
    val downloads: Long = 0,
    val categories: List<String> = emptyList(),
    val projectType: String = "mod",
    val author: String = "",
    val pageUrl: String = "",
    val latestFileName: String = "",
    val latestDownloadUrl: String = "",
    val latestFileSize: Long = 0,
)

@Serializable
data class CurseForgeFile(
    val fileId: Int = 0,
    val fileName: String = "",
    val displayName: String = "",
    val downloadUrl: String = "",
    val fileSize: Long = 0,
    val gameVersions: List<String> = emptyList(),
    val releaseDate: String = "",
)

@Serializable
data class ModrinthProject(
    val slug: String = "",
    val title: String = "",
    val description: String = "",
    val iconUrl: String = "",
    val downloads: Long = 0,
    val categories: List<String> = emptyList(),
    val projectType: String = "mod",
    val author: String = "",
)

@Serializable
data class ModrinthDependency(
    val versionId: String = "",
    val projectId: String = "",
    val dependencyType: String = "", // "required", "optional", "incompatible", "embedded"
)

@Serializable
data class ModrinthVersion(
    val id: String = "",
    val name: String = "",
    val versionNumber: String = "",
    val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val files: List<ModrinthFile> = emptyList(),
    val dependencies: List<ModrinthDependency> = emptyList(),
    val projectId: String = "",
)

@Serializable
data class ModrinthFile(
    val url: String = "",
    val filename: String = "",
    val size: Long = 0,
    val primary: Boolean = false,
)

object ModrinthApi {

    private const val BASE = "https://api.modrinth.com/v2"
    private const val CF_API = "https://api.curseforge.com/v1"
    private const val CF_PROXY = "https://api.curse.tools/v1"
    private const val CF_GAME_ID = 432
    private const val CF_CLASS_MOD = 6
    private const val CF_CLASS_RESOURCEPACK = 12
    private const val CF_CLASS_SHADER = 6552
    private const val CF_CLASS_MODPACK = 4471

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 20_000 }
    }

    private fun parseMcmodHtmlResults(
        html: String,
        projectType: String,
        limit: Int,
    ): List<ModrinthProject> {
        val anchors = Regex(
            """<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        val seen = linkedSetOf<String>()
        val projects = mutableListOf<ModrinthProject>()

        anchors.findAll(html).forEach { match ->
            if (projects.size >= limit) return@forEach
            val rawHref = match.groupValues.getOrNull(1).orEmpty()
            val href = normalizeMcmodHref(rawHref) ?: return@forEach
            if (!seen.add(href)) return@forEach

            val titleRaw = match.groupValues.getOrNull(2).orEmpty()
            val title = cleanHtmlText(titleRaw)
            if (title.length < 2) return@forEach

            projects.add(
                ModrinthProject(
                    slug = "mcmod:$href",
                    title = title,
                    description = "来源：MC百科（点击查看详情页）",
                    iconUrl = "",
                    downloads = -1,
                    categories = listOf("MC百科"),
                    projectType = projectType,
                    author = "MC百科",
                ),
            )
        }

        return projects
    }

    private fun normalizeMcmodHref(href: String): String? {
        if (href.isBlank()) return null
        val absolute = when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "https://www.mcmod.cn$href"
            else -> return null
        }
        val clean = absolute.substringBefore('#')
        val allow = listOf("/class/", "/modpack/", "/resourcepack/", "/pack/")
        if (allow.none { clean.contains(it, ignoreCase = true) }) return null
        return clean
    }

    private fun cleanHtmlText(raw: String): String {
        val noTags = raw.replace(Regex("<[^>]+>"), " ")
        return decodeHtmlEntities(noTags).replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }

    private fun parseMcmodDownloadFiles(html: String): List<ModrinthFile> {
        val anchorRegex = Regex(
            """<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val fileLinks = linkedMapOf<String, ModrinthFile>()

        anchorRegex.findAll(html).forEach { match ->
            val rawHref = match.groupValues.getOrNull(1).orEmpty()
            val href = normalizePossibleDownloadUrl(rawHref) ?: return@forEach
            if (!looksLikeDownloadLink(href)) return@forEach

            val label = cleanHtmlText(match.groupValues.getOrNull(2).orEmpty())
            val filename = resolveFileName(href, label)
            if (filename.isBlank()) return@forEach

            fileLinks.putIfAbsent(
                href,
                ModrinthFile(
                    url = href,
                    filename = filename,
                    size = 0,
                    primary = fileLinks.isEmpty(),
                ),
            )
        }

        return fileLinks.values.toList()
    }

    private fun normalizePossibleDownloadUrl(href: String): String? {
        if (href.isBlank()) return null
        if (href.startsWith("javascript", ignoreCase = true)) return null
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "https://www.mcmod.cn$href"
            else -> null
        }
    }

    private fun looksLikeDownloadLink(url: String): Boolean {
        val u = url.lowercase()
        val ext = listOf(".jar", ".zip", ".mrpack", ".rar", ".7z", ".litemod")
        if (ext.any { u.contains(it) }) return true
        if ("/download" in u) return true
        if ("download.mcmod.cn" in u) return true
        if ("curseforge.com" in u || "modrinth.com" in u) return true
        if ("github.com" in u && "/releases" in u) return true
        return false
    }

    private fun resolveFileName(url: String, label: String): String {
        val pathPart = url.substringBefore('?').substringAfterLast('/').trim()
        val decodedPath = runCatching { URLDecoder.decode(pathPart, "UTF-8") }.getOrDefault(pathPart)
        if (decodedPath.contains('.')) return decodedPath

        val cleanLabel = label
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "mcmod_resource" }
        return "$cleanLabel.url"
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(
        query: String,
        facets: String = "",
        limit: Int = 20,
        offset: Int = 0,
        projectType: String = "mod",
    ): List<ModrinthProject> = withContext(Dispatchers.IO) {
        try {
            val facetList = buildList {
                add("""["project_type:$projectType"]""")
                if (facets.isNotBlank()) add(facets)
            }
            val facetsParam = "[${facetList.joinToString(",")}]"

            val actualQuery = if (query.isNotBlank() && containsChinese(query)) {
                translateToEnglish(query) ?: query
            } else query

            val resp = client.get("$BASE/search") {
                parameter("query", actualQuery)
                parameter("facets", facetsParam)
                parameter("limit", limit)
                parameter("offset", offset)
                header("User-Agent", "MD3L/1.1 (https://github.com/yunoniaodudu)")
            }
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val hits = body["hits"]?.jsonArray ?: return@withContext emptyList()

            hits.map { hit ->
                val obj = hit.jsonObject
                ModrinthProject(
                    slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: "",
                    title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                    description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    iconUrl = obj["icon_url"]?.jsonPrimitive?.contentOrNull ?: "",
                    downloads = obj["downloads"]?.jsonPrimitive?.longOrNull ?: 0,
                    categories = obj["categories"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    projectType = obj["project_type"]?.jsonPrimitive?.contentOrNull ?: "mod",
                    author = obj["author"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getMcmodProjectVersions(
        pageUrl: String,
        projectType: String = "mod",
    ): List<ModrinthVersion> = withContext(Dispatchers.IO) {
        try {
            val normalizedPage = normalizeMcmodHref(pageUrl) ?: pageUrl
            val resp = client.get(normalizedPage) {
                header("User-Agent", "MD3L/1.1 (https://github.com/yunoniaodudu)")
            }
            val html = resp.bodyAsText()
            val files = parseMcmodDownloadFiles(html)
            if (files.isEmpty()) {
                return@withContext emptyList()
            }

            val versionName = when (projectType) {
                "shader" -> "MC百科 光影资源"
                "resourcepack" -> "MC百科 材质资源"
                "modpack" -> "MC百科 整合包资源"
                else -> "MC百科 模组资源"
            }
            listOf(
                ModrinthVersion(
                    id = "mcmod-${normalizedPage.hashCode()}",
                    name = versionName,
                    versionNumber = "web-crawled",
                    gameVersions = emptyList(),
                    loaders = emptyList(),
                    files = files,
                ),
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun searchMcmod(
        query: String,
        projectType: String = "mod",
        limit: Int = 20,
    ): List<ModrinthProject> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val mold = when (projectType) {
                "modpack" -> "2"
                "resourcepack", "shader" -> "3"
                else -> "1"
            }
            val searchUrl = "https://search.mcmod.cn/s?key=$encoded&filter=0&mold=$mold&page=1"
            val resp = client.get(searchUrl) {
                header("User-Agent", "MD3L/1.1 (https://github.com/yunoniaodudu)")
            }
            val html = resp.bodyAsText()

            val results = parseMcmodHtmlResults(html, projectType, limit)
            if (results.isNotEmpty()) return@withContext results

            listOf(
                ModrinthProject(
                    slug = "mcmod:${searchUrl}",
                    title = if (query.isBlank()) "MC百科资源" else "在 MC百科 搜索：$query",
                    description = "未解析到结构化结果，点击进入 MC百科 搜索页",
                    iconUrl = "",
                    downloads = -1,
                    categories = listOf("MC百科"),
                    projectType = projectType,
                    author = "MC百科",
                ),
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getProjectVersions(
        slugOrId: String,
        gameVersion: String? = null,
        loader: String? = null,
    ): List<ModrinthVersion> = withContext(Dispatchers.IO) {
        try {
            val resp = client.get("$BASE/project/$slugOrId/version") {
                gameVersion?.let { parameter("game_versions", """["$it"]""") }
                loader?.let { parameter("loaders", """["$it"]""") }
                header("User-Agent", "MD3L/1.1 (https://github.com/yunoniaodudu)")
            }
            val arr = json.parseToJsonElement(resp.bodyAsText()).jsonArray
            arr.map { el ->
                val obj = el.jsonObject
                ModrinthVersion(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    versionNumber = obj["version_number"]?.jsonPrimitive?.contentOrNull ?: "",
                    gameVersions = obj["game_versions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    loaders = obj["loaders"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    files = obj["files"]?.jsonArray?.map { fEl ->
                        val fObj = fEl.jsonObject
                        ModrinthFile(
                            url = fObj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                            filename = fObj["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                            size = fObj["size"]?.jsonPrimitive?.longOrNull ?: 0,
                            primary = fObj["primary"]?.jsonPrimitive?.booleanOrNull ?: false,
                        )
                    } ?: emptyList(),
                    dependencies = obj["dependencies"]?.jsonArray?.map { dEl ->
                        val dObj = dEl.jsonObject
                        ModrinthDependency(
                            versionId = dObj["version_id"]?.jsonPrimitive?.contentOrNull ?: "",
                            projectId = dObj["project_id"]?.jsonPrimitive?.contentOrNull ?: "",
                            dependencyType = dObj["dependency_type"]?.jsonPrimitive?.contentOrNull ?: "",
                        )
                    } ?: emptyList(),
                    projectId = obj["project_id"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 解析 version 的所有 required 依赖，返回每个依赖的 (项目名, 下载文件)。
     * 仅递归 required 类型，最多两层，避免无限循环。
     * gameVersions / loaders 用于匹配最佳版本。
     */
    suspend fun resolveDependencyFiles(
        version: ModrinthVersion,
        preferGameVersion: String = "",
        preferLoader: String = "",
    ): List<Pair<String, ModrinthFile>> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Pair<String, ModrinthFile>>()
        val visited = mutableSetOf(version.projectId)

        suspend fun fetchDep(dep: ModrinthDependency, depth: Int) {
            if (depth > 2) return
            if (dep.dependencyType != "required") return

            // 优先直接用 versionId
            val depVersion: ModrinthVersion? = if (dep.versionId.isNotBlank()) {
                runCatching {
                    val resp = client.get("$BASE/version/${dep.versionId}") {
                        header("User-Agent", "MD3L/1.1 (https://github.com/yunoniaoduza)")
                    }
                    val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
                    val pid = obj["project_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (pid in visited) return@runCatching null
                    visited.add(pid)
                    ModrinthVersion(
                        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        versionNumber = obj["version_number"]?.jsonPrimitive?.contentOrNull ?: "",
                        gameVersions = obj["game_versions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                        loaders = obj["loaders"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                        files = obj["files"]?.jsonArray?.map { fEl ->
                            val fObj = fEl.jsonObject
                            ModrinthFile(
                                url = fObj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                                filename = fObj["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                                size = fObj["size"]?.jsonPrimitive?.longOrNull ?: 0,
                                primary = fObj["primary"]?.jsonPrimitive?.booleanOrNull ?: false,
                            )
                        } ?: emptyList(),
                        dependencies = obj["dependencies"]?.jsonArray?.map { dEl ->
                            val dObj = dEl.jsonObject
                            ModrinthDependency(
                                versionId = dObj["version_id"]?.jsonPrimitive?.contentOrNull ?: "",
                                projectId = dObj["project_id"]?.jsonPrimitive?.contentOrNull ?: "",
                                dependencyType = dObj["dependency_type"]?.jsonPrimitive?.contentOrNull ?: "",
                            )
                        } ?: emptyList(),
                        projectId = pid,
                    )
                }.getOrNull()
            } else if (dep.projectId.isNotBlank()) {
                if (dep.projectId in visited) return
                visited.add(dep.projectId)
                // 取该项目的版本列表，选最佳匹配
                val versions = getProjectVersions(dep.projectId)
                versions.firstOrNull { v ->
                    (preferGameVersion.isBlank() || preferGameVersion in v.gameVersions) &&
                    (preferLoader.isBlank() || preferLoader in v.loaders)
                } ?: versions.firstOrNull()
            } else null

            val file = depVersion?.files?.firstOrNull { it.primary } ?: depVersion?.files?.firstOrNull()
            if (file != null && depVersion != null) {
                val name = depVersion.name.ifBlank { file.filename }
                result.add(name to file)
                // 递归
                depVersion.dependencies.forEach { fetchDep(it, depth + 1) }
            }
        }

        version.dependencies.forEach { fetchDep(it, 0) }
        result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  中文自动翻译 — MyMemory 免费 API（无需 API Key）
    // ═══════════════════════════════════════════════════════════════════════════

    private val chineseRegex = Regex("[\u4e00-\u9fff]")

    private fun containsChinese(text: String): Boolean = chineseRegex.containsMatchIn(text)

    private fun translateToEnglish(text: String): String? {
        return try {
            val translated = kotlinx.coroutines.runBlocking { MicrosoftTranslate.toEnglish(text) }
            println("[Translate] '$text' => '$translated'")
            translated?.takeIf { it.isNotBlank() && !it.equals(text, ignoreCase = true) }
        } catch (e: Exception) {
            println("[Translate] 翻译失败: ${e.message}")
            null
        }
    }

    suspend fun downloadModFile(
        file: ModrinthFile,
        targetDir: File,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            targetDir.mkdirs()
            val dest = File(targetDir, file.filename)
            // use single download with a simple scope
            val resp = client.get(file.url) {
                header("User-Agent", "MD3L/1.1")
            }
            dest.writeBytes(resp.readBytes())
            true
        } catch (_: Exception) {
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // CurseForge Java / Java-like resources
    // ─────────────────────────────────────────────────────────────────────

    private fun cfClassIdFor(projectType: String): Int = when (projectType) {
        "resourcepack" -> CF_CLASS_RESOURCEPACK
        "shader" -> CF_CLASS_SHADER
        "modpack" -> CF_CLASS_MODPACK
        else -> CF_CLASS_MOD
    }

    private fun cfTypeLabel(projectType: String): String = when (projectType) {
        "resourcepack" -> "资源包"
        "shader" -> "光影"
        "modpack" -> "整合包"
        else -> "模组"
    }

    private fun cfLatestFileName(fileObj: JsonObject): String {
        val name = fileObj["fileName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (name.isNotBlank()) return name
        val download = fileObj["downloadUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return download.substringAfterLast('/').substringBefore('?').ifBlank { "curseforge_file.jar" }
    }

    private fun parseCurseForgeProjects(body: String, projectType: String): List<CurseForgeProject> {
        val root = json.parseToJsonElement(body).jsonObject
        val arr = root["data"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val obj = el.jsonObject
                val latestFile = obj["latestFiles"]?.jsonArray?.firstOrNull()?.jsonObject
                val logo = obj["logo"]?.jsonObject
                val links = obj["links"]?.jsonObject
                val authors = obj["authors"]?.jsonArray?.firstOrNull()?.jsonObject
                CurseForgeProject(
                    modId = obj["id"]?.jsonPrimitive?.intOrNull ?: 0,
                    slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: "",
                    title = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    summary = obj["summary"]?.jsonPrimitive?.contentOrNull ?: "",
                    iconUrl = logo?.get("thumbnailUrl")?.jsonPrimitive?.contentOrNull
                        ?: logo?.get("url")?.jsonPrimitive?.contentOrNull
                        ?: "",
                    downloads = obj["downloadCount"]?.jsonPrimitive?.longOrNull ?: 0L,
                    categories = obj["categories"]?.jsonArray?.mapNotNull { cEl ->
                        cEl.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    } ?: emptyList(),
                    projectType = projectType,
                    author = authors?.get("name")?.jsonPrimitive?.contentOrNull ?: "CurseForge",
                    pageUrl = links?.get("websiteUrl")?.jsonPrimitive?.contentOrNull
                        ?: "https://www.curseforge.com/minecraft/mc-mods",
                    latestFileName = latestFile?.let(::cfLatestFileName).orEmpty(),
                    latestDownloadUrl = latestFile?.get("downloadUrl")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    latestFileSize = latestFile?.get("fileLength")?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }.getOrNull()
        }
    }

    private fun parseCurseForgeFiles(body: String): List<CurseForgeFile> {
        val root = json.parseToJsonElement(body).jsonObject
        val arr = root["data"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val obj = el.jsonObject
                CurseForgeFile(
                    fileId = obj["id"]?.jsonPrimitive?.intOrNull ?: 0,
                    fileName = obj["fileName"]?.jsonPrimitive?.contentOrNull ?: "",
                    displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: "",
                    downloadUrl = obj["downloadUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                    fileSize = obj["fileLength"]?.jsonPrimitive?.longOrNull ?: 0L,
                    gameVersions = obj["gameVersions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    releaseDate = obj["fileDate"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }.getOrNull()
        }
    }

    suspend fun searchCurseForge(
        query: String,
        projectType: String = "mod",
        limit: Int = 20,
        index: Int = 0,
    ): List<CurseForgeProject> = withContext(Dispatchers.IO) {
        try {
            val actualQuery = if (query.isNotBlank() && containsChinese(query)) {
                translateToEnglish(query) ?: query
            } else query
            val classId = cfClassIdFor(projectType)
            val resp = client.get("$CF_PROXY/mods/search") {
                parameter("gameId", CF_GAME_ID)
                parameter("classId", classId)
                parameter("pageSize", limit)
                parameter("index", index)
                parameter("sortField", 2)
                parameter("sortOrder", "desc")
                if (actualQuery.isNotBlank()) parameter("searchFilter", actualQuery)
                header("Accept", "application/json")
                header("User-Agent", "MD3L/1.1")
            }
            parseCurseForgeProjects(resp.bodyAsText(), projectType)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getCurseForgeProjectFiles(modId: Int): List<CurseForgeFile> = withContext(Dispatchers.IO) {
        try {
            val resp = client.get("$CF_PROXY/mods/$modId/files") {
                parameter("pageSize", 20)
                header("Accept", "application/json")
                header("User-Agent", "MD3L/1.1")
            }
            parseCurseForgeFiles(resp.bodyAsText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getCurseForgeProject(modId: Int, projectType: String = "mod"): CurseForgeProject? = withContext(Dispatchers.IO) {
        try {
            val resp = client.get("$CF_PROXY/mods/$modId") {
                header("Accept", "application/json")
                header("User-Agent", "MD3L/1.1")
            }
            val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val data = root["data"]?.jsonObject ?: return@withContext null
            val fakeBody = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("data" to JsonArray(listOf(data)))))
            parseCurseForgeProjects(fakeBody, projectType).firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun downloadCurseForgeFile(file: CurseForgeFile, targetDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            targetDir.mkdirs()
            val dest = File(targetDir, file.fileName.ifBlank { file.displayName.ifBlank { "curseforge_file.jar" } })
            val url = file.downloadUrl.ifBlank { return@withContext false }
            ResourceDownloadManager.launch(
                name = dest.name,
                url = url,
                dest = dest,
                size = file.fileSize,
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
