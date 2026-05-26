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
    val themeMode: String = "dark",
    val backgroundImagePath: String = "",
    val backgroundBlurRadius: Int = 20,
    val backgroundBrightness: Float = 0.75f,
    val uiPanelOpacity: Float = 0.75f,
    // 基岩版专项设置
    val bedrockPreheatEnabled: Boolean = true,
    val bedrockVersionIsolation: Boolean = true,
    val bedrockFastSwitchEnabled: Boolean = true,
    val bedrockSkipRegistrationIfCached: Boolean = true,
    val bedrockForceRegisterEveryLaunch: Boolean = false,
    val bedrockPackageCacheTtlMinutes: Int = 5,
    val bedrockAutoMigrateOnFirstLaunch: Boolean = true,
    val bedrockShowRegistrationLog: Boolean = false,
    // Java版高级设置 — GC 调优
    val jvmMetaspaceSize: Int = 256,
    val jvmReservedCodeCache: Int = 256,
    val jvmG1NewSizePercent: Int = 20,
    val jvmG1MaxNewSizePercent: Int = 50,
    val jvmG1HeapRegionSize: Int = 16,
    val jvmG1GCPauseTarget: Int = 50,
    val jvmUseLargePages: Boolean = false,
    val jvmAlwaysPreTouch: Boolean = true,
    val jvmDisableExplicitGC: Boolean = true,
    val jvmParallelRefProcEnabled: Boolean = true,
    val jvmEnableAggressiveOpts: Boolean = false,
    val jvmStringDedup: Boolean = true,
    val jvmUseZGC: Boolean = false,
    // Java版高级设置 — 启动行为
    val launchDemoMode: Boolean = false,
    val skipVersionCheck: Boolean = false,
    val javaGameWidth: Int = 0,
    val javaGameHeight: Int = 0,
    val javaUseNativeGlfw: Boolean = false,
    val javaUseNativeOpenAl: Boolean = false,
    val javaEnableNarratorOnStart: Boolean = false,
    val javaQuickPlayPath: String = "",
    val javaQuickPlaySingleplayer: String = "",
    val javaQuickPlayMultiplayer: String = "",
    val javaExtraGameArgs: String = "",
    // Java版高级设置 — JVM 运行时
    val jvmThreadStackSize: Int = 0,       // -Xss, KB; 0=default
    val jvmEnableJit: Boolean = true,
    val jvmTieredCompilation: Boolean = true,
    val jvmInlineSize: Int = 325,           // -XX:MaxInlineSize
    val jvmFreqInlineSize: Int = 325,       // -XX:FreqInlineSize
    val jvmLoopUnrollingLimit: Int = 60,    // -XX:LoopUnrollingLimit
    val jvmEnableIEEE: Boolean = false,     // -XX:+UseStrictFP
    val jvmNativeMemoryTracking: Boolean = false,
    // 基岩版高级设置 — 版本管理
    val bedrockMigrateFromProfilesOnOpen: Boolean = true,
    val bedrockMaxParallelInstalls: Int = 2,
    val bedrockSkipHashVerify: Boolean = false,
    val bedrockKeepCacheAfterInstall: Boolean = true,
    val bedrockJunctionFallbackCopy: Boolean = false,
    // 基岩版高级设置 — 游戏行为
    val bedrockFpsLimit: Int = 0,           // 0=unlimited
    val bedrockRenderDistance: Int = 0,     // 0=default
    val bedrockGraphicsMode: String = "fancy",
    val bedrockShowCoordinates: Boolean = false,
    val bedrockHideHud: Boolean = false,
    val bedrockSimulationDistance: Int = 0,
) {
    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val configFile: File
            get() = File(LauncherDirs.dataDir, "settings.json")

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
