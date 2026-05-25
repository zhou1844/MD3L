package launcher.core

import java.io.File

/**
 * 启动器数据目录管理。
 * 所有持久化数据存储在启动器 EXE/JAR 同目录下的 "data" 子文件夹，
 * 而非用户主目录，保证便携性。
 */
object LauncherDirs {

    /**
     * 启动器自身所在目录（EXE 或 JAR 所在目录）。
     * 运行时通过 ProcessHandle 获取 EXE 路径，找不到则回退到 user.dir。
     */
    val launcherDir: File by lazy {
        resolveExeDir() ?: File(System.getProperty("user.dir"))
    }

    /**
     * 数据根目录：<launcherDir>/data
     */
    val dataDir: File by lazy {
        File(launcherDir, "data").also { it.mkdirs() }
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
