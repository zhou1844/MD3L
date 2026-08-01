package launcher.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import kotlin.io.path.*


object MultiplayerManager {

    enum class State {
        Uninitialized,
        Downloading,
        Installing,
        Launching,
        Unknown,
        Idle,
        HostScanning,
        HostOK,
        GuestConnecting,
        GuestOK,
        Exception,
        Fatal,
    }

    data class PlayerProfile(
        val name: String,
        val vendor: String = "",
        val type: String = "guest",
    )

    data class RoomInfo(
        val code: String,
        val profiles: List<PlayerProfile> = emptyList(),
        val profileIndex: Int = 0,
    )

    @Serializable
    data class TerracottaConfig(
        val version_latest: String = "",
        val packages: Map<String, TerracottaPackage> = emptyMap(),
        val downloads: List<String> = emptyList(),
        val downloads_CN: List<String> = emptyList(),
    )

    @Serializable
    data class TerracottaPackage(
        val hash: String = "",
        val files: Map<String, String> = emptyMap(),
    )

    private val _state = MutableStateFlow(State.Uninitialized)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _roomInfo = MutableStateFlow<RoomInfo?>(null)
    val roomInfo: StateFlow<RoomInfo?> = _roomInfo.asStateFlow()

    private val _serverAddress = MutableStateFlow("")
    val serverAddress: StateFlow<String> = _serverAddress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var terracottaProcess: Process? = null
    private var terracottaPort: Int = -1
    private var statePollJob: Job? = null
    private var daemonActive = false

    private val terracottaDir: Path by lazy {
        LauncherDirs.dataDir.toPath().resolve("terracotta").also { Files.createDirectories(it) }
    }

    private var config: TerracottaConfig? = null
    private var currentExe: Path? = null
    private val currentClassifier: String by lazy { detectClassifier() }

    private val json = Json { ignoreUnknownKeys = true }

    fun initialize() {
        scope.launch {
            try {
                config = loadConfig()
                if (config == null || config!!.packages.isEmpty()) {
                    _state.value = State.Fatal
                    _errorMessage.value = "不支持的平台：${currentClassifier}"
                    return@launch
                }

                val exePath = findLocalExecutable()
                if (exePath != null) {
                    currentExe = exePath
                    launchTerracotta()
                } else {
                    _state.value = State.Uninitialized
                    _statusMessage.value = "Terracotta 尚未安装，请点击下载"
                }
            } catch (e: Exception) {
                _state.value = State.Fatal
                _errorMessage.value = "初始化失败: ${e.message}"
            }
        }
    }

    fun startDownload() {
        if (_state.value != State.Uninitialized && _state.value != State.Fatal) return
        _state.value = State.Downloading
        _errorMessage.value = null
        _statusMessage.value = "正在下载 Terracotta..."
        _downloadProgress.value = 0f

        scope.launch {
            try {
                val cfg = config ?: return@launch
                val pkg = cfg.packages[currentClassifier] ?: return@launch

                val urls = buildDownloadUrls(cfg)
                val tmpFile = Files.createTempFile("terracotta-", ".tar.gz")

                var downloaded = false
                for ((idx, url) in urls.withIndex()) {
                    try {
                        downloadFile(url, tmpFile, pkg.hash)
                        downloaded = true
                        break
                    } catch (e: Exception) {
                        if (idx == urls.lastIndex) throw e
                        _statusMessage.value = "下载源 $idx 失败，尝试备用地址..."
                    }
                }

                if (!downloaded) throw IOException("所有下载源均失败")

                _state.value = State.Installing
                _statusMessage.value = "正在解压 Terracotta..."
                installBundle(tmpFile, pkg)

                val exePath = findLocalExecutable()
                if (exePath != null) {
                    currentExe = exePath
                    launchTerracotta()
                } else {
                    _state.value = State.Fatal
                    _errorMessage.value = "安装完成但找不到可执行文件"
                }
            } catch (e: Exception) {
                _state.value = State.Fatal
                _errorMessage.value = "下载/安装失败: ${e.message}"
            }
        }
    }

    fun createRoom() {
        scope.launch {
            try {
                _errorMessage.value = null

                val nodes = fetchNodeList()
                val playerName = getPlayerName()

                val query = buildString {
                    append("?player=").append(java.net.URLEncoder.encode(playerName, "UTF-8"))
                    for (node in nodes) {
                        append("&public_nodes=").append(java.net.URLEncoder.encode(node, "UTF-8"))
                    }
                }

                httpGet("/state/scanning$query")

                _state.value = State.HostScanning
                _statusMessage.value = "正在扫描中继节点..."
            } catch (e: Exception) {
                _errorMessage.value = "创建房间失败: ${e.message}"
                _state.value = State.Exception
            }
        }
    }

    fun kickPlayer(playerName: String) {
        if (playerName.isBlank()) return
        scope.launch {
            try {
                _errorMessage.value = null
                val encoded = java.net.URLEncoder.encode(playerName, "UTF-8")
                httpPost("/state/kick", "player=$encoded")
                _statusMessage.value = "已踢出玩家: $playerName"
            } catch (e: Exception) {
                _errorMessage.value = "踢出玩家失败: ${e.message}"
            }
        }
    }

    fun cancelToIdle() {
        scope.launch {
            try {
                httpGet("/state/ide")
                _state.value = State.Idle
                _statusMessage.value = "就绪 — 请选择创建或加入房间"
                _errorMessage.value = null
                _roomInfo.value = null
                _serverAddress.value = ""
            } catch (_: Exception) {
                reset()
            }
        }
    }

    fun joinRoom(code: String) {
        if (code.isBlank()) {
            _errorMessage.value = "房间码不能为空"
            return
        }
        scope.launch {
            try {
                _errorMessage.value = null

                val nodes = fetchNodeList()
                val playerName = getPlayerName()

                val query = buildString {
                    append("?room=").append(java.net.URLEncoder.encode(code, "UTF-8"))
                    append("&player=").append(java.net.URLEncoder.encode(playerName, "UTF-8"))
                    for (node in nodes) {
                        append("&public_nodes=").append(java.net.URLEncoder.encode(node, "UTF-8"))
                    }
                }

                httpGet("/state/guesting$query")

                _state.value = State.GuestConnecting
                _statusMessage.value = "正在加入房间 $code ..."
            } catch (e: Exception) {
                _errorMessage.value = "加入房间失败: ${e.message}"
                _state.value = State.Exception
            }
        }
    }

    fun setWaiting() {
        scope.launch {
            try {
                httpGet("/state/ide")
                _state.value = State.Idle
                _statusMessage.value = "就绪 — 请选择创建或加入房间"
            } catch (_: Exception) { }
        }
    }

    fun getQuickPlayArgs(): List<String> {
        val addr = _serverAddress.value
        if (addr.isBlank()) return emptyList()
        return listOf("--quickPlayMultiplayer", addr)
    }

    fun reset() {
        statePollJob?.cancel()
        terracottaProcess?.destroy()
        terracottaProcess = null
        terracottaPort = -1
        _state.value = State.Uninitialized
        _statusMessage.value = ""
        _errorMessage.value = null
        _roomInfo.value = null
        _serverAddress.value = ""
    }

    fun recover() {
        scope.launch {
            try {
                val exePath = findLocalExecutable()
                if (exePath != null) {
                    currentExe = exePath
                    launchTerracotta()
                } else {
                    startDownload()
                }
            } catch (e: Exception) {
                _state.value = State.Fatal
                _errorMessage.value = "恢复失败: ${e.message}"
            }
        }
    }


    private suspend fun launchTerracotta() {
        _state.value = State.Launching
        _statusMessage.value = "正在启动 Terracotta..."

        withContext(Dispatchers.IO) {
            try {
                val exe = currentExe ?: throw IOException("可执行文件路径为空")
                val portFile = Files.createTempFile("hmcl-terracotta-", ".tmp")

                val pb = ProcessBuilder(exe.toString(), "--hmcl", portFile.toString())
                pb.directory(terracottaDir.toFile())
                pb.redirectErrorStream(true)

                terracottaProcess = pb.start()

                val deadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < deadline) {
                    if (Files.exists(portFile) && Files.size(portFile) > 0) {
                        val content = Files.readString(portFile).trim()
                        val jsonObj = json.decodeFromString<kotlinx.serialization.json.JsonObject>(content)
                        terracottaPort = jsonObj["port"]?.toString()?.toIntOrNull()
                            ?: content.toIntOrNull()
                            ?: throw IOException("无法解析端口: $content")
                        break
                    }
                    if (!terracottaProcess!!.isAlive) {
                        throw IOException("Terracotta 进程已退出，退出码: ${terracottaProcess!!.exitValue()}")
                    }
                    delay(200)
                }

                if (terracottaPort <= 0) {
                    throw IOException("无法获取 Terracotta 端口")
                }

                _state.value = State.Unknown
                _statusMessage.value = "Terracotta 已启动，端口: $terracottaPort"

                startStatePolling()
            } catch (e: Exception) {
                _state.value = State.Fatal
                _errorMessage.value = "启动 Terracotta 失败: ${e.message}"
            }
        }
    }

    private fun startStatePolling() {
        statePollJob?.cancel()
        daemonActive = true
        statePollJob = scope.launch {
            var lastIndex = -1
            while (isActive && daemonActive && terracottaPort > 0) {
                try {
                    val stateJson = httpGet("/state")
                    if (stateJson == null) {
                        delay(500)
                        continue
                    }

                    val obj = json.decodeFromString<kotlinx.serialization.json.JsonObject>(stateJson)
                    val stateName = obj["state"]?.toString()?.trim('"') ?: ""
                    val index = (obj["index"]?.toString()?.toIntOrNull() ?: 0)

                    if (index <= lastIndex) {
                        delay(500)
                        continue
                    }
                    lastIndex = index

                    when (stateName) {
                        "waiting" -> {
                            _state.value = State.Idle
                            _statusMessage.value = "就绪 — 请选择创建或加入房间"
                        }
                        "host-scanning" -> {
                            _state.value = State.HostScanning
                            _statusMessage.value = "正在扫描中继节点..."
                        }
                        "host-starting" -> {
                            _statusMessage.value = "正在创建房间..."
                        }
                        "host-ok" -> {
                            _state.value = State.HostOK
                            val code = obj["room"]?.toString()?.trim('"') ?: ""
                            val profiles = parseProfiles(obj)
                            _roomInfo.value = RoomInfo(code = code, profiles = profiles)
                            _statusMessage.value = "房间已创建！房间码: $code"
                        }
                        "guest-connecting" -> {
                            _statusMessage.value = "正在连接房间..."
                        }
                        "guest-starting" -> {
                            _statusMessage.value = "正在建立连接..."
                        }
                        "guest-ok" -> {
                            _state.value = State.GuestOK
                            val url = obj["url"]?.toString()?.trim('"') ?: ""
                            _serverAddress.value = url
                            _statusMessage.value = "已连接到房间！可以启动游戏"
                        }
                        "exception" -> {
                            _state.value = State.Exception
                            val type = obj["type"]?.toString()?.toIntOrNull() ?: -1
                            val exceptionTypes = listOf(
                                "PING_HOST_FAIL", "PING_HOST_RST", "GUEST_ET_CRASH",
                                "HOST_ET_CRASH", "PING_SERVER_RST", "SCAFFOLDING_INVALID_RESPONSE"
                            )
                            val typeName = exceptionTypes.getOrElse(type) { "UNKNOWN" }
                            _errorMessage.value = "Terracotta 异常: $typeName"
                        }
                    }
                } catch (_: Exception) {
                }
                delay(500)
            }
        }
    }

    private suspend fun downloadFile(url: String, dest: Path, expectedHash: String) {
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "MD3L/1.1")

            val totalBytes = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-512")

            connection.inputStream.use { input ->
                DigestInputStream(input, digest).use { dis ->
                    Files.newOutputStream(dest).use { output ->
                        val buffer = ByteArray(8192)
                        var read = 0L
                        var n: Int
                        while (dis.read(buffer).also { n = it } != -1) {
                            output.write(buffer, 0, n)
                            read += n
                            if (totalBytes > 0) {
                                _downloadProgress.value = read.toFloat() / totalBytes.toFloat()
                            }
                        }
                    }
                }
            }

            val actualHash = HexFormat.of().formatHex(digest.digest())
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                Files.deleteIfExists(dest)
                throw IOException("SHA-512 校验失败！\n期望: $expectedHash\n实际: $actualHash")
            }
        }
    }

    private suspend fun installBundle(tarGz: Path, pkg: TerracottaPackage) {
        withContext(Dispatchers.IO) {
            Files.createDirectories(terracottaDir)

            GZIPInputStream(Files.newInputStream(tarGz)).use { gz ->
                val expectedFiles = pkg.files.keys.toSet()
                val extracted = mutableSetOf<String>()

                val buffer = ByteArray(8192)
                while (true) {
                    val header = ByteArray(512)
                    var read = 0
                    while (read < 512) {
                        val n = gz.read(header, read, 512 - read)
                        if (n == -1) break
                        read += n
                    }
                    if (read < 512) break

                    if (header.all { it == 0.toByte() }) {
                        val nextBlock = ByteArray(512)
                        var nextRead = 0
                        while (nextRead < 512) {
                            val n = gz.read(nextBlock, nextRead, 512 - nextRead)
                            if (n == -1) break
                            nextRead += n
                        }
                        if (nextRead < 512 || nextBlock.all { it == 0.toByte() }) break
                    }

                    val fileName = String(header, 0, 100).trimEnd('\u0000')
                    val fileSizeStr = String(header, 124, 12).trimEnd('\u0000')
                    val fileSize = fileSizeStr.toLongOrNull(8) ?: 0L

                    if (fileName.isEmpty()) continue

                    val baseName = fileName.substringAfterLast("/").ifBlank { fileName }
                    if (baseName !in expectedFiles) {
                        val skipBlocks = ((fileSize + 511) / 512).toInt()
                        gz.skipNBytes((skipBlocks * 512).toLong())
                        continue
                    }

                    val dest = terracottaDir.resolve(baseName)
                    Files.newOutputStream(dest).use { out ->
                        var remaining = fileSize
                        while (remaining > 0) {
                            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                            val n = gz.read(buffer, 0, toRead)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                            remaining -= n
                        }
                    }

                    val padding = (512 - (fileSize % 512)) % 512
                    if (padding > 0) gz.skipNBytes(padding)

                    val expectedHash = pkg.files[baseName] ?: continue
                    val actualHash = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-512").digest(Files.readAllBytes(dest))
                    )
                    if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                        throw IOException("文件 $baseName SHA-512 校验失败")
                    }
                    extracted.add(baseName)

                    if (baseName.endsWith(".exe") || !baseName.contains(".")) {
                        dest.toFile().setExecutable(true)
                    }
                }

                if (extracted.size < expectedFiles.size) {
                    throw IOException("缺少文件: ${expectedFiles - extracted}")
                }
            }

            Files.deleteIfExists(tarGz)
        }
    }

    private suspend fun httpGet(path: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://127.0.0.1:$terracottaPort$path")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 5000
                connection.inputStream.bufferedReader().readText()
            } catch (_: Exception) { null }
        }
    }

    private suspend fun httpPost(path: String, body: String) {
        withContext(Dispatchers.IO) {
            val url = URL("http://127.0.0.1:$terracottaPort$path")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.responseCode
        }
    }

    private suspend fun fetchNodeList(): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://terracotta.glavo.site/nodes")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                val body = connection.inputStream.bufferedReader().readText()
                val arr = json.decodeFromString<kotlinx.serialization.json.JsonArray>(body)
                arr.mapNotNull { el ->
                    val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val nodeUrl = obj["url"]?.toString()?.trim('"') ?: return@mapNotNull null
                    val region = obj["region"]?.toString()?.trim('"')
                    if (region.isNullOrBlank() || region.equals("CN", ignoreCase = true)) nodeUrl else null
                }
            } catch (_: Exception) { emptyList() }
        }
    }

    private fun buildDownloadUrls(cfg: TerracottaConfig): List<String> {
        val version = cfg.version_latest
        val classifier = currentClassifier
        val primary = cfg.downloads.map { it.replace("\${version}", version).replace("\${classifier}", classifier) }
        val cn = cfg.downloads_CN.map { it.replace("\${version}", version).replace("\${classifier}", classifier) }
        val isCN = java.util.Locale.getDefault().country == "CN"
        return if (isCN) cn + primary else primary + cn
    }

    private fun detectClassifier(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val osName = when {
            os.contains("win") -> "windows"
            os.contains("linux") -> "linux"
            os.contains("mac") || os.contains("darwin") -> "macos"
            else -> os
        }
        val archName = when {
            arch.contains("amd64") || arch.contains("x86_64") -> "x86_64"
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            else -> arch
        }
        return "$osName-$archName"
    }

    private fun findLocalExecutable(): Path? {
        val cfg = config ?: return null
        val pkg = cfg.packages[currentClassifier] ?: return null
        for (fileName in pkg.files.keys) {
            if (fileName.endsWith(".exe") || (!fileName.contains(".") && !fileName.endsWith(".dll"))) {
                val path = terracottaDir.resolve(fileName)
                if (Files.isRegularFile(path) && path.toFile().canExecute()) {
                    return path
                }
                if (Files.isRegularFile(path) && !fileName.endsWith(".dll", ignoreCase = true)) {
                    path.toFile().setExecutable(true)
                    return path
                }
            }
        }
        return null
    }

    private fun loadConfig(): TerracottaConfig? {
        return try {
            val stream = MultiplayerManager::class.java.classLoader.getResourceAsStream("terracotta.json")
                ?: return null
            val text = stream.bufferedReader().readText()
            val cfg = json.decodeFromString<TerracottaConfig>(text)
            if (cfg.packages.containsKey(currentClassifier)) cfg else null
        } catch (_: Exception) { null }
    }

    private fun parseProfiles(obj: kotlinx.serialization.json.JsonObject): List<PlayerProfile> {
        return try {
            val arr = obj["profiles"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val p = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                PlayerProfile(
                    name = p["name"]?.toString()?.trim('"') ?: "Unknown",
                    vendor = p["vendor"]?.toString()?.trim('"') ?: "",
                    type = p["type"]?.toString()?.trim('"') ?: "host",
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun getPlayerName(): String {
        val account = AccountRepository.activeAccount.value
        return account?.username ?: "MD3L_Player"
    }

    private class DigestInputStream(
        private val input: InputStream,
        private val digest: MessageDigest,
    ) : InputStream() {
        override fun read(): Int {
            val b = input.read()
            if (b != -1) digest.update(b.toByte())
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = input.read(b, off, len)
            if (n > 0) digest.update(b, off, n)
            return n
        }

        override fun close() = input.close()
    }

    private fun InputStream.skipNBytes(n: Long) {
        var remaining = n
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val skipped = read(buffer, 0, toRead)
            if (skipped < 0) break
            remaining -= skipped
        }
    }
}
