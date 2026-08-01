package launcher.core.java

import launcher.core.LaunchContext
import launcher.core.LoaderInstaller
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.zip.ZipFile

internal object NeoForgePreflight {

    fun preflight(root: JsonObject, context: LaunchContext, versionJsonFile: File) {
        val text = versionJsonFile.readText(Charsets.UTF_8)
        if ("net.neoforged" !in text && "neoforge" !in versionJsonFile.nameWithoutExtension.lowercase()) return

        val gameArgs = root["arguments"]?.jsonObject?.get("game")?.jsonArray ?: return
        val neoForgeVersion = gameArgs.mapIndexedNotNull { i, el ->
            if (el is JsonPrimitive && el.content == "--fml.neoForgeVersion") {
                (gameArgs.getOrNull(i + 1) as? JsonPrimitive)?.contentOrNull
            } else null
        }.firstOrNull() ?: return

        val mcVersion = gameArgs.mapIndexedNotNull { i, el ->
            if (el is JsonPrimitive && el.content == "--fml.mcVersion") {
                (gameArgs.getOrNull(i + 1) as? JsonPrimitive)?.contentOrNull
            } else null
        }.firstOrNull() ?: return

        val neoFormVersion = gameArgs.mapIndexedNotNull { i, el ->
            if (el is JsonPrimitive && el.content == "--fml.neoFormVersion") {
                (gameArgs.getOrNull(i + 1) as? JsonPrimitive)?.contentOrNull
            } else null
        }.firstOrNull()

        val librariesDir = context.librariesDir
        val clientJar = File(librariesDir, "net/neoforged/neoforge/$neoForgeVersion/neoforge-$neoForgeVersion-client.jar")
        val srgJar = if (neoFormVersion != null) {
            File(librariesDir, "net/minecraft/client/$mcVersion-$neoFormVersion/client-$mcVersion-$neoFormVersion-srg.jar")
        } else null

        val clientCorrupt = clientJar.isFile && !isJarWithClasses(clientJar)
        val srgCorrupt = srgJar != null && srgJar.isFile && !isJarWithClasses(srgJar)

        if (!clientCorrupt && !srgCorrupt) return

        if (clientCorrupt) clientJar.delete()
        if (srgCorrupt) srgJar?.delete()

        val repaired = kotlinx.coroutines.runBlocking {
            LoaderInstaller.repairForgeIfNeeded(
                versionJsonFile = versionJsonFile,
                minecraftDir = context.minecraftDir,
                javaPath = context.javaPath,
                onProgress = { println("[Preflight/NeoForge] $it") },
            )
        }

        if (!repaired) {
            throw RuntimeException("NeoForge repair failed for $neoForgeVersion")
        }
    }

    private fun isJarWithClasses(file: File): Boolean {
        if (!file.isFile || file.length() <= 1000L) return false
        return runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    if (entries.nextElement().name.endsWith(".class")) return@use true
                }
                false
            }
        }.getOrDefault(false)
    }
}
