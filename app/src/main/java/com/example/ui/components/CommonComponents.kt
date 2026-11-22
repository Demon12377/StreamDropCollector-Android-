package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.models.Platform
import com.example.core.models.TwitchDeviceCodePrompt
import com.example.data.KnownGameEntry
import com.example.ui.theme.BadgePurple
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.KickGreen
import com.example.ui.theme.LocalDropColors
import com.example.ui.theme.StatusDanger
import com.example.ui.theme.TwitchPurple

enum class StatusBadgeVariant {
    MINING,
    CLAIMED,
    BADGE,
    HEAD
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    isDanger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalDropColors.current
    val borderColor = if (isDanger) StatusDanger.copy(alpha = 0.65f) else colors.border
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.cardBackground,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun PlatformBadge(platform: Platform, modifier: Modifier = Modifier) {
    val bg = if (platform == Platform.TWITCH) TwitchPurple else KickGreen
    val fg = if (platform == Platform.TWITCH) Color.White else Color.Black
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = platform.displayName.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = fg
        )
    }
}

@Composable
fun StatusBadge(
    text: String,
    variant: StatusBadgeVariant,
    modifier: Modifier = Modifier
) {
    val colors = LocalDropColors.current
    val (bg, fg) = when (variant) {
        StatusBadgeVariant.MINING -> colors.accent.copy(alpha = 0.18f) to colors.accent
        StatusBadgeVariant.CLAIMED -> colors.secondaryAccent.copy(alpha = 0.18f) to colors.secondaryAccent
        StatusBadgeVariant.BADGE -> BadgePurple.copy(alpha = 0.22f) to Color(0xFFD5B8FF)
        StatusBadgeVariant.HEAD -> Color(0xFF00B8D4).copy(alpha = 0.2f) to Color(0xFF4DD0E1)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, fg.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = fg
        )
    }
}

@Composable
fun NeonProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    barColor: Color = LocalDropColors.current.accent,
    trackColor: Color = LocalDropColors.current.insetBackground
) {
    val clamped = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = clamped,
        animationSpec = tween(durationMillis = 350),
        label = "neon_progress"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(9.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(RoundedCornerShape(5.dp))
                .background(barColor)
        )
    }
}

@Composable
fun SettingsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    isDanger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalDropColors.current
    AppCard(modifier = modifier, isDanger = isDanger) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = if (isDanger) StatusDanger else colors.textPrimary
        )
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    testTag: String = ""
) {
    val colors = LocalDropColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.textPrimary else colors.textMuted
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.textMuted,
                uncheckedTrackColor = colors.insetBackground
            )
        )
    }
}

@Composable
fun GameFilterPanel(
    platformTitle: String,
    knownGames: List<KnownGameEntry>,
    selectedSlugs: List<String>,
    isBlacklistMode: Boolean,
    onToggleGame: (String) -> Unit,
    onMovePriority: (String, Int) -> Unit,
    onBlacklistModeChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDropColors.current
    var search by remember { mutableStateOf("") }

    val filteredGames = remember(knownGames, search) {
        val q = search.trim()
        if (q.isEmpty()) knownGames
        else knownGames.filter {
            it.name.contains(q, ignoreCase = true) || it.slug.contains(q, ignoreCase = true)
        }
    }

    val summary = when {
        selectedSlugs.isEmpty() -> "All games allowed"
        isBlacklistMode -> "Blocking ${selectedSlugs.size} game(s)"
        else -> "Allowing ${selectedSlugs.size} game(s)"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.insetBackground)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$platformTitle • $summary",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            TextButton(
                onClick = onClear,
                modifier = Modifier.testTag("clear_${platformTitle.lowercase()}_filter_button")
            ) {
                Text("Clear", color = colors.secondaryAccent)
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Search $platformTitle games…", color = colors.textMuted) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colors.textMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.border,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (filteredGames.isEmpty()) {
            Text(
                text = "No discovered games yet — sign in and sync campaigns to populate this list.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Column(modifier = Modifier.heightIn(max = 180.dp)) {
                filteredGames.take(30).forEach { game ->
                    val checked = selectedSlugs.any { it.equals(game.slug, ignoreCase = true) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onToggleGame(game.slug) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggleGame(game.slug) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.accent,
                                checkmarkColor = Color.Black
                            )
                        )
                        Text(
                            text = game.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (!isBlacklistMode && selectedSlugs.size > 1) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Mining priority order (top mined first):",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted
            )
            Spacer(modifier = Modifier.height(4.dp))
            selectedSlugs.forEachIndexed { idx, slug ->
                val name = knownGames.firstOrNull { it.slug.equals(slug, ignoreCase = true) }?.name ?: slug
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${idx + 1}. $name",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = { onMovePriority(slug, -1) },
                        enabled = idx > 0,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move up", tint = colors.accent)
                    }
                    IconButton(
                        onClick = { onMovePriority(slug, +1) },
                        enabled = idx < selectedSlugs.lastIndex,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move down", tint = colors.accent)
                    }
                }
            }
        }

        SettingsToggleRow(
            title = "Blacklist mode (mine everything except selected)",
            checked = isBlacklistMode,
            onCheckedChange = onBlacklistModeChange
        )
    }
}

@Composable
fun HelixDeviceCodeDialog(
    prompt: TwitchDeviceCodePrompt,
    onOpenActivation: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalDropColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.cardBackground,
        title = {
            Text("Authorize Twitch Helix", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Enter this one-time code on Twitch to enable live stream metadata and EventSub updates:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.insetBackground,
                    border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = prompt.userCode,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            color = colors.accent
                        )
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(prompt.userCode))
                            copied = true
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy code", tint = colors.accent)
                        }
                    }
                }
                if (copied) {
                    Text("Code copied to clipboard", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                }
                Text(
                    text = "Waiting for approval…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(prompt.userCode))
                    onOpenActivation(prompt.activationUrl)
                },
                colors = ButtonDefaults.buttonColors(containerColor = TwitchPurple, contentColor = Color.White),
                modifier = Modifier.testTag("helix_open_browser_button")
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Authorize on Twitch")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}
