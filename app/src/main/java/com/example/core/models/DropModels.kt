package com.example.core.models

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max

@Serializable
data class DropsReward(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val requiredMinutes: Int,
    val progressMinutes: Int = 0,
    val isClaimed: Boolean = false,
    val dropInstanceId: String? = null,
    val isCurrentReward: Boolean = false,
    val isBadge: Boolean = false,
    val isEmote: Boolean = false,
    val availableAtEpochMs: Long? = null
) {
    val isWaiting: Boolean
        get() = !isClaimed && availableAtEpochMs != null && availableAtEpochMs > System.currentTimeMillis()

    val remainingMinutes: Int
        get() = if (isClaimed) 0 else max(0, requiredMinutes - progressMinutes)

    val progressFraction: Float
        get() = if (requiredMinutes <= 0) 1f else (progressMinutes.toFloat() / requiredMinutes).coerceIn(0f, 1f)

    val isReadyToClaim: Boolean
        get() = !isClaimed && progressMinutes >= requiredMinutes

    val timeToCompleteText: String
        get() = when {
            isClaimed -> "Done"
            isWaiting -> DurationFormatter.formatAvailableAt(availableAtEpochMs!!)
            else -> DurationFormatter.formatRemaining(remainingMinutes)
        }
}

@Serializable
data class EligibleStreamer(
    val login: String,
    val displayName: String,
    val url: String,
    val isLive: Boolean = false,
    val viewerCount: Int = 0,
    val gameName: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class DropsCampaign(
    val id: String,
    val name: String,
    val slug: String,
    val gameId: String? = null,
    val gameName: String,
    val gameImageUrl: String? = null,
    val startsAtEpochMs: Long,
    val endsAtEpochMs: Long,
    val rewards: List<DropsReward>,
    val platform: Platform,
    val connectUrls: List<String> = emptyList(),
    val isGeneralDrop: Boolean = false,
    val isCurrentCampaign: Boolean = false,
    val needsAccountLink: Boolean = false,
    val isRewardCampaign: Boolean = false,
    val participatingCategories: List<String> = emptyList(),
    val categoryHint: String? = null,
    val eligibleStreamers: List<EligibleStreamer> = emptyList()
) {
    val canFilterByGame: Boolean
        get() = !isRewardCampaign && slug.isNotBlank()

    val acceptsAnyChannel: Boolean
        get() = isGeneralDrop || isRewardCampaign

    val allRewardsClaimed: Boolean
        get() = rewards.isNotEmpty() && rewards.all { it.isClaimed }

    val hasBadge: Boolean
        get() = rewards.any { it.isBadge }

    val hasEmote: Boolean
        get() = rewards.any { it.isEmote }

    val hasUnclaimedBadge: Boolean
        get() = rewards.any { it.isBadge && !it.isClaimed }

    val remainingMinutes: Int
        get() = rewards.maxOfOrNull { it.remainingMinutes } ?: 0

    val completionPercentage: Double
        get() {
            if (rewards.isEmpty()) return 0.0
            val totalReq = rewards.sumOf { max(1, it.requiredMinutes) }
            val totalProg = rewards.sumOf { if (it.isClaimed) max(1, it.requiredMinutes) else it.progressMinutes.coerceIn(0, max(1, it.requiredMinutes)) }
            return (totalProg.toDouble() / totalReq.toDouble()) * 100.0
        }

    val waitingUntilEpochMs: Long?
        get() {
            val open = rewards.filter { !it.isClaimed && it.progressMinutes < it.requiredMinutes }
            return if (open.isNotEmpty() && open.all { it.isWaiting }) open.minOfOrNull { it.availableAtEpochMs!! } else null
        }

    val timeToCompleteText: String
        get() = when {
            allRewardsClaimed -> "Completed"
            waitingUntilEpochMs != null -> DurationFormatter.formatAvailableAt(waitingUntilEpochMs!!)
            else -> DurationFormatter.formatRemaining(remainingMinutes).replace("left", "to complete")
        }

    val timeUntilEndText: String
        get() {
            val diffMs = endsAtEpochMs - System.currentTimeMillis()
            if (diffMs <= 0L) return "Ended"
            val mins = max(1, ceil(diffMs / 60_000.0).toInt())
            return "${DurationFormatter.format(mins)} until it ends"
        }

    val formattedEndsAt: String
        get() {
            val zdt = Instant.ofEpochMilli(endsAtEpochMs).atZone(ZoneId.systemDefault())
            return DateTimeFormatter.ofPattern("MMM dd • HH:mm").format(zdt)
        }

    fun hasReadyToClaimRewards(): Boolean = rewards.any { !it.isClaimed && it.progressMinutes >= it.requiredMinutes }

    fun hasProgressToMake(autoClaimRewards: Boolean): Boolean {
        if (needsAccountLink) return false
        if (System.currentTimeMillis() >= endsAtEpochMs) return false
        if (waitingUntilEpochMs != null) return false
        return rewards.any { reward ->
            !reward.isClaimed && !reward.isWaiting && (reward.progressMinutes < reward.requiredMinutes || (autoClaimRewards && !isRewardCampaign))
        }
    }
}

@Serializable
data class KickLevelProgress(
    val level: Int,
    val progressXp: Long,
    val xpToNextLevel: Long,
    val totalXp: Long? = null,
    val badgeUrl: String? = null
) {
    val percent: Double
        get() {
            val total = progressXp + xpToNextLevel
            return if (total <= 0) 0.0 else (progressXp.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0)
        }
}

@Serializable
data class KickChallenge(
    val id: String,
    val name: String,
    val recurrence: String,
    val status: String,
    val conditionType: String,
    val progress: Long,
    val threshold: Long,
    val endsAtEpochMs: Long? = null
) {
    val isDaily: Boolean
        get() = recurrence.equals("daily", ignoreCase = true)

    val isClaimed: Boolean
        get() = status.equals("claimed", ignoreCase = true) || status.equals("completed", ignoreCase = true)

    val isReadyToClaim: Boolean
        get() = !isClaimed && threshold > 0 && progress >= threshold
}

@Serializable
data class LiveChannelSnapshot(
    val login: String,
    val displayName: String,
    val isLive: Boolean,
    val viewerCount: Int = 0,
    val gameName: String? = null,
    val gameSlug: String? = null,
    val title: String? = null,
    val dropsEnabled: Boolean = true
)

data class TwitchDeviceCodePrompt(
    val verificationUri: String,
    val userCode: String,
    val activationUrl: String
)

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseUrl: String,
    val releaseNotes: String,
    val isBeta: Boolean,
    val isUpdateAvailable: Boolean
)

object DurationFormatter {
    fun format(minutes: Int): String {
        if (minutes <= 0) return "0m"
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val mins = minutes % 60
        return when {
            days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
            hours > 0 -> if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
            else -> "${mins}m"
        }
    }

    fun formatCountdown(seconds: Int): String {
        if (seconds <= 0) return ""
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> "%dh %02dm %02ds".format(hours, mins, secs)
            mins > 0 -> "%dm %02ds".format(mins, secs)
            else -> "${secs}s"
        }
    }

    fun formatAvailableAt(epochMs: Long): String {
        val zone = ZoneId.systemDefault()
        val target = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.now().atZone(zone).truncatedTo(ChronoUnit.DAYS)
        val targetDay = target.truncatedTo(ChronoUnit.DAYS)
        val fmt = if (today == targetDay) DateTimeFormatter.ofPattern("HH:mm") else DateTimeFormatter.ofPattern("EEE HH:mm")
        return "Daily limit reached, next at ${fmt.format(target)}"
    }

    fun formatRemaining(minutes: Int): String =
        if (minutes <= 0) "Ready to claim" else "${format(minutes)} left"
}
