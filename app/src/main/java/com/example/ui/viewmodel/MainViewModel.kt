package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DropCollectorApp
import com.example.core.helpers.StreamerUrlParser
import com.example.core.logging.AppLogger
import com.example.core.models.AccountCredentials
import com.example.core.models.AccountModel
import com.example.core.models.AccountSessionState
import com.example.core.models.AccountSettingsOverrides
import com.example.core.models.AppSettings
import com.example.core.models.DropsCampaign
import com.example.core.models.IgnoredGame
import com.example.core.models.InventorySortMode
import com.example.core.models.LogEntry
import com.example.core.models.NotificationType
import com.example.core.models.Platform
import com.example.core.models.ThemeMode
import com.example.core.models.TwitchDeviceCodePrompt
import com.example.core.models.UpdateFrequency
import com.example.core.models.UpdateInfo
import com.example.data.KnownGameEntry
import com.example.network.NetworkMeterRegistry
import com.example.service.MiningForegroundService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DropCollectorApp
    private val repo = app.settingsRepository
    private val engine = app.miningEngine

    val settings: StateFlow<AppSettings> = repo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val sessions: StateFlow<List<AccountSessionState>> = engine.sessions

    val knownGames: StateFlow<List<KnownGameEntry>> = repo.knownGamesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val logs: StateFlow<List<LogEntry>> = AppLogger.entries

    private val _helixPrompt = MutableStateFlow<TwitchDeviceCodePrompt?>(null)
    val helixPrompt: StateFlow<TwitchDeviceCodePrompt?> = _helixPrompt.asStateFlow()

    private val _helixConnected = MutableStateFlow(false)
    val helixConnected: StateFlow<Boolean> = _helixConnected.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    init {
        viewModelScope.launch {
            repo.settingsFlow.collectLatest { s ->
                AppLogger.verboseDebugLogging = s.verboseDebugLogging
                if (s.runInBackground && repo.getAccounts().any { it.enabled }) {
                    MiningForegroundService.start(app)
                } else {
                    MiningForegroundService.stop(app)
                }
            }
        }

        viewModelScope.launch {
            repo.accountsFlow.collectLatest { accounts ->
                engine.syncAccountsList(accounts)
                val s = repo.getSettings()
                if (s.runInBackground && accounts.any { it.enabled }) {
                    MiningForegroundService.start(app)
                } else if (accounts.none { it.enabled }) {
                    MiningForegroundService.stop(app)
                }
            }
        }

        viewModelScope.launch {
            _helixConnected.value = app.twitchHelixClient.hasValidToken()
            val s = repo.getSettings()
            if (shouldAutoCheckUpdates(s)) {
                checkForUpdates(manual = false)
            }
        }
    }

    private fun shouldAutoCheckUpdates(s: AppSettings): Boolean {
        val last = s.lastUpdateCheckEpochMs ?: return s.updateFrequency != UpdateFrequency.NEVER
        val elapsedMs = System.currentTimeMillis() - last
        return when (s.updateFrequency) {
            UpdateFrequency.ON_LAUNCH -> true
            UpdateFrequency.DAILY -> elapsedMs >= 24 * 60 * 60 * 1000L
            UpdateFrequency.WEEKLY -> elapsedMs >= 7 * 24 * 60 * 60 * 1000L
            UpdateFrequency.NEVER -> false
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            repo.updateSettings(transform)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        updateSettings { it.copy(themeMode = mode) }
    }

    fun setInventorySortMode(mode: InventorySortMode) {
        updateSettings { it.copy(inventorySortMode = mode) }
    }

    fun setHideCompletedCampaigns(hide: Boolean) {
        updateSettings { it.copy(hideCompletedCampaigns = hide) }
    }

    fun completePlatformLogin(
        platform: Platform,
        existingAccountId: String?,
        authToken: String,
        displayName: String,
        cookieHeader: String
    ) {
        viewModelScope.launch {
            val cleanName = displayName.ifBlank { "${platform.displayName} User" }
            val accounts = repo.getAccounts().toMutableList()
            val accountId: String

            if (!existingAccountId.isNullOrBlank()) {
                accountId = existingAccountId
                val idx = accounts.indexOfFirst { it.id == existingAccountId }
                if (idx >= 0) {
                    accounts[idx] = accounts[idx].copy(displayName = cleanName, enabled = true)
                }
            } else {
                val newAccount = AccountModel(
                    platform = platform,
                    displayName = cleanName,
                    enabled = true
                )
                accountId = newAccount.id
                accounts += newAccount
            }

            repo.saveCredentials(
                AccountCredentials(
                    accountId = accountId,
                    platform = platform,
                    authToken = authToken,
                    cookieHeader = cookieHeader,
                    loginName = cleanName
                )
            )
            repo.saveAccounts(accounts)
            engine.syncAccountsList(accounts)
            engine.triggerImmediateRefresh(accountId)

            if (platform == Platform.TWITCH && !app.twitchHelixClient.hasValidToken()) {
                startHelixAuthorization()
            }
            _snackbar.tryEmit("Signed in to ${platform.displayName} as $cleanName")
        }
    }

    fun startHelixAuthorization() {
        viewModelScope.launch {
            runCatching {
                app.twitchHelixClient.startDeviceCodeFlow { prompt ->
                    _helixPrompt.value = prompt
                }
                _helixPrompt.value = null
                _helixConnected.value = true
                _snackbar.tryEmit("Twitch Helix authorized")
                engine.triggerImmediateRefresh()
            }.onFailure { ex ->
                _helixPrompt.value = null
                AppLogger.warn("TwitchHelix", "Device code flow failed: ${ex.message}")
                _snackbar.tryEmit("Helix authorization cancelled or timed out")
            }
        }
    }

    fun dismissHelixPrompt() {
        _helixPrompt.value = null
    }

    fun toggleAccountEnabled(accountId: String, enabled: Boolean) {
        viewModelScope.launch {
            val updated = repo.getAccounts().map {
                if (it.id == accountId) it.copy(enabled = enabled) else it
            }
            repo.saveAccounts(updated)
            if (enabled) engine.triggerImmediateRefresh(accountId)
        }
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            repo.removeAccountData(accountId)
            _snackbar.tryEmit("Account removed")
        }
    }

    fun removeAllAccounts() {
        viewModelScope.launch {
            repo.clearAllAccounts()
            app.webViewBridge.clearAllCookies()
            _snackbar.tryEmit("All accounts deleted")
        }
    }

    fun resetAccountNetwork(accountId: String) {
        NetworkMeterRegistry.reset(accountId)
        _snackbar.tryEmit("Network counters reset")
    }

    fun refreshAll() {
        engine.triggerImmediateRefresh()
        _snackbar.tryEmit("Syncing active campaigns...")
    }

    fun pinCampaign(accountId: String, campaignId: String) {
        viewModelScope.launch {
            engine.pinCampaign(accountId, campaignId)
            _snackbar.tryEmit("Pinned campaign for mining")
        }
    }

    fun moveGameToTop(platform: Platform, slug: String) {
        if (slug.isBlank()) return
        val norm = slug.trim().lowercase()
        updateSettings { s ->
            if (platform == Platform.TWITCH) {
                val list = s.twitchGameWhitelistSlugs.filterNot { it.equals(norm, ignoreCase = true) }
                s.copy(
                    twitchGameFilterBlacklistMode = false,
                    twitchGameWhitelistSlugs = listOf(norm) + list
                )
            } else {
                val list = s.kickGameWhitelistSlugs.filterNot { it.equals(norm, ignoreCase = true) }
                s.copy(
                    kickGameFilterBlacklistMode = false,
                    kickGameWhitelistSlugs = listOf(norm) + list
                )
            }
        }
        _snackbar.tryEmit("Prioritized game at top of mining queue")
    }

    fun ignoreGame(platform: Platform, slug: String) {
        if (slug.isBlank()) return
        val norm = slug.trim().lowercase()
        updateSettings { s ->
            if (platform == Platform.TWITCH) {
                if (s.twitchGameFilterBlacklistMode) {
                    s.copy(twitchGameWhitelistSlugs = (s.twitchGameWhitelistSlugs + norm).distinct())
                } else if (s.twitchGameWhitelistSlugs.isNotEmpty()) {
                    s.copy(twitchGameWhitelistSlugs = s.twitchGameWhitelistSlugs.filterNot { it.equals(norm, ignoreCase = true) })
                } else {
                    s.copy(twitchGameFilterBlacklistMode = true, twitchGameWhitelistSlugs = listOf(norm))
                }
            } else {
                if (s.kickGameFilterBlacklistMode) {
                    s.copy(kickGameWhitelistSlugs = (s.kickGameWhitelistSlugs + norm).distinct())
                } else if (s.kickGameWhitelistSlugs.isNotEmpty()) {
                    s.copy(kickGameWhitelistSlugs = s.kickGameWhitelistSlugs.filterNot { it.equals(norm, ignoreCase = true) })
                } else {
                    s.copy(kickGameFilterBlacklistMode = true, kickGameWhitelistSlugs = listOf(norm))
                }
            }
        }
        _snackbar.tryEmit("Game ignored")
    }

    fun unignoreGame(platform: Platform, slug: String) {
        val norm = slug.trim().lowercase()
        updateSettings { s ->
            if (platform == Platform.TWITCH) {
                if (s.twitchGameFilterBlacklistMode) {
                    s.copy(twitchGameWhitelistSlugs = s.twitchGameWhitelistSlugs.filterNot { it.equals(norm, ignoreCase = true) })
                } else {
                    s.copy(twitchGameWhitelistSlugs = (s.twitchGameWhitelistSlugs + norm).distinct())
                }
            } else {
                if (s.kickGameFilterBlacklistMode) {
                    s.copy(kickGameWhitelistSlugs = s.kickGameWhitelistSlugs.filterNot { it.equals(norm, ignoreCase = true) })
                } else {
                    s.copy(kickGameWhitelistSlugs = (s.kickGameWhitelistSlugs + norm).distinct())
                }
            }
        }
        _snackbar.tryEmit("Game allowed")
    }

    fun getIgnoredGames(s: AppSettings, games: List<KnownGameEntry>): List<IgnoredGame> {
        val result = mutableListOf<IgnoredGame>()
        if (s.twitchGameFilterBlacklistMode) {
            s.twitchGameWhitelistSlugs.forEach { slug ->
                val name = games.firstOrNull { it.platform == Platform.TWITCH && it.slug.equals(slug, ignoreCase = true) }?.name ?: slug
                result += IgnoredGame(slug, name, Platform.TWITCH)
            }
        }
        if (s.kickGameFilterBlacklistMode) {
            s.kickGameWhitelistSlugs.forEach { slug ->
                val name = games.firstOrNull { it.platform == Platform.KICK && it.slug.equals(slug, ignoreCase = true) }?.name ?: slug
                result += IgnoredGame(slug, name, Platform.KICK)
            }
        }
        return result
    }

    fun updatePriorityStreamers(platform: Platform, rawText: String) {
        val logins = StreamerUrlParser.parseCommaSeparatedLogins(rawText, platform)
        updateSettings { s ->
            if (platform == Platform.TWITCH) s.copy(twitchPriorityStreamers = logins)
            else s.copy(kickPriorityStreamers = logins)
        }
    }

    fun importKickFollows(onImported: ((List<String>) -> Unit)? = null) {
        viewModelScope.launch {
            val kickAccount = repo.getAccounts().firstOrNull { it.platform == Platform.KICK && it.enabled }
            if (kickAccount == null) {
                _snackbar.tryEmit("Add an enabled Kick account first")
                return@launch
            }
            val token = repo.getCredentialsMap()[kickAccount.id]?.authToken
            if (token.isNullOrBlank()) {
                _snackbar.tryEmit("Sign in to Kick first")
                return@launch
            }
            val follows = app.kickApiClient.fetchFollowedChannels(kickAccount.id, token)
            if (follows.isEmpty()) {
                _snackbar.tryEmit("No followed Kick channels returned")
                return@launch
            }
            if (onImported != null) {
                onImported(follows)
            } else {
                updateSettings { s ->
                    s.copy(kickPriorityStreamers = (s.kickPriorityStreamers + follows).distinct())
                }
            }
            _snackbar.tryEmit("Imported ${follows.size} followed Kick channel(s)")
        }
    }

    fun saveAccountOverrides(accountId: String, overrides: AccountSettingsOverrides) {
        viewModelScope.launch {
            val updated = repo.getAccounts().map {
                if (it.id == accountId) it.copy(overrides = overrides) else it
            }
            repo.saveAccounts(updated)
            engine.triggerImmediateRefresh(accountId)
            _snackbar.tryEmit("Saved account overrides")
        }
    }

    fun sendTestWebhook(url: String) {
        viewModelScope.launch {
            val err = app.notificationDispatcher.sendTestWebhook(url)
            if (err == null) {
                _snackbar.tryEmit("Test webhook sent!")
            } else {
                _snackbar.tryEmit("Webhook failed: $err")
            }
        }
    }

    fun checkForUpdates(manual: Boolean = true) {
        viewModelScope.launch {
            val s = repo.getSettings()
            val info = app.updateChecker.checkForUpdates(s.includeBetaUpdates)
            _updateInfo.value = info
            repo.updateSettings {
                it.copy(
                    updateAvailable = info.isUpdateAvailable,
                    lastUpdateCheckEpochMs = System.currentTimeMillis()
                )
            }
            if (info.isUpdateAvailable && s.notifyOnNewUpdateAvailable) {
                app.notificationDispatcher.notify(
                    s,
                    "Update Available",
                    "Version ${info.latestVersion} is available on GitHub.",
                    NotificationType.UPDATE_AVAILABLE,
                    listOf("Current" to info.currentVersion, "Latest" to info.latestVersion)
                )
            } else if (manual) {
                _snackbar.tryEmit(
                    if (info.isUpdateAvailable) "Update ${info.latestVersion} available!"
                    else "You're up to date (v${info.currentVersion})"
                )
            }
        }
    }

    fun getSortedCampaigns(
        campaigns: List<DropsCampaign>,
        sortMode: InventorySortMode,
        hideCompleted: Boolean
    ): List<DropsCampaign> {
        val visible = if (hideCompleted) campaigns.filterNot { it.allRewardsClaimed } else campaigns
        return when (sortMode) {
            InventorySortMode.DEFAULT -> visible.sortedWith(
                compareByDescending<DropsCampaign> { it.isCurrentCampaign }
                    .thenBy { it.allRewardsClaimed }
            )
            InventorySortMode.ENDING_SOONEST -> visible.sortedBy { it.endsAtEpochMs }
            InventorySortMode.GAME_NAME -> visible.sortedBy { it.gameName.lowercase() }
            InventorySortMode.LEAST_TIME_REMAINING -> visible.sortedBy { it.remainingMinutes }
        }
    }
}
