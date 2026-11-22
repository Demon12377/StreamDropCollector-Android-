package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.core.models.DropsCampaign
import com.example.core.models.DropsReward
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalDropColors
import com.example.ui.theme.StatusDanger
import com.example.ui.theme.StatusWarning

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InventoryCampaignCard(
    campaign: DropsCampaign,
    onPinCampaign: () -> Unit,
    onMoveToTop: () -> Unit,
    onIgnoreGame: () -> Unit,
    onOpenStreamerUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDropColors.current
    var showStreamers by remember { mutableStateOf(false) }

    AppCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            if (!campaign.gameImageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = campaign.gameImageUrl,
                    contentDescription = stringResource(R.string.cd_campaign_art),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.insetBackground)
                )
                Spacer(modifier = Modifier.width(14.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = campaign.gameName,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = campaign.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PlatformBadge(platform = campaign.platform)
                    if (campaign.isCurrentCampaign) {
                        StatusBadge("MINING", StatusBadgeVariant.MINING)
                    }
                    if (campaign.hasBadge) {
                        StatusBadge("BADGE", StatusBadgeVariant.BADGE)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Ends ${campaign.formattedEndsAt}",
                style = MaterialTheme.typography.labelSmall,
                color = StatusDanger
            )
            Text(
                text = "${campaign.timeUntilEndText} • ${campaign.timeToCompleteText}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
        }

        if (campaign.needsAccountLink) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = StatusWarning.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Link your game account on ${campaign.platform.displayName} to mine and claim this campaign.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusWarning
                    )
                }
            }
        }

        // Action bar
        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!campaign.isCurrentCampaign && !campaign.allRewardsClaimed && !campaign.needsAccountLink) {
                Button(
                    onClick = onPinCampaign,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("pin_campaign_${campaign.id}")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Mine instead", fontWeight = FontWeight.Bold)
                }
            }

            if (campaign.canFilterByGame) {
                OutlinedButton(
                    onClick = onMoveToTop,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("mine_first_${campaign.id}"),
                    border = BorderStroke(1.dp, colors.border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary)
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Mine first")
                }

                OutlinedButton(
                    onClick = onIgnoreGame,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("ignore_game_${campaign.id}"),
                    border = BorderStroke(1.dp, StatusDanger.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusDanger)
                ) {
                    Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ignore game")
                }
            }
        }

        if (campaign.eligibleStreamers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showStreamers = !showStreamers }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Eligible streamers (${campaign.eligibleStreamers.size})",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.secondaryAccent,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (showStreamers) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle eligible streamers",
                    tint = colors.secondaryAccent
                )
            }
            AnimatedVisibility(visible = showStreamers) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    campaign.eligibleStreamers.take(18).forEach { streamer ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = colors.insetBackground,
                            border = BorderStroke(1.dp, colors.border),
                            modifier = Modifier.clickable { onOpenStreamerUrl(streamer.url) }
                        ) {
                            Text(
                                text = streamer.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // Rewards list
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Rewards",
            style = MaterialTheme.typography.titleMedium,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            campaign.rewards.forEach { reward ->
                InventoryRewardCard(reward = reward)
            }
        }
    }
}

@Composable
fun InventoryRewardCard(
    reward: DropsReward,
    modifier: Modifier = Modifier
) {
    val colors = LocalDropColors.current
    val borderColor = if (reward.isCurrentReward) colors.secondaryAccent else colors.border
    val borderWidth = if (reward.isCurrentReward) 1.5.dp else 1.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.insetBackground)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!reward.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = reward.imageUrl,
                contentDescription = stringResource(R.string.cd_reward_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.cardBackground)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = reward.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (reward.isBadge) {
                    Spacer(modifier = Modifier.width(6.dp))
                    StatusBadge("BADGE", StatusBadgeVariant.BADGE)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${reward.progressMinutes} / ${reward.requiredMinutes} min • ${reward.timeToCompleteText}",
                fontFamily = JetBrainsMonoFamily,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            NeonProgressBar(
                progress = reward.progressFraction,
                barColor = colors.accent,
                trackColor = colors.border
            )
        }

        if (reward.isClaimed) {
            Spacer(modifier = Modifier.width(8.dp))
            StatusBadge("CLAIMED", StatusBadgeVariant.CLAIMED)
        } else if (reward.isCurrentReward) {
            Spacer(modifier = Modifier.width(8.dp))
            StatusBadge("MINING", StatusBadgeVariant.MINING)
        }
    }
}
