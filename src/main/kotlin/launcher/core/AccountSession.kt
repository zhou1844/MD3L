package launcher.core

import kotlinx.serialization.Serializable

@Serializable
enum class AccountType { MSA, Offline, ThirdParty }

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
    val authServerUrl: String = "",
    val serverName: String = "",
    val thirdPartyEmail: String = "",
) {
    val isExpired: Boolean
        get() = if (type == AccountType.ThirdParty) false else System.currentTimeMillis() > tokenExpiresAt

    val displayType: String
        get() = when (type) {
            AccountType.MSA -> "微软正版"
            AccountType.Offline -> "离线模式"
            AccountType.ThirdParty -> if (serverName.isNotBlank()) serverName else "第三方登录"
        }
}

@Serializable
data class AccountStore(
    val activeIndex: Int = -1,
    val accounts: List<AccountSession> = emptyList(),
)
