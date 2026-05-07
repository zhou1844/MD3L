package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 首次启动时检测并安装内置的 Windows 运行库（StoreEngagement.Appx）。
 * 无感安装：静默、失败不阻塞主流程。
 */
object BundledRuntimeInstaller {
    private const val APPX_RESOURCE = "/runtime/StoreEngagement.Appx"
    private const val MARKER_FAMILY = "Microsoft.Services.Store.Engagement"

    suspend fun ensureInstalled(): Boolean = withContext(Dispatchers.IO) {
        if (!isWindows()) return@withContext false
        runCatching {
            if (isAppxInstalled(MARKER_FAMILY)) return@withContext true
        }
        val appxFile = extractAppx() ?: return@withContext false
        runCatching {
            val proc = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-Command",
                "Add-AppxPackage -Path \"${appxFile.absolutePath}\" -ForceApplicationShutdown",
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            println("[BundledRuntimeInstaller] Add-AppxPackage exit=${if (ok) proc.exitValue() else "timeout"} output=${out.take(500)}")
            ok && proc.exitValue() == 0
        }.getOrDefault(false)
    }

    private fun isAppxInstalled(family: String): Boolean {
        val proc = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-Command",
            "if (Get-AppxPackage -Name '$family*' -ErrorAction SilentlyContinue) { 'yes' } else { 'no' }",
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
        return out.contains("yes")
    }

    private fun extractAppx(): File? {
        val stream = BundledRuntimeInstaller::class.java.getResourceAsStream(APPX_RESOURCE) ?: return null
        val tempDir = File(System.getProperty("java.io.tmpdir"), "md3l-runtime")
        tempDir.mkdirs()
        val outFile = File(tempDir, "StoreEngagement.Appx")
        stream.use { input -> outFile.outputStream().use { output -> input.copyTo(output) } }
        return outFile.takeIf { it.isFile && it.length() > 0 }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")
}
