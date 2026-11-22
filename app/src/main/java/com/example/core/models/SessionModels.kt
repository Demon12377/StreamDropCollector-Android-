package com.example.core.models

data class NetworkTelemetryState(
    val liveRateText: String = "",
    val averageRateText: String = "",
    val totalBytesText: String = "",
    val hasUsage: Boolean = false
)

data class PlatformConnectionState(
    val platform: Platform,
    val status: ConnectionStatus = ConnectionStatus.CHECKING,
    val loginButtonText: String = if (platform == Platform.TWITCH) "Login Twitch" else "Login Kick",
    val isLoginEnabled: Boolean = true,
    val isLoggedIn: Boolean = false
)

data class PlatformProgressState(
    val platform: Platform,
    val campaignName: String = "",
    val campaignImageUrl: String? = null,
    val gameName: String = "",
    val campaignProgressPercent: Double = 0.0,
    val campaignCaptionText: String = "No active campaign",
    val dropName: String = "",
    val dropImageUrl: String? = null,
    val dropProgressPercent: Double = 0.0,
    val dropCaptionText: String = "No active drop",
    val minedChannel: String = "",
    val minedChannelUrl: String = "",
    val levelLineText: String = "",
    val levelPercentText: String = "",
    val levelBadgeUrl: String? = null,
    val levelProgressFraction: Float = 0f,
    val dailyStreakText: String = "",
    val dailyRewardText: String = "",
    val showKickExtras: Boolean = platform == Platform.KICK
)

data class AccountSessionState(
    val account: AccountModel,
    val connection: PlatformConnectionState = PlatformConnectionState(account.platform),
    val progress: PlatformProgressState = PlatformProgressState(account.platform),
    val network: NetworkTelemetryState = NetworkTelemetryState(),
    val statusText: String = if (account.enabled) "Initializing" else "Disabled",
    val statusDetails: String = if (account.enabled) "Connecting..." else "Account is disabled",
    val minerStatus: String = if (account.enabled) "Checking" else "Disabled",
    val campaigns: List<DropsCampaign> = emptyList(),
    val rewardCampaigns: List<DropsCampaign> = emptyList(),
    val pinnedCampaignId: String? = null,
    val lastSyncEpochMs: Long? = null
) {
    val customSettingsLabel: String
        get() = if (account.overrides.hasAny) " (custom settings)" else ""
}
