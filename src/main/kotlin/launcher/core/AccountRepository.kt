package launcher.core

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID

/**
 * 持久化身份提供商 (IdP) —— 管理多账号池、Token 静默刷新、头像聚合。
 *
 * 存储位置: ~/.md3l/.accounts.json (隐藏目录)
 * 状态分发: 通过 [activeAccount] StateFlow 驱动 UI 重组，
 *          切换已授权 MSA 账号时 **绝不** 重新唤起 OAuth Device Flow，
 *          而是走 refresh_token 静默刷新链路。
 */
object AccountRepository {

    private const val CLIENT_ID = "00000000402b5328"
    private const val TOKEN_URL = "https://login.live.com/oauth20_token.srf"
    private const val XBOX_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_AUTH_URL = "https://api.minecraftservices.com/authentication/login_with_xbox"
    private const val MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"
    private const val XBOX_PROFILE_URL = "https://profile.xboxlive.com/users/me/profile/settings"

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

    private val storeFile: File
        get() {
            val dir = File(System.getProperty("user.home"), ".md3l")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, ".accounts.json")
        }

    private val avatarCacheDir: File
        get() {
            val dir = File(System.getProperty("user.home"), ".md3l/avatar_cache")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    // ═══════════════════════════════════════════════════════════════════════════
    //  持久化 I/O
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun loadFromDisk() = withContext(Dispatchers.IO) {
        try {
            if (storeFile.exists()) {
                val store = json.decodeFromString<AccountStore>(storeFile.readText(Charsets.UTF_8))
                // 为缺少头像的 MSA 账号补上 Crafatar 兜底
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  账号池 CRUD
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun addMsaAccount(
        msAccessToken: String,
        refreshToken: String,
        expiresInSeconds: Long,
    ): AccountSession = withContext(Dispatchers.IO) {
        val (xblToken, uhs) = authenticateXboxLive(msAccessToken)
        val xstsToken = authenticateXSTS(xblToken)
        val mcToken = authenticateMinecraft(xstsToken, uhs)
        val profile = fetchMinecraftProfile(mcToken)

        val avatarUrl = fetchXboxGamerpic(xstsToken, uhs).ifBlank {
            // 兜底：用 Crafatar 获取 Minecraft 皮肤头像
            "https://crafatar.com/avatars/${profile.first}?overlay&size=128"
        }

        val session = AccountSession(
            uuid = profile.first,
            username = profile.second,
            accessToken = msAccessToken,
            refreshToken = refreshToken,
            type = AccountType.MSA,
            avatarUri = avatarUrl,
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

    suspend fun removeAccount(uuid: String) {
        val list = _accounts.value.toMutableList()
        list.removeAll { it.uuid == uuid }
        _accounts.value = list
        if (_activeAccount.value?.uuid == uuid) {
            _activeAccount.value = list.firstOrNull()
        }
        persistToDisk()
    }

    /**
     * 切换活跃账号。对 MSA 账号绝对不唤起 OAuth 设备流——
     * 若 token 已过期，走 refresh_token 静默刷新。
     */
    suspend fun switchAccount(uuid: String) {
        val target = _accounts.value.find { it.uuid == uuid } ?: return
        if (target.type == AccountType.MSA && target.isExpired) {
            silentRefresh(target)
        } else {
            _activeAccount.value = target
            persistToDisk()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Token 静默刷新 (Silent Refresh)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 使用 refresh_token grant 静默获取新的 MS OAuth access_token，
     * 然后沿 XBL → XSTS → MC Auth 链路重建整条 token 链。
     * 全程无用户交互。
     */
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
            val avatarUrl = fetchXboxGamerpic(xstsToken, uhs).ifBlank {
                session.avatarUri.ifBlank { "https://crafatar.com/avatars/${profile.first}?overlay&size=128" }
            }

            val refreshed = session.copy(
                username = profile.second,
                accessToken = newAccessToken,
                refreshToken = newRefreshToken,
                xstsToken = xstsToken,
                userHash = uhs,
                avatarUri = avatarUrl,
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

    /**
     * 手动触发当前活跃 MSA 账号的 token 刷新。
     */
    suspend fun refreshActiveIfNeeded() {
        val active = _activeAccount.value ?: return
        if (active.type == AccountType.MSA && active.isExpired) {
            silentRefresh(active)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Xbox 真实头像嗅探 (Gamerpic Sniffer)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 使用 XBL3.0 鉴权请求 Xbox Profile Settings API，
     * 提取 AppDisplayPicRaw 字段获取真实 Gamerpic URL。
     */
    private suspend fun fetchXboxGamerpic(xstsToken: String, userHash: String): String {
        return try {
            // 请求多个头像字段，兼容不同账号类型
            val fields = "GameDisplayPicRaw,AppDisplayPicRaw,PublicGamerpic"
            val resp = client.get("$XBOX_PROFILE_URL?settings=$fields") {
                header("Authorization", "XBL3.0 x=$userHash;$xstsToken")
                header("x-xbl-contract-version", "2")
                header("Accept-Language", "en-US")
            }
            val bodyText = resp.bodyAsText()
            println("[Xbox] Profile response: ${bodyText.take(500)}")
            val body = json.parseToJsonElement(bodyText).jsonObject
            val profileUsers = body["profileUsers"]?.jsonArray
            val settingsList = profileUsers?.firstOrNull()?.jsonObject
                ?.get("settings")?.jsonArray

            // 按优先级尝试多个头像字段
            var url = ""
            settingsList?.forEach { settingEl ->
                val setting = settingEl.jsonObject
                val id = setting["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val value = setting["value"]?.jsonPrimitive?.contentOrNull ?: ""
                if (value.isNotBlank() && value.startsWith("http")) {
                    if (url.isBlank() || id == "GameDisplayPicRaw") {
                        url = value
                    }
                }
            }
            println("[Xbox] Gamerpic URL: $url")
            url
        } catch (e: Exception) {
            println("[Xbox] fetchXboxGamerpic failed: ${e.message}")
            ""
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  离线头像映射 (Offline Avatar Mapping)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 调用 java.awt.FileDialog 让用户选择本地图片，
     * 将其拷贝至启动器缓存目录，并将持久化 URI 映射至离线 AccountSession。
     */
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  离线皮肤选择 (Offline Skin Picker)
    // ═══════════════════════════════════════════════════════════════════════════

    private val skinCacheDir: File
        get() {
            val dir = File(System.getProperty("user.home"), ".md3l/skin_cache")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    /**
     * 调用 java.awt.FileDialog 让离线用户选择本地 .png 皮肤文件。
     * 拷贝至缓存目录，将路径持久化至 AccountSession.skinUri 并触发 UI 重组。
     */
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
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  MS OAuth → XBL → XSTS → MC Auth 内部链路
    // ═══════════════════════════════════════════════════════════════════════════

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

    /**
     * 返回 (uuid, username)
     */
    private suspend fun fetchMinecraftProfile(mcAccessToken: String): Pair<String, String> {
        val resp = client.get(MC_PROFILE_URL) {
            header("Authorization", "Bearer $mcAccessToken")
        }
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val uuid = body["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val name = body["name"]?.jsonPrimitive?.contentOrNull ?: ""
        return Pair(uuid, name)
    }
}

sealed class RefreshState {
    data object Idle : RefreshState()
    data class Refreshing(val username: String) : RefreshState()
    data class Success(val username: String) : RefreshState()
    data class Failed(val message: String) : RefreshState()
}
