package launcher.core

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID

object AccountRepository {

    private const val CLIENT_ID = "00000000402b5328"
    private const val TOKEN_URL = "https://login.live.com/oauth20_token.srf"
    private const val XBOX_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_AUTH_URL = "https://api.minecraftservices.com/authentication/login_with_xbox"
    private const val MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 30_000 }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _accounts = MutableStateFlow<List<AccountSession>>(emptyList())
    val accounts: StateFlow<List<AccountSession>> = _accounts.asStateFlow()

    private val _activeAccount = MutableStateFlow<AccountSession?>(null)
    val activeAccount: StateFlow<AccountSession?> = _activeAccount.asStateFlow()

    private val _refreshState = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()

    /** 皮肤导入成功事件（用于右下角弹窗提示） */
    data class SkinImportEvent(val username: String, val model: String)

    private val _skinImportEvent = MutableSharedFlow<SkinImportEvent>(extraBufferCapacity = 1)
    val skinImportEvent: SharedFlow<SkinImportEvent> = _skinImportEvent.asSharedFlow()

    private val storeFile: File
        get() = File(LauncherDirs.dataDir, ".accounts.json")

    private val avatarCacheDir: File
        get() = File(LauncherDirs.dataDir, "avatar_cache").also { it.mkdirs() }

    suspend fun loadFromDisk() = withContext(Dispatchers.IO) {
        try {
            if (storeFile.exists()) {
                val store = json.decodeFromString<AccountStore>(storeFile.readText(Charsets.UTF_8))
                var needsPersist = false
                val patched = store.accounts.map { acct ->
                    if (acct.type == AccountType.MSA && acct.avatarUri.isBlank() && acct.uuid.isNotBlank()) {
                        needsPersist = true
                        acct.copy(avatarUri = "https://crafatar.com/avatars/${acct.uuid}?overlay&size=128")
                    } else acct
                }
                _accounts.value = patched
                if (store.activeIndex in patched.indices) {
                    val active = patched[store.activeIndex]
                    _activeAccount.value = active
                    if (active.type == AccountType.MSA && active.isExpired) {
                        silentRefresh(active)
                    }
                }
                if (needsPersist) persistToDisk()
            }
        } catch (e: Exception) {
            _accounts.value = emptyList()
            _activeAccount.value = null
        }
    }

    private suspend fun persistToDisk() = withContext(Dispatchers.IO) {
        val currentAccounts = _accounts.value
        val activeIdx = _activeAccount.value?.let { active ->
            currentAccounts.indexOfFirst { it.uuid == active.uuid }
        } ?: -1
        val store = AccountStore(activeIndex = activeIdx, accounts = currentAccounts)
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(json.encodeToString(store), Charsets.UTF_8)
    }

    suspend fun addMsaAccount(
        msAccessToken: String,
        refreshToken: String,
        expiresInSeconds: Long,
    ): AccountSession = withContext(Dispatchers.IO) {
        val (xblToken, uhs) = authenticateXboxLive(msAccessToken)
        val xstsToken = authenticateXSTS(xblToken)
        val mcToken = authenticateMinecraft(xstsToken, uhs)
        val profile = fetchMinecraftProfile(mcToken)

        // 使用 crafatar 作为头像来源，不再调用 Xbox Profile API
        val avatarUrl = "https://crafatar.com/avatars/${profile.uuid}?overlay&size=128"

        val session = AccountSession(
            uuid = profile.uuid,
            username = profile.name,
            accessToken = msAccessToken,
            refreshToken = refreshToken,
            type = AccountType.MSA,
            avatarUri = avatarUrl,
            skinUri = profile.skinUrl,
            skinModel = profile.skinModel,
            xstsToken = xstsToken,
            userHash = uhs,
            tokenExpiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000),
            minecraftAccessToken = mcToken,
        )

        val list = _accounts.value.toMutableList()
        val existingIdx = list.indexOfFirst { it.uuid == session.uuid }
        if (existingIdx >= 0) {
            list[existingIdx] = session
        } else {
            list.add(session)
        }
        _accounts.value = list
        _activeAccount.value = session
        persistToDisk()
        session
    }

    suspend fun addOfflineAccount(playerName: String): AccountSession = withContext(Dispatchers.IO) {
        val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$playerName".toByteArray()).toString()
        val session = AccountSession(
            uuid = uuid,
            username = playerName,
            accessToken = "0",
            refreshToken = "",
            type = AccountType.Offline,
        )
        val list = _accounts.value.toMutableList()
        val existingIdx = list.indexOfFirst { it.uuid == session.uuid }
        if (existingIdx >= 0) {
            list[existingIdx] = session
        } else {
            list.add(session)
        }
        _accounts.value = list
        _activeAccount.value = session
        persistToDisk()
        session
    }

    suspend fun addThirdPartyAccount(
        authServerUrl: String,
        serverName: String,
        email: String,
        password: String
    ): AccountSession = withContext(Dispatchers.IO) {
        val serverUrl = authServerUrl.trimEnd('/')
        val jsonBody = """
            {
                "agent": {
                    "name": "Minecraft",
                    "version": 1
                },
                "username": "$email",
                "password": "$password"
            }
        """.trimIndent()

        val resp = client.post("$serverUrl/authserver/authenticate") {
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        
        if (!resp.status.isSuccess()) {
            val errorBody = try { json.parseToJsonElement(resp.bodyAsText()).jsonObject } catch (e: Exception) { null }
            val errorMsg = errorBody?.get("errorMessage")?.jsonPrimitive?.contentOrNull ?: "HTTP ${resp.status.value}"
            throw RuntimeException("第三方登录失败: $errorMsg")
        }

        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val accessToken = body["accessToken"]?.jsonPrimitive?.contentOrNull ?: throw RuntimeException("响应中缺少 accessToken")
        val clientToken = body["clientToken"]?.jsonPrimitive?.contentOrNull ?: ""
        
        val selectedProfile = body["selectedProfile"]?.jsonObject
            ?: body["availableProfiles"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw RuntimeException("账号下没有可用的角色(Profile)")
            
        val uuid = selectedProfile["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val name = selectedProfile["name"]?.jsonPrimitive?.contentOrNull ?: ""

        // 皮肤解析使用对象级别的 parseSkinFromTextures()（定义在 pickOfflineSkin 上方）

        var skinUrl = ""
        var skinModel = "classic"

        // 优先从 authenticate 响应的 selectedProfile.properties 中解析皮肤（部分第三方服务器
        // 的 sessionserver 接口无水纹/权限限制，但 authenticate 响应中已包含完整纹理信息）
        val authTexturesProp = selectedProfile["properties"]?.jsonArray
            ?.find { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == "textures" }
        val authBase64Value = authTexturesProp?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
        if (!authBase64Value.isNullOrBlank()) {
            try {
                val (url, model) = parseSkinFromTextures(authBase64Value)
                if (url.isNotBlank()) {
                    skinUrl = url
                    skinModel = model
                    println("[Auth] 从 authenticate 响应中获取皮肤: $skinUrl ($skinModel)")
                }
            } catch (e: Exception) {
                println("[Auth] 解析 authenticate 响应中的纹理失败: ${e.message}")
            }
        }

        // 如果 authenticate 响应中没有皮肤纹理，再尝试 sessionserver profile 接口
        if (skinUrl.isBlank()) {
            try {
                val profileResp = client.get("$serverUrl/sessionserver/session/minecraft/profile/$uuid")
                if (profileResp.status.isSuccess()) {
                    val profileBody = json.parseToJsonElement(profileResp.bodyAsText()).jsonObject
                    val properties = profileBody["properties"]?.jsonArray
                    val texturesProp = properties?.find { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == "textures" }
                    val base64Value = texturesProp?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                    if (!base64Value.isNullOrBlank()) {
                        val (url, model) = parseSkinFromTextures(base64Value)
                        if (url.isNotBlank()) {
                            skinUrl = url
                            skinModel = model
                            println("[Auth] 从 sessionserver 获取皮肤: $skinUrl ($skinModel)")
                        }
                    }
                }
            } catch (e: Exception) {
                println("[Auth] 获取第三方登录角色皮肤失败: ${e.message}")
            }
        }

        // 格式化UUID为标准连字符格式（8-4-4-4-12）
        val formattedUuid = if (uuid.length == 32 && !uuid.contains("-")) {
            "${uuid.substring(0,8)}-${uuid.substring(8,12)}-${uuid.substring(12,16)}-${uuid.substring(16,20)}-${uuid.substring(20)}"
        } else uuid
        // 第三方账号头像：crafatar 不认识第三方服务器的 UUID，
        // 优先使用皮肤文件 URL 作为头像（皮肤图片可直接展示为头像），
        // 若无皮肤则回退到 crafatar（至少有个占位显示）
        val avatarUrl = if (skinUrl.isNotBlank()) {
            skinUrl
        } else if (formattedUuid.isNotBlank()) {
            "https://crafatar.com/avatars/$formattedUuid?overlay&size=128"
        } else ""

        val session = AccountSession(
            uuid = formattedUuid.ifBlank { uuid },
            username = name,
            accessToken = accessToken,
            refreshToken = clientToken,
            type = AccountType.ThirdParty,
            authServerUrl = serverUrl,
            serverName = serverName.ifBlank { "第三方服务器" },
            thirdPartyEmail = email,
            minecraftAccessToken = accessToken,
            avatarUri = avatarUrl,
            skinUri = skinUrl,
            skinModel = skinModel,
        )

        val list = _accounts.value.toMutableList()
        val existingIdx = list.indexOfFirst { it.uuid == session.uuid }
        if (existingIdx >= 0) {
            list[existingIdx] = session
        } else {
            list.add(session)
        }
        _accounts.value = list
        _activeAccount.value = session
        persistToDisk()
        session
    }

    suspend fun removeAccount(uuid: String) {
        val list = _accounts.value.toMutableList()
        list.removeAll { it.uuid == uuid }
        _accounts.value = list
        if (_activeAccount.value?.uuid == uuid) {
            _activeAccount.value = list.firstOrNull()
        }
        persistToDisk()
    }

    suspend fun switchAccount(uuid: String) {
        val target = _accounts.value.find { it.uuid == uuid } ?: return
        if (target.type == AccountType.MSA && target.isExpired) {
            silentRefresh(target)
        } else {
            _activeAccount.value = target
            persistToDisk()
        }
    }

    private suspend fun silentRefresh(session: AccountSession) {
        _refreshState.value = RefreshState.Refreshing(session.username)
        try {
            val resp = client.submitForm(
                url = TOKEN_URL,
                formParameters = parameters {
                    append("grant_type", "refresh_token")
                    append("client_id", CLIENT_ID)
                    append("refresh_token", session.refreshToken)
                    append("scope", "XboxLive.signin XboxLive.offline_access")
                },
            )
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val error = body["error"]?.jsonPrimitive?.contentOrNull
            if (!error.isNullOrBlank()) {
                _refreshState.value = RefreshState.Failed("Refresh token 已失效: $error")
                return
            }

            val newAccessToken = body["access_token"]!!.jsonPrimitive.content
            val newRefreshToken = body["refresh_token"]?.jsonPrimitive?.contentOrNull ?: session.refreshToken
            val expiresIn = body["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600

            val (xblToken, uhs) = authenticateXboxLive(newAccessToken)
            val xstsToken = authenticateXSTS(xblToken)
            val mcToken = authenticateMinecraft(xstsToken, uhs)
            val profile = fetchMinecraftProfile(mcToken)
            // 使用 crafatar 作为头像来源，不再调用 Xbox Profile API
            val avatarUrl = session.avatarUri.ifBlank { "https://crafatar.com/avatars/${profile.uuid}?overlay&size=128" }

            val refreshed = session.copy(
                username = profile.name,
                accessToken = newAccessToken,
                refreshToken = newRefreshToken,
                xstsToken = xstsToken,
                userHash = uhs,
                avatarUri = avatarUrl,
                skinUri = profile.skinUrl.ifBlank { session.skinUri },
                skinModel = if (profile.skinUrl.isNotBlank()) profile.skinModel else session.skinModel,
                tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000),
                minecraftAccessToken = mcToken,
            )

            val list = _accounts.value.toMutableList()
            val idx = list.indexOfFirst { it.uuid == refreshed.uuid }
            if (idx >= 0) list[idx] = refreshed
            _accounts.value = list
            _activeAccount.value = refreshed
            persistToDisk()
            _refreshState.value = RefreshState.Success(refreshed.username)
        } catch (e: Exception) {
            _refreshState.value = RefreshState.Failed("静默刷新失败: ${e.message}")
        }
    }

    suspend fun refreshActiveIfNeeded() {
        val active = _activeAccount.value ?: return
        if (active.type == AccountType.MSA && active.isExpired) {
            silentRefresh(active)
        }
    }


    suspend fun pickOfflineAvatar(uuid: String) = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "选择头像图片", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
            val lower = name.lowercase()
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".bmp")
        }
        dialog.isVisible = true

        val selectedFile = dialog.file ?: return@withContext
        val selectedDir = dialog.directory ?: return@withContext
        val sourceFile = File(selectedDir, selectedFile)
        if (!sourceFile.exists()) return@withContext

        avatarCacheDir.listFiles()?.forEach { old ->
            if (old.nameWithoutExtension == uuid || old.nameWithoutExtension.startsWith("$uuid-")) {
                old.delete()
            }
        }
        val ext = sourceFile.extension.ifBlank { "png" }
        val destFile = File(avatarCacheDir, "$uuid-${System.currentTimeMillis()}.$ext")
        sourceFile.copyTo(destFile, overwrite = true)

        val list = _accounts.value.toMutableList()
        val idx = list.indexOfFirst { it.uuid == uuid }
        if (idx >= 0) {
            val updated = list[idx].copy(avatarUri = destFile.absolutePath)
            list[idx] = updated
            _accounts.value = list
            if (_activeAccount.value?.uuid == uuid) {
                _activeAccount.value = updated
            }
            persistToDisk()
        }
    }

    private val skinCacheDir: File
        get() = File(LauncherDirs.dataDir, "skin_cache").also { it.mkdirs() }

    /**
     * 刷新正版(MSA)或第三方登录的皮肤信息。
     * MSA：调用 Minecraft Profile API 获取当前激活的皮肤 URL/型号。
     * 第三方：调用 sessionserver profile 端点重新拉取纹理。
     */
    suspend fun refreshSkin(uuid: String): AccountSession? = withContext(Dispatchers.IO) {
        val account = _accounts.value.find { it.uuid == uuid } ?: return@withContext null
        val updated = when (account.type) {
            AccountType.MSA -> {
                try {
                    val profile = fetchMinecraftProfile(account.minecraftAccessToken)
                    account.copy(skinUri = profile.skinUrl, skinModel = profile.skinModel)
                } catch (e: Exception) {
                    println("[SkinRefresh] MSA 刷新皮肤失败: ${e.message}")
                    null
                }
            }
            AccountType.ThirdParty -> {
                try {
                    val serverUrl = account.authServerUrl.trimEnd('/')
                    val profileResp = client.get("$serverUrl/sessionserver/session/minecraft/profile/$uuid")
                    if (!profileResp.status.isSuccess()) {
                        println("[SkinRefresh] 第三方 sessionserver 返回 ${profileResp.status.value}")
                        null
                    } else {
                        val profileBody = json.parseToJsonElement(profileResp.bodyAsText()).jsonObject
                        val texturesProp = profileBody["properties"]?.jsonArray
                            ?.find { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == "textures" }
                        val base64Value = texturesProp?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                        if (!base64Value.isNullOrBlank()) {
                            val (skinUrl, skinModel) = parseSkinFromTextures(base64Value)
                            account.copy(skinUri = skinUrl, skinModel = skinModel)
                        } else {
                            account.copy(skinUri = "", skinModel = "classic")
                        }
                    }
                } catch (e: Exception) {
                    println("[SkinRefresh] 第三方刷新皮肤失败: ${e.message}")
                    null
                }
            }
            else -> null // 离线账号不支持此操作
        }
        if (updated != null) {
            val list = _accounts.value.toMutableList()
            val idx = list.indexOfFirst { it.uuid == uuid }
            if (idx >= 0) {
                list[idx] = updated
                _accounts.value = list
                if (_activeAccount.value?.uuid == uuid) {
                    _activeAccount.value = updated
                }
                persistToDisk()
            }
        }
        updated
    }

    // 将 parseSkinFromTextures 提升为 object 级别的可复用函数（第三方登录添加/刷新共用）
    private fun parseSkinFromTextures(base64Value: String): Pair<String, String> {
        val decoded = String(java.util.Base64.getDecoder().decode(base64Value))
        val texturesJson = json.parseToJsonElement(decoded).jsonObject
        val skinObj = texturesJson["textures"]?.jsonObject?.get("SKIN")?.jsonObject
        var url = skinObj?.get("url")?.jsonPrimitive?.contentOrNull ?: ""
        if (url.contains("minecraft.net/")) {
            url = url.replace("http://", "https://")
        }
        val modelMeta = skinObj?.get("metadata")?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull
        val model = if (modelMeta == "slim") "slim" else "classic"
        return Pair(url, model)
    }

    suspend fun pickOfflineSkin(uuid: String, model: String) = withContext(Dispatchers.IO) {
        val account = _accounts.value.find { it.uuid == uuid } ?: return@withContext
        if (account.type != AccountType.Offline) return@withContext
        val dialog = FileDialog(null as Frame?, "选择皮肤文件 (.png)", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.lowercase().endsWith(".png") }
        dialog.isVisible = true

        val selectedFile = dialog.file ?: return@withContext
        val selectedDir = dialog.directory ?: return@withContext
        val sourceFile = File(selectedDir, selectedFile)
        if (!sourceFile.exists()) return@withContext

        val destFile = File(skinCacheDir, "${uuid}_skin.png")
        sourceFile.copyTo(destFile, overwrite = true)

        val list = _accounts.value.toMutableList()
        val idx = list.indexOfFirst { it.uuid == uuid }
        if (idx >= 0) {
            val updated = list[idx].copy(skinUri = destFile.absolutePath, skinModel = model)
            list[idx] = updated
            _accounts.value = list
            if (_activeAccount.value?.uuid == uuid) {
                _activeAccount.value = updated
            }
            persistToDisk()
            _skinImportEvent.tryEmit(SkinImportEvent(account.username, model))
        }
    }

    private suspend fun authenticateXboxLive(msAccessToken: String): Pair<String, String> {
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
        return Pair(token, uhs)
    }

    private suspend fun authenticateXSTS(xblToken: String): String {
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
        return body["Token"]!!.jsonPrimitive.content
    }

    private suspend fun authenticateMinecraft(xstsToken: String, uhs: String): String {
        val resp = client.post(MC_AUTH_URL) {
            contentType(ContentType.Application.Json)
            setBody("""{"identityToken": "XBL3.0 x=$uhs;$xstsToken"}""")
        }
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        return body["access_token"]!!.jsonPrimitive.content
    }

    data class McProfile(val uuid: String, val name: String, val skinUrl: String, val skinModel: String)

    private suspend fun fetchMinecraftProfile(mcAccessToken: String): McProfile {
        val resp = client.get(MC_PROFILE_URL) {
            header("Authorization", "Bearer $mcAccessToken")
        }
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val uuid = body["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val name = body["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val activeSkin = runCatching {
            body["skins"]?.jsonArray
                ?.firstOrNull { it.jsonObject["state"]?.jsonPrimitive?.contentOrNull == "ACTIVE" }
                ?.jsonObject
        }.getOrNull()
        val skinUrl = activeSkin?.get("url")?.jsonPrimitive?.contentOrNull ?: ""
        // MC Profile API 返回 "variant": "SLIM" 或 "CLASSIC"
        val skinVariant = activeSkin?.get("variant")?.jsonPrimitive?.contentOrNull?.lowercase() ?: "classic"
        val skinModel = if (skinVariant == "slim") "slim" else "classic"
        return McProfile(uuid, name, skinUrl, skinModel)
    }
}

sealed class RefreshState {
    data object Idle : RefreshState()
    data class Refreshing(val username: String) : RefreshState()
    data class Success(val username: String) : RefreshState()
    data class Failed(val message: String) : RefreshState()
}
