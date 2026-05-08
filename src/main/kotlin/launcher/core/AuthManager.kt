package launcher.core

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.awt.Desktop
import java.net.URI

/**
 * curl.exe fallback HTTP helper for Windows machines where JVM SSL fails.
 */
private fun curlPost(url: String, headers: Map<String, String> = emptyMap(), body: String = "", formData: Map<String, String> = emptyMap()): String? {
    return try {
        val cmd = mutableListOf("curl.exe", "-sL", "--connect-timeout", "15", "--max-time", "30")
        headers.forEach { (k, v) -> cmd.addAll(listOf("-H", "$k: $v")) }
        if (formData.isNotEmpty()) {
            formData.forEach { (k, v) -> cmd.addAll(listOf("-d", "$k=$v")) }
        } else if (body.isNotBlank()) {
            cmd.addAll(listOf("-H", "Content-Type: application/json", "-d", body))
        }
        cmd.add(url)
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        val ok = proc.waitFor(35, java.util.concurrent.TimeUnit.SECONDS)
        if (ok && proc.exitValue() == 0 && text.isNotBlank()) text.trim() else null
    } catch (_: Exception) { null }
}

private fun curlGet(url: String, headers: Map<String, String> = emptyMap()): String? {
    return try {
        val cmd = mutableListOf("curl.exe", "-sL", "--connect-timeout", "15", "--max-time", "30")
        headers.forEach { (k, v) -> cmd.addAll(listOf("-H", "$k: $v")) }
        cmd.add(url)
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        val ok = proc.waitFor(35, java.util.concurrent.TimeUnit.SECONDS)
        if (ok && proc.exitValue() == 0 && text.isNotBlank()) text.trim() else null
    } catch (_: Exception) { null }
}

data class DeviceCodeInfo(
    val userCode: String,
    val deviceCode: String,
    val verificationUri: String,
    val interval: Long,
)

data class OAuthTokenResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

data class MinecraftProfile(
    val uuid: String,
    val name: String,
    val accessToken: String,
    val skinUrl: String = "",
    val msAccessToken: String = "",
    val refreshToken: String = "",
    val expiresIn: Long = 3600,
)

object AuthManager {

    private const val CLIENT_ID = "00000000402b5328"
    private const val DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf"
    private const val TOKEN_URL = "https://login.live.com/oauth20_token.srf"
    private const val XBOX_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_AUTH_URL = "https://api.minecraftservices.com/authentication/login_with_xbox"
    private const val MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 30_000 }
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun requestDeviceCode(): DeviceCodeInfo = withContext(Dispatchers.IO) {
        val text = try {
            client.submitForm(
                url = DEVICE_CODE_URL,
                formParameters = parameters {
                    append("client_id", CLIENT_ID)
                    append("scope", "XboxLive.signin XboxLive.offline_access")
                    append("response_type", "device_code")
                },
            ).bodyAsText()
        } catch (e: Exception) {
            println("[Auth] Ktor device code 请求失败: ${e.message}，尝试 curl")
            curlPost(DEVICE_CODE_URL, formData = mapOf(
                "client_id" to CLIENT_ID,
                "scope" to "XboxLive.signin+XboxLive.offline_access",
                "response_type" to "device_code",
            )) ?: throw RuntimeException("获取设备码失败（Ktor + curl 均失败）: ${e.message}")
        }
        val body = json.parseToJsonElement(text).jsonObject
        DeviceCodeInfo(
            userCode = body["user_code"]!!.jsonPrimitive.content,
            deviceCode = body["device_code"]!!.jsonPrimitive.content,
            verificationUri = body["verification_uri"]?.jsonPrimitive?.contentOrNull ?: "https://microsoft.com/devicelogin",
            interval = body["interval"]?.jsonPrimitive?.longOrNull ?: 5,
        )
    }

    fun openBrowser(url: String) {
        // 方式 1: Desktop API
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        } catch (_: Exception) { }
        // 方式 2: cmd start
        try {
            ProcessBuilder("cmd", "/c", "start", url.replace("&", "^&")).start()
            return
        } catch (_: Exception) { }
        // 方式 3: rundll32
        try {
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
            return
        } catch (_: Exception) { }
        // 方式 4: powershell
        try {
            ProcessBuilder("powershell", "-NoProfile", "-Command", "Start-Process '${url.replace("'", "''")}'")
                .start()
        } catch (_: Exception) { }
    }

    suspend fun pollForToken(deviceCode: String, interval: Long): OAuthTokenResult = withContext(Dispatchers.IO) {
        while (true) {
            delay(interval * 1000)
            val text = try {
                client.submitForm(
                    url = TOKEN_URL,
                    formParameters = parameters {
                        append("grant_type", "device_code")
                        append("client_id", CLIENT_ID)
                        append("device_code", deviceCode)
                    },
                ).bodyAsText()
            } catch (e: Exception) {
                println("[Auth] Ktor token poll 失败: ${e.message}，尝试 curl")
                curlPost(TOKEN_URL, formData = mapOf(
                    "grant_type" to "device_code",
                    "client_id" to CLIENT_ID,
                    "device_code" to deviceCode,
                )) ?: continue
            }
            val body = json.parseToJsonElement(text).jsonObject
            val error = body["error"]?.jsonPrimitive?.contentOrNull
            if (error == null || error.isBlank()) {
                return@withContext OAuthTokenResult(
                    accessToken = body["access_token"]!!.jsonPrimitive.content,
                    refreshToken = body["refresh_token"]?.jsonPrimitive?.contentOrNull ?: "",
                    expiresIn = body["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600,
                )
            }
            if (error == "expired_token" || error == "bad_verification_code") {
                throw Exception("验证超时或验证码无效: $error")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw Exception("unreachable")
    }

    suspend fun authenticateXboxLive(msAccessToken: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val jsonBody = """{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"d=$msAccessToken"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}"""
        val text = try {
            client.post(XBOX_AUTH_URL) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }.bodyAsText()
        } catch (e: Exception) {
            println("[Auth] Ktor Xbox auth 失败: ${e.message}，尝试 curl")
            curlPost(XBOX_AUTH_URL, body = jsonBody)
                ?: throw RuntimeException("Xbox Live 认证失败: ${e.message}")
        }
        val body = json.parseToJsonElement(text).jsonObject
        val token = body["Token"]!!.jsonPrimitive.content
        val uhs = body["DisplayClaims"]!!.jsonObject["xui"]!!.jsonArray[0].jsonObject["uhs"]!!.jsonPrimitive.content
        Pair(token, uhs)
    }

    suspend fun authenticateXSTS(xblToken: String): String = withContext(Dispatchers.IO) {
        val jsonBody = """{"Properties":{"SandboxId":"RETAIL","UserTokens":["$xblToken"]},"RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}"""
        val text = try {
            client.post(XSTS_URL) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }.bodyAsText()
        } catch (e: Exception) {
            println("[Auth] Ktor XSTS 失败: ${e.message}，尝试 curl")
            curlPost(XSTS_URL, body = jsonBody)
                ?: throw RuntimeException("XSTS 认证失败: ${e.message}")
        }
        val body = json.parseToJsonElement(text).jsonObject
        body["Token"]!!.jsonPrimitive.content
    }

    suspend fun authenticateMinecraft(xstsToken: String, uhs: String): String = withContext(Dispatchers.IO) {
        val jsonBody = """{"identityToken":"XBL3.0 x=$uhs;$xstsToken"}"""
        val text = try {
            client.post(MC_AUTH_URL) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }.bodyAsText()
        } catch (e: Exception) {
            println("[Auth] Ktor MC auth 失败: ${e.message}，尝试 curl")
            curlPost(MC_AUTH_URL, body = jsonBody)
                ?: throw RuntimeException("Minecraft 认证失败: ${e.message}")
        }
        val body = json.parseToJsonElement(text).jsonObject
        body["access_token"]!!.jsonPrimitive.content
    }

    suspend fun fetchProfile(mcAccessToken: String): MinecraftProfile = withContext(Dispatchers.IO) {
        val text = try {
            client.get(MC_PROFILE_URL) {
                header("Authorization", "Bearer $mcAccessToken")
            }.bodyAsText()
        } catch (e: Exception) {
            println("[Auth] Ktor profile 失败: ${e.message}，尝试 curl")
            curlGet(MC_PROFILE_URL, mapOf("Authorization" to "Bearer $mcAccessToken"))
                ?: throw RuntimeException("获取 Minecraft 档案失败: ${e.message}")
        }
        val body = json.parseToJsonElement(text).jsonObject
        val uuid = body["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val name = body["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val skinUrl = try {
            body["skins"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull ?: ""
        } catch (_: Exception) { "" }
        MinecraftProfile(uuid = uuid, name = name, accessToken = mcAccessToken, skinUrl = skinUrl)
    }

    suspend fun fullLogin(deviceCode: String, interval: Long): MinecraftProfile {
        val oauthResult = pollForToken(deviceCode, interval)
        val (xblToken, uhs) = authenticateXboxLive(oauthResult.accessToken)
        val xstsToken = authenticateXSTS(xblToken)
        val mcToken = authenticateMinecraft(xstsToken, uhs)
        val profile = fetchProfile(mcToken)
        return profile.copy(
            msAccessToken = oauthResult.accessToken,
            refreshToken = oauthResult.refreshToken,
            expiresIn = oauthResult.expiresIn,
        )
    }
}
