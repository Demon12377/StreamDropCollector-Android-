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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.models.AccountSessionState
import com.example.core.models.ConnectionStatus
import com.example.core.models.Platform
import com.example.ui.components.AccountSessionCard
import com.example.ui.components.AppCard
import com.example.ui.theme.KickGreen
import com.example.ui.theme.LocalDropColors
import com.example.ui.theme.StatusChecking
import com.example.ui.theme.StatusDanger
import com.example.ui.theme.StatusOffline
import com.example.ui.theme.StatusOnline
import com.example.ui.theme.TwitchPurple

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    sessions: List<AccountSessionState>,
    helixConnected: Boolean,
    isExpandedWidth: Boolean,
    onAddAccount: (Platform) -> Unit,
    onLoginAccount: (AccountSessionState) -> Unit,
    onToggleAccountEnabled: (String, Boolean) -> Unit,
    onOpenAccountSettings: (String) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onResetNetwork: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onAuthorizeHelix: () -> Unit,
    onEnterNightScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDropColors.current
    var accountPendingRemoval by remember { mutableStateOf<AccountSessionState?>(null) }

    val activeMining = sessions.filter {
        it.account.enabled && it.progress.minedChannel.isNotBlank()
    }
    val anyChecking = sessions.any {
        it.account.enabled && it.connection.status == ConnectionStatus.CHECKING
    }
    val hasTwitchAccount = sessions.any { it.account.platform == Platform.TWITCH }

    val minerStatusText = when {
        activeMining.isNotEmpty() -> "Mining"
        anyChecking -> "Checking"
        sessions.any { it.account.enabled } -> "Idle"
        else -> "Standby"
    }
    val minerDotColor = when {
        activeMining.isNotEmpty() -> StatusOnline
        anyChecking -> StatusChecking
        else -> StatusOffline
    }
    val minerSummary = when {
        activeMining.isNotEmpty() -> {
            val channels = activeMining.joinToString(", ") {
                "${it.account.platform.displayName}: ${it.progress.minedChannel}"
            }
            "Active on ${activeMining.size} session(s) • $channels"
        }
        anyChecking -> "Scanning campaigns and checking live eligible channels…"
        sessions.isNotEmpty() -> "Enabled accounts are standing by for drops."
        else -> "Add a Twitch or Kick account below to start collecting drops automatically."
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (isExpandedWidth) 2 else 1),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            AppCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(minerDotColor)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "${stringResource(R.string.miner_status_title)}: $minerStatusText",
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.textPrimary
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onEnterNightScreen,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("dashboard_night_screen_button"),
                            border = BorderStroke(1.dp, colors.border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.secondaryAccent)
                        ) {
                            Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Night")
                        }

                        OutlinedButton(
                            onClick = onRefreshAll,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("dashboard_refresh_button"),
                            border = BorderStroke(1.dp, colors.border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accent)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = minerSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )

                if (hasTwitchAccount) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = colors.insetBackground,
                        border = BorderStroke(1.dp, colors.border)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = if (helixConnected) Icons.Default.CheckCircle else Icons.Default.Key,
                                    contentDescription = null,
                                    tint = if (helixConnected) StatusOnline else TwitchPurple,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (helixConnected) "Twitch Helix API authorized" else "Authorize Twitch Helix for EventSub & metadata",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textPrimary
                                )
                            }
                            if (!helixConnected) {
                                TextButton(
                                    onClick = onAuthorizeHelix,
                                    modifier = Modifier.testTag("authorize_helix_button")
                                ) {
                                    Text("Authorize", color = TwitchPurple, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            AppCard {
                Text(
                    text = stringResource(R.string.accounts_card_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.accounts_card_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted
                )
                Spacer(modifier = Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onAddAccount(Platform.TWITCH) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TwitchPurple,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("add_twitch_account_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.add_twitch_account), fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onAddAccount(Platform.KICK) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KickGreen,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("add_kick_account_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.add_kick_account), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (sessions.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                AppCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.no_accounts_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.no_accounts_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted
                        )
                    }
                }
            }
        } else {
            items(sessions, key = { it.account.id }) { session ->
                AccountSessionCard(
                    session = session,
                    onToggleEnabled = { enabled -> onToggleAccountEnabled(session.account.id, enabled) },
                    onLoginClick = { onLoginAccount(session) },
                    onSettingsClick = { onOpenAccountSettings(session.account.id) },
                    onRemoveClick = { accountPendingRemoval = session },
                    onResetNetworkClick = { onResetNetwork(session.account.id) }
                )
            }
        }
    }

    val pending = accountPendingRemoval
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { accountPendingRemoval = null },
            containerColor = colors.cardBackground,
            title = {
                Text("Remove account?", color = colors.textPrimary)
            },
            text = {
                Text(
                    "Remove ${pending.account.platform.displayName} account \"${pending.account.displayName}\" and its saved session credentials?",
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveAccount(pending.account.id)
                        accountPendingRemoval = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusDanger, contentColor = Color.White),
                    modifier = Modifier.testTag("confirm_remove_account_button")
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { accountPendingRemoval = null }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }
}
