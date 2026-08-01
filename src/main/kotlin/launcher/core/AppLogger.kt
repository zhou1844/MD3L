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

    private val pending = ArrayBlockingQueue<LogLine>(8192)

    private val composeScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        val t = Thread({
            val batch = mutableListOf<LogLine>()
            while (true) {
                try {
                    batch.clear()
                    batch.add(pending.take())
                    pending.drainTo(batch, 199)
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

    fun log(category: LogCategory, tag: String, msg: String, isError: Boolean = false) {
        val time = LocalTime.now().format(timeFmt)
        msg.lines().forEach { line ->
            if (line.isBlank()) return@forEach
            val logLine = LogLine(time, tag, line, isError)
            pending.offer(logLine)
            writeToFile(category, time, tag, line, isError)
        }
    }

    fun log(tag: String, msg: String, isError: Boolean = false) {
        log(LogCategory.Launcher, tag, msg, isError)
    }

    fun clear() {
        pending.clear()
        composeScope.launch { lines.clear() }
    }

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
            FileWriter(logFile, Charsets.UTF_8, true).use { writer ->
                writer.write("$prefix $time [$tag] $msg\n")
            }
        } catch (_: Exception) {
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
