package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.logging.AppLogger
import com.example.core.models.LogEntry
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalDropColors
import com.example.ui.theme.StatusDanger
import com.example.ui.theme.StatusWarning

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogsViewerScreen(
    logs: List<LogEntry>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDropColors.current
    val context = LocalContext.current
    var levelFilter by remember { mutableStateOf("ALL") }

    val filtered = remember(logs, levelFilter) {
        val ordered = logs.asReversed()
        if (levelFilter == "ALL") ordered else ordered.filter { it.level == levelFilter }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.mainBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Diagnostic Logs (${filtered.size})",
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { AppLogger.clear() },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("clear_logs_button")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs", tint = StatusWarning)
                    }
                    IconButton(
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "DropCollector Diagnostic Logs")
                                putExtra(Intent.EXTRA_TEXT, AppLogger.exportAllText())
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Export Diagnostic Logs"))
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("share_logs_button")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share logs", tint = colors.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.headerBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.headerBackground)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("ALL", "INFO", "WARN", "ERROR", "DEBUG").forEach { lvl ->
                    FilterChip(
                        selected = levelFilter == lvl,
                        onClick = { levelFilter = lvl },
                        label = { Text(lvl) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent.copy(alpha = 0.2f),
                            selectedLabelColor = colors.accent
                        )
                    )
                }
            }

            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "No log entries recorded for filter $levelFilter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        val lvlColor = when (entry.level) {
                            "ERROR" -> StatusDanger
                            "WARN" -> StatusWarning
                            "DEBUG" -> colors.secondaryAccent
                            else -> colors.accent
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.cardBackground,
                            border = BorderStroke(1.dp, colors.border)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = entry.timestamp,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 11.sp,
                                        color = colors.textMuted
                                    )
                                    Text(
                                        text = entry.level,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 11.sp,
                                        color = lvlColor
                                    )
                                    Text(
                                        text = "[${entry.category}]",
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 11.sp,
                                        color = colors.secondaryAccent
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = entry.message,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 12.sp,
                                    color = colors.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
