package launcher.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 玩家游玩统计数据 — 持久化存储到 data/stats.json
 */
@Serializable
data class StatsData(
    val javaLaunchCount: Int = 0,
    val bedrockLaunchCount: Int = 0,
    val javaPlayTimeSec: Long = 0L,
    val bedrockPlayTimeSec: Long = 0L,
    val lastPlayedVersion: String = "",
    val lastPlayedMs: Long = 0L,
    val totalCrashCount: Int = 0,
    val longestSessionSec: Long = 0L,
)

object PlayerStats {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: File get() = File(LauncherDirs.dataDir, "stats.json")

    private val _data = MutableStateFlow(StatsData())
    val data: StateFlow<StatsData> = _data.asStateFlow()

    fun load() {
        scope.launch {
            _data.value = runCatching {
                if (file.exists()) json.decodeFromString<StatsData>(file.readText(Charsets.UTF_8))
                else StatsData()
            }.getOrDefault(StatsData())
        }
    }

    fun recordSession(edition: GameEdition, elapsedSec: Long, versionId: String, crashed: Boolean) {
        scope.launch {
            val cur = _data.value
            val updated = when (edition) {
                GameEdition.Java -> cur.copy(
                    javaLaunchCount = cur.javaLaunchCount + 1,
                    javaPlayTimeSec = cur.javaPlayTimeSec + elapsedSec,
                    lastPlayedVersion = versionId,
                    lastPlayedMs = System.currentTimeMillis(),
                    totalCrashCount = cur.totalCrashCount + if (crashed) 1 else 0,
                    longestSessionSec = maxOf(cur.longestSessionSec, elapsedSec),
                )
                GameEdition.Bedrock -> cur.copy(
                    bedrockLaunchCount = cur.bedrockLaunchCount + 1,
                    bedrockPlayTimeSec = cur.bedrockPlayTimeSec + elapsedSec,
                    lastPlayedVersion = versionId,
                    lastPlayedMs = System.currentTimeMillis(),
                    totalCrashCount = cur.totalCrashCount + if (crashed) 1 else 0,
                    longestSessionSec = maxOf(cur.longestSessionSec, elapsedSec),
                )
            }
            _data.value = updated
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(updated), Charsets.UTF_8)
            }
        }
    }
}
