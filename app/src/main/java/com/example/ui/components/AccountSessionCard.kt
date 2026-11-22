package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.core.models.AccountSessionState
import com.example.core.models.ConnectionStatus
import com.example.core.models.Platform
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.KickGreen
import com.example.ui.theme.LocalDropColors
import com.example.ui.theme.StatusChecking
import com.example.ui.theme.StatusDanger
import com.example.ui.theme.StatusOffline
import com.example.ui.theme.StatusOnline
import com.example.ui.theme.TwitchPurple

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountSessionCard(
    session: AccountSessionState,
    onToggleEnabled: (Boolean) -> Unit,
    onLoginClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onResetNetworkClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDropColors.current
    val account = session.account
    val isTwitch = account.platform == Platform.TWITCH
    val brandColor = if (isTwitch) TwitchPurple else KickGreen
    val statusDotColor = when (session.connection.status) {
        ConnectionStatus.ONLINE -> StatusOnline
        ConnectionStatus.CHECKING -> StatusChecking
        ConnectionStatus.ERROR -> StatusDanger
        ConnectionStatus.OFFLINE, ConnectionStatus.DISABLED -> StatusOffline
    }

    AppCard(modifier = modifier) {
        // Header row: status dot + platform + username + enabled toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusDotColor)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = account.platform.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = brandColor
                    )
                    Text(
                        text = " • ",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textMuted
                    )
                    Text(
                        text = account.displayName.ifBlank { "New Account" },
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (session.customSettingsLabel.isNotBlank()) {
                        Text(
                            text = session.customSettingsLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${session.connection.status.label} • ${session.statusText}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = account.enabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.testTag("account_enabled_switch_${account.id}"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = colors.accent,
                    uncheckedThumbColor = colors.textMuted,
                    uncheckedTrackColor = colors.insetBackground
                )
            )
        }

        // Network Telemetry Pill
        if (session.network.hasUsage) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = colors.insetBackground,
                border = BorderStroke(1.dp, colors.border)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "↓ ${session.network.liveRateText}",
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent
                    )
                    Text(
                        text = "  •  avg ${session.network.averageRateText}",
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                    Text(
                        text = "  •  ${session.network.totalBytesText}",
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IconButton(
                        onClick = onResetNetworkClick,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("reset_network_${account.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset network counters",
                            tint = colors.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Account action buttons
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onLoginClick,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("account_login_btn_${account.id}"),
                border = BorderStroke(1.dp, brandColor.copy(alpha = 0.7f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = brandColor)
            ) {
                Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(session.connection.loginButtonText)
            }

            OutlinedButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("account_settings_btn_${account.id}"),
                border = BorderStroke(1.dp, colors.border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Settings")
            }

            OutlinedButton(
                onClick = onRemoveClick,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("account_remove_btn_${account.id}"),
                border = BorderStroke(1.dp, StatusDanger.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusDanger)
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Remove")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Progress subsection (matching PlatformCampaignProgressCard.xaml)
        val prog = session.progress
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.insetBackground)
                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                .padding(14.dp)
        ) {
            Text(
                text = "${account.platform.displayName} Campaign Progress",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )

            if (prog.campaignName.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!prog.campaignImageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = prog.campaignImageUrl,
                            contentDescription = stringResource(R.string.cd_campaign_art),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 34.dp, height = 44.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = prog.campaignName,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = brandColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (prog.gameName.isNotBlank()) {
                            Text(
                                text = prog.gameName,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            NeonProgressBar(
                progress = (prog.campaignProgressPercent / 100.0).toFloat(),
                barColor = colors.accent,
                trackColor = colors.border
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = prog.campaignCaptionText,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Drop Progress",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )

            if (prog.dropName.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!prog.dropImageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = prog.dropImageUrl,
                            contentDescription = stringResource(R.string.cd_reward_art),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = prog.dropName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            NeonProgressBar(
                progress = (prog.dropProgressPercent / 100.0).toFloat(),
                barColor = colors.secondaryAccent,
                trackColor = colors.border
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = prog.dropCaptionText,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted
            )

            if (prog.showKickExtras && (prog.levelLineText.isNotBlank() || prog.dailyRewardText.isNotBlank())) {
                Spacer(modifier = Modifier.height(10.dp))
                if (prog.levelLineText.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!prog.levelBadgeUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = prog.levelBadgeUrl,
                                contentDescription = stringResource(R.string.cd_level_badge),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = prog.levelLineText + prog.levelPercentText,
                            style = MaterialTheme.typography.labelSmall,
                            color = KickGreen
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    NeonProgressBar(
                        progress = prog.levelProgressFraction,
                        barColor = KickGreen,
                        trackColor = colors.border
                    )
                }
                if (prog.dailyRewardText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Daily streak: ${prog.dailyStreakText}  •  Daily reward: ${prog.dailyRewardText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
            }

            if (prog.minedChannel.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Mining: ${prog.minedChannel}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = brandColor
                )
            }
        }
    }
}
