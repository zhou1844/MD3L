package launcher.core

import java.io.File

object LauncherDirs {


    val launcherDir: File by lazy {
        resolveExeDir() ?: File(System.getProperty("user.dir"))
    }

    val dataDir: File by lazy {
        File(launcherDir, "data").also { it.mkdirs() }
    }


    val logDir: File by lazy {
        File(launcherDir, "log").also { it.mkdirs() }
    }


    val javaLogDir: File by lazy {
        File(logDir, "Java").also { it.mkdirs() }
    }


    val bedrockLogDir: File by lazy {
        File(logDir, "bedrock").also { it.mkdirs() }
    }

    private val legacyDataDir: File
        get() = File(System.getProperty("user.home"), ".md3l")

    private val migrationDoneMarker: File
        get() = File(dataDir, ".migration_done")


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
        val own = runCatching {
            ProcessHandle.current().info().command().orElse("")
        }.getOrNull().orEmpty()
        if (own.endsWith(".exe", ignoreCase = true) &&
            !own.contains("java", ignoreCase = true) &&
            !own.contains("javaw", ignoreCase = true)
        ) {
            return File(own).parentFile?.takeIf { it.isDirectory }
        }

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
