package launcher.core

import kotlinx.serialization.Serializable

@Serializable
enum class AccountType { MSA, Offline }

/**
 * 持久化账号会话实体。
 * 序列化至 ~/.md3l/.accounts.json，包含完整的 OAuth Token 链。
 */
@Serializable
data class AccountSession(
    val uuid: String,
    val username: String,
    val accessToken: String,
    val refreshToken: String,
    val type: AccountType,
    val avatarUri: String = "",
    val skinUri: String = "",
    val skinModel: String = "classic",
    val xstsToken: String = "",
    val userHash: String = "",
    val tokenExpiresAt: Long = 0L,
    val minecraftAccessToken: String = "",
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() > tokenExpiresAt

    val displayType: String
        get() = when (type) {
            AccountType.MSA -> "微软正版"
            AccountType.Offline -> "离线模式"
        }
}

/**
 * 持久化容器：包含活跃账号索引 + 完整账号池。
 */
@Serializable
data class AccountStore(
    val activeIndex: Int = -1,
    val accounts: List<AccountSession> = emptyList(),
)
