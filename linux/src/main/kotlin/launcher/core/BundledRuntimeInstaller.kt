package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * 首次启动时检测并安装内置的 Windows 运行库（StoreEngagement.Appx）。
 * 无感安装：静默、失败不阻塞主流程。
 */
object BundledRuntimeInstaller {
    private const val APPX_RESOURCE = "/runtime/StoreEngagement.Appx"
    private const val MARKER_FAMILY = "Microsoft.Services.Store.Engagement"
    private const val REQUIRED_DOWNLOAD_APPX = "必须安装.appx"

    suspend fun ensureInstalled(): Boolean = withContext(Dispatchers.IO) {
        if (!isWindows()) return@withContext false
        if (runCatching { isAppxInstalled(MARKER_FAMILY) }.getOrDefault(false)) {
            return@withContext true
        }

        val candidates = collectCandidates()
        if (candidates.isEmpty()) return@withContext false

        var installedAny = false
        candidates.forEach { appx ->
            val identityName = runCatching { resolveIdentityName(appx) }.getOrNull().orEmpty()
            if (identityName.isNotBlank() && runCatching { isAppxInstalled(identityName) }.getOrDefault(false)) {
                println("[BundledRuntimeInstaller] 已安装，跳过: $identityName")
                installedAny = true
                return@forEach
            }
            if (installAppx(appx)) {
                installedAny = true
            }
        }
        installedAny
    }

    private fun collectCandidates(): List<File> {
        val items = linkedSetOf<File>()

        extractAppx()?.let { items.add(it) }

        val downloadsDir = File(System.getProperty("user.home"), "Downloads")
        val mustInstall = File(downloadsDir, REQUIRED_DOWNLOAD_APPX)
        if (mustInstall.isFile && mustInstall.length() > 0L) {
            items.add(mustInstall)
        }

        downloadsDir.listFiles { f ->
            f.isFile && f.extension.equals("appx", ignoreCase = true) &&
                ("安装" in f.name || "must" in f.name.lowercase())
        }?.forEach { items.add(it) }

        return items.filter { it.isFile && it.length() > 0L }
    }

    private fun isAppxInstalled(family: String): Boolean {
        val proc = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-Command",
            "if (Get-AppxPackage -Name '$family*' -ErrorAction SilentlyContinue) { 'yes' } else { 'no' }",
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor(20, TimeUnit.SECONDS)
        return out.contains("yes")
    }

    private fun installAppx(appxFile: File): Boolean {
        return runCatching {
            val psCmd = "Add-AppxPackage -Path \"${appxFile.absolutePath}\" -ForceApplicationShutdown"
            val proc = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-Command", psCmd,
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor(180, TimeUnit.SECONDS)
            val exit = if (ok) proc.exitValue() else -1
            println("[BundledRuntimeInstaller] Add-AppxPackage file=${appxFile.name} exit=$exit output=${out.take(500)}")
            ok && exit == 0
        }.getOrDefault(false)
    }

    private fun resolveIdentityName(appxFile: File): String {
        ZipFile(appxFile).use { zip ->
            val entry = zip.getEntry("AppxManifest.xml") ?: return ""
            val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val pattern = Regex("""<Identity\\s+[^>]*Name=\"([^\"]+)\"""")
            return pattern.find(xml)?.groupValues?.getOrNull(1).orEmpty()
        }
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
