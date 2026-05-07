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
) {
    val gameDir: File get() = File(minecraftDir, "versions/${version.id}")
    val rootGameDir: File get() = File(minecraftDir)
    val versionDir: File get() = File(version.versionDir)
    val nativesDir: File get() = File(version.versionDir, "natives")
    val librariesDir: File get() = File(minecraftDir, "libraries")
    val assetsDir: File get() = File(minecraftDir, "assets")
}

enum class GameEdition { Java, Bedrock }
