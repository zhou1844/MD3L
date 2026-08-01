package launcher.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object GdkXvdExtractor {

    private const val PAGE_SIZE = 0x1000
    private const val CACHE_SIZE = 0x100000
    private const val HASH_ENTRY_SIZE = 0x18
    private const val HASH_ENTRIES_IN_PAGE = 0xAA
    private const val XVD_HEADER_INCL_SIGNATURE_SIZE = 0x3000L

    private const val FLAG_ENCRYPTION_DISABLED = 0x02
    private const val FLAG_DATA_INTEGRITY_DISABLED = 0x04
    private const val FLAG_RESILIENCY_ENABLED = 0x10

    fun interface ExtractProgress {
        fun onProgress(current: Int, total: Int, fileName: String)
    }



    fun extract(
        msixvcFile: File,
        outputDir: File,
        guidHex: String,
        keyHex: String,
        onProgress: ExtractProgress? = null,
    ): Boolean {
        return try {
            val cleanKey = keyHex.replace(" ", "").replace("-", "").trim()
            require(cleanKey.length == 64) { "CIK 密钥必须为 64 位 hex (TKey+DKey)，实际长度=${cleanKey.length}" }
            val tKey = hexToBytes(cleanKey.substring(0, 32))
            val dKey = hexToBytes(cleanKey.substring(32, 64))
            println("[XVD] 开始解码 ${msixvcFile.name} (CIK GUID=$guidHex)")
            outputDir.mkdirs()
            MsiXvdStream(msixvcFile).use { stream ->
                stream.parse()
                stream.extractAll(outputDir, XtsDecryptor(tKey, dKey), onProgress)
            }
        } catch (e: Exception) {
            System.err.println("[XVD] 解压失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }


    internal class XtsDecryptor(tKey: ByteArray, dKey: ByteArray) {
        private val encTweak = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(tKey, "AES"))
        }
        private val decData = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(dKey, "AES"))
        }
        private val tmp = ByteArray(16)

        fun decryptPage(buf: ByteArray, off: Int, tweakIv: ByteArray) {
            var tweak = encTweak.doFinal(tweakIv)
            var p = off
            val end = off + PAGE_SIZE
            while (p < end) {
                for (i in 0 until 16) tmp[i] = (buf[p + i].toInt() xor tweak[i].toInt()).toByte()
                val dec = decData.doFinal(tmp)
                for (i in 0 until 16) buf[p + i] = (dec[i].toInt() xor tweak[i].toInt()).toByte()
                tweak = gf128MulAlpha(tweak)
                p += 16
            }
        }
    }

    internal fun gf128MulAlpha(iv: ByteArray): ByteArray {
        val r = ByteArray(16)
        var carry = 0
        for (i in 0 until 16) {
            val b = iv[i].toInt() and 0xFF
            r[i] = ((b shl 1) or carry).toByte()
            carry = (b ushr 7) and 1
        }
        if (carry != 0) r[0] = (r[0].toInt() xor 0x87).toByte()
        return r
    }


    private class MsiXvdStream(file: File) : AutoCloseable {
        private val raf = RandomAccessFile(file, "r")

        private var volumes = 0
        private var kind = 0
        private var driveSize = 0L
        private var embeddedXvdLength = 0
        private var userDataLength = 0
        private var xvcDataLength = 0
        private var dynamicHeaderLength = 0
        private var mutableDataPageCount = 0
        private lateinit var vdUid: ByteArray

        private var isEncrypted = false
        private var dataIntegrity = false
        private var resiliency = false
        private var hashEntryLength = 0x18

        private var hashTreePageCount = 0L
        private var hashTreeLevels = 0L
        private var hashTreePageOffset = 0L
        private var xvdUserDataOffset = 0L
        private var numberOfHashedPages = 0L

        private val userDataContents = LinkedHashMap<String, ByteArray>()
        private lateinit var segments: List<Segment>
        private lateinit var segmentPaths: List<String>
        private lateinit var regions: List<Region>
        private var firstUpdateSegmentPageNum = 0L
        private var hasUpdateSegments = false

        private data class Segment(val flags: Int, val pathLength: Int, val pathOffset: Int, val fileSize: Long)
        private data class Region(
            val id: Int, val keyId: Int, val flags: Int, val firstSegmentIndex: Int,
            val offset: Long, val length: Long,
        )

        fun parse() {
            parseHeader()
            resiliency = (volumes and FLAG_RESILIENCY_ENABLED) != 0
            dataIntegrity = (volumes and FLAG_DATA_INTEGRITY_DISABLED) == 0
            isEncrypted = (volumes and FLAG_ENCRYPTION_DISABLED) == 0
            hashEntryLength = if (isEncrypted) 0x14 else 0x18

            numberOfHashedPages =
                bytesToPages(driveSize) + bytesToPages(userDataLength.toLong()) +
                    bytesToPages(xvcDataLength.toLong()) + bytesToPages(dynamicHeaderLength.toLong())

            val (htPages, htLevels) = calculateNumberHashPages(numberOfHashedPages, resiliency)
            hashTreePageCount = htPages
            hashTreeLevels = htLevels

            val mutableDataOffset = pageToOffset(bytesToPages(embeddedXvdLength.toLong())) + XVD_HEADER_INCL_SIGNATURE_SIZE
            val mutableDataLength = pageToOffset(mutableDataPageCount.toLong())
            hashTreePageOffset = mutableDataLength + mutableDataOffset
            xvdUserDataOffset = (if (dataIntegrity) pageToOffset(hashTreePageCount) else 0L) + hashTreePageOffset

            println(
                "[XVD] Volumes=0x${volumes.toString(16)} Encrypted=$isEncrypted DataIntegrity=$dataIntegrity " +
                    "Resiliency=$resiliency Kind=$kind"
            )
            println(
                "[XVD] hashedPages=$numberOfHashedPages hashTreePages=$hashTreePageCount levels=$hashTreeLevels " +
                    "userDataOffset=0x${xvdUserDataOffset.toString(16)}"
            )

            parseUserData()
            parseSegments()
            parseArea()
        }

        private fun parseHeader() {
            val header = ByteArray(PAGE_SIZE)
            raf.seek(0)
            raf.readFully(header)
            val magic = String(header, 0x200, 8, Charsets.US_ASCII).trimEnd('\u0000')
            volumes = readLE32(header, 0x208)
            driveSize = readLE64(header, 0x218)
            vdUid = header.copyOfRange(0x220, 0x230)
            kind = readLE32(header, 0x280)
            embeddedXvdLength = readLE32(header, 0x288)
            userDataLength = readLE32(header, 0x28C)
            xvcDataLength = readLE32(header, 0x290)
            dynamicHeaderLength = readLE32(header, 0x294)
            mutableDataPageCount = header[0x470].toInt() and 0xFF
            println("[XVD] Magic='$magic' DriveSize=$driveSize UserDataLength=$userDataLength XvcDataLength=$xvcDataLength")
        }

        private fun parseUserData() {
            if (userDataLength <= 0) return
            val buf = ByteArray(userDataLength)
            readFullyAt(xvdUserDataOffset, buf, buf.size)

            val udLength = readLE32(buf, 0)
            val udType = readLE32(buf, 8)
            if (udType != 0) {
                println("[XVD] UserData 非 PackageFiles 类型 (Type=$udType)，跳过")
                return
            }

            var pos = udLength
            pos += 4
            pos += 260 * 2
            val fileCount = readLE32(buf, pos); pos += 4

            val entries = ArrayList<Triple<String, Int, Int>>(fileCount)
            for (i in 0 until fileCount) {
                if (pos + 528 > buf.size) break
                val path = readUtf16(buf, pos, 260); pos += 260 * 2
                val size = readLE32(buf, pos); pos += 4
                val offset = readLE32(buf, pos); pos += 4
                entries.add(Triple(path, size, offset))
            }
            for ((path, size, offset) in entries) {
                val dataOff = udLength + offset
                if (size >= 0 && dataOff + size <= buf.size) {
                    userDataContents[path] = buf.copyOfRange(dataOff, dataOff + size)
                }
            }
            println("[XVD] UserData 文件: ${userDataContents.keys}")
        }

        private fun parseSegments() {
            val meta = userDataContents["SegmentMetadata.bin"]
                ?: throw IllegalStateException("未找到 SegmentMetadata.bin")

            val headerLength = readLE32(meta, 12)
            val segmentCount = readLE32(meta, 16)

            val headerSize = 4 * 6 + 0x10 + 0x3C
            val segList = ArrayList<Segment>(segmentCount)
            var pos = headerSize
            for (i in 0 until segmentCount) {
                val flags = readLE16(meta, pos)
                val pathLen = readLE16(meta, pos + 2)
                val pathOff = readLE32(meta, pos + 4)
                val fileSize = readLE64(meta, pos + 8)
                segList.add(Segment(flags, pathLen, pathOff, fileSize))
                pos += 0x10
            }

            val pathsBase = headerLength + segmentCount * 0x10
            val paths = segList.mapIndexed { idx, seg ->
                val off = pathsBase + seg.pathOffset
                if (seg.pathLength > 0 && off + seg.pathLength * 2 <= meta.size) {
                    String(meta, off, seg.pathLength * 2, Charsets.UTF_16LE)
                } else {
                    "segment_$idx"
                }
            }
            segments = segList
            segmentPaths = paths
            println("[XVD] 段数量=$segmentCount headerLength=$headerLength")
        }

        private fun parseArea() {
            val xvcInfoOffset = pageToOffset(bytesToPages(userDataLength.toLong())) + xvdUserDataOffset
            val buf = ByteArray(xvcDataLength)
            readFullyAt(xvcInfoOffset, buf, buf.size)

            var pos = 16 + 0xC00 + 0x100
            val version = readLE32(buf, pos); pos += 4
            val regionCount = readLE32(buf, pos); pos += 4
            pos += 4
            pos += 2
            pos += 2
            pos += 4
            pos += 4
            pos += 8
            pos += 8
            pos += 4
            val updateSegmentCount = readLE32(buf, pos); pos += 4
            pos += 8
            pos += 8
            pos += 4
            pos += 0x54

            val regionList = ArrayList<Region>(regionCount)
            if (version >= 1) {
                for (i in 0 until regionCount) {
                    if (pos + 0x80 > buf.size) break
                    val id = readLE32(buf, pos)
                    val keyId = readLE16(buf, pos + 4)
                    val flags = readLE32(buf, pos + 8)
                    val firstSeg = readLE32(buf, pos + 12)
                    val offset = readLE64(buf, pos + 16 + 0x40)
                    val length = readLE64(buf, pos + 16 + 0x40 + 8)
                    regionList.add(Region(id, keyId, flags, firstSeg, offset, length))
                    pos += 0x80
                }
                if (updateSegmentCount > 0 && pos + 12 <= buf.size) {
                    firstUpdateSegmentPageNum = readLE32(buf, pos).toLong() and 0xFFFFFFFFL
                    hasUpdateSegments = true
                }
            }
            regions = regionList
            println("[XVD] XvcInfo version=$version regions=$regionCount updateSegments=$updateSegmentCount")
        }

        fun extractAll(outputDir: File, decryptor: XtsDecryptor, onProgress: ExtractProgress?): Boolean {
            val firstSegmentOffset = if (hasUpdateSegments) pageToOffset(firstUpdateSegmentPageNum) else 0L
            val extractable = regions.filter { it.firstSegmentIndex != 0 || firstSegmentOffset == it.offset }
            if (extractable.isEmpty()) {
                println("[XVD] 没有可提取的 Region")
                return false
            }
            var extracted = 0
            for (region in extractable) {
                val shouldDecrypt = isEncrypted && region.keyId != 0xFFFF
                println(
                    "[XVD] Region id=0x${region.id.toString(16)} keyId=${region.keyId} " +
                        "offset=0x${region.offset.toString(16)} len=0x${region.length.toString(16)} " +
                        "startSeg=${region.firstSegmentIndex} decrypt=$shouldDecrypt"
                )
                extracted += extractPart(outputDir, decryptor, region, shouldDecrypt, onProgress)
            }
            println("[XVD] 解压完成，共 $extracted 个文件")
            return extracted > 0
        }

        private fun extractPart(
            outputDir: File,
            decryptor: XtsDecryptor,
            region: Region,
            shouldDecrypt: Boolean,
            onProgress: ExtractProgress?,
        ): Int {
            val tweakIv = ByteArray(16)
            if (shouldDecrypt) {
                writeLE32(tweakIv, 4, region.id)
                System.arraycopy(vdUid, 0, tweakIv, 8, 8)
            }

            var shouldRefreshPageCache = true
            var totalPageCacheOffset = region.offset
            var pageCacheOffset = 0
            val pageCache = ByteArray(CACHE_SIZE)

            var shouldRefreshHashCache = dataIntegrity
            val (initHashOffset, initEntryIndex) =
                calculateHashEntryBlockOffset(getPageOffset(region.offset - xvdUserDataOffset))
            var totalHashCacheOffset = initHashOffset
            var hashCacheEntryIndex = initEntryIndex
            var hashCacheOffset = (hashCacheEntryIndex * HASH_ENTRY_SIZE).toInt()
            val hashCache = ByteArray(CACHE_SIZE)

            var currentSegmentIndex = region.firstSegmentIndex
            var processedPageCount = 0L
            val totalPageCount = getPageOffset(region.length)
            val totalSegments = segments.size
            var extractedInRegion = 0

            while (segments.size > currentSegmentIndex && totalPageCount > processedPageCount) {
                val seg = segments[currentSegmentIndex]
                val segPath = segmentPaths[currentSegmentIndex].replace('\\', '/')
                val outFile = File(outputDir, segPath)
                outFile.parentFile?.mkdirs()

                BufferedOutputStream(FileOutputStream(outFile)).use { out ->
                    var remaining = seg.fileSize
                    do {
                        val chunk = minOf(remaining, PAGE_SIZE.toLong()).toInt()

                        if (shouldRefreshHashCache) {
                            readFullyAt(totalHashCacheOffset, hashCache, hashCache.size)
                            shouldRefreshHashCache = false
                        }
                        if (shouldRefreshPageCache) {
                            readFullyAt(totalPageCacheOffset, pageCache, pageCache.size)
                            shouldRefreshPageCache = false
                        }

                        if (dataIntegrity) {
                            if (shouldDecrypt) {
                                writeLE32(tweakIv, 0, readLE32(hashCache, hashCacheOffset + hashEntryLength))
                            }
                            hashCacheOffset += HASH_ENTRY_SIZE
                            hashCacheEntryIndex++
                            if (hashCacheEntryIndex == HASH_ENTRIES_IN_PAGE.toLong()) {
                                hashCacheEntryIndex = 0
                                hashCacheOffset += 0x10
                            }
                            if (hashCacheOffset == hashCache.size) {
                                totalHashCacheOffset += hashCacheOffset
                                hashCacheOffset = 0
                                hashCacheEntryIndex = 0
                                shouldRefreshHashCache = true
                            }
                        }

                        if (shouldDecrypt) {
                            decryptor.decryptPage(pageCache, pageCacheOffset, tweakIv)
                        }

                        out.write(pageCache, pageCacheOffset, chunk)
                        remaining -= chunk

                        pageCacheOffset += PAGE_SIZE
                        if (pageCacheOffset == pageCache.size) {
                            totalPageCacheOffset += pageCacheOffset
                            pageCacheOffset = 0
                            shouldRefreshPageCache = true
                        }
                        processedPageCount++
                    } while (remaining > 0)
                }

                onProgress?.onProgress(currentSegmentIndex + 1, totalSegments, segPath)
                extractedInRegion++
                currentSegmentIndex++
            }
            return extractedInRegion
        }

        private fun calculateHashEntryBlockOffset(blockNo: Long): Pair<Long, Long> {
            val (hashBlockPage, entryId) =
                computeHashBlockIndexForDataBlockLevel0(kind, hashTreeLevels, numberOfHashedPages, blockNo, resiliency)
            return (hashTreePageOffset + pageToOffset(hashBlockPage)) to entryId
        }


        private fun readFullyAt(pos: Long, buf: ByteArray, len: Int) {
            raf.seek(pos)
            var read = 0
            while (read < len) {
                val n = raf.read(buf, read, len - read)
                if (n < 0) break
                read += n
            }
            if (read < len) java.util.Arrays.fill(buf, read, len, 0)
        }

        override fun close() {
            raf.close()
        }
    }



    private fun calculateNumberHashPages(hashedPagesCount: Long, resilient: Boolean): Pair<Long, Long> {
        val perPage = HASH_ENTRIES_IN_PAGE.toLong()
        val lvl0 = perPage
        val lvl1 = perPage * lvl0
        val lvl2 = perPage * lvl1
        val lvl3 = perPage * lvl2

        var hashTreePageCount = ceilDiv(hashedPagesCount, perPage)
        var hashTreeLevels = 1L
        if (hashTreePageCount > 1) {
            var result = 2L
            while (result > 1) {
                val hashBlocks = when (hashTreeLevels) {
                    1L -> ceilDiv(hashedPagesCount, lvl1)
                    2L -> ceilDiv(hashedPagesCount, lvl2)
                    3L -> ceilDiv(hashedPagesCount, lvl3)
                    else -> 1L
                }
                result = hashBlocks
                hashTreeLevels += 1
                hashTreePageCount += result
            }
        }
        if (resilient) hashTreePageCount *= 2
        return hashTreePageCount to hashTreeLevels
    }

    private fun computeHashBlockIndexForDataBlockLevel0(
        imageType: Int,
        hashTreeDepthIn: Long,
        totalHashedPages: Long,
        dataBlockIndex: Long,
        isResilient: Boolean,
    ): Pair<Long, Long> {
        if (imageType > 1) return 0L to 0L
        val perPage = HASH_ENTRIES_IN_PAGE.toLong()
        val lvl2 = perPage * perPage
        val lvl3 = lvl2 * perPage
        val lvl4 = lvl3 * perPage

        val entryIndex = dataBlockIndex % perPage
        var hashBlockIndex = dataBlockIndex / perPage
        var depth = hashTreeDepthIn - 1
        if (depth > 0) {
            hashBlockIndex += ceilDiv(totalHashedPages, lvl2)
            depth--
        }
        if (depth > 0) {
            hashBlockIndex += ceilDiv(totalHashedPages, lvl3)
            depth--
        }
        if (depth > 0) {
            hashBlockIndex += ceilDiv(totalHashedPages, lvl4)
        }
        if (isResilient) hashBlockIndex *= 2
        return hashBlockIndex to entryIndex
    }


    private fun pageToOffset(pages: Long): Long = pages * PAGE_SIZE
    private fun getPageOffset(value: Long): Long = value / PAGE_SIZE
    private fun bytesToPages(bytes: Long): Long = ceilDiv(bytes, PAGE_SIZE.toLong())
    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun readLE16(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)

    private fun readLE32(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or
            ((data[off + 1].toInt() and 0xFF) shl 8) or
            ((data[off + 2].toInt() and 0xFF) shl 16) or
            ((data[off + 3].toInt() and 0xFF) shl 24)

    private fun readLE64(data: ByteArray, off: Int): Long =
        (readLE32(data, off).toLong() and 0xFFFFFFFFL) or
            ((readLE32(data, off + 4).toLong() and 0xFFFFFFFFL) shl 32)

    private fun writeLE32(data: ByteArray, off: Int, value: Int) {
        data[off] = (value and 0xFF).toByte()
        data[off + 1] = ((value ushr 8) and 0xFF).toByte()
        data[off + 2] = ((value ushr 16) and 0xFF).toByte()
        data[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun readUtf16(data: ByteArray, off: Int, maxChars: Int): String {
        val sb = StringBuilder(maxChars)
        var i = off
        val end = minOf(off + maxChars * 2, data.size)
        while (i + 1 < end) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt() and 0xFF
            if (lo == 0 && hi == 0) break
            sb.append(((hi shl 8) or lo).toChar())
            i += 2
        }
        return sb.toString()
    }
}
