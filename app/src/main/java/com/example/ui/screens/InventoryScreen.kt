package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.core.models.AccountSessionState
import com.example.core.models.AppSettings
import com.example.core.models.DropsCampaign
import com.example.core.models.IgnoredGame
import com.example.core.models.InventorySortMode
import com.example.core.models.Platform
import com.example.ui.components.AppCard
import com.example.ui.components.InventoryCampaignCard
import com.example.ui.components.PlatformBadge
import com.example.ui.theme.LocalDropColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InventoryScreen(
    sessions: List<AccountSessionState>,
    settings: AppSettings,
    ignoredGames: List<IgnoredGame>,
    getSortedCampaigns: (List<DropsCampaign>, InventorySortMode, Boolean) -> List<DropsCampaign>,
    onSortModeChange: (InventorySortMode) -> Unit,
    onHideCompletedChange: (Boolean) -> Unit,
    onRefreshAll: () -> Unit,
    onPinCampaign: (String, String) -> Unit,
    onMoveGameToTop: (Platform, String) -> Unit,
    onIgnoreGame: (Platform, String) -> Unit,
    onUnignoreGame: (Platform, String) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDropColors.current
    var selectedAccountId by remember { mutableStateOf(sessions.firstOrNull()?.account?.id) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(sessions) {
        if (selectedAccountId == null || sessions.none { it.account.id == selectedAccountId }) {
            selectedAccountId = sessions.firstOrNull()?.account?.id
        }
    }

    val activeSession = sessions.firstOrNull { it.account.id == selectedAccountId } ?: sessions.firstOrNull()
    val sortedCampaigns = remember(activeSession?.campaigns, settings.inventorySortMode, settings.hideCompletedCampaigns) {
        activeSession?.let {
            getSortedCampaigns(it.campaigns, settings.inventorySortMode, settings.hideCompletedCampaigns)
        } ?: emptyList()
    }
    val rewardCampaigns = activeSession?.rewardCampaigns.orEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Filter / Account Picker Card
        item {
            AppCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.inventory_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    IconButton(
                        onClick = onRefreshAll,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("inventory_refresh_button")
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh campaigns",
                            tint = colors.accent
                        )
                    }
                }

                if (sessions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        sessions.forEach { session ->
                            val selected = session.account.id == activeSession?.account?.id
                            FilterChip(
                                selected = selected,
                                onClick = { selectedAccountId = session.account.id },
                                label = {
                                    Text("${session.account.platform.displayName}: ${session.account.displayName}")
                                },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.accent.copy(alpha = 0.2f),
                                    selectedLabelColor = colors.accent
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box {
                        OutlinedButton(
                            onClick = { sortMenuExpanded = true },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("inventory_sort_button"),
                            border = BorderStroke(1.dp, colors.border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary)
                        ) {
                            Text("Sort: ${settings.inventorySortMode.label}")
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            InventorySortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = {
                                        onSortModeChange(mode)
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.hide_completed_campaigns),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = settings.hideCompletedCampaigns,
                            onCheckedChange = onHideCompletedChange,
                            modifier = Modifier.testTag("hide_completed_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = colors.accent
                            )
                        )
                    }
                }
            }
        }

        if (activeSession == null) {
            item {
                AppCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CardGiftcard,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No account selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Add a Twitch or Kick account from Dashboard to inspect campaigns and drop progress.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted
                        )
                    }
                }
            }
        } else if (sortedCampaigns.isEmpty()) {
            item {
                AppCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.no_active_campaigns),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Pull to sync or tap Refresh after signing in.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted
                        )
                    }
                }
            }
        } else {
            items(sortedCampaigns, key = { "${activeSession.account.id}_${it.id}" }) { campaign ->
                InventoryCampaignCard(
                    campaign = campaign,
                    onPinCampaign = { onPinCampaign(activeSession.account.id, campaign.id) },
                    onMoveToTop = { onMoveGameToTop(campaign.platform, campaign.slug) },
                    onIgnoreGame = { onIgnoreGame(campaign.platform, campaign.slug) },
                    onOpenStreamerUrl = onOpenUrl
                )
            }
        }

        // Twitch Reward Campaigns section
        if (rewardCampaigns.isNotEmpty()) {
            item {
                AppCard {
                    Text(
                        text = stringResource(R.string.reward_campaigns_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.reward_campaigns_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        rewardCampaigns.forEach { rc ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = colors.insetBackground,
                                border = BorderStroke(1.dp, colors.border)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!rc.gameImageUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = rc.gameImageUrl,
                                            contentDescription = stringResource(R.string.cd_campaign_art),
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(colors.cardBackground)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = rc.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = colors.textPrimary
                                        )
                                        Text(
                                            text = "${rc.gameName} • ${rc.timeUntilEndText}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.textMuted
                                        )
                                    }
                                    IconButton(
                                        onClick = { onOpenUrl("https://www.twitch.tv/drops/campaigns") },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.OpenInNew,
                                            contentDescription = "Open Twitch campaigns",
                                            tint = colors.secondaryAccent
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Ignored Games section
        if (ignoredGames.isNotEmpty()) {
            item {
                AppCard {
                    Text(
                        text = stringResource(R.string.ignored_games_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.ignored_games_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ignoredGames.forEach { game ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = colors.insetBackground,
                                border = BorderStroke(1.dp, colors.border)
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PlatformBadge(platform = game.platform)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = game.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.textPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = { onUnignoreGame(game.platform, game.slug) },
                                        modifier = Modifier.height(40.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp)
                                    ) {
                                        Text("Allow", color = colors.accent, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
