package launcher.core

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object GpuDetector {

    data class GpuInfo(
        val name: String,
        val adapterRamBytes: Long,
    ) {
        val isDedicated: Boolean get() {
            val n = name.lowercase()
            if ("arc" in n && adapterRamBytes >= 2_000_000_000L) return true
            if (adapterRamBytes >= 1_000_000_000L &&
                ("nvidia" in n || "geforce" in n || "rtx" in n || "gtx" in n || "quadro" in n ||
                 "radeon rx" in n || "radeon pro" in n || "firepro" in n)
            ) return true
            if (adapterRamBytes >= 2_000_000_000L &&
                "intel" !in n && "hd graphics" !in n && "uhd graphics" !in n && "iris" !in n
            ) return true
            return false
        }
    }

    val gpuInfos: List<GpuInfo> by lazy { queryGpuInfos() }

    val hasDedicatedGpu: Boolean by lazy { gpuInfos.any { it.isDedicated } }

    val isIntegratedOnly: Boolean by lazy {
        gpuInfos.isNotEmpty() && gpuInfos.none { it.isDedicated }
    }

    fun tryActivateDedicatedGpu(): Boolean {
        if (!hasDedicatedGpu) return false
        var activated = false

        runCatching {
            val nvapiPath = java.io.File("C:\\Windows\\System32\\nvapi64.dll")
            val nvapi = NativeLibrary.getInstance(nvapiPath.absolutePath)
            val initFunc: Function = nvapi.getFunction("nvapi_Init")
            val status = initFunc.invoke(Int::class.java, emptyArray<Any>()) as? Int ?: -1
            if (status == 0) {
                println("[GpuDetector] NVAPI nvapi_Init success, dGPU activated")
                activated = true
            } else {
                println("[GpuDetector] NVAPI nvapi_Init returned $status")
            }
        }.onFailure {
            println("[GpuDetector] NVAPI unavailable: ${it.message}")
        }

        runCatching {
            System.loadLibrary("amd_ags_x64")
            println("[GpuDetector] AMD AGS loaded, dGPU activated")
            activated = true
        }.onFailure {
            println("[GpuDetector] AMD AGS unavailable: ${it.message}")
        }

        return activated
    }

    private fun queryGpuInfos(): List<GpuInfo> {
        if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            return emptyList()
        }
        val raw = runCatching {
            val proc = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                """Get-CimInstance Win32_VideoController | Select-Object Name, AdapterRAM | ConvertTo-Json -Compress"""
            )
                .redirectErrorStream(true)
                .start()

            val finished = proc.waitFor(8, TimeUnit.SECONDS)
            val text = if (finished) {
                proc.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                proc.destroyForcibly()
                ""
            }
            text
        }.getOrDefault("")

        val infos = if (raw.isNotBlank()) parseGpuJson(raw) else emptyList()

        println("[GpuDetector] Detected ${infos.size} GPU(s):")
        infos.forEach { gpu ->
            val tag = if (gpu.isDedicated) "[dGPU]" else "[iGPU]"
            println("  $tag ${gpu.name}  VRAM=${gpu.adapterRamBytes / (1024 * 1024)}MB")
        }
        println("[GpuDetector] hasDedicatedGpu=${infos.any { it.isDedicated }} isIntegratedOnly=${infos.isNotEmpty() && infos.none { it.isDedicated }}")
        return infos
    }

    private fun parseGpuJson(json: String): List<GpuInfo> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.startsWith("[")) {
            val objects = splitJsonArray(trimmed)
            return objects.mapNotNull { parseSingleGpu(it) }
        }

        val single = parseSingleGpu(trimmed)
        return if (single != null) listOf(single) else emptyList()
    }

    private fun parseSingleGpu(jsonObj: String): GpuInfo? {
        val name = extractJsonString(jsonObj, "Name") ?: return null
        val ram = extractJsonNumber(jsonObj, "AdapterRAM") ?: 0L
        return GpuInfo(name = name, adapterRamBytes = ram)
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return pattern.find(json)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
    }

    private fun extractJsonNumber(json: String, key: String): Long? {
        val pattern = Regex("\"$key\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
        return pattern.find(json)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()
    }

    private fun splitJsonArray(arrayJson: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = -1
        for ((i, ch) in arrayJson.withIndex()) {
            when (ch) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) { result.add(arrayJson.substring(start, i + 1)); start = -1 } }
            }
        }
        return result
    }
}
