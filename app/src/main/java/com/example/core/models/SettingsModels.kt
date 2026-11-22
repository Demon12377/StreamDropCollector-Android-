package com.example.core.models

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val runInBackground: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val updateFrequency: UpdateFrequency = UpdateFrequency.DAILY,
    val includeBetaUpdates: Boolean = false,
    val autoClaimRewards: Boolean = true,
    val notifyOnReadyToClaim: Boolean = true,
    val notifyOnAutoClaimed: Boolean = true,
    val notifyOnNewUpdateAvailable: Boolean = true,
    val quietMode: Boolean = false,
    val webhookEnabled: Boolean = false,
    val webhookUrl: String = "",
    val webhookOnDropClaimed: Boolean = true,
    val webhookOnDropReadyToClaim: Boolean = true,
    val webhookOnDropClaimFailed: Boolean = true,
    val webhookOnAccountNotLinked: Boolean = true,
    val webhookOnUpdateAvailable: Boolean = true,
    val webhookOnAppUpdated: Boolean = true,
    val verboseDebugLogging: Boolean = false,
    val updateAvailable: Boolean = false,
    val lastUpdateCheckEpochMs: Long? = null,
    val miningPriorityMode: MiningPriorityMode = MiningPriorityMode.AVAILABILITY_THEN_PROGRESS,
    val headOnlyMining: Boolean = false,
    val prioritizeBadges: Boolean = false,
    val mineAnything: Boolean = false,
    val mineRewardCampaigns: Boolean = true,
    val hideCompletedCampaigns: Boolean = true,
    val kickLevelFarming: Boolean = false,
    val kickDailyClaim: Boolean = false,
    val twitchGameWhitelistSlugs: List<String> = emptyList(),
    val kickGameWhitelistSlugs: List<String> = emptyList(),
    val twitchGameFilterBlacklistMode: Boolean = false,
    val kickGameFilterBlacklistMode: Boolean = false,
    val twitchPriorityStreamers: List<String> = emptyList(),
    val kickPriorityStreamers: List<String> = emptyList(),
    val inventorySortMode: InventorySortMode = InventorySortMode.DEFAULT
) {
    fun isWebhookEventEnabled(type: NotificationType): Boolean = when (type) {
        NotificationType.DROP_CLAIMED -> webhookOnDropClaimed
        NotificationType.DROP_READY_TO_CLAIM -> webhookOnDropReadyToClaim
        NotificationType.DROP_CLAIM_FAILED -> webhookOnDropClaimFailed
        NotificationType.ACCOUNT_NOT_LINKED -> webhookOnAccountNotLinked
        NotificationType.UPDATE_AVAILABLE -> webhookOnUpdateAvailable
        NotificationType.APP_UPDATED -> webhookOnAppUpdated
    }
}

data class GameFilterOption(
    val slug: String,
    val displayName: String,
    val isSelected: Boolean
)

data class IgnoredGame(
    val slug: String,
    val name: String,
    val platform: Platform
)

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val level: String,
    val category: String,
    val message: String
)
