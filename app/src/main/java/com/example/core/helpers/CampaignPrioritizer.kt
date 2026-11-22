package com.example.core.helpers

import com.example.core.models.DropsCampaign
import com.example.core.models.MiningPriorityMode

data class CampaignSelectionResult(
    val campaign: DropsCampaign?,
    val pinReleased: Boolean
)

object CampaignPrioritizer {

    fun selectBest(
        campaigns: List<DropsCampaign>,
        pinnedCampaignId: String?,
        mode: MiningPriorityMode,
        prioritizeBadges: Boolean = false,
        gamePriority: List<String>? = null
    ): CampaignSelectionResult {
        val ranked = rankCandidates(
            campaigns = campaigns,
            pinnedCampaignId = pinnedCampaignId,
            mode = mode,
            prioritizeBadges = prioritizeBadges,
            gamePriority = gamePriority
        )
        return CampaignSelectionResult(
            campaign = ranked.campaigns.firstOrNull(),
            pinReleased = ranked.pinReleased
        )
    }

    data class RankedCampaignsResult(
        val campaigns: List<DropsCampaign>,
        val pinReleased: Boolean
    )

    fun rankCandidates(
        campaigns: List<DropsCampaign>,
        pinnedCampaignId: String?,
        mode: MiningPriorityMode,
        prioritizeBadges: Boolean = false,
        gamePriority: List<String>? = null
    ): RankedCampaignsResult {
        if (campaigns.isEmpty()) {
            return RankedCampaignsResult(emptyList(), pinReleased = pinnedCampaignId != null)
        }

        val orderedAll = sortAllByPriority(campaigns, mode, prioritizeBadges, gamePriority)
        if (!pinnedCampaignId.isNullOrBlank()) {
            val pinned = campaigns.firstOrNull { it.id == pinnedCampaignId }
            if (pinned != null) {
                val rest = orderedAll.filterNot { it.id == pinned.id }
                return RankedCampaignsResult(listOf(pinned) + rest, pinReleased = false)
            }
            return RankedCampaignsResult(orderedAll, pinReleased = true)
        }

        return RankedCampaignsResult(orderedAll, pinReleased = false)
    }

    private fun sortAllByPriority(
        campaigns: List<DropsCampaign>,
        mode: MiningPriorityMode,
        prioritizeBadges: Boolean,
        gamePriority: List<String>?
    ): List<DropsCampaign> {
        fun gameRank(c: DropsCampaign): Int {
            if (gamePriority.isNullOrEmpty()) return 0
            val slug = c.slug.trim()
            val idx = gamePriority.indexOfFirst { it.equals(slug, ignoreCase = true) }
            return if (idx >= 0) idx else Int.MAX_VALUE
        }

        fun minRemaining(c: DropsCampaign): Int =
            c.rewards.filter { !it.isClaimed }.minOfOrNull { (it.requiredMinutes - it.progressMinutes).coerceAtLeast(0) } ?: Int.MAX_VALUE

        val baseComparator = when (mode) {
            MiningPriorityMode.ENDING_SOONEST -> compareBy<DropsCampaign> { gameRank(it) }
                .thenBy { it.isRewardCampaign }
                .thenBy { it.endsAtEpochMs }
                .thenBy { minRemaining(it) }
            MiningPriorityMode.LEAST_TIME_TO_NEXT_REWARD -> compareBy<DropsCampaign> { gameRank(it) }
                .thenBy { it.isRewardCampaign }
                .thenBy { it.isGeneralDrop }
                .thenBy { minRemaining(it) }
                .thenByDescending { it.completionPercentage }
            MiningPriorityMode.HIGHEST_COMPLETION -> compareBy<DropsCampaign> { gameRank(it) }
                .thenBy { it.isRewardCampaign }
                .thenBy { it.isGeneralDrop }
                .thenByDescending { it.completionPercentage }
                .thenBy { it.endsAtEpochMs }
                .thenBy { minRemaining(it) }
            MiningPriorityMode.AVAILABILITY_THEN_PROGRESS -> compareBy<DropsCampaign> { gameRank(it) }
                .thenBy { it.isRewardCampaign }
                .thenBy { it.isGeneralDrop }
                .thenByDescending { it.completionPercentage }
                .thenBy { minRemaining(it) }
        }

        val sorted = campaigns.sortedWith(baseComparator)
        if (!prioritizeBadges) return sorted
        val withBadges = sorted.filter { it.hasUnclaimedBadge }
        val withoutBadges = sorted.filterNot { it.hasUnclaimedBadge }
        return withBadges + withoutBadges
    }
}
