package launcher.core

import androidx.compose.runtime.mutableStateListOf
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue

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

    // 跨线程安全队列，由 drainLoop 统一刷入 Compose 状态
    private val pending = ArrayBlockingQueue<LogLine>(8192)

    init {
        // 后台 daemon 线程定期把 pending 刷入 SnapshotStateList（在 AWT EDT 上执行）
        val t = Thread({
            val batch = mutableListOf<LogLine>()
            while (true) {
                try {
                    batch.clear()
                    batch.add(pending.take())           // 阻塞等待第一条
                    pending.drainTo(batch, 199)         // 批量取最多 199 条
                    javax.swing.SwingUtilities.invokeLater {
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

    fun log(tag: String, msg: String, isError: Boolean = false) {
        val time = LocalTime.now().format(timeFmt)
        msg.lines().forEach { line ->
            if (line.isBlank()) return@forEach
            pending.offer(LogLine(time, tag, line, isError))
        }
    }

    fun clear() {
        pending.clear()
        javax.swing.SwingUtilities.invokeLater { lines.clear() }
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
                    log("SYS", s, false)
                } else buf.append(ch)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                val s = String(b, off, len)
                origOut.print(s)
                s.lines().forEach { line -> if (line.isNotBlank()) log("SYS", line, false) }
            }
        }, true, Charsets.UTF_8))

        System.setErr(PrintStream(object : OutputStream() {
            private val buf = StringBuilder()
            override fun write(b: Int) {
                val ch = b.toChar()
                if (ch == '\n') {
                    val s = buf.toString(); buf.clear()
                    origErr.println(s)
                    log("ERR", s, true)
                } else buf.append(ch)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                val s = String(b, off, len)
                origErr.print(s)
                s.lines().forEach { line -> if (line.isNotBlank()) log("ERR", line, true) }
            }
        }, true, Charsets.UTF_8))
    }
}
