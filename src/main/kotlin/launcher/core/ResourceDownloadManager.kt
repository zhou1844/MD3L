package launcher.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ResourceDownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    fun launch(
        name: String,
        url: String,
        dest: File,
        size: Long = -1L,
        onFinished: (Boolean, File) -> Unit = { _, _ -> },
    ) {
        val taskId = "resource_${dest.absolutePath.hashCode()}_${System.currentTimeMillis()}"
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        DownloadHub.upsert(DownloadHub.HubTask(
            id = taskId,
            name = name,
            type = DownloadHub.TaskType.ResourceDownload,
            step = "准备下载 ${dest.name}",
            fraction = 0f,
        ))

        fun pauseTask() {
            jobs[taskId]?.cancel(CancellationException("资源下载已暂停"))
            jobs.remove(taskId)
            DownloadHub.upsert(DownloadHub.HubTask(
                id = taskId,
                name = name,
                type = DownloadHub.TaskType.ResourceDownload,
                status = DownloadHub.TaskStatus.Paused,
                step = "已暂停，点击右侧继续按钮恢复下载",
                fraction = if (size > 0 && dest.exists()) (dest.length().toFloat() / size).coerceIn(0f, 1f) else 0f,
            ))
        }

        fun closeTask() {
            jobs[taskId]?.cancel(CancellationException("资源下载已关闭"))
            jobs.remove(taskId)
            dest.delete()
            DownloadHub.remove(taskId)
        }

        DownloadHub.registerControls(
            taskId,
            onPause = ::pauseTask,
            onResume = { launch(name, url, dest, size, onFinished) },
            onClose = ::closeTask,
        )

        val job = scope.launch {
            try {
                val ok = DownloadManager.downloadSingle(DownloadTask(url, dest, size = size)) { downloaded, total, speed ->
                    val fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                    val mb = downloaded / 1_048_576.0
                    val totalText = if (total > 0) " / ${"%.1f".format(total / 1_048_576.0)} MB" else ""
                    val speedText = "${"%.1f".format(speed / 1_048_576.0)} MB/s"
                    DownloadHub.upsert(DownloadHub.HubTask(
                        id = taskId,
                        name = name,
                        type = DownloadHub.TaskType.ResourceDownload,
                        step = "下载中 ${"%.1f".format(mb)} MB$totalText · $speedText",
                        fraction = fraction,
                    ))
                }
                val valid = ok && dest.exists() && dest.length() > 0 && (size <= 0 || dest.length() >= size * 0.95)
                if (!valid) dest.delete()
                DownloadHub.upsert(DownloadHub.HubTask(
                    id = taskId,
                    name = name,
                    type = DownloadHub.TaskType.ResourceDownload,
                    status = if (valid) DownloadHub.TaskStatus.Done else DownloadHub.TaskStatus.Error,
                    step = if (valid) "下载完成: ${dest.name}" else "下载失败",
                    fraction = if (valid) 1f else 0f,
                    error = if (valid) "" else "下载失败或文件不完整",
                ))
                onFinished(valid, dest)
            } catch (_: CancellationException) {
                onFinished(false, dest)
            } catch (e: Exception) {
                dest.delete()
                DownloadHub.upsert(DownloadHub.HubTask(
                    id = taskId,
                    name = name,
                    type = DownloadHub.TaskType.ResourceDownload,
                    status = DownloadHub.TaskStatus.Error,
                    step = "下载失败: ${e.message}",
                    error = e.message ?: "",
                ))
                onFinished(false, dest)
            } finally {
                jobs.remove(taskId)
            }
        }
        jobs[taskId] = job
    }
}
