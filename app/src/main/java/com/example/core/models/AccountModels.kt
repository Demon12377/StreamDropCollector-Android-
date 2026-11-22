package com.example.core.models

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AccountSettingsOverrides(
    val autoClaimRewards: Boolean? = null,
    val kickLevelFarming: Boolean? = null,
    val kickDailyClaim: Boolean? = null,
    val miningPriorityMode: MiningPriorityMode? = null,
    val prioritizeBadges: Boolean? = null,
    val mineAnything: Boolean? = null,
    val headOnlyMining: Boolean? = null,
    val gameWhitelistSlugs: List<String>? = null,
    val gameFilterBlacklistMode: Boolean = false,
    val priorityStreamers: List<String>? = null
) {
    val hasAny: Boolean
        get() = autoClaimRewards != null ||
            kickLevelFarming != null ||
            kickDailyClaim != null ||
            miningPriorityMode != null ||
            prioritizeBadges != null ||
            mineAnything != null ||
            headOnlyMining != null ||
            gameWhitelistSlugs != null ||
            priorityStreamers != null
}

@Serializable
data class AccountModel(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val platform: Platform,
    val displayName: String = "",
    val enabled: Boolean = true,
    val overrides: AccountSettingsOverrides = AccountSettingsOverrides()
)

@Serializable
data class AccountCredentials(
    val accountId: String,
    val platform: Platform,
    val authToken: String = "",
    val cookieHeader: String = "",
    val userId: String = "",
    val loginName: String = "",
    val clientId: String = "",
    val deviceId: String = "",
    val integrityToken: String = ""
)

@Serializable
data class TwitchHelixTokens(
    val accessToken: String,
    val refreshToken: String,
    val obtainedAtEpochMs: Long = System.currentTimeMillis()
)
