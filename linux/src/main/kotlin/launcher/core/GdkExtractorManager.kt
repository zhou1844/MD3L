package launcher.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object GdkExtractorManager {

    data class ExtractProgress(
        val step: String = "",
        val fraction: Float = 0f,
        val isRunning: Boolean = false,
        val error: String = "",
    )

    private val _extractProgress = MutableStateFlow(ExtractProgress())
    val extractProgress: StateFlow<ExtractProgress> = _extractProgress.asStateFlow()

    private var extractJob: Job? = null

    suspend fun extractToolIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val toolsDir = File(LauncherDirs.dataDir, "tools").also { it.mkdirs() }
        val extractorExe = File(toolsDir, "GdkExtractor.exe")

        if (extractorExe.exists() && extractorExe.length() > 0) {
            println("[GdkExtractor] 工具已存在: ${extractorExe.absolutePath}")
            return@withContext true
        }

        _extractProgress.value = ExtractProgress(step = "正在提取 GdkExtractor 工具...", fraction = 0f, isRunning = true)

        try {
            val resourceStream = javaClass.classLoader.getResourceAsStream("tools/GdkExtractor.exe")
                ?: run {
                    _extractProgress.value = ExtractProgress(error = "找不到 bundled GdkExtractor.exe 资源", isRunning = false)
                    return@withContext false
                }

            resourceStream.use { input ->
                extractorExe.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            println("[GdkExtractor] 工具已提取到: ${extractorExe.absolutePath}")
            _extractProgress.value = ExtractProgress(step = "提取完成", fraction = 1f, isRunning = false)
            true
        } catch (e: Exception) {
            _extractProgress.value = ExtractProgress(error = "提取失败: ${e.message}", isRunning = false)
            println("[GdkExtractor] 提取失败: ${e.message}")
            false
        }
    }

    suspend fun extractMsixvc(
        msixvcFile: File,
        outputDir: File,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ): Boolean = withContext(Dispatchers.IO) {
        if (!msixvcFile.exists()) {
            _extractProgress.value = ExtractProgress(error = "MSIXVC 文件不存在: ${msixvcFile.absolutePath}", isRunning = false)
            return@withContext false
        }

        if (!extractToolIfNeeded()) {
            return@withContext false
        }

        val protonPath = ProtonManager.getSelectedProtonPath()
        if (protonPath.isBlank() || !ProtonManager.isInstalled()) {
            _extractProgress.value = ExtractProgress(error = "ProtonGDK 未安装，无法运行 GdkExtractor", isRunning = false)
            return@withContext false
        }

        val toolsDir = File(LauncherDirs.dataDir, "tools")
        val extractorExe = File(toolsDir, "GdkExtractor.exe")
        if (!extractorExe.exists()) {
            _extractProgress.value = ExtractProgress(error = "GdkExtractor.exe 不存在", isRunning = false)
            return@withContext false
        }

        val prefixPath = File(LauncherDirs.dataDir, "ProtonGDK/game_prefix").also { it.mkdirs() }
        val protonScript = File(protonPath, "proton")
        if (!protonScript.exists()) {
            _extractProgress.value = ExtractProgress(error = "找不到 proton 脚本: ${protonScript.absolutePath}", isRunning = false)
            return@withContext false
        }

        _extractProgress.value = ExtractProgress(step = "正在启动 GdkExtractor 解压 MSIXVC...", fraction = 0.1f, isRunning = true)

        outputDir.mkdirs()

        try {
            val pb = ProcessBuilder(
                protonScript.absolutePath,
                "waitforexitandrun",
                extractorExe.absolutePath,
                msixvcFile.absolutePath,
                outputDir.absolutePath,
            )

            val env = pb.environment()
            env["STEAM_COMPAT_DATA_PATH"] = prefixPath.absolutePath
            env["STEAM_COMPAT_CLIENT_INSTALL_PATH"] = "${System.getProperty("user.home")}/.local/share/Steam"
            env["UMU_ID"] = "md3l-gdk-extractor"

            val lib64 = "${protonPath}/files/lib64"
            val lib32 = "${protonPath}/files/lib"
            val currentLd = env["LD_LIBRARY_PATH"] ?: ""
            env["LD_LIBRARY_PATH"] = "$lib64:$lib32${if (currentLd.isNotBlank()) ":$currentLd" else ""}"

            env["WINEDLLOVERRIDES"] = "dxgi,d3d11,d3d10core,d3d9=b"

            pb.redirectErrorStream(true)
            pb.directory(msixvcFile.parentFile)

            _extractProgress.value = ExtractProgress(step = "GdkExtractor 解压中...", fraction = 0.2f, isRunning = true)

            val process = pb.start()

            val outputThread = Thread {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        var lineCount = 0
                        reader.forEachLine { line ->
                            lineCount++
                            println("[GdkExtractor] $line")
                            val frac = (0.2f + (lineCount / 500f).coerceAtMost(0.7f)).coerceIn(0.2f, 0.9f)
                            _extractProgress.value = ExtractProgress(
                                step = "GdkExtractor 解压中... ($lineCount lines)",
                                fraction = frac,
                                isRunning = true,
                            )
                        }
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }
            outputThread.start()

            val exitCode = process.waitFor()
            outputThread.join(5000)

            val hasContent = outputDir.listFiles()?.isNotEmpty() == true
            if (hasContent) {
                _extractProgress.value = ExtractProgress(step = "解压完成", fraction = 1f, isRunning = false)
                println("[GdkExtractor] 解压成功: ${outputDir.absolutePath}")
                true
            } else {
                _extractProgress.value = ExtractProgress(error = "GdkExtractor 退出码: $exitCode，输出为空", isRunning = false)
                println("[GdkExtractor] 失败: exitCode=$exitCode, 输出为空")
                false
            }
        } catch (e: CancellationException) {
            _extractProgress.value = ExtractProgress(error = "解压已取消", isRunning = false)
            throw e
        } catch (e: Exception) {
            _extractProgress.value = ExtractProgress(error = "解压异常: ${e.message}", isRunning = false)
            println("[GdkExtractor] 异常: ${e.message}")
            false
        }
    }

    fun cancelExtract() {
        extractJob?.cancel(CancellationException("解压已取消"))
    }
}
