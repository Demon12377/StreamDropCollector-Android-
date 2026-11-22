package com.example.core.helpers

import com.example.core.models.AccountModel
import com.example.core.models.AppSettings
import com.example.core.models.DropsCampaign
import com.example.core.models.MiningPriorityMode
import com.example.core.models.Platform

data class EffectiveAccountSettings(
    val autoClaimRewards: Boolean,
    val kickLevelFarming: Boolean,
    val kickDailyClaim: Boolean,
    val miningPriorityMode: MiningPriorityMode,
    val prioritizeBadges: Boolean,
    val mineAnything: Boolean,
    val headOnlyMining: Boolean,
    val gameWhitelistSlugs: List<String>,
    val gameFilterBlacklistMode: Boolean,
    val priorityStreamers: List<String>,
    val mineRewardCampaigns: Boolean
)

object StreamerUrlParser {
    private val twitchHostRegex = Regex("^(?:https?://)?(?:www\\.|m\\.)?twitch\\.tv/([a-zA-Z0-9_]+)(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
    private val kickHostRegex = Regex("^(?:https?://)?(?:www\\.)?kick\\.com/([a-zA-Z0-9_-]+)(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
    private val plainLoginRegex = Regex("^[a-zA-Z0-9_-]+$")

    private val reservedPaths = setOf(
        "browse", "category", "directory", "drops", "inventory", "login", "settings", "subscriptions", "videos"
    )

    fun extractLogin(input: String, platform: Platform? = null): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null

        val twitchMatch = twitchHostRegex.matchEntire(trimmed)
        if (twitchMatch != null && platform != Platform.KICK) {
            val login = twitchMatch.groupValues[1].lowercase()
            return if (login in reservedPaths) null else login
        }

        val kickMatch = kickHostRegex.matchEntire(trimmed)
        if (kickMatch != null && platform != Platform.TWITCH) {
            val login = kickMatch.groupValues[1].lowercase()
            return if (login in reservedPaths) null else login
        }

        if (!trimmed.contains('/') && !trimmed.contains('.') && plainLoginRegex.matches(trimmed)) {
            return trimmed.lowercase()
        }
        return null
    }

    fun parseCommaSeparatedLogins(raw: String, platform: Platform): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',', '\n', ';')
            .mapNotNull { extractLogin(it, platform) }
            .distinct()
    }
}

object ActiveCampaignFilter {
    fun resolveEffectiveSettings(account: AccountModel, global: AppSettings): EffectiveAccountSettings {
        val ov = account.overrides
        val isTwitch = account.platform == Platform.TWITCH
        val defaultFilter = if (isTwitch) global.twitchGameWhitelistSlugs else global.kickGameWhitelistSlugs
        val defaultBlacklist = if (isTwitch) global.twitchGameFilterBlacklistMode else global.kickGameFilterBlacklistMode
        val defaultStreamers = if (isTwitch) global.twitchPriorityStreamers else global.kickPriorityStreamers

        return EffectiveAccountSettings(
            autoClaimRewards = ov.autoClaimRewards ?: global.autoClaimRewards,
            kickLevelFarming = ov.kickLevelFarming ?: global.kickLevelFarming,
            kickDailyClaim = ov.kickDailyClaim ?: global.kickDailyClaim,
            miningPriorityMode = ov.miningPriorityMode ?: global.miningPriorityMode,
            prioritizeBadges = ov.prioritizeBadges ?: global.prioritizeBadges,
            mineAnything = ov.mineAnything ?: global.mineAnything,
            headOnlyMining = ov.headOnlyMining ?: global.headOnlyMining,
            gameWhitelistSlugs = ov.gameWhitelistSlugs ?: defaultFilter,
            gameFilterBlacklistMode = if (ov.gameWhitelistSlugs != null) ov.gameFilterBlacklistMode else defaultBlacklist,
            priorityStreamers = ov.priorityStreamers ?: defaultStreamers,
            mineRewardCampaigns = global.mineRewardCampaigns
        )
    }

    fun filterForMining(
        campaigns: List<DropsCampaign>,
        effective: EffectiveAccountSettings
    ): List<DropsCampaign> {
        return campaigns.filter { campaign ->
            if (!effective.mineRewardCampaigns && campaign.isRewardCampaign) return@filter false
            if (!effective.mineAnything && campaign.rewards.all { it.isEmote }) return@filter false
            if (campaign.canFilterByGame && effective.gameWhitelistSlugs.isNotEmpty()) {
                val slug = campaign.slug.trim().lowercase()
                val inList = effective.gameWhitelistSlugs.any { it.trim().lowercase() == slug }
                if (effective.gameFilterBlacklistMode) {
                    if (inList) return@filter false
                } else {
                    if (!inList) return@filter false
                }
            }
            true
        }
    }
}

object GameSlugHelper {
    fun toSlug(name: String): String {
        return name.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }
}
