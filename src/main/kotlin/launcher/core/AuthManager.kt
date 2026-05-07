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
        val resp = client.submitForm(
            url = DEVICE_CODE_URL,
            formParameters = parameters {
                append("client_id", CLIENT_ID)
                append("scope", "XboxLive.signin XboxLive.offline_access")
                append("response_type", "device_code")
            },
        )
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        DeviceCodeInfo(
            userCode = body["user_code"]!!.jsonPrimitive.content,
            deviceCode = body["device_code"]!!.jsonPrimitive.content,
            verificationUri = body["verification_uri"]?.jsonPrimitive?.contentOrNull ?: "https://microsoft.com/devicelogin",
            interval = body["interval"]?.jsonPrimitive?.longOrNull ?: 5,
        )
    }

    fun openBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (_: Exception) { }
    }

    suspend fun pollForToken(deviceCode: String, interval: Long): OAuthTokenResult = withContext(Dispatchers.IO) {
        while (true) {
            delay(interval * 1000)
            val resp = client.submitForm(
                url = TOKEN_URL,
                formParameters = parameters {
                    append("grant_type", "device_code")
                    append("client_id", CLIENT_ID)
                    append("device_code", deviceCode)
                },
            )
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
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
        val resp = client.post(XBOX_AUTH_URL) {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "Properties": {
                        "AuthMethod": "RPS",
                        "SiteName": "user.auth.xboxlive.com",
                        "RpsTicket": "d=$msAccessToken"
                    },
                    "RelyingParty": "http://auth.xboxlive.com",
                    "TokenType": "JWT"
                }
            """.trimIndent())
        }
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val token = body["Token"]!!.jsonPrimitive.content
        val uhs = body["DisplayClaims"]!!.jsonObject["xui"]!!.jsonArray[0].jsonObject["uhs"]!!.jsonPrimitive.content
        Pair(token, uhs)
    }

    suspend fun authenticateXSTS(xblToken: String): String = withContext(Dispatchers.IO) {
        val resp = client.post(XSTS_URL) {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "Properties": {
                        "SandboxId": "RETAIL",
                        "UserTokens": ["$xblToken"]
                    },
                    "RelyingParty": "rp://api.minecraftservices.com/",
                    "TokenType": "JWT"
                }
            """.trimIndent())
        }
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        body["Token"]!!.jsonPrimitive.content
    }

    suspend fun authenticateMinecraft(xstsToken: String, uhs: String): String = withContext(Dispatchers.IO) {
        val resp = client.post(MC_AUTH_URL) {
            contentType(ContentType.Application.Json)
            setBody("""{"identityToken": "XBL3.0 x=$uhs;$xstsToken"}""")
        }
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        body["access_token"]!!.jsonPrimitive.content
    }

    suspend fun fetchProfile(mcAccessToken: String): MinecraftProfile = withContext(Dispatchers.IO) {
        val resp = client.get(MC_PROFILE_URL) {
            header("Authorization", "Bearer $mcAccessToken")
        }
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
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
