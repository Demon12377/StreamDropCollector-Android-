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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.helpers.StreamerUrlParser
import com.example.core.models.AccountModel
import com.example.core.models.AccountSettingsOverrides
import com.example.core.models.AppSettings
import com.example.core.models.MiningPriorityMode
import com.example.core.models.Platform
import com.example.data.KnownGameEntry
import com.example.ui.components.AppCard
import com.example.ui.components.GameFilterPanel
import com.example.ui.components.PlatformBadge
import com.example.ui.theme.KickGreen
import com.example.ui.theme.LocalDropColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AccountSettingsScreen(
    account: AccountModel?,
    globalSettings: AppSettings,
    knownGames: List<KnownGameEntry>,
    onImportKickFollows: ((List<String>) -> Unit) -> Unit,
    onSaveOverrides: (AccountSettingsOverrides) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDropColors.current

    if (account == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Account Settings") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                Text("Account not found.", color = colors.textMuted)
            }
        }
        return
    }

    val initial = account.overrides
    var autoClaim by remember(account.id) { mutableStateOf(initial.autoClaimRewards) }
    var kickLevel by remember(account.id) { mutableStateOf(initial.kickLevelFarming) }
    var kickDaily by remember(account.id) { mutableStateOf(initial.kickDailyClaim) }
    var priorityMode by remember(account.id) { mutableStateOf(initial.miningPriorityMode) }
    var prioritizeBadges by remember(account.id) { mutableStateOf(initial.prioritizeBadges) }
    var mineAnything by remember(account.id) { mutableStateOf(initial.mineAnything) }
    var headOnly by remember(account.id) { mutableStateOf(initial.headOnlyMining) }
    var gameFilterSlugs by remember(account.id) { mutableStateOf(initial.gameWhitelistSlugs) }
    var gameBlacklistMode by remember(account.id) { mutableStateOf(initial.gameFilterBlacklistMode) }
    var priorityStreamersOverride by remember(account.id) { mutableStateOf(initial.priorityStreamers) }
    var streamersText by remember(account.id) {
        mutableStateOf(
            (initial.priorityStreamers
                ?: if (account.platform == Platform.TWITCH) globalSettings.twitchPriorityStreamers else globalSettings.kickPriorityStreamers)
                .joinToString(", ")
        )
    }

    val platformGames = remember(knownGames, account.platform) {
        knownGames.filter { it.platform == account.platform }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.mainBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlatformBadge(platform = account.platform)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${account.displayName} Overrides",
                            color = colors.textPrimary,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.headerBackground)
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("account_settings_cancel_btn"),
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Text("Cancel", color = colors.textSecondary)
                }
                Button(
                    onClick = {
                        val finalStreamers = if (priorityStreamersOverride != null) {
                            StreamerUrlParser.parseCommaSeparatedLogins(streamersText, account.platform)
                        } else null

                        onSaveOverrides(
                            AccountSettingsOverrides(
                                autoClaimRewards = autoClaim,
                                kickLevelFarming = kickLevel,
                                kickDailyClaim = kickDaily,
                                miningPriorityMode = priorityMode,
                                prioritizeBadges = prioritizeBadges,
                                mineAnything = mineAnything,
                                headOnlyMining = headOnly,
                                gameWhitelistSlugs = gameFilterSlugs,
                                gameFilterBlacklistMode = gameBlacklistMode,
                                priorityStreamers = finalStreamers
                            )
                        )
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("account_settings_save_btn")
                ) {
                    Text("Save Overrides", fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OverrideBooleanCard(
                    title = "Auto-claim drops",
                    useGlobal = autoClaim == null,
                    value = autoClaim ?: globalSettings.autoClaimRewards,
                    onUseGlobalChange = { useGlobal ->
                        autoClaim = if (useGlobal) null else globalSettings.autoClaimRewards
                    },
                    onValueChange = { autoClaim = it }
                )
            }

            if (account.platform == Platform.KICK) {
                item {
                    OverrideBooleanCard(
                        title = "Kick level farming",
                        useGlobal = kickLevel == null,
                        value = kickLevel ?: globalSettings.kickLevelFarming,
                        onUseGlobalChange = { useGlobal ->
                            kickLevel = if (useGlobal) null else globalSettings.kickLevelFarming
                        },
                        onValueChange = { kickLevel = it }
                    )
                }
                item {
                    OverrideBooleanCard(
                        title = "Kick daily reward claim",
                        useGlobal = kickDaily == null,
                        value = kickDaily ?: globalSettings.kickDailyClaim,
                        onUseGlobalChange = { useGlobal ->
                            kickDaily = if (useGlobal) null else globalSettings.kickDailyClaim
                        },
                        onValueChange = { kickDaily = it }
                    )
                }
            }

            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Mining priority",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Checkbox(
                            checked = priorityMode == null,
                            onCheckedChange = { useGlobal ->
                                priorityMode = if (useGlobal) null else globalSettings.miningPriorityMode
                            },
                            colors = CheckboxDefaults.colors(checkedColor = colors.accent, checkmarkColor = Color.Black)
                        )
                        Text("Use global", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                    }
                    if (priorityMode != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MiningPriorityMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = priorityMode == mode,
                                    onClick = { priorityMode = mode },
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
            }

            item {
                OverrideBooleanCard(
                    title = "Prioritize badge drops",
                    useGlobal = prioritizeBadges == null,
                    value = prioritizeBadges ?: globalSettings.prioritizeBadges,
                    onUseGlobalChange = { useGlobal ->
                        prioritizeBadges = if (useGlobal) null else globalSettings.prioritizeBadges
                    },
                    onValueChange = { prioritizeBadges = it }
                )
            }

            item {
                OverrideBooleanCard(
                    title = "Mine emotes & raffles (Mine anything)",
                    useGlobal = mineAnything == null,
                    value = mineAnything ?: globalSettings.mineAnything,
                    onUseGlobalChange = { useGlobal ->
                        mineAnything = if (useGlobal) null else globalSettings.mineAnything
                    },
                    onValueChange = { mineAnything = it }
                )
            }

            item {
                OverrideBooleanCard(
                    title = "Experimental HEAD-only audio loop",
                    useGlobal = headOnly == null,
                    value = headOnly ?: globalSettings.headOnlyMining,
                    onUseGlobalChange = { useGlobal ->
                        headOnly = if (useGlobal) null else globalSettings.headOnlyMining
                    },
                    onValueChange = { headOnly = it }
                )
            }

            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Priority streamers override",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Checkbox(
                            checked = priorityStreamersOverride == null,
                            onCheckedChange = { useGlobal ->
                                priorityStreamersOverride = if (useGlobal) null else emptyList()
                            },
                            colors = CheckboxDefaults.colors(checkedColor = colors.accent, checkmarkColor = Color.Black)
                        )
                        Text("Use global", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                    }
                    if (priorityStreamersOverride != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = streamersText,
                            onValueChange = { streamersText = it },
                            label = { Text("Comma-separated streamers or URLs") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.accent,
                                unfocusedBorderColor = colors.border,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary
                            )
                        )
                        if (account.platform == Platform.KICK) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    onImportKickFollows { follows ->
                                        val existing = StreamerUrlParser.parseCommaSeparatedLogins(streamersText, Platform.KICK)
                                        streamersText = (existing + follows).distinct().joinToString(", ")
                                    }
                                },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = KickGreen)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Import Kick follows", color = KickGreen)
                            }
                        }
                    }
                }
            }

            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Game filter override",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Checkbox(
                            checked = gameFilterSlugs == null,
                            onCheckedChange = { useGlobal ->
                                if (useGlobal) {
                                    gameFilterSlugs = null
                                } else {
                                    val globalList = if (account.platform == Platform.TWITCH) globalSettings.twitchGameWhitelistSlugs else globalSettings.kickGameWhitelistSlugs
                                    val globalBlacklist = if (account.platform == Platform.TWITCH) globalSettings.twitchGameFilterBlacklistMode else globalSettings.kickGameFilterBlacklistMode
                                    gameFilterSlugs = globalList
                                    gameBlacklistMode = globalBlacklist
                                }
                            },
                            colors = CheckboxDefaults.colors(checkedColor = colors.accent, checkmarkColor = Color.Black)
                        )
                        Text("Use global", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                    }
                    val customSlugs = gameFilterSlugs
                    if (customSlugs != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        GameFilterPanel(
                            platformTitle = account.platform.displayName,
                            knownGames = platformGames,
                            selectedSlugs = customSlugs,
                            isBlacklistMode = gameBlacklistMode,
                            onToggleGame = { slug ->
                                val exists = customSlugs.any { it.equals(slug, ignoreCase = true) }
                                gameFilterSlugs = if (exists) {
                                    customSlugs.filterNot { it.equals(slug, ignoreCase = true) }
                                } else {
                                    customSlugs + slug.lowercase()
                                }
                            },
                            onMovePriority = { slug, delta ->
                                val list = customSlugs.toMutableList()
                                val idx = list.indexOfFirst { it.equals(slug, ignoreCase = true) }
                                val target = idx + delta
                                if (idx in list.indices && target in list.indices) {
                                    val item = list.removeAt(idx)
                                    list.add(target, item)
                                    gameFilterSlugs = list
                                }
                            },
                            onBlacklistModeChange = { gameBlacklistMode = it },
                            onClear = { gameFilterSlugs = emptyList() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverrideBooleanCard(
    title: String,
    useGlobal: Boolean,
    value: Boolean,
    onUseGlobalChange: (Boolean) -> Unit,
    onValueChange: (Boolean) -> Unit
) {
    val colors = LocalDropColors.current
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = useGlobal,
                onCheckedChange = onUseGlobalChange,
                colors = CheckboxDefaults.colors(checkedColor = colors.accent, checkmarkColor = Color.Black)
            )
            Text("Use global", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = value,
                onCheckedChange = onValueChange,
                enabled = !useGlobal,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = colors.accent
                )
            )
        }
    }
}
