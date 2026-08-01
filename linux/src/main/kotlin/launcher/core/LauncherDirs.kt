package launcher.core

import java.io.File

/**
 * 启动器数据目录管理。
 *
 * 在 Windows 上，所有持久化数据存储在启动器 EXE/JAR 同目录下的 "data" 子文件夹，
 * 保证便携性（U 盘可携带）。
 *
 * 在 Linux 上，如果安装目录不可写（如 /opt/md3l/），则自动回退到
 * XDG 标准路径 ~/.local/share/md3l，避免权限错误。
 *
 * 日志目录结构：
 *   <launcherDir>/log/          ← 启动器自身日志（渲染、启动事件等）
 *   <launcherDir>/log/Java/     ← Java 版游戏相关日志
 *   <launcherDir>/log/bedrock/  ← 基岩版游戏相关日志
 */
object LauncherDirs {

    /**
     * 是否为 Windows 系统（用于判断便携模式）。
     */
    private val isWindows: Boolean by lazy {
        System.getProperty("os.name").lowercase().contains("win")
    }

    /**
     * 启动器自身所在目录。
     *
     * - Windows: EXE/JAR 所在目录（便携模式）。
     * - Linux/macOS: 优先使用安装目录，若不可写则回退到 ~/.local/share/md3l。
     */
    val launcherDir: File by lazy {
        val exeDir = resolveExeDir()
        if (exeDir != null && (isWindows || exeDir.canWrite())) {
            exeDir
        } else {
            // Linux 上安装于 /opt 等系统目录时，回退到用户数据目录
            val xdgDataHome = System.getenv("XDG_DATA_HOME")
                ?: "${System.getProperty("user.home")}/.local/share"
            File(xdgDataHome, "md3l")
        }
    }

    /**
     * 数据根目录：
     * - Windows: <launcherDir>/data
     * - Linux:   <launcherDir>（本身就是 ~/.local/share/md3l）
     */
    val dataDir: File by lazy {
        val dir = if (isWindows) File(launcherDir, "data") else launcherDir
        dir.also { it.mkdirs() }
    }

    /**
     * 启动器日志根目录：<launcherDir>/log
     * 所有启动器自身事件（渲染、启动流程等）写入此目录。
     */
    val logDir: File by lazy {
        File(launcherDir, "log").also { it.mkdirs() }
    }

    /**
     * Java 版游戏日志目录：<launcherDir>/log/Java
     * Java 版启动、游戏输出等日志写入此目录。
     */
    val javaLogDir: File by lazy {
        File(logDir, "Java").also { it.mkdirs() }
    }

    /**
     * 基岩版游戏日志目录：<launcherDir>/log/bedrock
     * 基岩版启动、游戏输出等日志写入此目录。
     */
    val bedrockLogDir: File by lazy {
        File(logDir, "bedrock").also { it.mkdirs() }
    }

    /**
     * ProtonGDK 安装目录：<dataDir>/ProtonGDK
     * GDK-Proton 运行时（Wine/Proton 定制版）安装在此目录。
     */
    val protonDir: File by lazy {
        File(dataDir, "ProtonGDK").also { it.mkdirs() }
    }

    /**
     * 基岩版下载缓存目录：<dataDir>/bedrock_cache
     * 基岩版 GDK 包下载后暂存于此。
     */
    val bedrockCacheDir: File by lazy {
        File(dataDir, "bedrock_cache").also { it.mkdirs() }
    }

    /**
     * 旧版数据目录（~/.md3l）。迁移完成后不再使用。
     */
    private val legacyDataDir: File
        get() = File(System.getProperty("user.home"), ".md3l")

    /**
     * 迁移标志文件，存在则说明已迁移过，不再重复迁移。
     */
    private val migrationDoneMarker: File
        get() = File(dataDir, ".migration_done")

    /**
     * 返回当前平台适用的 curl 命令名。
     * - Windows: "curl.exe"
     * - Linux/macOS: "curl"
     */
    fun curlCmd(): String = if (isWindows) "curl.exe" else "curl"

    /**
     * 首次启动时调用：将旧版 ~/.md3l 中的所有数据迁移到新 dataDir。
     * 已迁移过（.migration_done 存在）或旧目录不存在则跳过。
     */
    fun migrateFromLegacyIfNeeded(onProgress: (String) -> Unit = {}) {
        if (migrationDoneMarker.exists()) return
        val legacy = legacyDataDir
        if (!legacy.isDirectory) {
            migrationDoneMarker.writeText("no_legacy")
            return
        }
        onProgress("检测到旧版数据，正在迁移...")
        var movedCount = 0
        var errorCount = 0
        legacy.walkTopDown().forEach { src ->
            if (src == legacy) return@forEach
            val rel = src.relativeTo(legacy)
            val dst = File(dataDir, rel.path)
            try {
                if (src.isDirectory) {
                    dst.mkdirs()
                } else {
                    dst.parentFile?.mkdirs()
                    if (!dst.exists()) {
                        src.copyTo(dst, overwrite = false)
                        movedCount++
                    }
                }
            } catch (e: Exception) {
                errorCount++
                println("[LauncherDirs] 迁移失败: ${src.absolutePath} -> ${dst.absolutePath}: ${e.message}")
            }
        }
        migrationDoneMarker.writeText("migrated_from=${legacy.absolutePath};files=$movedCount;errors=$errorCount")
        onProgress("数据迁移完成：迁移 $movedCount 个文件，失败 $errorCount 个")
        println("[LauncherDirs] 迁移完成: $movedCount 个文件，$errorCount 个失败，来源=${legacy.absolutePath}")
    }

    private fun resolveExeDir(): File? {
        // 1. 当前进程自身（打包 EXE 时有效）
        val own = runCatching {
            ProcessHandle.current().info().command().orElse("")
        }.getOrNull().orEmpty()
        if (own.endsWith(".exe", ignoreCase = true) &&
            !own.contains("java", ignoreCase = true) &&
            !own.contains("javaw", ignoreCase = true)
        ) {
            return File(own).parentFile?.takeIf { it.isDirectory }
        }

        // 2. 父进程链（某些打包方式下进程名为 wrapper）
        var ph: ProcessHandle? = runCatching {
            ProcessHandle.current().parent().orElse(null)
        }.getOrNull()
        repeat(4) {
            val cmd = runCatching { ph?.info()?.command()?.orElse("") }.getOrNull().orEmpty()
            if (cmd.endsWith(".exe", ignoreCase = true) &&
                !cmd.contains("java", ignoreCase = true) &&
                !cmd.contains("powershell", ignoreCase = true) &&
                !cmd.contains("cmd.exe", ignoreCase = true)
            ) {
                return File(cmd).parentFile?.takeIf { it.isDirectory }
            }
            ph = runCatching { ph?.parent()?.orElse(null) }.getOrNull()
        }

        // 3. JAR 自身路径
        val jarUrl = LauncherDirs::class.java.protectionDomain?.codeSource?.location
        if (jarUrl != null) {
            val jarFile = runCatching { File(jarUrl.toURI()) }.getOrNull()
                ?: runCatching { File(jarUrl.path) }.getOrNull()
            if (jarFile != null && jarFile.isFile) {
                return jarFile.parentFile?.takeIf { it.isDirectory }
            }
        }

        return null
    }
}
