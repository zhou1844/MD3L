package launcher.core

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.request.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * 本地 Yggdrasil 认证 + 纹理服务器，用于离线账户皮肤加载。
 *
 * 完全参照 HMCL 的 YggdrasilServer 实现：
 *   - 启动一个嵌入式的 HTTP 服务器（Ktor Netty）
 *   - 实现 Yggdrasil API 的 /、/status、/api/profiles/minecraft、
 *     /sessionserver/session/minecraft/hasJoined、/sessionserver/session/minecraft/profile/{uuid}、
 *     /textures/{hash} 路由
 *   - 皮肤纹理用 SHA-256 哈希标识，通过 /textures/{hash} 提供 PNG
 *   - RSA 密钥对用于 properties 签名
 *   - 配合 authlib-injector Java Agent 注入到 MC 启动参数
 *
 * 相比资源包方案的优势：
 *   - 完全绕过资源包兼容性检查（pack_format、supported_formats、known_packs.json）
 *   - 对所有 MC 版本（包括 1.21.11+、快照版 26w06a 等）通用
 *   - MC 通过原生认证管线加载皮肤，不会出现"不兼容的资源包"提示
 */
class OfflineSkinServer {

    // ── 角色数据结构 ──────────────────────────────────────────────────

    data class CharacterData(
        val uuid: UUID,
        val name: String,
        val skinFile: File,
        val skinHash: String,
        val isSlim: Boolean
    )

    // ── 纹理缓存 ──────────────────────────────────────────────────────
    // hash → PNG bytes
    private val textureCache = ConcurrentHashMap<String, ByteArray>()

    // ── 角色注册表 ────────────────────────────────────────────────────
    private val charactersByUuid = ConcurrentHashMap<UUID, CharacterData>()
    private val charactersByName = ConcurrentHashMap<String, CharacterData>()

    // ── RSA 密钥对 ────────────────────────────────────────────────────
    private val keyPair: KeyPair = generateRsaKeyPair()

    private fun generateRsaKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, SecureRandom())
        return gen.generateKeyPair()
    }

    fun getPublicKeyPem(): String {
        val pub = keyPair.public as RSAPublicKey
        val encoded = Base64.getEncoder().encodeToString(pub.encoded)
        return "-----BEGIN PUBLIC KEY-----\n${encoded.chunked(64).joinToString("\n")}\n-----END PUBLIC KEY-----"
    }

    // ── SHA-256 纹理哈希（完全参照 HMCL 算法）────────────────────────

    private fun computeTextureHash(imageBytes: ByteArray): String {
        val img = ImageIO.read(ByteArrayInputStream(imageBytes))
            ?: throw IllegalArgumentException("Cannot decode image for hashing")
        val digest = MessageDigest.getInstance("SHA-256")
        val width = img.width
        val height = img.height
        val buf = ByteArray(4096)

        fun putInt(buf: ByteArray, offset: Int, value: Int) {
            buf[offset + 0] = (value shr 24 and 0xff).toByte()
            buf[offset + 1] = (value shr 16 and 0xff).toByte()
            buf[offset + 2] = (value shr 8 and 0xff).toByte()
            buf[offset + 3] = (value shr 0 and 0xff).toByte()
        }

        putInt(buf, 0, width)
        putInt(buf, 4, height)
        var pos = 8
        for (x in 0 until width) {
            for (y in 0 until height) {
                val argb = img.getRGB(x, y)
                putInt(buf, pos, argb)
                // alpha=0 → 所有通道清零（HMCL 行为）
                if (buf[pos + 0] == 0.toByte()) {
                    buf[pos + 1] = 0
                    buf[pos + 2] = 0
                    buf[pos + 3] = 0
                }
                pos += 4
                if (pos == buf.size) {
                    pos = 0
                    digest.update(buf, 0, buf.size)
                }
            }
        }
        if (pos > 0) {
            digest.update(buf, 0, pos)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ── 注册离线角色 ──────────────────────────────────────────────────

    fun addCharacter(uuid: String, name: String, skinFile: File, isSlim: Boolean) {
        val imageBytes = skinFile.readBytes()
        val hash = computeTextureHash(imageBytes)
        textureCache[hash] = imageBytes
        val character = CharacterData(
            uuid = UUID.fromString(uuid),
            name = name,
            skinFile = skinFile,
            skinHash = hash,
            isSlim = isSlim
        )
        charactersByUuid[UUID.fromString(uuid)] = character
        charactersByName[name] = character
        println("[OfflineSkinServer] 已注册角色: $name (uuid=$uuid, hash=$hash, slim=$isSlim, size=${imageBytes.size})")
    }

    // ── RSA 签名 ──────────────────────────────────────────────────────

    private fun sign(data: String): String {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(keyPair.private, SecureRandom())
        sig.update(data.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    // ── Ktor 服务器 ───────────────────────────────────────────────────

    private var server: ApplicationEngine? = null
    @Volatile var port: Int = 0
        private set

    val rootUrl: String get() = "http://localhost:$port"

    fun start(): Int {
        if (server != null) return port

        val engine = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            routing {
                // ── 根路径：Yggdrasil 元数据 ──────────────────────────
                get("/") {
                    val responseJson = Json.encodeToString(
                        buildJsonObject {
                            put("signaturePublickey", getPublicKeyPem())
                            putJsonArray("skinDomains") {
                                add("127.0.0.1")
                                add("localhost")
                            }
                            putJsonObject("meta") {
                                put("serverName", "MD3L")
                                put("implementationName", "MD3L")
                                put("implementationVersion", "1.0")
                                put("feature.non_email_login", true)
                            }
                        }
                    )
                    call.respondText(responseJson, ContentType.Application.Json)
                }

                // ── /status ──────────────────────────────────────────
                get("/status") {
                    val responseJson = Json.encodeToString(
                        buildJsonObject {
                            put("user.count", charactersByUuid.size)
                            put("token.count", 0)
                            put("pendingAuthentication.count", 0)
                        }
                    )
                    call.respondText(responseJson, ContentType.Application.Json)
                }

                // ── /api/profiles/minecraft ──────────────────────────
                post("/api/profiles/minecraft") {
                    val body = call.receiveText()
                    val names = try {
                        Json.parseToJsonElement(body).jsonArray.map { it.jsonPrimitive.content }
                    } catch (_: Exception) {
                        listOf<String>()
                    }
                    val profilesArray = buildJsonArray {
                        names.distinct().forEach { name ->
                            charactersByName[name]?.let { c ->
                                addJsonObject {
                                    put("id", c.uuid.toString().replace("-", ""))
                                    put("name", c.name)
                                }
                            }
                        }
                    }
                    call.respondText(Json.encodeToString(profilesArray), ContentType.Application.Json)
                }

                // ── /sessionserver/session/minecraft/hasJoined ───────
                get("/sessionserver/session/minecraft/hasJoined") {
                    val username = call.request.queryParameters["username"]
                    if (username == null) {
                        call.respondText("400 bad request", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                    val character = charactersByName[username]
                    if (character != null) {
                        call.respondText(Json.encodeToString(buildCompleteResponse(character)), ContentType.Application.Json)
                    } else {
                        call.respondText("", status = HttpStatusCode.NoContent)
                    }
                }

                // ── /sessionserver/session/minecraft/join ────────────
                post("/sessionserver/session/minecraft/join") {
                    call.respondText("", status = HttpStatusCode.NoContent)
                }

                // ── /sessionserver/session/minecraft/profile/<uuid> ──
                get("/sessionserver/session/minecraft/profile/{uuid}") {
                    val uuidStr = call.parameters["uuid"] ?: ""
                    val uuid = try {
                        UUID.fromString(uuidStr.replaceFirst(
                            "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})".toRegex(),
                            "$1-$2-$3-$4-$5"
                        ))
                    } catch (_: Exception) { null }
                    val character = uuid?.let { charactersByUuid[it] }
                    if (character != null) {
                        call.respondText(Json.encodeToString(buildCompleteResponse(character)), ContentType.Application.Json)
                    } else {
                        call.respondText("", status = HttpStatusCode.NoContent)
                    }
                }

                // ── /textures/{hash} ─────────────────────────────────
                get("/textures/{hash}") {
                    val hash = call.parameters["hash"] ?: ""
                    val data = textureCache[hash]
                    if (data != null) {
                        call.response.header("Etag", "\"$hash\"")
                        call.response.header("Cache-Control", "max-age=2592000, public")
                        call.respondBytes(data, ContentType.Image.PNG)
                    } else {
                        call.respondText("404 not found", status = HttpStatusCode.NotFound)
                    }
                }
            }
        }

        server = engine
        engine.start(wait = false)
        port = runBlocking { engine.resolvedConnectors().first().port }
        println("[OfflineSkinServer] 已启动在 http://localhost:$port")
        return port
    }

    fun stop() {
        server?.stop(500, 1000)
        server = null
        println("[OfflineSkinServer] 已停止")
    }

    // ── 构建完整角色响应（含 textures 属性）──────────────────────────

    private fun buildCompleteResponse(character: CharacterData): JsonObject {
        val skinUrl = "$rootUrl/textures/${character.skinHash}"

        // 构建内层 textures JSON（将嵌入到 sign 中）
        val innerTexturesJson = Json.encodeToString(
            buildJsonObject {
                put("timestamp", System.currentTimeMillis())
                put("profileId", character.uuid.toString().replace("-", ""))
                put("profileName", character.name)
                putJsonObject("textures") {
                    if (character.isSlim) {
                        putJsonObject("SKIN") {
                            put("url", skinUrl)
                            putJsonObject("metadata") {
                                put("model", "slim")
                            }
                        }
                    } else {
                        putJsonObject("SKIN") {
                            put("url", skinUrl)
                        }
                    }
                }
            }
        )

        val texturesBase64 = Base64.getEncoder().encodeToString(
            innerTexturesJson.toByteArray(Charsets.UTF_8)
        )

        // 构建完整响应
        return buildJsonObject {
            put("id", character.uuid.toString().replace("-", ""))
            put("name", character.name)
            putJsonArray("properties") {
                addJsonObject {
                    put("name", "textures")
                    put("value", texturesBase64)
                    put("signature", sign(texturesBase64))
                }
            }
        }
    }
}
