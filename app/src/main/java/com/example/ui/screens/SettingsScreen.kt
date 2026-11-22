package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.models.AppSettings
import com.example.core.models.MiningPriorityMode
import com.example.core.models.Platform
import com.example.core.models.ThemeMode
import com.example.core.models.UpdateFrequency
import com.example.core.models.UpdateInfo
import com.example.data.KnownGameEntry
import com.example.ui.components.GameFilterPanel
import com.example.ui.components.SettingsSectionCard
import com.example.ui.components.SettingsToggleRow
import com.example.ui.theme.KickGreen
import com.example.ui.theme.LocalDropColors
import com.example.ui.theme.StatusDanger

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    knownGames: List<KnownGameEntry>,
    updateInfo: UpdateInfo?,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onUpdatePriorityStreamers: (Platform, String) -> Unit,
    onImportKickFollows: () -> Unit,
    onSendTestWebhook: (String) -> Unit,
    onOpenLogsViewer: () -> Unit,
    onRemoveAllAccounts: () -> Unit,
    onRequestNotificationPermissionIfNeeded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDropColors.current

    var twitchStreamersDraft by remember { mutableStateOf(settings.twitchPriorityStreamers.joinToString(", ")) }
    var kickStreamersDraft by remember { mutableStateOf(settings.kickPriorityStreamers.joinToString(", ")) }
    var webhookUrlDraft by remember { mutableStateOf(settings.webhookUrl) }
    var showRemoveAllDialog by remember { mutableStateOf(false) }

    LaunchedEffect(settings.twitchPriorityStreamers) {
        twitchStreamersDraft = settings.twitchPriorityStreamers.joinToString(", ")
    }
    LaunchedEffect(settings.kickPriorityStreamers) {
        kickStreamersDraft = settings.kickPriorityStreamers.joinToString(", ")
    }
    LaunchedEffect(settings.webhookUrl) {
        webhookUrlDraft = settings.webhookUrl
    }

    val twitchGames = remember(knownGames) { knownGames.filter { it.platform == Platform.TWITCH } }
    val kickGames = remember(knownGames) { knownGames.filter { it.platform == Platform.KICK } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // General
        item {
            SettingsSectionCard(title = "General") {
                SettingsToggleRow(
                    title = "Run in background",
                    subtitle = "Keep a foreground service running so campaign checks and mining stay active cleanly.",
                    checked = settings.runInBackground,
                    onCheckedChange = { enabled ->
                        onUpdateSettings { it.copy(runInBackground = enabled) }
                        if (enabled) onRequestNotificationPermissionIfNeeded()
                    },
                    testTag = "setting_run_in_background"
                )
            }
        }

        // Appearance
        item {
            SettingsSectionCard(title = "Appearance") {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = settings.themeMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                            label = { Text(mode.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.accent.copy(alpha = 0.2f),
                                selectedLabelColor = colors.accent
                            ),
                            modifier = Modifier.testTag("theme_chip_${mode.name.lowercase()}")
                        )
                    }
                }
            }
        }

        // Updates
        item {
            SettingsSectionCard(title = "Updates") {
                Text(
                    text = "Check frequency",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UpdateFrequency.entries.forEach { freq ->
                        val selected = settings.updateFrequency == freq
                        FilterChip(
                            selected = selected,
                            onClick = { onUpdateSettings { it.copy(updateFrequency = freq) } },
                            label = { Text(freq.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.accent.copy(alpha = 0.2f),
                                selectedLabelColor = colors.accent
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                SettingsToggleRow(
                    title = "Include beta updates",
                    subtitle = "Also check GitHub pre-releases for early features.",
                    checked = settings.includeBetaUpdates,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(includeBetaUpdates = v) } }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onCheckUpdates,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("check_updates_button"),
                        border = BorderStroke(1.dp, colors.border),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accent)
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Check Now")
                    }
                }

                if (updateInfo?.isUpdateAvailable == true) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = colors.accent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Update ${updateInfo.latestVersion} available",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.accent
                                )
                                Text(
                                    text = "Current: v${updateInfo.currentVersion}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                            Button(
                                onClick = { onOpenUrl(updateInfo.releaseUrl) },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("GitHub")
                            }
                        }
                    }
                }
            }
        }

        // Drops Behavior
        item {
            SettingsSectionCard(title = "Drops Behavior") {
                SettingsToggleRow(
                    title = "Auto-claim drops",
                    subtitle = "Automatically claim completed drops when progress reaches 100%.",
                    checked = settings.autoClaimRewards,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(autoClaimRewards = v) } },
                    testTag = "setting_auto_claim"
                )
                SettingsToggleRow(
                    title = "Kick level farming",
                    subtitle = "Farm viewer XP toward channel level progression when no drops are pending.",
                    checked = settings.kickLevelFarming,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(kickLevelFarming = v) } },
                    testTag = "setting_kick_level"
                )
                SettingsToggleRow(
                    title = "Kick daily reward claim",
                    subtitle = "Automatically claim Kick daily challenge streaks when ready.",
                    checked = settings.kickDailyClaim,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(kickDailyClaim = v) } },
                    testTag = "setting_kick_daily"
                )
                SettingsToggleRow(
                    title = "Prioritize badge drops",
                    subtitle = "Prefer campaigns containing chat badge rewards before general drops.",
                    checked = settings.prioritizeBadges,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(prioritizeBadges = v) } }
                )
                SettingsToggleRow(
                    title = "Mine emotes & raffles (Mine anything)",
                    subtitle = "Include general non-game drops and streamer-specific emote campaigns.",
                    checked = settings.mineAnything,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(mineAnything = v) } }
                )
                SettingsToggleRow(
                    title = "Mine Twitch reward campaigns",
                    subtitle = "Watch participating channels for Twitch open reward campaigns after standard drops finish.",
                    checked = settings.mineRewardCampaigns,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(mineRewardCampaigns = v) } }
                )
                SettingsToggleRow(
                    title = "Experimental HEAD-only audio loop",
                    subtitle = "Use ultra-low bandwidth HEAD requests on HLS audio playlists instead of full WebView playback.",
                    checked = settings.headOnlyMining,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(headOnlyMining = v) } },
                    testTag = "setting_head_only"
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Mining priority order",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiningPriorityMode.entries.forEach { mode ->
                        val selected = settings.miningPriorityMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = { onUpdateSettings { it.copy(miningPriorityMode = mode) } },
                            label = { Text(mode.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.accent.copy(alpha = 0.2f),
                                selectedLabelColor = colors.accent
                            )
                        )
                    }
                }
            }
        }

        // Game Filtering
        item {
            SettingsSectionCard(title = "Game Filtering") {
                GameFilterPanel(
                    platformTitle = "Twitch",
                    knownGames = twitchGames,
                    selectedSlugs = settings.twitchGameWhitelistSlugs,
                    isBlacklistMode = settings.twitchGameFilterBlacklistMode,
                    onToggleGame = { slug ->
                        onUpdateSettings { s ->
                            val exists = s.twitchGameWhitelistSlugs.any { it.equals(slug, ignoreCase = true) }
                            val updated = if (exists) {
                                s.twitchGameWhitelistSlugs.filterNot { it.equals(slug, ignoreCase = true) }
                            } else {
                                s.twitchGameWhitelistSlugs + slug.lowercase()
                            }
                            s.copy(twitchGameWhitelistSlugs = updated)
                        }
                    },
                    onMovePriority = { slug, delta ->
                        onUpdateSettings { s ->
                            val list = s.twitchGameWhitelistSlugs.toMutableList()
                            val idx = list.indexOfFirst { it.equals(slug, ignoreCase = true) }
                            val target = idx + delta
                            if (idx in list.indices && target in list.indices) {
                                val item = list.removeAt(idx)
                                list.add(target, item)
                            }
                            s.copy(twitchGameWhitelistSlugs = list)
                        }
                    },
                    onBlacklistModeChange = { v -> onUpdateSettings { it.copy(twitchGameFilterBlacklistMode = v) } },
                    onClear = { onUpdateSettings { it.copy(twitchGameWhitelistSlugs = emptyList()) } }
                )

                Spacer(modifier = Modifier.height(14.dp))

                GameFilterPanel(
                    platformTitle = "Kick",
                    knownGames = kickGames,
                    selectedSlugs = settings.kickGameWhitelistSlugs,
                    isBlacklistMode = settings.kickGameFilterBlacklistMode,
                    onToggleGame = { slug ->
                        onUpdateSettings { s ->
                            val exists = s.kickGameWhitelistSlugs.any { it.equals(slug, ignoreCase = true) }
                            val updated = if (exists) {
                                s.kickGameWhitelistSlugs.filterNot { it.equals(slug, ignoreCase = true) }
                            } else {
                                s.kickGameWhitelistSlugs + slug.lowercase()
                            }
                            s.copy(kickGameWhitelistSlugs = updated)
                        }
                    },
                    onMovePriority = { slug, delta ->
                        onUpdateSettings { s ->
                            val list = s.kickGameWhitelistSlugs.toMutableList()
                            val idx = list.indexOfFirst { it.equals(slug, ignoreCase = true) }
                            val target = idx + delta
                            if (idx in list.indices && target in list.indices) {
                                val item = list.removeAt(idx)
                                list.add(target, item)
                            }
                            s.copy(kickGameWhitelistSlugs = list)
                        }
                    },
                    onBlacklistModeChange = { v -> onUpdateSettings { it.copy(kickGameFilterBlacklistMode = v) } },
                    onClear = { onUpdateSettings { it.copy(kickGameWhitelistSlugs = emptyList()) } }
                )
            }
        }

        // Priority Streamers
        item {
            SettingsSectionCard(title = "Priority Streamers") {
                Text(
                    text = "Comma-separated usernames or channel URLs. Priority streamers are chosen first when live and drops-enabled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = twitchStreamersDraft,
                    onValueChange = { twitchStreamersDraft = it },
                    label = { Text("Twitch priority streamers") },
                    placeholder = { Text("shroud, summit1g, https://twitch.tv/lirik") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { onUpdatePriorityStreamers(Platform.TWITCH, twitchStreamersDraft) },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Save Twitch Streamers", color = colors.accent)
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = kickStreamersDraft,
                    onValueChange = { kickStreamersDraft = it },
                    label = { Text("Kick priority streamers") },
                    placeholder = { Text("xqc, trainwreckstv") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = KickGreen,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onUpdatePriorityStreamers(Platform.KICK, kickStreamersDraft) },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Save Kick Streamers", color = KickGreen)
                    }
                    OutlinedButton(
                        onClick = onImportKickFollows,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("import_kick_follows_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = KickGreen)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import Kick follows", color = KickGreen)
                    }
                }
            }
        }

        // Notifications & Webhooks
        item {
            SettingsSectionCard(title = "Notifications & Webhooks") {
                SettingsToggleRow(
                    title = "Notify when drop is ready to claim",
                    checked = settings.notifyOnReadyToClaim,
                    onCheckedChange = { v ->
                        onUpdateSettings { it.copy(notifyOnReadyToClaim = v) }
                        if (v) onRequestNotificationPermissionIfNeeded()
                    }
                )
                SettingsToggleRow(
                    title = "Notify when drop is auto-claimed",
                    checked = settings.notifyOnAutoClaimed,
                    onCheckedChange = { v ->
                        onUpdateSettings { it.copy(notifyOnAutoClaimed = v) }
                        if (v) onRequestNotificationPermissionIfNeeded()
                    }
                )
                SettingsToggleRow(
                    title = "Notify when app update is available",
                    checked = settings.notifyOnNewUpdateAvailable,
                    onCheckedChange = { v ->
                        onUpdateSettings { it.copy(notifyOnNewUpdateAvailable = v) }
                        if (v) onRequestNotificationPermissionIfNeeded()
                    }
                )
                SettingsToggleRow(
                    title = "Quiet mode (suppress local alerts)",
                    checked = settings.quietMode,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(quietMode = v) } }
                )

                Spacer(modifier = Modifier.height(10.dp))
                SettingsToggleRow(
                    title = "Enable webhook notifications (Discord / Slack / JSON)",
                    checked = settings.webhookEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(webhookEnabled = v) } },
                    testTag = "setting_webhook_enabled"
                )

                if (settings.webhookEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = webhookUrlDraft,
                        onValueChange = {
                            webhookUrlDraft = it
                            onUpdateSettings { s -> s.copy(webhookUrl = it.trim()) }
                        },
                        label = { Text("Webhook URL") },
                        placeholder = { Text("https://discord.com/api/webhooks/...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("webhook_url_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onSendTestWebhook(webhookUrlDraft.trim()) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("send_test_webhook_button"),
                        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.6f))
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.accent)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Send Test Webhook", color = colors.accent)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsToggleRow(
                        title = "On drop claimed",
                        checked = settings.webhookOnDropClaimed,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(webhookOnDropClaimed = v) } }
                    )
                    SettingsToggleRow(
                        title = "On drop ready to claim",
                        checked = settings.webhookOnDropReadyToClaim,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(webhookOnDropReadyToClaim = v) } }
                    )
                    SettingsToggleRow(
                        title = "On drop claim failed",
                        checked = settings.webhookOnDropClaimFailed,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(webhookOnDropClaimFailed = v) } }
                    )
                    SettingsToggleRow(
                        title = "On game account not linked",
                        checked = settings.webhookOnAccountNotLinked,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(webhookOnAccountNotLinked = v) } }
                    )
                    SettingsToggleRow(
                        title = "On update available",
                        checked = settings.webhookOnUpdateAvailable,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(webhookOnUpdateAvailable = v) } }
                    )
                    SettingsToggleRow(
                        title = "On app updated",
                        checked = settings.webhookOnAppUpdated,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(webhookOnAppUpdated = v) } }
                    )
                }
            }
        }

        // Diagnostics & Logs
        item {
            SettingsSectionCard(title = "Diagnostics & Logs") {
                SettingsToggleRow(
                    title = "Verbose debug logging",
                    subtitle = "Record detailed mining diagnostics and API troubleshooting events.",
                    checked = settings.verboseDebugLogging,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(verboseDebugLogging = v) } }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenLogsViewer,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("open_logs_viewer_button"),
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.secondaryAccent)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("View Diagnostic Logs", color = colors.secondaryAccent)
                }
            }
        }

        // Danger Zone
        item {
            SettingsSectionCard(
                title = stringResource(R.string.danger_zone),
                isDanger = true
            ) {
                Text(
                    text = stringResource(R.string.remove_all_accounts_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { showRemoveAllDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusDanger, contentColor = Color.White),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("remove_all_accounts_button")
                ) {
                    Text(stringResource(R.string.remove_all_accounts_btn), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showRemoveAllDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveAllDialog = false },
            containerColor = colors.cardBackground,
            title = { Text("Delete ALL accounts?", color = StatusDanger) },
            text = {
                Text(
                    "This will permanently delete all saved Twitch and Kick accounts, tokens, and browser cookies from this device.",
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveAllAccounts()
                        showRemoveAllDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusDanger, contentColor = Color.White),
                    modifier = Modifier.testTag("confirm_remove_all_accounts_button")
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveAllDialog = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }
}
