package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList

object WUDownloadClient {

    data class WUVersion(
        val name: String,
        val uuid: String,
        val type: Int,
        val packageType: String,
        val downloadUrls: List<String> = emptyList(),
    ) {
        val typeName: String get() = when (type) {
            0 -> "Release"
            1 -> "Preview"
            2 -> "Preview"
            else -> "Unknown"
        }
        val isRelease: Boolean get() = type == 0
        val isPreview: Boolean get() = type != 0
        val isGdk: Boolean get() = packageType.equals("GDK", ignoreCase = true) || isGdkByVersion
        val isUwp: Boolean get() = !isGdk
        /** 版本号 >= 1.21.120.21 判定为 GDK（无 UWP 包） */
        val isGdkByVersion: Boolean get() {
            val threshold = listOf(1, 21, 120, 21)
            val parts = name.trim().split(".").mapNotNull { it.toIntOrNull() }
            if (parts.isEmpty()) return false
            val len = maxOf(parts.size, threshold.size)
            for (i in 0 until len) {
                val a = parts.getOrElse(i) { 0 }
                val b = threshold.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return true
        }
        val displayLabel: String get() = "$typeName · ${if (isGdk) "GDK / MSIXVC" else "Appx / UWP"} · MCMrARM"
    }

    private val UWP_VERSION_URLS = listOf(
        "https://mrarm.io/r/w10-vdb",
        "https://raw.githubusercontent.com/MCMrARM/mc-w10-versiondb/master/versions.json.min",
        "https://raw.githubusercontent.com/ddf8196/mc-w10-versiondb-auto-update/master/versions.json.min",
    )
    // LeviLauncher 版本数据库（主力源，覆盖 1.21.120+ 全部 GDK 版本）
    private val LEVI_GDK_URLS = listOf(
        "https://raw.githubusercontent.com/LiteLDev/minecraft-windows-gdk-version-db/main/historical_versions.json",
        "https://cdn.jsdelivr.net/gh/LiteLDev/minecraft-windows-gdk-version-db@main/historical_versions.json",
    )
    // MinecraftBedrockArchiver/GdkLinks（备用源，覆盖部分 1.21.120+ 版本）
    private val GDK_VERSION_URLS = listOf(
        "https://raw.githubusercontent.com/MinecraftBedrockArchiver/GdkLinks/refs/heads/master/urls.min.json",
        "https://cdn.jsdelivr.net/gh/MinecraftBedrockArchiver/GdkLinks@master/urls.min.json",
        "https://github.bibk.top/MinecraftBedrockArchiver/GdkLinks/raw/refs/heads/master/urls.min.json",
    )
    private const val WU_URL = "https://fe3.delivery.mp.microsoft.com/ClientWebService/client.asmx/secured"
    private const val CHUNK_SIZE = 512L * 1024L
    private const val MAX_THREADS = 8
    private const val CHUNK_STALL_TIMEOUT_MS = 15_000L
    private const val SPEED_WINDOW_MS = 3000L
    private const val NETWORK_LIST_TTL_MS = 5 * 60 * 1000L

    @Volatile
    private var cachedList: List<WUVersion> = emptyList()
    @Volatile
    private var networkRefreshedAt = 0L

    var onVersionsUpdated: ((List<WUVersion>) -> Unit)? = null

    suspend fun fetchVersions(forceNetwork: Boolean = false): List<WUVersion> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceNetwork && cachedList.isNotEmpty() && now - networkRefreshedAt < NETWORK_LIST_TTL_MS) {
            return@withContext cachedList
        }

        if (forceNetwork) {
            val strict = refreshFromNetwork(strict = true)
            if (strict.isEmpty()) {
                throw RuntimeException("网络版本库刷新失败，请检查网络后重试")
            }
            return@withContext strict
        }

        val network = runCatching { refreshFromNetwork(strict = false) }.getOrDefault(emptyList())
        if (network.isNotEmpty()) return@withContext network

        val local = loadLocal()
        if (local.isNotEmpty()) {
            cachedList = local
            return@withContext local
        }

        if (cachedList.isNotEmpty()) return@withContext cachedList
        throw RuntimeException("版本库为空")
    }

    private fun cacheDir(): File {
        val dir = File(System.getProperty("user.home"), ".craftlauncher/cache")
        dir.mkdirs()
        return dir
    }

    private fun loadLocal(): List<WUVersion> {
        val dir = cacheDir()
        val uwpCache = File(dir, "wu_versions.json")
        val gdkCache = File(dir, "gdk_versions.json")
        val leviCache = File(dir, "levi_versions.json")
        val result = mutableListOf<WUVersion>()
        val seen = mutableSetOf<String>()

        // LeviLauncher DB（最新版本最全）
        val leviCandidates = listOfNotNull(
            if (leviCache.exists() && leviCache.length() > 50) leviCache.readText() else null,
        )
        for (json in leviCandidates) {
            for (version in parseLeviVersions(json)) {
                if (seen.add("${version.name}_${version.packageType}")) result += version
            }
        }

        val gdkCandidates = listOfNotNull(
            if (gdkCache.exists() && gdkCache.length() > 50) gdkCache.readText() else null,
            WUDownloadClient::class.java.getResourceAsStream("/gdk_versions.json")?.bufferedReader()?.readText(),
        )
        for (json in gdkCandidates) {
            for (version in parseGdkVersions(json)) {
                if (seen.add("${version.name}_${version.packageType}")) result += version
            }
        }

        val uwpCandidates = listOfNotNull(
            if (uwpCache.exists() && uwpCache.length() > 50) uwpCache.readText() else null,
            WUDownloadClient::class.java.getResourceAsStream("/wu_versions.json")?.bufferedReader()?.readText(),
        )
        for (json in uwpCandidates) {
            for (version in parseUwpVersions(json)) {
                if (seen.add("${version.name}_${version.packageType}")) result += version
            }
        }

        return deduplicateGdkUwp(result).sortedByDescending { versionSortKey(it.name) }
    }

    private suspend fun refreshFromNetwork(strict: Boolean): List<WUVersion> {
        val dir = cacheDir()
        val result = if (strict) mutableListOf() else loadLocal().toMutableList()
        val seen = result.mapTo(mutableSetOf()) { "${it.name}_${it.packageType}" }

        // 优先 LeviLauncher 数据库（含 26.x 全部版本）
        val leviText = LEVI_GDK_URLS.firstNotNullOfOrNull { fetchText(it) }
        if (leviText != null) {
            val list = parseLeviVersions(leviText)
            if (list.isNotEmpty()) {
                File(dir, "levi_versions.json").writeText(leviText)
                for (version in list) if (seen.add("${version.name}_${version.packageType}")) result += version
            }
        }
        // 备用：MinecraftBedrockArchiver/GdkLinks
        val gdkText = GDK_VERSION_URLS.firstNotNullOfOrNull { fetchText(it) }
        if (gdkText != null) {
            val list = parseGdkVersions(gdkText)
            if (list.isNotEmpty()) {
                File(dir, "gdk_versions.json").writeText(gdkText)
                for (version in list) if (seen.add("${version.name}_${version.packageType}")) result += version
            }
        }

        val uwpText = UWP_VERSION_URLS.firstNotNullOfOrNull { fetchText(it) }
        if (uwpText != null) {
            val list = parseUwpVersions(uwpText)
            if (list.isNotEmpty()) {
                File(dir, "wu_versions.json").writeText(uwpText)
                for (version in list) if (seen.add("${version.name}_${version.packageType}")) result += version
            }
        }

        val mcAppx = runCatching { McAppxClient.fetchVersions() }.getOrDefault(emptyList())
        for (entry in mcAppx) {
            val synthetic = WUVersion(
                name = entry.version,
                uuid = "",
                type = if (entry.isPreview) 2 else 0,
                packageType = "UWP",
            )
            if (seen.add("${synthetic.name}_${synthetic.packageType}")) result += synthetic
        }

        if (result.isNotEmpty()) {
            cachedList = deduplicateGdkUwp(result).sortedByDescending { versionSortKey(it.name) }
            networkRefreshedAt = System.currentTimeMillis()
            onVersionsUpdated?.invoke(cachedList)
        }
        return cachedList
    }

    /**
     * 对于版本号 >= 1.21.120.21（即 isGdkByVersion=true）的版本：
     * - 若同时存在 GDK 和 UWP 条目，丢弃 UWP 条目（UWP 包不存在）
     * - 若只有 UWP 条目，将其升级为 GDK（packageType 改为 GDK），downloadUrls 保持空，
     *   下游 chooseGdkDownloadUrl 返回 null 后走 Xbox catalog fallback
     */
    private fun deduplicateGdkUwp(list: List<WUVersion>): List<WUVersion> {
        val gdkNames = list.filter { it.packageType.equals("GDK", ignoreCase = true) }.map { it.name }.toSet()
        return list.filter { it.name.startsWith("1.") }.mapNotNull { ver ->
            if (!ver.isGdkByVersion) return@mapNotNull ver          // 真 UWP 版本，保留
            if (ver.packageType.equals("GDK", ignoreCase = true)) return@mapNotNull ver  // 已是 GDK，保留
            // UWP 条目但版本号属于 GDK 时代
            if (ver.name in gdkNames) null   // 已有对应 GDK 条目，丢弃此 UWP 副本
            else ver.copy(packageType = "GDK")  // 升级为 GDK，让下游走正确路径
        }
    }

    private fun parseUwpVersions(json: String): List<WUVersion> {
        val regex = Regex("""\[\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*(\d+)\s*]""")
        return regex.findAll(json)
            .map { WUVersion(it.groupValues[1], it.groupValues[2], it.groupValues[3].toInt(), "UWP") }
            .distinctBy { "${it.name}_${it.packageType}" }
            .sortedByDescending { versionSortKey(it.name) }
            .toList()
    }

    /**
     * 解析 LiteLDev/minecraft-windows-gdk-version-db 格式：
     * { "releaseVersions": [ { "version": "Release 1.26.13.01", "urls": [...] }, ... ],
     *   "previewVersions": [ { "version": "Preview 1.26.40.05", "urls": [...] }, ... ] }
     */
    private fun parseLeviVersions(json: String): List<WUVersion> {
        val result = mutableListOf<WUVersion>()
        fun parseSection(sectionKey: String, type: Int) {
            val sectionStart = json.indexOf("\"$sectionKey\"")
            if (sectionStart < 0) return
            val arrStart = json.indexOf('[', sectionStart)
            if (arrStart < 0) return
            // 找匹配的 ]
            var depth = 0; var end = arrStart
            while (end < json.length) {
                when (json[end]) { '[' -> depth++; ']' -> { depth--; if (depth == 0) break } }
                end++
            }
            val section = json.substring(arrStart + 1, end)
            // 逐个对象解析 "version" 和 "urls"
            val objRegex = Regex("""\{[^{}]*"version"\s*:\s*"([^"]+)"[^{}]*"urls"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            for (m in objRegex.findAll(section)) {
                val rawVersion = m.groupValues[1]  // e.g. "Release 1.26.13.01"
                val name = rawVersion.removePrefix("Release ").removePrefix("Preview ").trim()
                if (name.isBlank()) continue
                val urls = Regex(""""(https?://[^"]+)"""").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
                result += WUVersion(name, "", type, "GDK", urls)
            }
        }
        parseSection("releaseVersions", 0)
        parseSection("previewVersions", 2)
        return result.distinctBy { "${it.name}_${it.packageType}" }
            .sortedByDescending { versionSortKey(it.name) }
    }

    private fun parseGdkVersions(json: String): List<WUVersion> {
        val result = mutableListOf<WUVersion>()

        fun parseSection(section: String, type: Int) {
            val sectionStart = json.indexOf("\"$section\"")
            if (sectionStart < 0) return
            val objectStart = json.indexOf('{', sectionStart)
            if (objectStart < 0) return
            var depth = 0
            var end = objectStart
            while (end < json.length) {
                when (json[end]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                end++
            }
            if (end <= objectStart) return
            val content = json.substring(objectStart + 1, end)
            val entryRegex = Regex(""""([^"]+)"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            for (match in entryRegex.findAll(content)) {
                val versionName = match.groupValues[1]
                val urls = Regex(""""(https?://[^"]+)"""").findAll(match.groupValues[2]).map { it.groupValues[1] }.toList()
                if (urls.isNotEmpty()) result += WUVersion(versionName, "", type, "GDK", urls)
            }
        }

        parseSection("release", 0)
        parseSection("preview", 2)
        return result.distinctBy { "${it.name}_${it.packageType}" }
            .sortedByDescending { versionSortKey(it.name) }
    }

    private fun versionSortKey(version: String): String {
        return version.split(".").joinToString(".") { it.toIntOrNull()?.toString()?.padStart(6, '0') ?: it }
    }

    private fun fetchText(url: String): String? {
        return curlFetch(url) ?: httpFetch(url)
    }

    private fun curlFetch(url: String): String? {
        return try {
            val proc = ProcessBuilder("curl.exe", "-sL", "--connect-timeout", "10", "--max-time", "30", url)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val done = proc.waitFor(35, java.util.concurrent.TimeUnit.SECONDS)
            if (done && proc.exitValue() == 0 && output.length > 50) output else null
        } catch (_: Exception) {
            null
        }
    }

    private fun httpFetch(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MD3L/1.1")
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            text.takeIf { it.length > 50 }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolveDownloadUrl(updateId: String, revision: String = "1"): Pair<String, Long> = withContext(Dispatchers.IO) {
        val body = buildDownloadRequest(updateId, revision)
        val response = curlPostSoap(WU_URL, body)
        val urls = Regex("""<[^:>]*:?Url>(.*?)</[^:>]*:?Url>""")
            .findAll(response)
            .map { xmlUnescape(it.groupValues[1]) }
            .toList()
        val selected = urls.firstOrNull { it.startsWith("http://tlu.dl.delivery.mp.microsoft.com/", ignoreCase = true) }
            ?: urls.firstOrNull { "delivery.mp.microsoft.com" in it }
            ?: urls.firstOrNull()
            ?: throw RuntimeException("未能从 MCMrARM/WU 获取 Appx 下载链接")
        val size = probeSize(selected)
        selected to size
    }

    private fun curlPostSoap(url: String, body: String): String {
        val tmp = File.createTempFile("md3l-wu-", ".xml")
        return try {
            tmp.writeText(body, Charsets.UTF_8)
            val proc = ProcessBuilder(
                "curl.exe",
                "-sL",
                "--connect-timeout", "20",
                "--max-time", "60",
                "-H", "Content-Type: application/soap+xml; charset=utf-8",
                "-H", "User-Agent: Windows-Update-Agent/10.0",
                "--data-binary", "@${tmp.absolutePath}",
                url,
            ).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val done = proc.waitFor(70, java.util.concurrent.TimeUnit.SECONDS)
            if (!done || proc.exitValue() != 0 || output.isBlank()) {
                throw RuntimeException("curl 解析 Appx 下载链接失败: ${if (done) "exit=${proc.exitValue()}" else "timeout"} ${output.take(300)}")
            }
            output
        } finally {
            tmp.delete()
        }
    }

    private fun buildDownloadRequest(updateId: String, revision: String): String {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val expires = Instant.now().plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS).toString()
        val messageId = UUID.randomUUID().toString()
        return """
            <s:Envelope xmlns:a="http://www.w3.org/2005/08/addressing" xmlns:s="http://www.w3.org/2003/05/soap-envelope">
              <s:Header>
                <a:Action s:mustUnderstand="1">http://www.microsoft.com/SoftwareDistribution/Server/ClientWebService/GetExtendedUpdateInfo2</a:Action>
                <a:MessageID>urn:uuid:$messageId</a:MessageID>
                <a:To s:mustUnderstand="1">$WU_URL</a:To>
                <o:Security s:mustUnderstand="1" xmlns:o="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
                  <u:Timestamp xmlns:u="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
                    <u:Created>$now</u:Created>
                    <u:Expires>$expires</u:Expires>
                  </u:Timestamp>
                  <WindowsUpdateTicketsToken xmlns="http://schemas.microsoft.com/msus/2014/10/WindowsUpdateAuthorization" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd" wsu:id="ClientMSA">
                    <TicketType Name="AAD" Version="1.0" Policy="MBI_SSL"></TicketType>
                  </WindowsUpdateTicketsToken>
                </o:Security>
              </s:Header>
              <s:Body>
                <GetExtendedUpdateInfo2 xmlns="http://www.microsoft.com/SoftwareDistribution/Server/ClientWebService">
                  <updateIDs>
                    <UpdateIdentity>
                      <UpdateID>$updateId</UpdateID>
                      <RevisionNumber>$revision</RevisionNumber>
                    </UpdateIdentity>
                  </updateIDs>
                  <infoTypes>
                    <XmlUpdateFragmentType>FileUrl</XmlUpdateFragmentType>
                  </infoTypes>
                  <deviceAttributes>E:BranchReadinessLevel=CBB&amp;ProcessorIdentifier=Intel64%20Family%206%20Model%2063%20Stepping%202&amp;CurrentBranch=rs4_release&amp;FlightRing=Retail&amp;InstallLanguage=en-US&amp;OSUILocale=en-US&amp;InstallationType=Client&amp;App=WU&amp;ProcessorManufacturer=GenuineIntel&amp;AppVer=10.0.17134.471&amp;OSArchitecture=AMD64&amp;UpdateManagementGroup=2&amp;IsDeviceRetailDemo=0&amp;IsFlightingEnabled=0&amp;TelemetryLevel=1&amp;DefaultUserRegion=244&amp;WuClientVer=10.0.17134.471&amp;OSVersion=10.0.17134.472&amp;DeviceFamily=Windows.Desktop</deviceAttributes>
                </GetExtendedUpdateInfo2>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    private fun xmlUnescape(value: String): String {
        return value.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun probeSize(url: String): Long {
        curlProbeSize(url).takeIf { it > 0 }?.let { return it }
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                conn.contentLengthLong
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            -1L
        }
    }

    private fun curlProbeSize(url: String): Long {
        return try {
            val proc = ProcessBuilder(
                "curl.exe",
                "-sIL",
                "--connect-timeout", "10",
                "--max-time", "25",
                "-H", "User-Agent: Windows-Update-Agent/10.0",
                url,
            ).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val done = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!done || proc.exitValue() != 0) return -1L
            Regex("""(?im)^content-length:\s*(\d+)\s*$""")
                .findAll(output)
                .mapNotNull { it.groupValues[1].toLongOrNull() }
                .lastOrNull()
                ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    suspend fun downloadFile(
        url: String,
        dest: File,
        expectedSize: Long = -1L,
        onProgress: (downloaded: Long, total: Long, speedBps: Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.absolutePath + ".filepart")
        val partsFile = File(dest.absolutePath + ".filepart.parts")
        val total = if (expectedSize > 0) expectedSize else probeSize(url)
        val started = System.currentTimeMillis()
        val downloaded = AtomicLong(0L)
        val ok = AtomicBoolean(true)

        if (total <= CHUNK_SIZE || total <= 0L) {
            downloaded.set(part.takeIf { it.exists() }?.length() ?: 0L)
            return@withContext singleThreadDownload(url, part, downloaded.get(), total, started, onProgress) { !isActive }.also { success ->
                if (success) finishPart(part, dest, partsFile)
            }
        }

        RandomAccessFile(part, "rw").use { it.setLength(total) }
        val completed = readCompletedRanges(partsFile).toMutableSet()
        downloaded.set(completed.sumOf { it.last - it.first + 1 })
        val ranges = mutableListOf<LongRange>()
        var start = 0L
        while (start < total) {
            val end = minOf(start + CHUNK_SIZE - 1, total - 1)
            val range = start..end
            if (range !in completed) ranges += range
            start = end + 1
        }
        onProgress(downloaded.get(), total, 0L)

        // 滑动窗口测速：记录 (时间戳, 累计字节) 样本
        val speedSamples = CopyOnWriteArrayList<Pair<Long, Long>>()
        speedSamples.add(System.currentTimeMillis() to downloaded.get())

        // 活跃chunk的临时文件集合，用于实时进度轮询
        val activeTmps = CopyOnWriteArrayList<File>()

        // 进度上报协程：每300ms轮询一次
        val failedRanges = CopyOnWriteArrayList<LongRange>()
        val semaphore = Semaphore(MAX_THREADS)
        kotlinx.coroutines.coroutineScope {
            // 实时进度轮询 job
            val progressJob = launch(Dispatchers.IO) {
                var highWater = downloaded.get()
                while (isActive) {
                    Thread.sleep(300)
                    // 累计已完成 + 所有活跃tmp文件当前大小
                    val inFlight = activeTmps.sumOf { if (it.exists()) it.length() else 0L }
                    val raw = (downloaded.get() + inFlight).coerceAtMost(total)
                    val current = maxOf(raw, highWater)
                    if (current != highWater) {
                        highWater = current
                        val lastReported = current
                        val now = System.currentTimeMillis()
                        speedSamples.add(now to current)
                        // 保留窗口内的样本
                        val cutoff = now - SPEED_WINDOW_MS
                        while (speedSamples.size > 2 && speedSamples[0].first < cutoff) speedSamples.removeAt(0)
                        val speed = if (speedSamples.size >= 2) {
                            val dt = (speedSamples.last().first - speedSamples.first().first).coerceAtLeast(1L)
                            val db = speedSamples.last().second - speedSamples.first().second
                            (db * 1000L / dt).coerceAtLeast(0L)
                        } else 0L
                        onProgress(current, total, speed)
                    }
                }
            }

            val jobs = ranges.map { range ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        val tmp = File(part.parentFile, "${part.name}.${range.first}-${range.last}.chunk")
                        activeTmps.add(tmp)
                        try {
                            val success = downloadRange(url, part, range, total, downloaded, started, onProgress, activeTmps) { !isActive }
                            if (success) synchronized(completed) {
                                completed += range
                                partsFile.writeText(completed.joinToString("\n") { "${it.first}-${it.last}" })
                            } else {
                                failedRanges.add(range)
                                ok.set(false)
                            }
                        } finally {
                            activeTmps.remove(tmp)
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }
            jobs.awaitAll()
            progressJob.cancel()
        }

        if (failedRanges.isNotEmpty() && isActive) {
            val pending = failedRanges.distinct().toMutableList()
            repeat(2) {
                if (pending.isEmpty() || !isActive) return@repeat
                val iter = pending.iterator()
                while (iter.hasNext()) {
                    val range = iter.next()
                    val success = downloadRange(url, part, range, total, downloaded, started, onProgress, activeTmps) { !isActive }
                    if (success) {
                        synchronized(completed) {
                            completed += range
                            partsFile.writeText(completed.joinToString("\n") { "${it.first}-${it.last}" })
                        }
                        iter.remove()
                    }
                }
            }
            if (pending.isEmpty()) ok.set(true)
        }
        if (ok.get() && downloaded.get() >= total) {
            finishPart(part, dest, partsFile)
            true
        } else {
            false
        }
    }

    private fun singleThreadDownload(
        url: String,
        part: File,
        existing: Long,
        total: Long,
        started: Long,
        onProgress: (Long, Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000
                conn.setRequestProperty("User-Agent", "Windows-Update-Agent/10.0")
                if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
                val append = existing > 0 && conn.responseCode == 206
                val current = AtomicLong(if (append) existing else 0L)
                RandomAccessFile(part, "rw").use { raf ->
                    if (append) raf.seek(existing) else raf.setLength(0)
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            if (isCancelled()) return false
                            val n = input.read(buffer)
                            if (n <= 0) break
                            raf.write(buffer, 0, n)
                            val done = current.addAndGet(n.toLong())
                            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                            onProgress(done, total, done * 1000L / elapsed)
                        }
                    }
                }
                true
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadRange(
        url: String,
        part: File,
        range: LongRange,
        total: Long,
        downloaded: AtomicLong,
        started: Long,
        onProgress: (Long, Long, Long) -> Unit,
        activeTmps: CopyOnWriteArrayList<File> = CopyOnWriteArrayList(),
        isCancelled: () -> Boolean,
    ): Boolean {
        repeat(3) {
            val tmp = File(part.parentFile, "${part.name}.${range.first}-${range.last}.chunk")
            if (!activeTmps.contains(tmp)) activeTmps.add(tmp)
            try {
                if (isCancelled()) return false
                if (tmp.exists()) tmp.delete()
                val proc = ProcessBuilder(
                    "curl.exe",
                    "-sS",
                    "-fL",
                    "--connect-timeout", "15",
                    "--max-time", "180",
                    "--speed-time", "20",
                    "--speed-limit", "1024",
                    "--retry", "2",
                    "--retry-delay", "1",
                    "-H", "User-Agent: Windows-Update-Agent/10.0",
                    "-r", "${range.first}-${range.last}",
                    "-o", tmp.absolutePath,
                    url,
                ).redirectErrorStream(true).start()
                val outputReader = proc.inputStream.bufferedReader()
                val startedAt = System.currentTimeMillis()
                var lastChunkSize = -1L
                var lastChunkProgressAt = startedAt
                while (true) {
                    if (isCancelled()) {
                        proc.destroyForcibly()
                        tmp.delete()
                        return false
                    }
                    val currentChunkSize = if (tmp.exists()) tmp.length() else 0L
                    if (currentChunkSize != lastChunkSize) {
                        lastChunkSize = currentChunkSize
                        lastChunkProgressAt = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastChunkProgressAt > CHUNK_STALL_TIMEOUT_MS) {
                        proc.destroyForcibly()
                        tmp.delete()
                        return@repeat
                    }
                    if (proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                    if (System.currentTimeMillis() - startedAt > 190_000L) {
                        proc.destroyForcibly()
                        tmp.delete()
                        return@repeat
                    }
                }
                outputReader.readText()
                if (proc.exitValue() != 0) {
                    tmp.delete()
                    Thread.sleep(1000L)
                    return@repeat
                }
                val expected = range.last - range.first + 1
                if (!tmp.exists() || tmp.length() != expected) {
                    tmp.delete()
                    Thread.sleep(1000L)
                    return@repeat
                }
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(range.first)
                    tmp.inputStream().use { input ->
                        val buffer = ByteArray(1024 * 1024)
                        var written = 0L
                        while (written < expected) {
                            if (isCancelled()) return false
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - written).toInt())
                            if (n <= 0) break
                            raf.write(buffer, 0, n)
                            written += n
                            val current = downloaded.addAndGet(n.toLong()).coerceAtMost(total)
                            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                            onProgress(current, total, current * 1000L / elapsed)
                        }
                    }
                }
                tmp.delete()
                return true
            } catch (_: Exception) {
                tmp.delete()
                Thread.sleep(1000L)
            }
        }
        return false
    }

    private fun readCompletedRanges(file: File): Set<LongRange> {
        if (!file.exists()) return emptySet()
        return file.readLines().mapNotNull { line ->
            val parts = line.split("-")
            val start = parts.getOrNull(0)?.toLongOrNull()
            val end = parts.getOrNull(1)?.toLongOrNull()
            if (start != null && end != null) start..end else null
        }.toSet()
    }

    private fun finishPart(part: File, dest: File, partsFile: File) {
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        partsFile.delete()
    }
}
