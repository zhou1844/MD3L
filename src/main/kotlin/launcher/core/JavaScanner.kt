package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class JavaInstallation(
    val path: String,
    val version: String,
    val is64Bit: Boolean,
)

object JavaScanner {

    suspend fun findAll(): List<JavaInstallation> = withContext(Dispatchers.IO) {
        val candidates = mutableSetOf<String>()
        val osName = System.getProperty("os.name").lowercase()

        // Current JAVA_HOME
        System.getenv("JAVA_HOME")?.let { candidates.add(it) }

        // PATH entries
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            val javaBin = File(dir, if ("win" in osName) "java.exe" else "java")
            if (javaBin.exists()) {
                candidates.add(javaBin.parentFile?.parentFile?.absolutePath ?: dir)
            }
        }

        if ("win" in osName) {
            // Common Windows locations
            listOf(
                "C:\\Program Files\\Java",
                "C:\\Program Files (x86)\\Java",
                "C:\\Program Files\\Eclipse Adoptium",
                "C:\\Program Files\\Microsoft\\jdk-17",
                "C:\\Program Files\\Zulu",
                "C:\\Program Files\\BellSoft",
                "C:\\Program Files\\AdoptOpenJDK",
            ).forEach { base ->
                File(base).listFiles()?.forEach { dir ->
                    if (File(dir, "bin/java.exe").exists()) {
                        candidates.add(dir.absolutePath)
                    }
                }
            }
            // Registry-discovered (look in standard adoptium/oracle locations)
            listOf(
                System.getenv("LOCALAPPDATA"),
                System.getenv("ProgramFiles"),
            ).filterNotNull().forEach { base ->
                File(base).walkTopDown().maxDepth(4).forEach { f ->
                    if (f.name == "java.exe" && f.parentFile?.name == "bin") {
                        candidates.add(f.parentFile.parentFile.absolutePath)
                    }
                }
            }
        } else {
            // Linux / macOS common paths
            listOf(
                "/usr/lib/jvm",
                "/usr/java",
                "/Library/Java/JavaVirtualMachines",
                "${System.getProperty("user.home")}/.sdkman/candidates/java",
            ).forEach { base ->
                File(base).listFiles()?.forEach { dir ->
                    val bin = File(dir, "bin/java")
                    val bin2 = File(dir, "Contents/Home/bin/java")
                    if (bin.exists()) candidates.add(dir.absolutePath)
                    if (bin2.exists()) candidates.add(File(dir, "Contents/Home").absolutePath)
                }
            }
        }

        candidates
            .filter { path ->
                val javaExe = if ("win" in osName) "bin/java.exe" else "bin/java"
                File(path, javaExe).exists()
            }
            .mapNotNull { path -> probeJava(path, osName) }
            .distinctBy { it.path }
            .sortedByDescending { it.version }
    }

    private fun probeJava(javaHome: String, osName: String): JavaInstallation? {
        return try {
            val javaExe = if ("win" in osName) "$javaHome/bin/java.exe" else "$javaHome/bin/java"
            val process = ProcessBuilder(javaExe, "-version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val versionRegex = """(?:java|openjdk) version "([^"]+)"""".toRegex()
            val altRegex = """(?:Runtime Environment|build) .*?(\d+[\.\d+_\-]*)""".toRegex()

            val version = versionRegex.find(output)?.groupValues?.get(1)
                ?: altRegex.find(output)?.groupValues?.get(1)
                ?: "unknown"

            val is64 = "64-Bit" in output || "amd64" in output || "x86_64" in output

            JavaInstallation(path = javaHome, version = version, is64Bit = is64)
        } catch (_: Exception) {
            null
        }
    }
}
