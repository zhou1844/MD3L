package launcher.core

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue

/**
 * 日志分类枚举。
 * - [Launcher]：启动器自身日志（渲染事件、启动事件等），写入 <launcherDir>/log/
 * - [Java]：Java 版游戏相关日志，写入 <launcherDir>/log/Java/
 * - [Bedrock]：基岩版游戏相关日志，写入 <launcherDir>/log/bedrock/
 */
enum class LogCategory(val tag: String) {
    Launcher("LAUNCHER"),
    Java("JAVA"),
    Bedrock("BEDROCK"),
}

data class LogLine(
    val time: String,
    val tag: String,
    val text: String,
    val isError: Boolean = false,
)

object AppLogger {
    private const val MAX_LINES = 2000

    val lines = mutableStateListOf<LogLine>()

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // 跨线程安全队列，由 drainLoop 统一刷入 Compose 状态
    private val pending = ArrayBlockingQueue<LogLine>(8192)

    // Compose 协程作用域，用于在主线程更新状态
    private val composeScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        // 后台 daemon 线程定期把 pending 刷入 SnapshotStateList（使用 Compose Dispatchers.Main）
        val t = Thread({
            val batch = mutableListOf<LogLine>()
            while (true) {
                try {
                    batch.clear()
                    batch.add(pending.take())           // 阻塞等待第一条
                    pending.drainTo(batch, 199)         // 批量取最多 199 条
                    composeScope.launch {
                        for (line in batch) {
                            if (lines.size >= MAX_LINES) lines.removeAt(0)
                            lines.add(line)
                        }
                    }
                } catch (_: InterruptedException) { break }
            }
        }, "AppLogger-drain")
        t.isDaemon = true
        t.start()
    }

    /**
     * 记录日志到内存队列，同时写入对应的文件日志。
     *
     * @param category 日志分类，决定写入哪个子目录
     * @param tag 日志标签（显示在 UI 上）
     * @param msg 日志内容
     * @param isError 是否为错误级别
     */
    fun log(category: LogCategory, tag: String, msg: String, isError: Boolean = false) {
        val time = LocalTime.now().format(timeFmt)
        msg.lines().forEach { line ->
            if (line.isBlank()) return@forEach
            val logLine = LogLine(time, tag, line, isError)
            pending.offer(logLine)
            // 同步写入文件日志
            writeToFile(category, time, tag, line, isError)
        }
    }

    /**
     * 向后兼容：无分类的日志默认写入 Launcher 分类。
     */
    fun log(tag: String, msg: String, isError: Boolean = false) {
        log(LogCategory.Launcher, tag, msg, isError)
    }

    fun clear() {
        pending.clear()
        composeScope.launch { lines.clear() }
    }

    /**
     * 将日志行写入对应的文件。
     * 文件按日期轮转：<logDir>/<category>/yyyy-MM-dd.log
     */
    private fun writeToFile(category: LogCategory, time: String, tag: String, msg: String, isError: Boolean) {
        try {
            val logDir = when (category) {
                LogCategory.Launcher -> LauncherDirs.logDir
                LogCategory.Java -> LauncherDirs.javaLogDir
                LogCategory.Bedrock -> LauncherDirs.bedrockLogDir
            }
            val dateStr = LocalDate.now().format(dateFmt)
            val logFile = File(logDir, "$dateStr.log")
            val prefix = if (isError) "[ERR]" else "[INF]"
            // 使用 FileWriter 追加写入，UTF-8
            FileWriter(logFile, Charsets.UTF_8, true).use { writer ->
                writer.write("$prefix $time [$tag] $msg\n")
            }
        } catch (_: Exception) {
            // 文件写入失败不抛出异常，避免影响主流程
        }
    }

    fun installSystemStreams() {
        val origOut = System.out
        val origErr = System.err

        System.setOut(PrintStream(object : OutputStream() {
            private val buf = StringBuilder()
            override fun write(b: Int) {
                val ch = b.toChar()
                if (ch == '\n') {
                    val s = buf.toString(); buf.clear()
                    origOut.println(s)
                    log(LogCategory.Launcher, "SYS", s, false)
                } else buf.append(ch)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                val s = String(b, off, len)
                origOut.print(s)
                s.lines().forEach { line -> if (line.isNotBlank()) log(LogCategory.Launcher, "SYS", line, false) }
            }
        }, true, Charsets.UTF_8))

        System.setErr(PrintStream(object : OutputStream() {
            private val buf = StringBuilder()
            override fun write(b: Int) {
                val ch = b.toChar()
                if (ch == '\n') {
                    val s = buf.toString(); buf.clear()
                    origErr.println(s)
                    log(LogCategory.Launcher, "ERR", s, true)
                } else buf.append(ch)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                val s = String(b, off, len)
                origErr.print(s)
                s.lines().forEach { line -> if (line.isNotBlank()) log(LogCategory.Launcher, "ERR", line, true) }
            }
        }, true, Charsets.UTF_8))
    }
}
