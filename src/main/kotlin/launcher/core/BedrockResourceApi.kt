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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * CurseForge Bedrock Edition API
 * gameId=78022 (Minecraft Bedrock)
 * 通过 api.curse.tools 公开代理（无需 API Key）
 *
 * classId:
 *   4984 = Addons（行为包/模组）
 *   6929 = Texture Packs（材质/资源包）
 *   6913 = Maps（地图）
 *   6925 = Skins（皮肤）
 */
object BedrockResourceApi {
    private const val CF_PROXY = "http://api.curse.tools/v1"
    private const val GAME_ID = 78022

    // CurseForge Bedrock classId 映射
    const val CLASS_ADDONS = 4984
    const val CLASS_TEXTURE_PACKS = 6929
    const val CLASS_MAPS = 6913
    const val CLASS_SKINS = 6925

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 25_000 }
    }
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 搜索 CurseForge Bedrock 资源。
     * contentType: "addon" | "texture_pack" | "map" | "skin"
     */
    suspend fun search(
        query: String,
        contentType: String,
        limit: Int = 30,
        index: Int = 0,
    ): List<CfBedrockProject> = withContext(Dispatchers.IO) {
        val classId = contentTypeToClassId(contentType)
        val actualQuery = if (query.isNotBlank() && containsChinese(query)) {
            translateToEnglish(query) ?: query
        } else query
        runCatching {
            val resp = client.get("$CF_PROXY/mods/search") {
                parameter("gameId", GAME_ID)
                parameter("classId", classId)
                parameter("pageSize", limit)
                parameter("index", index)
                parameter("sortField", 2) // 2=Popularity
                parameter("sortOrder", "desc")
                if (actualQuery.isNotBlank()) parameter("searchFilter", actualQuery)
                header("Accept", "application/json")
                header("User-Agent", "MD3L-Launcher/1.3")
            }
            parseCfMods(resp.bodyAsText(), contentType)
        }.getOrDefault(emptyList())
    }

    private val chineseRegex = Regex("[\u4e00-\u9fff]")
    private fun containsChinese(text: String) = chineseRegex.containsMatchIn(text)
    private fun translateToEnglish(text: String): String? = runCatching {
        kotlinx.coroutines.runBlocking { MicrosoftTranslate.toEnglish(text) }
    }.getOrNull()

    /** 获取单个 mod 的文件列表 */
    suspend fun getModFiles(modId: Int): List<CfBedrockFile> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = client.get("$CF_PROXY/mods/$modId/files") {
                parameter("pageSize", 20)
                header("Accept", "application/json")
                header("User-Agent", "MD3L-Launcher/1.3")
            }
            parseCfFiles(resp.bodyAsText())
        }.getOrDefault(emptyList())
    }

    /** 获取单个 mod 详情 */
    suspend fun getMod(modId: Int): CfBedrockProject? = withContext(Dispatchers.IO) {
        runCatching {
            val resp = client.get("$CF_PROXY/mods/$modId") {
                header("Accept", "application/json")
                header("User-Agent", "MD3L-Launcher/1.3")
            }
            val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]?.jsonObject
                ?: return@runCatching null
            parseMod(data, "addon")
        }.getOrNull()
    }

    private fun contentTypeToClassId(contentType: String): Int = when (contentType) {
        "texture_pack" -> CLASS_TEXTURE_PACKS
        "map" -> CLASS_MAPS
        "skin" -> CLASS_SKINS
        else -> CLASS_ADDONS // addon (default)
    }

    private fun parseCfMods(body: String, contentType: String): List<CfBedrockProject> {
        val arr = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching { parseMod(el.jsonObject, contentType) }.getOrNull()
        }
    }

    private fun parseMod(obj: kotlinx.serialization.json.JsonObject, contentType: String): CfBedrockProject {
        val modId = obj["id"]?.jsonPrimitive?.intOrNull ?: 0
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: modId.toString()
        val summary = obj["summary"]?.jsonPrimitive?.contentOrNull ?: ""
        val downloads = obj["downloadCount"]?.jsonPrimitive?.longOrNull ?: 0L
        val thumbUrl = obj["logo"]?.jsonObject?.get("thumbnailUrl")?.jsonPrimitive?.contentOrNull
            ?: obj["logo"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull ?: ""
        val author = obj["authors"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: "CurseForge"
        val categories = obj["categories"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: emptyList()
        val cfUrl = obj["links"]?.jsonObject?.get("websiteUrl")?.jsonPrimitive?.contentOrNull
            ?: "https://www.curseforge.com/minecraft-bedrock"
        // 最新文件
        val latestFile = obj["latestFiles"]?.jsonArray?.firstOrNull()?.jsonObject
        val downloadUrl = latestFile?.get("downloadUrl")?.jsonPrimitive?.contentOrNull ?: ""
        val fileName = latestFile?.get("fileName")?.jsonPrimitive?.contentOrNull ?: ""
        val fileSize = latestFile?.get("fileLength")?.jsonPrimitive?.longOrNull ?: 0L
        val gameVersions = latestFile?.get("gameVersions")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

        return CfBedrockProject(
            modId = modId,
            slug = slug,
            name = name,
            summary = summary,
            downloads = downloads,
            iconUrl = thumbUrl,
            author = author,
            categories = categories,
            contentType = contentType,
            cfPageUrl = cfUrl,
            latestDownloadUrl = downloadUrl,
            latestFileName = fileName,
            latestFileSize = fileSize,
            gameVersions = gameVersions,
        )
    }

    private fun parseSingleFile(obj: kotlinx.serialization.json.JsonObject): CfBedrockFile {
        return CfBedrockFile(
            fileId = obj["id"]?.jsonPrimitive?.intOrNull ?: 0,
            fileName = obj["fileName"]?.jsonPrimitive?.contentOrNull ?: "",
            displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: "",
            downloadUrl = obj["downloadUrl"]?.jsonPrimitive?.contentOrNull ?: "",
            fileSize = obj["fileLength"]?.jsonPrimitive?.longOrNull ?: 0L,
            gameVersions = obj["gameVersions"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            releaseDate = obj["fileDate"]?.jsonPrimitive?.contentOrNull ?: "",
            dependencies = obj["dependencies"]?.jsonArray?.mapNotNull { dEl ->
                val dObj = dEl.jsonObject
                val modId = dObj["modId"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val relationType = dObj["relationType"]?.jsonPrimitive?.intOrNull ?: 0
                CfFileDependency(modId = modId, relationType = relationType)
            } ?: emptyList(),
        )
    }

    private fun parseCfFiles(body: String): List<CfBedrockFile> {
        val arr = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching { parseSingleFile(el.jsonObject) }.getOrNull()
        }
    }

    /**
     * 获取某个文件的所有 required 依赖的最新可下载文件。
     * relationType == 3 => required dependency
     * 返回列表：(项目名, CfBedrockFile)
     */
    suspend fun getRequiredDependencyFiles(
        file: CfBedrockFile,
    ): List<Pair<String, CfBedrockFile>> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Pair<String, CfBedrockFile>>()
        val requiredDeps = file.dependencies.filter { it.relationType == 3 }
        for (dep in requiredDeps) {
            runCatching {
                // 取依赖项目信息
                val modResp = client.get("$CF_PROXY/mods/${dep.modId}") {
                    header("Accept", "application/json")
                    header("User-Agent", "MD3L-Launcher/1.3")
                }
                val modData = json.parseToJsonElement(modResp.bodyAsText()).jsonObject["data"]?.jsonObject
                    ?: return@runCatching
                val depName = modData["name"]?.jsonPrimitive?.contentOrNull ?: "依赖模组"
                // 取该依赖的文件列表，选第一个有下载链接的
                val filesResp = client.get("$CF_PROXY/mods/${dep.modId}/files") {
                    parameter("pageSize", 10)
                    header("Accept", "application/json")
                    header("User-Agent", "MD3L-Launcher/1.3")
                }
                val filesArr = json.parseToJsonElement(filesResp.bodyAsText()).jsonObject["data"]?.jsonArray
                    ?: return@runCatching
                val depFile = filesArr.mapNotNull { el ->
                    runCatching { parseSingleFile(el.jsonObject) }.getOrNull()
                }.firstOrNull { it.downloadUrl.isNotBlank() } ?: return@runCatching
                result.add(depName to depFile)
            }
        }
        result
    }
}

data class CfBedrockProject(
    val modId: Int,
    val slug: String,
    val name: String,
    val summary: String,
    val downloads: Long,
    val iconUrl: String,
    val author: String,
    val categories: List<String>,
    val contentType: String,
    val cfPageUrl: String,
    val latestDownloadUrl: String,
    val latestFileName: String,
    val latestFileSize: Long,
    val gameVersions: List<String>,
)

data class CfFileDependency(
    val modId: Int,
    val relationType: Int, // 1=embedded, 2=optional, 3=required, 4=tool, 5=incompatible, 6=include
)

data class CfBedrockFile(
    val fileId: Int,
    val fileName: String,
    val displayName: String,
    val downloadUrl: String,
    val fileSize: Long,
    val gameVersions: List<String>,
    val releaseDate: String,
    val dependencies: List<CfFileDependency> = emptyList(),
)
