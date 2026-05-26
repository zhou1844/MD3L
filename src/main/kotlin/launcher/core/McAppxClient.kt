package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

object McAppxClient {

    private val V2_SOURCES = listOf(
        "https://data.mcappx.com/v2/bedrock.json",
        "https://mcappx.52caecb8.er.aliyun-esa.net",
        "https://mcappx.chlna6666.com",
        "https://api.chlna6666.com/api/v1/bedrock/mcappx",
    )
    private const val CONNECT_TIMEOUT = 12_000
    private const val READ_TIMEOUT    = 20_000

    private val json = Json { ignoreUnknownKeys = true }

    data class BedrockVersion(
        val version: String,
        val downloadUrl: String = "",
        val fileName: String = "",
        val arch: String = "x64",
        val isPreview: Boolean = false,
        val fileSize: Long = -1L,
        val detailPath: String = "",
        val metaData: List<String> = emptyList(),
        val md5: String = "",
    )

    suspend fun fetchVersions(): List<BedrockVersion> = withContext(Dispatchers.IO) {
        for (url in V2_SOURCES) {
            val result = runCatching { parseV2Json(httpGet(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) {
                println("[MCAPPX] 版本库加载成功 (${result.size} 个版本) from $url")
                return@withContext result
            }
        }
        println("[MCAPPX] 所有镜像源均失败，返回空列表")
        emptyList()
    }

    suspend fun resolveDownloadUrl(version: BedrockVersion): BedrockVersion = withContext(Dispatchers.IO) {
        if (version.downloadUrl.isNotBlank()) return@withContext version

        val meta = version.metaData.lastOrNull()
        if (meta != null && meta.startsWith("http")) {
            val ext = meta.substringAfterLast('.').lowercase().let {
                if (it.length <= 8) it else "appx"
            }
            val fileName = "Minecraft-Bedrock-${version.version}-x64.$ext"
            println("[MCAPPX] 直链: $fileName => $meta")
            return@withContext version.copy(downloadUrl = meta, fileName = fileName)
        }

        if (meta != null && meta.isNotBlank()) {
            val (url, size) = WUDownloadClient.resolveDownloadUrl(meta)
            val fileName = "Minecraft-Bedrock-${version.version}-x64.appx"
            println("[MCAPPX] WU SOAP: $fileName ($size bytes) => $url")
            return@withContext version.copy(downloadUrl = url, fileName = fileName, fileSize = size)
        }

        throw RuntimeException("版本 ${version.version} 无有效 MetaData")
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

    private fun parseV2Json(body: String): List<BedrockVersion> {
        val root = json.parseToJsonElement(body).jsonObject
        val versionsObj = root.entries
            .firstOrNull { it.key != "CreationTime" && it.value is JsonObject }
            ?.value?.jsonObject ?: return emptyList()

        val result = mutableListOf<BedrockVersion>()
        for ((versionId, buildElem) in versionsObj) {
            val build = buildElem.jsonObject
            val gameType  = build["Type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "release"
            val isPreview = gameType == "preview" || gameType == "beta"

            val variations = build["Variations"]?.jsonArray ?: continue
            for (varElem in variations) {
                val varObj  = varElem.jsonObject
                val arch    = varObj["Arch"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "x64"
                if (arch != "x64") continue

                val metaData = varObj["MetaData"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()
                if (metaData.isEmpty()) continue

                val md5 = varObj["MD5"]?.jsonPrimitive?.contentOrNull ?: ""

                result += BedrockVersion(
                    version   = versionId,
                    arch      = "x64",
                    isPreview = isPreview,
                    metaData  = metaData,
                    md5       = md5,
                )
            }
        }

        return result
            .distinctBy { "${it.version}_${it.isPreview}" }
            .sortedWith(compareByDescending<BedrockVersion> { versionSortKey(it.version) }
                .thenBy { it.isPreview })
    }

    private fun versionSortKey(version: String): String =
        version.split(".").joinToString(".") { it.toIntOrNull()?.toString()?.padStart(6, '0') ?: it }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "mcappx_developer")
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code for $url")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
