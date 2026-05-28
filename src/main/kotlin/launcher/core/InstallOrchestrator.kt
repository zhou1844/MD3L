package launcher.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * 全局安装编排器 —— 在全局 scope 中运行，切换页面绝不中断。
 *
 * 流程：
 *   1. 将 vanilla 下载推送到 DownloadHub，桥接 DownloadManager.progress 实时更新
 *   2. vanilla 完成后，自动启动加载器安装（若有）
 */
object InstallOrchestrator {

    private val globalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class InstallRequest(
        val version: RemoteVersion,
        val minecraftDir: String,
        val customName: String,
        val maxThreads: Int,
        val javaPath: String = "java",
        val loaderType: String? = null,     // "Fabric" / "Forge" / "NeoForge"
        val loaderVersion: String? = null,
        val forgeBuild: Int = 0,
        val optifineVersion: String? = null, // e.g. "HD_U_I7"
    )

    /**
     * 启动安装（全局 scope）。立即返回，不阻塞。
     */
    fun launch(req: InstallRequest) {
        val taskId = "java_${req.version.id}_${System.currentTimeMillis()}"
        val taskName = "${req.version.id}${if (req.loaderType != null) " + ${req.loaderType}" else ""}"

        lateinit var installJob: Job
        installJob = globalScope.launch {
            // ── 推送初始状态 ────────────────────────────────────────
            DownloadHub.upsert(DownloadHub.HubTask(
                id = taskId, name = taskName,
                type = DownloadHub.TaskType.JavaVersion,
                step = "正在下载 ${req.version.id}…", fraction = 0f,
            ))
            DownloadHub.registerControls(
                id = taskId,
                onPause = {
                    installJob.cancel(CancellationException("安装已暂停"))
                    DownloadManager.cancel()
                    DownloadHub.upsert(DownloadHub.HubTask(
                        id = taskId, name = taskName,
                        type = DownloadHub.TaskType.JavaVersion,
                        status = DownloadHub.TaskStatus.Paused,
                        step = "已暂停，点击右侧继续按钮恢复安装",
                        fraction = DownloadManager.progress.value.fraction,
                    ))
                },
                onResume = { launch(req) },
                onClose = {
                    installJob.cancel(CancellationException("安装已关闭"))
                    DownloadManager.cancel()
                    DownloadHub.remove(taskId)
                },
            )

            // ── 桥接 DownloadManager.progress → DownloadHub ───────
            val bridgeJob = launch {
                DownloadManager.progress.collectLatest { p ->
                    if (p.isRunning) {
                        DownloadHub.upsert(DownloadHub.HubTask(
                            id = taskId, name = taskName,
                            type = DownloadHub.TaskType.JavaVersion,
                            step = "下载中 ${p.completedFiles}/${p.totalFiles} · ${p.speedMbps}",
                            fraction = p.fraction,
                        ))
                    }
                }
            }

            // ── Step 1: 安装原版 ──────────────────────────────────
            val ok = try {
                VersionManifest.installVersion(
                    version = req.version,
                    minecraftDir = req.minecraftDir,
                    customName = req.customName,
                    maxThreads = req.maxThreads,
                )
            } catch (e: CancellationException) {
                bridgeJob.cancel()
                return@launch
            } catch (e: Exception) {
                bridgeJob.cancel()
                DownloadHub.upsert(DownloadHub.HubTask(
                    id = taskId, name = taskName,
                    type = DownloadHub.TaskType.JavaVersion,
                    status = DownloadHub.TaskStatus.Error,
                    step = "下载出错: ${e.message}", error = e.message ?: "",
                ))
                return@launch
            }
            bridgeJob.cancel()

            if (!ok) {
                DownloadHub.upsert(DownloadHub.HubTask(
                    id = taskId, name = taskName,
                    type = DownloadHub.TaskType.JavaVersion,
                    status = DownloadHub.TaskStatus.Error,
                    step = "原版安装失败", error = "下载失败",
                ))
                return@launch
            }

            // 原版安装完成 —— 检查是否安装 OptiFine
            if (req.optifineVersion != null && req.loaderType == null) {
                DownloadHub.upsert(DownloadHub.HubTask(
                    id = taskId, name = taskName,
                    type = DownloadHub.TaskType.JavaVersion,
                    step = "原版完成，正在安装 OptiFine…", fraction = 0.6f,
                ))
                try {
                    val resolvedJava = withContext(Dispatchers.IO) {
                        JavaManager.resolveJavaForLaunch(req.version.id, req.javaPath) { msg ->
                            DownloadHub.upsert(DownloadHub.HubTask(id = taskId, name = taskName, type = DownloadHub.TaskType.JavaVersion, step = "准备 Java: $msg", fraction = 0.65f))
                        }
                    }
                    LoaderInstaller.installOptiFine(
                        mcVersion = req.version.id,
                        optifineVersion = req.optifineVersion,
                        minecraftDir = req.minecraftDir,
                        baseVersionId = req.customName,
                        javaPath = resolvedJava,
                    )
                    DownloadHub.upsert(DownloadHub.HubTask(id = taskId, name = taskName, type = DownloadHub.TaskType.JavaVersion, status = DownloadHub.TaskStatus.Done, step = "全部安装完成", fraction = 1f))
                } catch (e: Exception) {
                    DownloadHub.upsert(DownloadHub.HubTask(id = taskId, name = taskName, type = DownloadHub.TaskType.JavaVersion, status = DownloadHub.TaskStatus.Error, step = "原版已装，OptiFine 失败: ${e.message}", error = e.message ?: ""))
                }
                return@launch
            }
            if (req.loaderType == null) {
                DownloadHub.upsert(DownloadHub.HubTask(
                    id = taskId, name = taskName,
                    type = DownloadHub.TaskType.JavaVersion,
                    status = DownloadHub.TaskStatus.Done,
                    step = "安装完成", fraction = 1f,
                ))
                return@launch
            }

            // ── Step 2: 安装加载器 ────────────────────────────────
            DownloadHub.upsert(DownloadHub.HubTask(
                id = taskId, name = taskName,
                type = DownloadHub.TaskType.JavaVersion,
                step = "原版完成，正在安装 ${req.loaderType}…", fraction = 0.5f,
            ))

            val resolvedJavaPath = if (req.loaderType == "Forge" || req.loaderType == "NeoForge") {
                try {
                    JavaManager.resolveJavaForLaunch(req.version.id, req.javaPath) { msg ->
                        DownloadHub.upsert(DownloadHub.HubTask(
                            id = taskId,
                            name = taskName,
                            type = DownloadHub.TaskType.JavaVersion,
                            step = "准备 Java: $msg",
                            fraction = 0.55f,
                        ))
                    }
                } catch (e: Exception) {
                    DownloadHub.upsert(DownloadHub.HubTask(
                        id = taskId,
                        name = taskName,
                        type = DownloadHub.TaskType.JavaVersion,
                        status = DownloadHub.TaskStatus.Error,
                        step = "安装 ${req.loaderType} 前 Java 准备失败: ${e.message}",
                        error = e.message ?: "",
                    ))
                    return@launch
                }
            } else {
                req.javaPath
            }

            // 加载器通过 LoaderInstaller 自己的 emit 推送到 DownloadHub
            try {
                when (req.loaderType) {
                    "Fabric" -> LoaderInstaller.installFabric(
                        mcVersion = req.version.id,
                        loaderVersion = req.loaderVersion!!,
                        minecraftDir = req.minecraftDir,
                        maxThreads = req.maxThreads,
                    )
                    "Forge" -> LoaderInstaller.installForge(
                        mcVersion = req.version.id,
                        loaderVersion = req.loaderVersion!!,
                        forgeBuild = req.forgeBuild,
                        minecraftDir = req.minecraftDir,
                        baseVersionId = req.customName,
                        javaPath = resolvedJavaPath,
                    )
                    "NeoForge" -> LoaderInstaller.installNeoForge(
                        mcVersion = req.version.id,
                        loaderVersion = req.loaderVersion!!,
                        minecraftDir = req.minecraftDir,
                        baseVersionId = req.customName,
                        javaPath = resolvedJavaPath,
                    )
                }
                // 加载器安装完成后，若同时选了 OptiFine 则继续安装
                if (req.optifineVersion != null) {
                    DownloadHub.upsert(DownloadHub.HubTask(
                        id = taskId, name = taskName,
                        type = DownloadHub.TaskType.JavaVersion,
                        step = "${req.loaderType} 完成，正在安装 OptiFine…", fraction = 0.9f,
                    ))
                    val resolvedJava = withContext(Dispatchers.IO) {
                        JavaManager.resolveJavaForLaunch(req.version.id, req.javaPath) { }
                    }
                    LoaderInstaller.installOptiFine(
                        mcVersion = req.version.id,
                        optifineVersion = req.optifineVersion,
                        minecraftDir = req.minecraftDir,
                        baseVersionId = req.customName,
                        javaPath = resolvedJava,
                    )
                }
                DownloadHub.upsert(DownloadHub.HubTask(
                    id = taskId, name = taskName,
                    type = DownloadHub.TaskType.JavaVersion,
                    status = DownloadHub.TaskStatus.Done,
                    step = "全部安装完成", fraction = 1f,
                ))
            } catch (e: Exception) {
                DownloadHub.upsert(DownloadHub.HubTask(
                    id = taskId, name = taskName,
                    type = DownloadHub.TaskType.JavaVersion,
                    status = DownloadHub.TaskStatus.Error,
                    step = "原版已装，${req.loaderType} 失败: ${e.message}",
                    error = e.message ?: "",
                ))
            }
        }
    }
}
