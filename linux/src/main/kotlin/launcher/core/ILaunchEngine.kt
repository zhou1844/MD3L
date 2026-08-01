package launcher.core

import java.io.File

/**
 * 启动引擎抽象基类。
 * 派生 [JavaLaunchEngine] 和 [BedrockLaunchEngine]，
 * 通过多态实现 Java Edition / Bedrock Edition 的启动隔离。
 */
interface ILaunchEngine {
    fun execute(context: LaunchContext): Process
}

/**
 * 统一启动上下文，携带版本信息、鉴权凭据、JVM 参数等。
 */
data class LaunchContext(
    val version: LocalVersion,
    val javaPath: String,
    val memoryMb: Int,
    val playerName: String,
    val uuid: String,
    val accessToken: String,
    val minecraftDir: String,
    val customJvmArgs: String = "",
    val windowWidth: Int = 854,
    val windowHeight: Int = 480,
    val fullscreen: Boolean = false,
    val edition: GameEdition = GameEdition.Java,
    val bedrockExePath: String = "",
    val bedrockPackageId: String = "Microsoft.MinecraftUWP_8wekyb3d8bbwe!App",
    val skinUri: String = "",
    val skinModel: String = "classic",
    val authServerUrl: String = "", // 第三方登录 Yggdrasil API URL
    val gcPolicy: String = "G1GC",
    val accountType: AccountType = AccountType.Offline,
    // Advanced JVM settings (from AppSettings)
    val jvmMetaspaceSize: Int = 256,
    val jvmReservedCodeCache: Int = 256,
    val jvmG1NewSizePercent: Int = 20,
    val jvmG1MaxNewSizePercent: Int = 50,
    val jvmG1HeapRegionSize: Int = 16,
    val jvmG1GCPauseTarget: Int = 50,
    val jvmZUncommitDelay: Int = 60,
    val jvmConcGCThreads: Int = 0,
    val jvmShenandoahMode: String = "iu",
    val jvmShenandoahHeapSizePercent: Int = 10,
    val jvmParallelGCThreads: Int = 0,
    val jvmUseLargePages: Boolean = false,
    val jvmAlwaysPreTouch: Boolean = true,
    val jvmDisableExplicitGC: Boolean = true,
    val jvmParallelRefProcEnabled: Boolean = true,
    val jvmStringDedup: Boolean = true,
    val jvmThreadStackSize: Int = 0,
    val jvmTieredCompilation: Boolean = true,
    val jvmInlineSize: Int = 325,
    val jvmFreqInlineSize: Int = 325,
    val jvmLoopUnrollingLimit: Int = 60,
    val jvmEnableIEEE: Boolean = false,
    val jvmNativeMemoryTracking: Boolean = false,
    // Advanced game args
    val launchDemoMode: Boolean = false,
    val javaUseNativeGlfw: Boolean = false,
    val javaUseNativeOpenAl: Boolean = false,
    val javaExtraGameArgs: String = "",
    val javaQuickPlaySingleplayer: String = "",
    val javaQuickPlayMultiplayer: String = "",
) {
    val gameDir: File
        get() {
            val versionRoot = File(minecraftDir, "versions/${version.id}")
            val modpackRoot = File(versionRoot, ".minecraft")
            return if (modpackRoot.isDirectory) modpackRoot else versionRoot
        }
    val rootGameDir: File get() = File(minecraftDir)
    val versionDir: File get() = File(version.versionDir)
    val nativesDir: File get() = File(version.versionDir, "natives")
    val librariesDir: File get() = File(minecraftDir, "libraries")
    val assetsDir: File get() = File(minecraftDir, "assets")
}

enum class GameEdition { Java, Bedrock }
