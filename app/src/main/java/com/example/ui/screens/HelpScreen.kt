package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.AppCard
import com.example.ui.theme.LocalDropColors

@Composable
fun HelpScreen(
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDropColors.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AppCard {
                Text(
                    text = "Getting Started",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                HelpBullet("1. Open Dashboard and tap Add Twitch account or Add Kick account to sign in inside an isolated session.")
                HelpBullet("2. Authorize Twitch Helix once when prompted so live stream categories and EventSub metadata stay fast and accurate.")
                HelpBullet("3. Each enabled account discovers active campaigns, selects the best eligible live streamer, and watches at the lowest bitrate automatically.")
            }
        }

        item {
            AppCard {
                Text(
                    text = "Inventory & Campaign Controls",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                HelpBullet("• Tap Mine instead on any campaign card to pin it immediately for that account.")
                HelpBullet("• Tap Mine first to bump that game to the top of your platform whitelist queue.")
                HelpBullet("• Tap Ignore game to exclude a game from mining; restore it anytime from Ignored games at the bottom of Inventory.")
                HelpBullet("• Twitch open reward campaigns track watch time after standard drops and can be opened directly in Twitch from Inventory.")
            }
        }

        item {
            AppCard {
                Text(
                    text = "Mining Modes & Bandwidth Saving",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                HelpBullet("• Run in background keeps a low-priority Android foreground service active so drop checks continue smoothly.")
                HelpBullet("• Experimental HEAD-only mode loops HLS audio playlists using HEAD requests to minimize data usage on Twitch and Kick.")
                HelpBullet("• Kick Level Farming & Daily Rewards keep earning channel XP and daily streak rewards while no campaign drop is active.")
                HelpBullet("• Per-account overrides let each account customize auto-claim, priority mode, streamers, and game filters.")
            }
        }

        item {
            AppCard {
                Text(
                    text = "Community & Links",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))

                ExternalHelpLinkRow(
                    iconRes = R.drawable.ic_discord,
                    iconCd = stringResource(R.string.cd_discord_icon),
                    title = "Discord Community",
                    subtitle = "Join for support, release notes, and drop troubleshooting",
                    onClick = { onOpenUrl("https://discord.gg/Cddu5aJ") },
                    testTag = "help_discord_link"
                )
                Spacer(modifier = Modifier.height(8.dp))
                ExternalHelpLinkRow(
                    iconRes = R.drawable.ic_github,
                    iconCd = stringResource(R.string.cd_github_icon),
                    title = "GitHub Repository",
                    subtitle = "Browse source code, open issues, and check releases",
                    onClick = { onOpenUrl("https://github.com/tsgsOFFICIAL/StreamDropCollector") },
                    testTag = "help_github_link"
                )

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { onOpenUrl("https://ko-fi.com/tsgsOFFICIAL") },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("help_kofi_button")
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Support on Ko-fi", fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.creator_credit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted
                )
            }
        }
    }
}

@Composable
private fun HelpBullet(text: String) {
    val colors = LocalDropColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textSecondary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun ExternalHelpLinkRow(
    iconRes: Int,
    iconCd: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String
) {
    val colors = LocalDropColors.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.insetBackground,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = iconCd,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted
                )
            }
            Icon(
                Icons.Default.OpenInNew,
                contentDescription = null,
                tint = colors.secondaryAccent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
