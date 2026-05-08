package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

enum class LoaderType { Vanilla, Forge, Fabric, NeoForge, Quilt, Unknown }

@Serializable
data class LocalVersion(
    val id: String,
    val type: String = "release",
    val loaderType: LoaderType = LoaderType.Vanilla,
    val inheritsFrom: String? = null,
    val mainClass: String = "",
    val assetsIndex: String = "",
    val versionDir: String = "",
)

object VersionScanner {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun scan(minecraftDir: String): List<LocalVersion> = withContext(Dispatchers.IO) {
        val versionsDir = File(minecraftDir, "versions")
        if (!versionsDir.isDirectory) return@withContext emptyList()

        versionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val jsonFile = findVersionJsonFile(dir) ?: return@mapNotNull null
                parseVersionJson(jsonFile, dir.absolutePath)
            }
            ?.sortedByDescending { it.id }
            ?: emptyList()
    }

    private fun findVersionJsonFile(dir: File): File? {
        val standard = File(dir, "${dir.name}.json")
        if (standard.isFile) return standard
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.minByOrNull { it.name.length }
    }

    private fun parseVersionJson(file: File, versionDir: String): LocalVersion? {
        return try {
            val root = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            val id = root["id"]?.jsonPrimitive?.contentOrNull ?: file.parentFile.name
            val type = root["type"]?.jsonPrimitive?.contentOrNull ?: "release"
            val inheritsFrom = root["inheritsFrom"]?.jsonPrimitive?.contentOrNull
            val mainClass = root["mainClass"]?.jsonPrimitive?.contentOrNull ?: ""
            val assetsIndex = root["assets"]?.jsonPrimitive?.contentOrNull
                ?: root["assetIndex"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                ?: ""

            val loader = detectLoader(root, id)
            if (!isJavaVersionLaunchable(root, id, versionDir)) {
                return null
            }

            LocalVersion(
                id = id,
                type = type,
                loaderType = loader,
                inheritsFrom = inheritsFrom,
                mainClass = mainClass,
                assetsIndex = assetsIndex,
                versionDir = versionDir,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun isJavaVersionLaunchable(root: JsonObject, id: String, versionDir: String): Boolean {
        val versionFolder = File(versionDir)
        val jarCandidates = linkedSetOf<File>().apply {
            add(File(versionFolder, "$id.jar"))
            add(File(versionFolder, "${versionFolder.name}.jar"))
            versionFolder.listFiles()
                ?.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
                ?.forEach { add(it) }
        }.toList()
        val inheritsFrom = root["inheritsFrom"]?.jsonPrimitive?.contentOrNull

        if (jarCandidates.any { it.isFile && it.length() > 0L }) {
            val expectedClientSize = root["downloads"]?.jsonObject
                ?.get("client")?.jsonObject
                ?.get("size")?.jsonPrimitive?.longOrNull
            if (expectedClientSize != null && expectedClientSize > 0) {
                return jarCandidates.any { it.isFile && it.length() >= expectedClientSize * 0.9 }
            }
            return true
        }

        if (inheritsFrom.isNullOrBlank()) {
            return false
        }

        val minecraftDir = versionFolder.parentFile?.parentFile ?: return false
        val parentDir = File(minecraftDir, "versions/$inheritsFrom")
        if (!parentDir.isDirectory) return false
        val parentStandard = File(parentDir, "$inheritsFrom.json")
        if (parentStandard.isFile) return true
        return parentDir.listFiles()?.any { it.isFile && it.extension.equals("json", ignoreCase = true) } == true
    }

    suspend fun scanBedrock(minecraftDir: String): List<LocalVersion> = withContext(Dispatchers.IO) {
        val bedrockDir = File(minecraftDir, "bedrock_versions")
        if (!bedrockDir.isDirectory) return@withContext emptyList()

        bedrockDir.listFiles()
            ?.filter { it.isDirectory && it.name != "cache" }
            ?.filter { isLaunchableBedrockDir(it) }
            ?.map { dir ->
                LocalVersion(
                    id = "Bedrock ${dir.name}",
                    type = "bedrock",
                    loaderType = LoaderType.Vanilla,
                    versionDir = dir.absolutePath,
                )
            }
            ?.sortedByDescending { it.id }
            ?: emptyList()
    }

    private fun isLaunchableBedrockDir(dir: File): Boolean {
        if (dir.walkTopDown().maxDepth(2).any { it.name == "AppxManifest.xml" }) return true
        val marker = File(dir, ".installed")
        if (!marker.exists()) return false
        val source = marker.readLines().firstOrNull { it.startsWith("source=") }
            ?.substringAfter("source=")
            ?.let(::File)
            ?: return false
        return source.exists()
    }

    private fun detectLoader(root: JsonObject, id: String): LoaderType {
        val mainClass = root["mainClass"]?.jsonPrimitive?.contentOrNull ?: ""
        val idLower = id.lowercase()
        val libs = root["libraries"]?.jsonArray?.mapNotNull { lib ->
            lib.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        } ?: emptyList()

        return when {
            libs.any { "net.neoforged" in it } || "neoforge" in idLower -> LoaderType.NeoForge
            libs.any { "net.fabricmc" in it } || "fabric" in idLower -> LoaderType.Fabric
            libs.any { "org.quiltmc" in it } || "quilt" in idLower -> LoaderType.Quilt
            libs.any { "net.minecraftforge" in it } || "forge" in idLower ||
                mainClass.contains("forge", ignoreCase = true) -> LoaderType.Forge
            else -> LoaderType.Vanilla
        }
    }
}
