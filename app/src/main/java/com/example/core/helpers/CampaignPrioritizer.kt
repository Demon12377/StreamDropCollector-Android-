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
        if (campaigns.isEmpty()) {
            return CampaignSelectionResult(null, pinReleased = pinnedCampaignId != null)
        }

        if (!pinnedCampaignId.isNullOrBlank()) {
            val pinned = campaigns.firstOrNull { it.id == pinnedCampaignId }
            if (pinned != null) {
                return CampaignSelectionResult(pinned, pinReleased = false)
            }
            val narrowed = topPriorityGame(campaigns, gamePriority)
            return CampaignSelectionResult(selectByPriority(narrowed, mode, prioritizeBadges), pinReleased = true)
        }

        val narrowed = topPriorityGame(campaigns, gamePriority)
        return CampaignSelectionResult(selectByPriority(narrowed, mode, prioritizeBadges), pinReleased = false)
    }

    private fun topPriorityGame(campaigns: List<DropsCampaign>, gamePriority: List<String>?): List<DropsCampaign> {
        if (gamePriority.isNullOrEmpty() || campaigns.isEmpty()) return campaigns

        fun rank(c: DropsCampaign): Int {
            val slug = c.slug.trim()
            val idx = gamePriority.indexOfFirst { it.equals(slug, ignoreCase = true) }
            return if (idx >= 0) idx else Int.MAX_VALUE
        }

        val bestRank = campaigns.minOf(::rank)
        return campaigns.filter { rank(it) == bestRank }
    }

    private fun selectByPriority(
        campaigns: List<DropsCampaign>,
        mode: MiningPriorityMode,
        prioritizeBadges: Boolean
    ): DropsCampaign? {
        fun minRemaining(c: DropsCampaign): Int =
            c.rewards.filter { !it.isClaimed }.minOfOrNull { (it.requiredMinutes - it.progressMinutes).coerceAtLeast(0) } ?: Int.MAX_VALUE

        val sorted = when (mode) {
            MiningPriorityMode.ENDING_SOONEST -> campaigns.sortedWith(
                compareBy<DropsCampaign> { it.isRewardCampaign }
                    .thenBy { it.endsAtEpochMs }
                    .thenBy { minRemaining(it) }
            )
            MiningPriorityMode.LEAST_TIME_TO_NEXT_REWARD -> campaigns.sortedWith(
                compareBy<DropsCampaign> { it.isRewardCampaign }
                    .thenBy { it.isGeneralDrop }
                    .thenBy { minRemaining(it) }
                    .thenByDescending { it.completionPercentage }
            )
            MiningPriorityMode.HIGHEST_COMPLETION -> campaigns.sortedWith(
                compareBy<DropsCampaign> { it.isRewardCampaign }
                    .thenBy { it.isGeneralDrop }
                    .thenByDescending { it.completionPercentage }
                    .thenBy { it.endsAtEpochMs }
                    .thenBy { minRemaining(it) }
            )
            MiningPriorityMode.AVAILABILITY_THEN_PROGRESS -> campaigns.sortedWith(
                compareBy<DropsCampaign> { it.isRewardCampaign }
                    .thenBy { it.isGeneralDrop }
                    .thenByDescending { it.completionPercentage }
                    .thenBy { minRemaining(it) }
            )
        }

        val badgePick = if (prioritizeBadges) sorted.firstOrNull { it.hasUnclaimedBadge } else null
        return badgePick ?: sorted.firstOrNull()
    }
}
