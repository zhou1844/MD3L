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

class OfflineSkinServer {


    data class CharacterData(
        val uuid: UUID,
        val name: String,
        val skinFile: File,
        val skinHash: String,
        val isSlim: Boolean
    )

    private val textureCache = ConcurrentHashMap<String, ByteArray>()

    private val charactersByUuid = ConcurrentHashMap<UUID, CharacterData>()
    private val charactersByName = ConcurrentHashMap<String, CharacterData>()

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


    private fun sign(data: String): String {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(keyPair.private, SecureRandom())
        sig.update(data.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sig.sign())
    }


    private var server: ApplicationEngine? = null
    @Volatile var port: Int = 0
        private set

    val rootUrl: String get() = "http://localhost:$port"

    fun start(): Int {
        if (server != null) return port

        val engine = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            routing {
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

                post("/sessionserver/session/minecraft/join") {
                    call.respondText("", status = HttpStatusCode.NoContent)
                }

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


    private fun buildCompleteResponse(character: CharacterData): JsonObject {
        val skinUrl = "$rootUrl/textures/${character.skinHash}"

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
