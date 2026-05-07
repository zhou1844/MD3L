package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppSettings(
    val minecraftDir: String = "",
    val javaPath: String = "java",
    val memoryMb: Int = 4096,
    val maxDownloadThreads: Int = 64,
    val customJvmArgs: String = "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions",
    val accentIndex: Int = 0,
    val playerName: String = "",
    val playerUuid: String = "",
    val accessToken: String = "",
    val skinUrl: String = "",
    val loginMode: String = "offline",
    val windowWidth: Int = 1920,
    val windowHeight: Int = 1080,
    val fullscreen: Boolean = false,
    val gcPolicy: String = "G1GC",
    val downloadMirror: String = "bmclapi",
    val avatarPath: String = "",
    val eulaAccepted: Boolean = false,
    val lastVersionId: String = "",
    val lastVersionType: String = "",
) {
    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val configFile: File
            get() {
                val dir = File(System.getProperty("user.home"), ".md3l")
                if (!dir.exists()) dir.mkdirs()
                return File(dir, "settings.json")
            }

        suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
            try {
                if (configFile.exists()) {
                    json.decodeFromString<AppSettings>(configFile.readText(Charsets.UTF_8))
                } else {
                    AppSettings().also { save(it) }
                }
            } catch (e: Exception) {
                AppSettings().also { save(it) }
            }
        }

        suspend fun save(settings: AppSettings) = withContext(Dispatchers.IO) {
            configFile.parentFile?.mkdirs()
            configFile.writeText(json.encodeToString(settings), Charsets.UTF_8)
        }
    }
}

fun defaultMinecraftDir(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        "win" in os -> File(System.getenv("APPDATA"), ".minecraft").absolutePath
        "mac" in os -> File(System.getProperty("user.home"), "Library/Application Support/minecraft").absolutePath
        else -> File(System.getProperty("user.home"), ".minecraft").absolutePath
    }
}
