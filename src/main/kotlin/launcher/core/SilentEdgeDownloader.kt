package launcher.core

import java.io.File

/**
 * 利用系统 Edge/Chrome 在后台完成下载（绕过 Cloudflare）。
 *
 * 模式 1: --headless=new（无窗口、无任务栏图标、不会触发 App Installer）
 * 模式 2: 屏幕外窗口 -32000,-32000（headless 被拦截时的兜底）
 */
object SilentEdgeDownloader {

    private val BROWSER_CANDIDATES = listOf(
        "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
        "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
        "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    )

    fun download(
        url: String,
        downloadDir: File,
        expectedSize: Long = -1,
        timeoutMs: Long = 15 * 60 * 1000L,
        isCancelled: () -> Boolean = { false },
        onStatus: (String) -> Unit = {},
    ): File? {
        val browserExe = findBrowser() ?: run { onStatus("未找到 Edge/Chrome"); return null }
        downloadDir.mkdirs()
        val existing = downloadDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val tempProfile = File(System.getProperty("java.io.tmpdir"), "mc_dl_${System.currentTimeMillis()}")
        writePrefs(tempProfile, downloadDir)

        println("[EdgeDL] 浏览器=$browserExe  目录=${downloadDir.absolutePath}  URL=$url")

        val commonArgs = listOf(
            "--no-first-run", "--no-default-browser-check", "--disable-extensions",
            "--disable-popup-blocking", "--disable-translate", "--disable-sync",
            "--disable-gpu", "--disable-blink-features=AutomationControlled",
            "--user-data-dir=${tempProfile.absolutePath}",
        )

        // ── 模式 1: headless（无任务栏图标） ──────────────────────────
        onStatus("正在启动下载引擎...")
        val args1 = mutableListOf(browserExe, "--headless=new") + commonArgs + url
        var proc = ProcessBuilder(args1).redirectErrorStream(true).start()

        try {
            var result = monitor(downloadDir, existing, expectedSize, 35_000, isCancelled, onStatus)
            if (result != null) return result

            // headless 30 秒无活动 → 回退到屏幕外模式
            println("[EdgeDL] headless 无活动，切换到屏幕外模式")
            proc.destroyForcibly(); Thread.sleep(500)

            // ── 模式 2: 窗口在屏幕外 ──────────────────────────────────
            onStatus("正在切换下载模式...")
            val args2 = mutableListOf(browserExe,
                "--window-position=-32000,-32000", "--window-size=1,1") + commonArgs + url
            proc = ProcessBuilder(args2).redirectErrorStream(true).start()

            // 启动后立即用 PowerShell 隐藏窗口
            hideWindowAsync(proc)

            result = monitor(downloadDir, existing, expectedSize, timeoutMs - 40_000, isCancelled, onStatus)
            return result
        } finally {
            proc.destroyForcibly()
            Thread { Thread.sleep(3000); try { tempProfile.deleteRecursively() } catch (_: Exception) {} }.start()
        }
    }

    // ───────────────────────────────────────────────────────────────────
    //  监控下载目录
    // ───────────────────────────────────────────────────────────────────
    private fun monitor(
        dir: File, existing: Set<String>, expectedSize: Long,
        timeoutMs: Long, isCancelled: () -> Boolean, onStatus: (String) -> Unit,
    ): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSize = 0L; var stableCount = 0; var sawActivity = false

        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) return null
            Thread.sleep(1500)
            val all = dir.listFiles() ?: continue

            // 临时文件（正在下载）
            val tmp = all.filter { it.name !in existing &&
                (it.name.endsWith(".crdownload") || it.name.endsWith(".tmp")) }
            if (tmp.isNotEmpty()) {
                sawActivity = true
                val f = tmp.maxByOrNull { it.length() }!!
                val mb = f.length() / (1024.0 * 1024.0)
                val pct = if (expectedSize > 0) " (${f.length() * 100 / expectedSize}%)" else ""
                onStatus("下载中 ${"%.1f".format(mb)} MB$pct")
                stableCount = 0
            }

            // 完成的文件
            val done = all.filter { it.name !in existing &&
                !it.name.endsWith(".crdownload") && !it.name.endsWith(".tmp") &&
                !it.name.endsWith(".partial") && it.length() > 500_000 }
            val best = done.maxByOrNull { it.length() }
            if (best != null) {
                sawActivity = true
                if (best.length() == lastSize) {
                    stableCount++
                    if (stableCount >= 3) {
                        val mb = best.length() / (1024.0 * 1024.0)
                        onStatus("下载完成 ${"%.1f".format(mb)} MB")
                        println("[EdgeDL] 完成: ${best.absolutePath} (${best.length()} bytes)")
                        return best
                    }
                } else {
                    stableCount = 0; lastSize = best.length()
                    val mb = best.length() / (1024.0 * 1024.0)
                    onStatus("下载中 ${"%.1f".format(mb)} MB")
                }
            }

            // headless 模式下 30s 无活动 → 返回 null 让调用者切换
            if (!sawActivity && System.currentTimeMillis() - (deadline - timeoutMs) > 25_000) return null
        }
        onStatus("下载超时")
        return null
    }

    // ───────────────────────────────────────────────────────────────────
    //  写入 Edge/Chrome Preferences
    // ───────────────────────────────────────────────────────────────────
    private fun writePrefs(profile: File, dlDir: File) {
        val d = File(profile, "Default"); d.mkdirs()
        File(d, "Preferences").writeText("""
{
  "download":{"default_directory":"${dlDir.absolutePath.replace("\\","\\\\")}",
    "prompt_for_download":false,"directory_upgrade":true},
  "safebrowsing":{"enabled":false},
  "download_restrictions":0
}""".trimIndent())
    }

    // ───────────────────────────────────────────────────────────────────
    //  用 PowerShell 隐藏窗口（彻底从任务栏消失）
    // ───────────────────────────────────────────────────────────────────
    private fun hideWindowAsync(proc: Process) {
        Thread {
            try {
                Thread.sleep(1500) // 等窗口出现
                val pid = proc.pid()
                ProcessBuilder(
                    "powershell", "-NoProfile", "-Command",
                    "Add-Type -Name W -Namespace U -Member '[DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr h,int c);';" +
                    "try{(Get-Process -Id $pid).MainWindowHandle|ForEach-Object{[U.W]::ShowWindow([IntPtr]\$_,0)}}catch{}"
                ).redirectErrorStream(true).start().waitFor()
                println("[EdgeDL] 窗口已隐藏 (PID=$pid)")
            } catch (_: Exception) {}
        }.start()
    }

    private fun findBrowser(): String? {
        for (p in BROWSER_CANDIDATES) if (File(p).exists()) return p
        return try {
            val proc = ProcessBuilder("where", "msedge").redirectErrorStream(true).start()
            val r = proc.inputStream.bufferedReader().readLine()?.trim()
            proc.waitFor()
            if (r != null && File(r).exists()) r else null
        } catch (_: Exception) { null }
    }
}
