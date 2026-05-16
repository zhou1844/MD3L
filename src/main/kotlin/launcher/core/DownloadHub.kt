package launcher.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 统一下载/安装任务中心。
 *
 * 把 Java 版本下载、Bedrock 下载、加载器安装等事件汇总到一个列表，
 * 供全局悬浮球和下载管理页面使用。
 */
object DownloadHub {

    enum class TaskType { JavaVersion, BedrockVersion, LoaderInstall, ResourceDownload }
    enum class TaskStatus { Running, Paused, Done, Error }

    data class HubTask(
        val id: String,
        val name: String,
        val type: TaskType,
        val status: TaskStatus = TaskStatus.Running,
        val step: String = "",
        val fraction: Float = 0f,
        val error: String = "",
    )

    private val _tasks = MutableStateFlow<List<HubTask>>(emptyList())
    val tasks: StateFlow<List<HubTask>> = _tasks.asStateFlow()

    private val pauseActions = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private val resumeActions = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private val closeActions = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private val hiddenTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 活跃任务数 */
    val activeCount: Int
        get() = _tasks.value.count { it.status == TaskStatus.Running }

    /** 全局平均进度 0..1 */
    val overallFraction: Float
        get() {
            val running = _tasks.value.filter { it.status == TaskStatus.Running }
            return if (running.isEmpty()) 0f
            else running.map { it.fraction }.average().toFloat()
        }

    fun upsert(task: HubTask) {
        if (task.id in hiddenTasks) {
            if (task.status == TaskStatus.Running) hiddenTasks.remove(task.id) else return
        }
        val cur = _tasks.value.toMutableList()
        val idx = cur.indexOfFirst { it.id == task.id }
        if (idx >= 0) cur[idx] = task else cur.add(0, task)
        _tasks.value = cur
        if (task.status != TaskStatus.Running && task.status != TaskStatus.Paused) {
            pauseActions.remove(task.id)
            resumeActions.remove(task.id)
            closeActions.remove(task.id)
        }
    }

    fun remove(id: String) {
        hiddenTasks.add(id)
        _tasks.value = _tasks.value.filter { it.id != id }
        pauseActions.remove(id)
        resumeActions.remove(id)
        closeActions.remove(id)
    }

    fun registerControls(id: String, onPause: (() -> Unit)? = null, onResume: (() -> Unit)? = null, onClose: (() -> Unit)? = null) {
        if (onPause != null) pauseActions[id] = onPause
        if (onResume != null) resumeActions[id] = onResume
        if (onClose != null) closeActions[id] = onClose
    }

    fun pause(id: String) {
        pauseActions[id]?.invoke()
    }

    fun resume(id: String) {
        resumeActions[id]?.invoke()
    }

    fun close(id: String) {
        closeActions[id]?.invoke()
            ?: remove(id)
    }

    /** 清除已完成/错误的任务 */
    fun clearFinished() {
        _tasks.value = _tasks.value.filter { it.status == TaskStatus.Running }
    }
}
