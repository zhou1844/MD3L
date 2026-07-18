# MD3L1 vs HMCL 启动性能对比分析

## 一、项目架构对比

| 维度 | HMCL (快速/流畅) | MD3L1 (慢/卡顿) |
|------|------------------|-------------------|
| **语言** | Java | Kotlin (JVM) |
| **UI框架** | JavaFX (原生C++渲染管线) | Compose Desktop (Skiko/Skia 软件渲染) |
| **项目结构** | 3模块: HMCLCore + HMCL + HMCLBoot | 单模块 (所有代码在一个 jar) |
| **并发模型** | Task/Scheduler + 专用Thread | Kotlin Coroutines + StateFlow |
| **HTTP客户端** | Java HttpURLConnection / HttpClient | curl.exe 子进程 (大部分请求) |
| **进程监控** | StreamPump (专用Thread + BufferedReader) | Coroutine forEachLine + BufferedWriter |

---

## 二、启动流程时间线对比

### HMCL 启动流程 (总耗时约 1-2秒)
```
main() → JavaFX Application.launch()
  → UI 立即渲染 (硬件加速)
  → 后台异步加载版本列表 (Task Scheduler)
  → 用户可立即交互
```

### MD3L1 启动流程 (总耗时约 5-8秒)
```
main() → SplashScreen动画 (2200ms 强制等待!)
  → AppSettings.load() on Dispatchers.IO (JSON解析 130+字段)
  → BundledRuntimeInstaller.ensureInstalled() (PowerShell子进程)
  → AutoUpdater.checkForUpdate() (curl.exe子进程!)
  → 背景图片预处理 (BufferedImage解码 on IO)
  → 导航模式预加载
  → System.setProperty("skiko.renderApi", "DIRECT3D11") + SOFTWARE fallback检测
  → splashFinished = true 后才渲染主UI
  → 用户才能交互
```

---

## 三、核心性能瓶颈分析

### 🔴 瓶颈1: SplashScreen 动画阻塞 (损失 ~2.2秒)

**文件**: [`SplashScreen.kt`](src/main/kotlin/launcher/ui/screens/SplashScreen.kt:66)

```kotlin
LaunchedEffect(Unit) {
    // 阶段1: Logo 淡入 (0-500ms)
    launch { logoAlpha.animateTo(1f, tween(500, ...)) }
    launch { logoOffsetY.animateTo(0f, tween(500, ...)) }
    // 阶段2: 进度环动画 (250ms后开始, ~1100ms)
    delay(250)
    launch { rotation.animateTo(360f, ...) }  // 无限旋转
    launch { sweepAngle.animateTo(270f, ...); sweepAngle.animateTo(0f, ...) }
    // 阶段3: 文字淡入 (700ms后开始)
    delay(700)
    textAlpha.animateTo(1f, tween(500, ...))
    // 等待动画完成
    delay(700)
    delay(300)  // 保持显示
    onAnimationEnd()  // ← 主UI在此之前完全不可见!
}
```

**总动画时长**: 2200ms (约2.2秒)，在此期间：
- 主UI窗口甚至不显示
- 用户看到的是 SplashScreen，不是主界面
- HMCL 没有 SplashScreen，直接显示主界面

**建议**: 将 SplashScreen 动画缩短到 800ms 以内，或改为窗口背景动画（不阻塞主UI渲染）。

---

### 🔴 瓶颈2: 启动时子进程过多 (损失 ~3-5秒)

MD3L1 在启动路径上大量使用 `curl.exe` / `powershell.exe` / `java.exe` 子进程：

| 调用位置 | 子进程 | 用途 | 耗时估算 |
|---------|--------|------|---------|
| [`AutoUpdater.kt:91`](src/main/kotlin/launcher/core/AutoUpdater.kt:91) | `curl.exe` | 检查更新 | 1-3秒 |
| [`AutoUpdater.kt:301`](src/main/kotlin/launcher/core/AutoUpdater.kt:301) | `curl.exe` | 测速镜像 | 1-2秒 |
| [`BundledRuntimeInstaller.kt:62`](src/main/kotlin/launcher/core/BundledRuntimeInstaller.kt:62) | `powershell.exe` | 检查Appx安装 | 0.5-1秒 |
| [`JavaLaunchEngine.kt:951`](src/main/kotlin/launcher/core/JavaLaunchEngine.kt:951) | `curl.exe` | 下载authlib-injector | 0.5-2秒 |
| [`JavaLaunchEngine.kt:1120`](src/main/kotlin/launcher/core/JavaLaunchEngine.kt:1120) | `java.exe -version` | 探测Java版本 | 0.3-0.5秒 |
| [`JavaLaunchEngine.kt:1376`](src/main/kotlin/launcher/core/JavaLaunchEngine.kt:1376) | `curl.exe` | 获取version manifest | 0.5-3秒 |

**每个子进程启动开销** (Windows): ~200-500ms 进程创建 + 命令解释器初始化。

**HMCL做法**: 使用纯Java HTTP客户端 (`HttpURLConnection`)，零子进程开销。

**建议**: 用 `java.net.HttpURLConnection` 或 Ktor CIO 客户端替换所有 `curl.exe` 调用。

---

### 🔴 瓶颈3: 启动时同步IO操作过多

**文件**: [`Main.kt`](src/main/kotlin/launcher/Main.kt)

```kotlin
fun main() {
    // 所有这些都在窗口显示前执行:
    AppLogger.installSystemStreams()                    // IO
    val settings = runBlocking { AppSettings.load() }   // IO阻塞! JSON解析130+字段
    BundledRuntimeInstaller.ensureInstalled()           // 子进程
    if (settings.checkUpdateOnStartup) {
        AutoUpdater.checkForUpdate()                    // 网络+子进程
    }
    preloadNavMode()                                     // IO
    // ... 背景图片解码 (BufferedImage)
    // ... 然后才 runLauncherApp()
}
```

**问题**:
- `AppSettings.load()` 有130+个字段，序列化/反序列化开销大
- `BundledRuntimeInstaller` 每次都检查 PowerShell（即使已安装）
- 更新检查无条件执行（用户可能不需要）

**HMCL做法**: 配置文件极简（~10个字段），无启动时网络请求，无运行时安装器。

**建议**:
1. `BundledRuntimeInstaller` 成功安装后写入标记文件，后续启动跳过
2. `AutoUpdater.checkForUpdate()` 延迟到UI渲染后 3 秒再执行
3. `AppSettings` 考虑分离热数据（启动必需）和冷数据（按需加载）

---

### 🔴 瓶颈4: JavaLaunchEngine 过于庞大 (1511行单类)

**文件**: [`JavaLaunchEngine.kt`](src/main/kotlin/launcher/core/JavaLaunchEngine.kt) (1511行)

单个 `execute()` 方法做了太多事情：
1. JSON解析版本文件
2. NeoForge完整性校验 + `runBlocking` 修复 (阻塞调用!)
3. 继承链解析 (递归读多个 JSON 文件)
4. classpath构建 (遍历所有library)
5. 客户端JAR修复 (SHA1校验+下载)
6. JAR Manifest注入 (重写整个JAR!)
7. 离线皮肤服务器启动 (Ktor Netty 内嵌服务器)
8. authlib-injector下载 (curl.exe子进程)
9. Java版本探测 (java -version子进程)
10. JVM参数构建
11. Game参数构建
12. 调试日志写入

其中 `preflightNeoForgeIntegrity()` 在第200行使用了 `runBlocking`:

```kotlin
val repaired = kotlinx.coroutines.runBlocking {
    LoaderInstaller.repairForgeIfNeeded(...)
}
```

**这是一个阻塞当前线程的调用！** 如果在 UI 线程上调用 `execute()`，会导致 ANR。

**HMCL做法**: DefaultLauncher 只负责构建命令行和启动进程，下载/安装逻辑由 LauncherHelper 的 Task 流程在上层处理。

**建议**: 
1. 拆分 JavaLaunchEngine 为多个职责单一的类
2. 将 `runBlocking` 替换为真正的 suspend 函数
3. 预下载 authlib-injector（首次安装时缓存，后续直接用）

---

### 🔴 瓶颈5: Compose Desktop 渲染管线开销

Compose Desktop 使用 Skiko (Skia for Kotlin) 做渲染：
- 默认使用软件渲染 (Software rasterizer)
- MD3L强制设置了 `DIRECT3D11`，但 Skia 的 D3D11 后端不如 JavaFX 的 Prism 成熟
- 每次 recomposition 需要遍历整个 Composable 树

**HMCL**: JavaFX 使用硬件加速的 Prism 渲染管线，基于原生 GPU 驱动。

[`LaunchScreen.kt`](src/main/kotlin/launcher/ui/screens/LaunchScreen.kt) 有 2081 行，是一个巨大的 Composable 函数，包含大量 `derivedStateOf`、`collectAsState`、`LaunchedEffect`，recomposition 成本极高。

**建议**:
1. 拆分 LaunchScreen 为更小的 Composable 组件
2. 对不常变化的状态使用 `remember` + key 缓存
3. 减少不必要的 `derivedStateOf` 嵌套

---

### 🟡 瓶颈6: 进程输出监控方式不同

**MD3L1** ([`GameProcessManager.kt`](src/main/kotlin/launcher/core/GameProcessManager.kt:78)):
```kotlin
process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
    writer?.append("[Game] ")?.append(line)?.append('\n')
    lineCount++
    if (lineCount % 50 == 0) writer?.flush()
    synchronized(lastLines) {
        lastLines.add(line)
        if (lastLines.size > 200) lastLines.removeAt(0)
    }
}
```

**HMCL** ([`StreamPump.java`](D:\HMCL-main\HMCLCore\src\main\java\org\jackhuang\hmcl\launch\StreamPump.java)):
```java
public void run() {
    try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, charset))) {
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            if (Thread.currentThread().isInterrupted()) { break; }
            callback.accept(line);
        }
    }
}
```

**差异**:
- MD3L1 每行都要写日志文件 (`append` 操作)，每50行 flush
- MD3L1 维护一个 synchronized 的 lastLines 列表（锁竞争）
- MD3L1 的 forEachLine 是基于协程的，当缓冲区满时可能背压
- HMCL 只是简单的 callback，没有额外的IO操作
- HMCL 有 interrupt 检测，可以被外部中断

**建议**: 日志写入改为异步批量写入（如每500ms或每200行flush一次），减少 synchronized 块的使用。

---

### 🟡 瓶颈7: 下载管理器架构

**MD3L1** ([`DownloadManager.kt`](src/main/kotlin/launcher/core/DownloadManager.kt)):
- Semaphore(64) 并发控制（较大）
- 每个下载手动处理 HTTP 重定向
- 使用 BMCLAPI 镜像转发
- SHA1 验证 + 最多3次重试

**HMCL**:
- 使用 Java NIO 通道和内置线程池
- 通过 DownloadTask 统一管理

两者差异不大，但 MD3L1 的手动重定向处理增加了代码复杂度和出错概率。

---

## 四、优化方案优先级

### P0 - 立即实施 (预估提升 60-70%)

| # | 优化项 | 预估收益 | 风险 |
|---|--------|---------|------|
| 1 | **去掉 curl.exe 子进程**：AutoUpdater、JavaLaunchEngine 中的 HTTP 请求改用 `HttpURLConnection` 或 Ktor CIO | **启动快 1-3秒** | 低 |
| 2 | **SplashScreen 动画缩短**：2200ms → 800ms，或者改为非阻塞（后台播放动画，主UI同时渲染） | **用户感知快 2秒** | 低 |
| 3 | **延迟非关键初始化**：`BundledRuntimeInstaller`、`AutoUpdater` 延迟到主UI渲染后 | **窗口出现快 1-2秒** | 低 |

### P1 - 本周实施 (预估额外提升 15-20%)

| # | 优化项 | 预估收益 | 风险 |
|---|--------|---------|------|
| 4 | **BundledRuntimeInstaller 标记文件**：安装成功后写 `.md3l_runtime_installed` 标记，后续跳过 PowerShell 查询 | **每次启动快 0.5-1秒** | 极低 |
| 5 | **authlib-injector 预缓存**：安装版本时预下载到 cache 目录，启动时直接使用 | **启动快 0.5-2秒** | 低 |
| 6 | **移除 `probeJavaMajorVersion` 子进程**：使用 `System.getProperty("java.version")` 或缓存结果 | **启动快 0.3-0.5秒** | 低 |

### P2 - 后续优化 (预估额外提升 10-15%)

| # | 优化项 | 预估收益 | 风险 |
|---|--------|---------|------|
| 7 | **拆分 JavaLaunchEngine**：分离 NeoForge 修复、skin server、classpath 构建为独立类 | **可维护性显著提升，间接性能提升** | 中 |
| 8 | **拆分 LaunchScreen.kt**：2081行拆分为多个 Composable 组件 | **recomposition 性能提升** | 中 |
| 9 | **AppSettings 瘦身**：热/冷数据分离，启动时只加载必需字段 | **配置加载快 30-50%** | 中 |
| 10 | **进程输出日志异步批量写入** | **游戏运行时管道更流畅** | 低 |

---

## 五、总结

MD3L1 启动慢的根本原因不是单一问题，而是**大量小开销叠加**：

1. **SplashScreen 动画强制等待 2.2秒** - HMCL 无此开销
2. **启动路径上 5+ 个子进程** - 每个 Windows 进程创建开销 ~200-500ms
3. **同步阻塞的初始化链** - settings → runtime → update → background image
4. **JavaLaunchEngine 什么都做** - 包括 `runBlocking` 阻塞调用
5. **Compose Desktop 本身比 JavaFX 重** - Skia 渲染 vs 原生 Prism

HMCL 之所以流畅，是因为它在每个环节都做了"减法"：
- 无 SplashScreen
- 无启动时网络请求
- 无子进程调用
- 简洁的配置文件
- 职责分明的模块架构
