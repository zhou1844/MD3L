package launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
        val isGdk: Boolean get() = packageType.equals("GDK", ignoreCase = true)
        val isUwp: Boolean get() = packageType.equals("UWP", ignoreCase = true)
        val displayLabel: String get() = "$typeName · ${if (isGdk) "GDK / MSIXVC" else "Appx / UWP"} · MCMrARM"
    }

    private val UWP_VERSION_URLS = listOf(
        "https://mrarm.io/r/w10-vdb",
        "https://raw.githubusercontent.com/MCMrARM/mc-w10-versiondb/master/versions.json.min",
        "https://raw.githubusercontent.com/ddf8196/mc-w10-versiondb-auto-update/master/versions.json.min",
    )
    private val GDK_VERSION_URLS = listOf(
        "https://raw.githubusercontent.com/MinecraftBedrockArchiver/GdkLinks/refs/heads/master/urls.min.json",
        "https://cdn.jsdelivr.net/gh/MinecraftBedrockArchiver/GdkLinks@master/urls.min.json",
        "https://github.bibk.top/MinecraftBedrockArchiver/GdkLinks/raw/refs/heads/master/urls.min.json",
    )
    private const val WU_URL = "https://fe3.delivery.mp.microsoft.com/ClientWebService/client.asmx/secured"
    private const val CHUNK_SIZE = 8L * 1024L * 1024L
    private const val MAX_THREADS = 16

    @Volatile
    private var cachedList: List<WUVersion> = emptyList()

    var onVersionsUpdated: ((List<WUVersion>) -> Unit)? = null

    suspend fun fetchVersions(forceNetwork: Boolean = false): List<WUVersion> = withContext(Dispatchers.IO) {
        if (cachedList.isNotEmpty() && !forceNetwork) return@withContext cachedList
        if (forceNetwork) {
            refreshFromNetwork()
            if (cachedList.isNotEmpty()) return@withContext cachedList
        }
        val local = loadLocal()
        if (local.isNotEmpty()) cachedList = local
        if (cachedList.isEmpty()) throw RuntimeException("版本库为空")
        cachedList
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
        val result = mutableListOf<WUVersion>()
        val seen = mutableSetOf<String>()

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

        return result.sortedByDescending { versionSortKey(it.name) }
    }

    private fun refreshFromNetwork() {
        val dir = cacheDir()
        val result = loadLocal().toMutableList()
        val seen = result.mapTo(mutableSetOf()) { "${it.name}_${it.packageType}" }

        val gdkText = GDK_VERSION_URLS.firstNotNullOfOrNull { curlFetch(it) }
        if (gdkText != null) {
            val list = parseGdkVersions(gdkText)
            if (list.isNotEmpty()) {
                File(dir, "gdk_versions.json").writeText(gdkText)
                for (version in list) if (seen.add("${version.name}_${version.packageType}")) result += version
            }
        }

        val uwpText = UWP_VERSION_URLS.firstNotNullOfOrNull { curlFetch(it) }
        if (uwpText != null) {
            val list = parseUwpVersions(uwpText)
            if (list.isNotEmpty()) {
                File(dir, "wu_versions.json").writeText(uwpText)
                for (version in list) if (seen.add("${version.name}_${version.packageType}")) result += version
            }
        }

        if (result.isNotEmpty()) {
            cachedList = result.sortedByDescending { versionSortKey(it.name) }
            onVersionsUpdated?.invoke(cachedList)
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
        kotlinx.coroutines.coroutineScope {
            ranges.chunked(MAX_THREADS).forEach { batch ->
                val jobs = batch.map { range ->
                    this.async(Dispatchers.IO) {
                        val success = downloadRange(url, part, range, total, downloaded, started, onProgress) { !isActive }
                        if (success) synchronized(completed) {
                            completed += range
                            partsFile.writeText(completed.joinToString("\n") { "${it.first}-${it.last}" })
                        }
                        success
                    }
                }
                if (jobs.awaitAll().any { !it }) ok.set(false)
                if (!ok.get()) return@coroutineScope
            }
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
        isCancelled: () -> Boolean,
    ): Boolean {
        repeat(3) {
            val tmp = File(part.parentFile, "${part.name}.${range.first}-${range.last}.chunk")
            try {
                if (isCancelled()) return false
                if (tmp.exists()) tmp.delete()
                val proc = ProcessBuilder(
                    "curl.exe",
                    "-sS",
                    "-fL",
                    "--connect-timeout", "15",
                    "--max-time", "180",
                    "--retry", "2",
                    "--retry-delay", "1",
                    "-H", "User-Agent: Windows-Update-Agent/10.0",
                    "-r", "${range.first}-${range.last}",
                    "-o", tmp.absolutePath,
                    url,
                ).redirectErrorStream(true).start()
                val outputReader = proc.inputStream.bufferedReader()
                val startedAt = System.currentTimeMillis()
                while (true) {
                    if (isCancelled()) {
                        proc.destroyForcibly()
                        tmp.delete()
                        return false
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
