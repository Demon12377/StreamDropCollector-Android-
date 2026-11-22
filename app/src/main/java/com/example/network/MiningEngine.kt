package com.example.network

import com.example.core.helpers.ActiveCampaignFilter
import com.example.core.helpers.CampaignPrioritizer
import com.example.core.helpers.EffectiveAccountSettings
import com.example.core.logging.AppLogger
import com.example.core.models.AccountCredentials
import com.example.core.models.AccountModel
import com.example.core.models.AccountSessionState
import com.example.core.models.ConnectionStatus
import com.example.core.models.DropsCampaign
import com.example.core.models.DropsReward
import com.example.core.models.NotificationType
import com.example.core.models.Platform
import com.example.core.models.PlatformConnectionState
import com.example.core.models.PlatformProgressState
import com.example.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class MiningEngine(
    private val settingsRepository: SettingsRepository,
    private val webViewBridge: WebViewMiningBridge,
    private val twitchGqlClient: TwitchGqlClient,
    private val twitchHelixClient: TwitchHelixClient,
    private val kickApiClient: KickApiClient,
    private val notificationDispatcher: NotificationDispatcher,
    private val headHeartbeat: HeadOnlyStreamHeartbeat = HeadOnlyStreamHeartbeat()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _sessions = MutableStateFlow<List<AccountSessionState>>(emptyList())
    val sessions: StateFlow<List<AccountSessionState>> = _sessions.asStateFlow()

    private val accountClientIds = ConcurrentHashMap<String, String>()

    private val accountJobs = ConcurrentHashMap<String, Job>()
    private var telemetryJob: Job? = null
    private var isStarted = false

    fun start() {
        if (isStarted) return
        isStarted = true

        scope.launch {
            val initialAccounts = settingsRepository.getAccounts()
            syncAccountsList(initialAccounts)
        }

        telemetryJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                updateTelemetrySnapshot()
            }
        }
    }

    fun stop() {
        isStarted = false
        telemetryJob?.cancel()
        telemetryJob = null
        accountJobs.values.forEach { it.cancel() }
        accountJobs.clear()
        twitchHelixClient.disconnectEventSub()
    }

    suspend fun syncAccountsList(accounts: List<AccountModel>) {
        val credsMap = settingsRepository.getCredentialsMap()
        val currentMap = _sessions.value.associateBy { it.account.id }

        val nextSessions = accounts.map { acc ->
            val existing = currentMap[acc.id]
            val creds = credsMap[acc.id]
            val pinnedId = settingsRepository.getPinnedCampaignId(acc.id)
            if (existing != null) {
                existing.copy(
                    account = acc,
                    pinnedCampaignId = pinnedId
                )
            } else {
                val hasToken = creds?.authToken?.isNotBlank() == true
                AccountSessionState(
                    account = acc,
                    pinnedCampaignId = pinnedId,
                    connection = PlatformConnectionState(
                        platform = acc.platform,
                        status = when {
                            !acc.enabled -> ConnectionStatus.DISABLED
                            hasToken -> ConnectionStatus.CHECKING
                            else -> ConnectionStatus.OFFLINE
                        },
                        loginButtonText = if (hasToken) "Relogin" else "Login ${acc.platform.displayName}",
                        isLoggedIn = hasToken
                    ),
                    statusText = if (acc.enabled) "Initializing" else "Disabled",
                    statusDetails = if (acc.enabled) "Waiting for sync..." else "Account is disabled",
                    minerStatus = if (acc.enabled) "Starting" else "Disabled"
                )
            }
        }
        _sessions.value = nextSessions

        val activeIds = accounts.filter { it.enabled }.map { it.id }.toSet()
        accountJobs.keys.filter { it !in activeIds }.forEach { deadId ->
            accountJobs.remove(deadId)?.cancel()
        }
        accounts.filter { it.enabled }.forEach { acc ->
            if (!accountJobs.containsKey(acc.id)) {
                accountJobs[acc.id] = scope.launch { runAccountLoop(acc.id) }
            }
        }
    }

    fun triggerImmediateRefresh(accountId: String? = null) {
        scope.launch {
            val accounts = settingsRepository.getAccounts()
            if (accountId == null) {
                accounts.filter { it.enabled }.forEach { acc ->
                    accountJobs.remove(acc.id)?.cancel()
                    accountJobs[acc.id] = scope.launch { runAccountLoop(acc.id) }
                }
            } else {
                val acc = accounts.firstOrNull { it.id == accountId } ?: return@launch
                accountJobs.remove(acc.id)?.cancel()
                if (acc.enabled) {
                    accountJobs[acc.id] = scope.launch { runAccountLoop(acc.id) }
                }
            }
        }
    }

    suspend fun pinCampaign(accountId: String, campaignId: String) {
        settingsRepository.setPinnedCampaignId(accountId, campaignId)
        updateSession(accountId) { it.copy(pinnedCampaignId = campaignId) }
        triggerImmediateRefresh(accountId)
    }

    private suspend fun runAccountLoop(accountId: String) {
        AppLogger.info("Miner", "Started mining loop for account=$accountId")
        while (scope.isActive) {
            val account = settingsRepository.getAccounts().firstOrNull { it.id == accountId } ?: break
            if (!account.enabled) {
                updateSession(accountId) {
                    it.copy(
                        connection = it.connection.copy(status = ConnectionStatus.DISABLED),
                        statusText = "Disabled",
                        statusDetails = "Account is disabled",
                        minerStatus = "Disabled"
                    )
                }
                break
            }

            val creds = settingsRepository.getCredentialsMap()[accountId]
            if (creds == null || creds.authToken.isBlank()) {
                updateSession(accountId) {
                    it.copy(
                        connection = it.connection.copy(
                            status = ConnectionStatus.OFFLINE,
                            isLoggedIn = false,
                            loginButtonText = "Login ${account.platform.displayName}"
                        ),
                        statusText = "Login required",
                        statusDetails = "Sign in to ${account.platform.displayName} to fetch drops",
                        minerStatus = "Idle"
                    )
                }
                delay(15_000L)
                continue
            }

            val globalSettings = settingsRepository.getSettings()
            val effective = ActiveCampaignFilter.resolveEffectiveSettings(account, globalSettings)

            try {
                if (account.platform == Platform.TWITCH) {
                    runTwitchCycle(account, creds, effective, globalSettings)
                } else {
                    runKickCycle(account, creds, effective, globalSettings)
                }
            } catch (ex: Exception) {
                AppLogger.error("Miner", "Cycle failed for ${account.displayName}: ${ex.message}", ex)
                updateSession(accountId) {
                    it.copy(
                        connection = it.connection.copy(status = ConnectionStatus.ERROR),
                        statusText = "Retry scheduled",
                        statusDetails = ex.message ?: "Temporary network error",
                        minerStatus = "Idle"
                    )
                }
            }

            // Watch/tick loop for 4 minutes (pulsing low-power HEAD heartbeats every 20s and updating UI progress every 60s)
            for (minuteTick in 1..4) {
                for (subPulse in 1..3) {
                    delay(20_000L)
                    val latestAcc = settingsRepository.getAccounts().firstOrNull { it.id == accountId }
                    if (latestAcc == null || !latestAcc.enabled) break
                    val curSettings = settingsRepository.getSettings()
                    val curEffective = ActiveCampaignFilter.resolveEffectiveSettings(latestAcc, curSettings)
                    val curSession = _sessions.value.firstOrNull { it.account.id == accountId }
                    val activeChannel = curSession?.progress?.minedChannel.orEmpty()
                    if (latestAcc.platform == Platform.TWITCH &&
                        curSession?.minerStatus == "Mining" &&
                        activeChannel.isNotBlank() &&
                        (curEffective.headOnlyMining || curSettings.overnightBatterySaver)
                    ) {
                        val clientId = accountClientIds[accountId] ?: creds.clientId.ifBlank { "kimne78kx3ncx6brgo4mv6wki5h1ko" }
                        headHeartbeat.pulseTwitchChannel(
                            accountId = accountId,
                            authToken = creds.authToken,
                            clientId = clientId,
                            channelLogin = activeChannel
                        )
                    }
                }
                val latestAcc = settingsRepository.getAccounts().firstOrNull { it.id == accountId }
                if (latestAcc == null || !latestAcc.enabled) break
                advanceLocalProgressOneMinute(accountId)
            }
        }
    }

    private suspend fun runTwitchCycle(
        account: AccountModel,
        creds: AccountCredentials,
        effective: EffectiveAccountSettings,
        globalSettings: com.example.core.models.AppSettings
    ) {
        updateSession(account.id) {
            it.copy(
                connection = it.connection.copy(status = ConnectionStatus.CHECKING),
                statusText = "Syncing Twitch drops..."
            )
        }

        val headers = webViewBridge.captureTwitchHeaders(account.id, creds.authToken)
            ?: CapturedTwitchHeaders(
                clientId = creds.clientId.ifBlank { "kimne78kx3ncx6brgo4mv6wki5h1ko" },
                integrityToken = creds.integrityToken,
                deviceId = creds.deviceId,
                authToken = creds.authToken
            )

        val fetched = twitchGqlClient.fetchDashboardAndCampaigns(account.id, headers)
        if (fetched == null) {
            updateSession(account.id) {
                it.copy(
                    connection = it.connection.copy(status = ConnectionStatus.OFFLINE, isLoggedIn = false, loginButtonText = "Relogin"),
                    statusText = "Session expired or offline",
                    statusDetails = "Sign in again to refresh Twitch integrity",
                    minerStatus = "Idle"
                )
            }
            return
        }

        if (fetched.knownGames.isNotEmpty()) {
            settingsRepository.mergeKnownGames(fetched.knownGames)
        }
        if (fetched.login.isNotBlank() && account.displayName != fetched.login) {
            val all = settingsRepository.getAccounts().map {
                if (it.id == account.id) it.copy(displayName = fetched.login) else it
            }
            settingsRepository.saveAccounts(all)
        }

        val allCampaigns = fetched.campaigns + fetched.rewardCampaigns
        val updatedCampaigns = processClaims(
            account = account,
            campaigns = allCampaigns,
            autoClaim = effective.autoClaimRewards,
            globalSettings = globalSettings,
            claimAction = { camp, rew ->
                twitchGqlClient.claimDrop(account.id, headers, fetched.userId, camp.id, rew.id, rew.dropInstanceId)
            }
        )

        val filteredForMining = ActiveCampaignFilter.filterForMining(updatedCampaigns, effective)
            .filter { it.hasProgressToMake(effective.autoClaimRewards) }

        val pinnedId = settingsRepository.getPinnedCampaignId(account.id)
        val selection = CampaignPrioritizer.selectBest(
            campaigns = filteredForMining,
            pinnedCampaignId = pinnedId,
            mode = effective.miningPriorityMode,
            prioritizeBadges = effective.prioritizeBadges,
            gamePriority = effective.gameWhitelistSlugs
        )
        if (selection.pinReleased) {
            settingsRepository.setPinnedCampaignId(account.id, null)
        }

        val useLowPowerHead = effective.headOnlyMining || globalSettings.overnightBatterySaver
        accountClientIds[account.id] = headers.clientId

        val selectedCampaign = selection.campaign
        var minedChannelLogin = ""
        var minedChannelUrl = ""

        if (selectedCampaign != null) {
            val liveLogin = pickBestTwitchStreamer(selectedCampaign, effective.priorityStreamers, account.id)
            if (liveLogin != null) {
                minedChannelLogin = liveLogin
                minedChannelUrl = "https://www.twitch.tv/$liveLogin"
                settingsRepository.setLastMinedStreamer(account.id, liveLogin)
                webViewBridge.navigateAndPrepareTwitchStream(
                    accountId = account.id,
                    authToken = creds.authToken,
                    streamUrl = minedChannelUrl,
                    headOnly = useLowPowerHead
                )
                if (useLowPowerHead) {
                    headHeartbeat.pulseTwitchChannel(
                        accountId = account.id,
                        authToken = creds.authToken,
                        clientId = headers.clientId,
                        channelLogin = liveLogin
                    )
                }
            } else {
                webViewBridge.suspendWebViewForLowPower(Platform.TWITCH)
            }
        } else {
            webViewBridge.suspendWebViewForLowPower(Platform.TWITCH)
        }

        val markedCampaigns = markCurrentCampaigns(updatedCampaigns, selectedCampaign?.id)
        val regularCampaigns = markedCampaigns.filter { !it.isRewardCampaign }
        val rewardCampaigns = markedCampaigns.filter { it.isRewardCampaign }

        val progressState = buildProgressState(
            platform = Platform.TWITCH,
            selected = markedCampaigns.firstOrNull { it.id == selectedCampaign?.id },
            minedChannel = minedChannelLogin,
            minedUrl = minedChannelUrl
        )

        updateSession(account.id) {
            it.copy(
                account = if (fetched.login.isNotBlank()) it.account.copy(displayName = fetched.login) else it.account,
                connection = PlatformConnectionState(
                    platform = Platform.TWITCH,
                    status = ConnectionStatus.ONLINE,
                    loginButtonText = "Relogin",
                    isLoggedIn = true
                ),
                progress = progressState,
                statusText = if (minedChannelLogin.isNotBlank()) "Mining $minedChannelLogin" else "Idle",
                statusDetails = selectedCampaign?.name ?: "No eligible active drops",
                minerStatus = if (minedChannelLogin.isNotBlank()) "Mining" else "Idle",
                campaigns = regularCampaigns,
                rewardCampaigns = rewardCampaigns,
                pinnedCampaignId = if (selection.pinReleased) null else pinnedId,
                lastSyncEpochMs = System.currentTimeMillis()
            )
        }
    }

    private suspend fun runKickCycle(
        account: AccountModel,
        creds: AccountCredentials,
        effective: EffectiveAccountSettings,
        globalSettings: com.example.core.models.AppSettings
    ) {
        updateSession(account.id) {
            it.copy(
                connection = it.connection.copy(status = ConnectionStatus.CHECKING),
                statusText = "Syncing Kick drops..."
            )
        }

        webViewBridge.navigateKickOrigin(account.id, creds.authToken, onlyIfNotOnDomain = true)
        val (_, username) = kickApiClient.fetchUserProfile(account.id, creds.authToken)
        if (!username.isNullOrBlank() && account.displayName != username) {
            val all = settingsRepository.getAccounts().map {
                if (it.id == account.id) it.copy(displayName = username) else it
            }
            settingsRepository.saveAccounts(all)
        }

        val fetched = kickApiClient.fetchActiveCampaigns(account.id, creds.authToken)
        if (fetched.knownGames.isNotEmpty()) {
            settingsRepository.mergeKnownGames(fetched.knownGames)
        }

        val updatedCampaigns = processClaims(
            account = account,
            campaigns = fetched.campaigns,
            autoClaim = effective.autoClaimRewards,
            globalSettings = globalSettings,
            claimAction = { camp, rew ->
                kickApiClient.claimDrop(account.id, creds.authToken, camp.id, rew.id)
            }
        )

        // Kick level & daily reward checks
        val levelProgress = kickApiClient.fetchLevelProgress(account.id, creds.authToken)
        val streakDays = kickApiClient.fetchStreakDays(account.id, creds.authToken)
        val challenges = kickApiClient.fetchChallenges(account.id, creds.authToken)

        if (effective.kickDailyClaim) {
            challenges.filter { it.isReadyToClaim }.forEach { ch ->
                val ok = kickApiClient.claimChallenge(account.id, creds.authToken, ch.id)
                if (ok) {
                    AppLogger.info("KickDaily", "Auto-claimed Kick challenge '${ch.name}'")
                }
            }
        }

        val filteredForMining = ActiveCampaignFilter.filterForMining(updatedCampaigns, effective)
            .filter { it.hasProgressToMake(effective.autoClaimRewards) }

        val pinnedId = settingsRepository.getPinnedCampaignId(account.id)
        val selection = CampaignPrioritizer.selectBest(
            campaigns = filteredForMining,
            pinnedCampaignId = pinnedId,
            mode = effective.miningPriorityMode,
            prioritizeBadges = effective.prioritizeBadges,
            gamePriority = effective.gameWhitelistSlugs
        )
        if (selection.pinReleased) {
            settingsRepository.setPinnedCampaignId(account.id, null)
        }

        var selectedCampaign = selection.campaign
        var minedChannelLogin = ""
        var minedChannelUrl = ""

        if (selectedCampaign != null) {
            val liveLogin = pickBestKickStreamer(selectedCampaign, effective.priorityStreamers, account.id)
            if (liveLogin != null) {
                minedChannelLogin = liveLogin
                minedChannelUrl = "https://kick.com/$liveLogin"
                settingsRepository.setLastMinedStreamer(account.id, liveLogin)
                webViewBridge.navigateKickOrigin(account.id, creds.authToken, minedChannelUrl)
            }
        }

        // Kick level farming fallback
        if (minedChannelLogin.isBlank() && effective.kickLevelFarming) {
            val top = kickApiClient.getTopLiveChannels(null).firstOrNull()
            if (top != null) {
                minedChannelLogin = top.login
                minedChannelUrl = "https://kick.com/${top.login}"
                webViewBridge.navigateKickOrigin(account.id, creds.authToken, minedChannelUrl)
                selectedCampaign = DropsCampaign(
                    id = "kick-level-farming",
                    name = "Kick Level Farming",
                    slug = "",
                    gameName = top.gameName ?: "Just Chatting",
                    startsAtEpochMs = System.currentTimeMillis(),
                    endsAtEpochMs = System.currentTimeMillis() + 3_600_000L,
                    rewards = emptyList(),
                    platform = Platform.KICK,
                    isGeneralDrop = true,
                    isCurrentCampaign = true
                )
            }
        }
        if (minedChannelLogin.isBlank()) {
            webViewBridge.suspendWebViewForLowPower(Platform.KICK)
        }

        val markedCampaigns = markCurrentCampaigns(updatedCampaigns, selectedCampaign?.id)
        val dailyChallenge = challenges.firstOrNull { it.isDaily }
        val dailyText = when {
            dailyChallenge == null -> "N/A"
            dailyChallenge.isClaimed -> "Claimed"
            else -> "${dailyChallenge.progress} / ${dailyChallenge.threshold} min"
        }

        val progressState = buildProgressState(
            platform = Platform.KICK,
            selected = markedCampaigns.firstOrNull { it.id == selectedCampaign?.id } ?: selectedCampaign,
            minedChannel = minedChannelLogin,
            minedUrl = minedChannelUrl,
            levelLine = levelProgress?.let { "Level ${it.level} (${it.progressXp}/${it.progressXp + it.xpToNextLevel} XP)" }.orEmpty(),
            levelPercentText = levelProgress?.let { " • %.0f%%".format(it.percent) }.orEmpty(),
            levelBadgeUrl = levelProgress?.badgeUrl,
            levelFraction = ((levelProgress?.percent ?: 0.0) / 100.0).toFloat(),
            streakText = streakDays?.let { "$it days" } ?: "0 days",
            dailyRewardText = dailyText
        )

        updateSession(account.id) {
            it.copy(
                account = if (!username.isNullOrBlank()) it.account.copy(displayName = username) else it.account,
                connection = PlatformConnectionState(
                    platform = Platform.KICK,
                    status = ConnectionStatus.ONLINE,
                    loginButtonText = "Relogin",
                    isLoggedIn = true
                ),
                progress = progressState,
                statusText = if (minedChannelLogin.isNotBlank()) "Mining $minedChannelLogin" else "Idle",
                statusDetails = selectedCampaign?.name ?: "No eligible Kick drops",
                minerStatus = if (minedChannelLogin.isNotBlank()) "Mining" else "Idle",
                campaigns = markedCampaigns,
                pinnedCampaignId = if (selection.pinReleased) null else pinnedId,
                lastSyncEpochMs = System.currentTimeMillis()
            )
        }
    }

    private suspend fun processClaims(
        account: AccountModel,
        campaigns: List<DropsCampaign>,
        autoClaim: Boolean,
        globalSettings: com.example.core.models.AppSettings,
        claimAction: suspend (DropsCampaign, DropsReward) -> Boolean
    ): List<DropsCampaign> {
        val result = campaigns.toMutableList()
        val readyPairs = result.flatMap { camp ->
            camp.rewards.filter { !it.isClaimed && it.progressMinutes >= it.requiredMinutes && !camp.isRewardCampaign }
                .map { rew -> camp to rew }
        }
        if (readyPairs.isEmpty()) return result

        if (autoClaim) {
            for ((camp, rew) in readyPairs) {
                val ok = claimAction(camp, rew)
                if (ok) {
                    val idx = result.indexOfFirst { it.id == camp.id }
                    if (idx >= 0) {
                        val c = result[idx]
                        result[idx] = c.copy(
                            rewards = c.rewards.map {
                                if (it.id == rew.id) it.copy(isClaimed = true, progressMinutes = it.requiredMinutes) else it
                            }
                        )
                    }
                    if (globalSettings.notifyOnAutoClaimed) {
                        notificationDispatcher.notify(
                            globalSettings,
                            "Drop Claimed",
                            "Auto-claimed ${rew.name} (${camp.gameName})",
                            NotificationType.DROP_CLAIMED,
                            listOf("Account" to account.displayName, "Platform" to account.platform.displayName, "Campaign" to camp.name, "Reward" to rew.name)
                        )
                    }
                } else {
                    notificationDispatcher.notify(
                        globalSettings,
                        "Drop Claim Failed",
                        "Could not claim ${rew.name}; will retry automatically.",
                        NotificationType.DROP_CLAIM_FAILED,
                        listOf("Account" to account.displayName, "Platform" to account.platform.displayName, "Reward" to rew.name)
                    )
                }
            }
        } else if (globalSettings.notifyOnReadyToClaim) {
            notificationDispatcher.notify(
                globalSettings,
                "Drop Ready to Claim",
                "${readyPairs.size} drop reward(s) ready to claim manually.",
                NotificationType.DROP_READY_TO_CLAIM,
                listOf("Account" to account.displayName, "Platform" to account.platform.displayName)
            )
        }
        return result
    }

    private suspend fun pickBestTwitchStreamer(
        campaign: DropsCampaign,
        priorityStreamers: List<String>,
        accountId: String
    ): String? {
        val last = settingsRepository.getLastMinedStreamer(accountId)
        val candidates = (priorityStreamers + listOfNotNull(last) + campaign.eligibleStreamers.map { it.login })
            .map { it.lowercase() }
            .distinct()

        if (twitchHelixClient.hasValidToken()) {
            if (candidates.isNotEmpty()) {
                val live = twitchHelixClient.getLiveStreamsByLogins(candidates)
                for (pref in priorityStreamers) {
                    live.firstOrNull { it.login == pref.lowercase() && it.isLive }?.let { return it.login }
                }
                live.maxByOrNull { it.viewerCount }?.let { return it.login }
            }
            if (campaign.acceptsAnyChannel) {
                val gameId = campaign.gameId ?: twitchHelixClient.searchGameId(campaign.gameName)
                if (!gameId.isNullOrBlank()) {
                    twitchHelixClient.getTopLiveStreamsForGame(gameId, 10).firstOrNull()?.let { return it.login }
                }
            }
        }

        return campaign.eligibleStreamers.firstOrNull()?.login ?: priorityStreamers.firstOrNull()
    }

    private suspend fun pickBestKickStreamer(
        campaign: DropsCampaign,
        priorityStreamers: List<String>,
        accountId: String
    ): String? {
        val last = settingsRepository.getLastMinedStreamer(accountId)
        val candidates = (priorityStreamers + listOfNotNull(last) + campaign.eligibleStreamers.map { it.login })
            .map { it.lowercase() }
            .distinct()

        for (login in candidates) {
            val snap = kickApiClient.getChannelLiveSnapshot(login)
            if (snap?.isLive == true) return snap.login
        }
        if (campaign.acceptsAnyChannel && campaign.slug.isNotBlank()) {
            val top = kickApiClient.getTopLiveChannels(campaign.slug).firstOrNull()
            if (top != null) return top.login
        }
        return campaign.eligibleStreamers.firstOrNull()?.login
    }

    private fun markCurrentCampaigns(campaigns: List<DropsCampaign>, currentId: String?): List<DropsCampaign> {
        return campaigns.map { c ->
            val isCurr = c.id == currentId
            val activeRewardId = if (isCurr) c.rewards.firstOrNull { !it.isClaimed && it.progressMinutes < it.requiredMinutes }?.id else null
            c.copy(
                isCurrentCampaign = isCurr,
                rewards = c.rewards.map { r -> r.copy(isCurrentReward = r.id == activeRewardId) }
            )
        }
    }

    private fun buildProgressState(
        platform: Platform,
        selected: DropsCampaign?,
        minedChannel: String,
        minedUrl: String,
        levelLine: String = "",
        levelPercentText: String = "",
        levelBadgeUrl: String? = null,
        levelFraction: Float = 0f,
        streakText: String = "",
        dailyRewardText: String = ""
    ): PlatformProgressState {
        val activeDrop = selected?.rewards?.firstOrNull { it.isCurrentReward }
            ?: selected?.rewards?.firstOrNull { !it.isClaimed }
        val campPct = selected?.completionPercentage ?: 0.0
        val dropPct = ((activeDrop?.progressFraction ?: 0f) * 100f).toDouble()

        return PlatformProgressState(
            platform = platform,
            campaignName = selected?.name.orEmpty(),
            campaignImageUrl = selected?.gameImageUrl,
            gameName = selected?.gameName.orEmpty(),
            campaignProgressPercent = campPct,
            campaignCaptionText = if (selected != null) "%.0f%% • %s".format(campPct, selected.timeToCompleteText) else "No active campaign",
            dropName = activeDrop?.name.orEmpty(),
            dropImageUrl = activeDrop?.imageUrl,
            dropProgressPercent = dropPct,
            dropCaptionText = if (activeDrop != null) "${activeDrop.progressMinutes} / ${activeDrop.requiredMinutes} min • ${activeDrop.timeToCompleteText}" else "No active drop",
            minedChannel = minedChannel,
            minedChannelUrl = minedUrl,
            levelLineText = levelLine,
            levelPercentText = levelPercentText,
            levelBadgeUrl = levelBadgeUrl,
            levelProgressFraction = levelFraction,
            dailyStreakText = streakText,
            dailyRewardText = dailyRewardText,
            showKickExtras = platform == Platform.KICK
        )
    }

    private fun advanceLocalProgressOneMinute(accountId: String) {
        updateSession(accountId) { session ->
            if (session.minerStatus != "Mining") return@updateSession session
            val updatedCampaigns = session.campaigns.map { camp ->
                if (!camp.isCurrentCampaign) return@map camp
                val updatedRewards = camp.rewards.map { rew ->
                    if (!rew.isClaimed && rew.progressMinutes < rew.requiredMinutes) {
                        rew.copy(progressMinutes = rew.progressMinutes + 1)
                    } else rew
                }
                camp.copy(rewards = updatedRewards)
            }
            val current = updatedCampaigns.firstOrNull { it.isCurrentCampaign }
            session.copy(
                campaigns = updatedCampaigns,
                progress = buildProgressState(
                    platform = session.account.platform,
                    selected = current,
                    minedChannel = session.progress.minedChannel,
                    minedUrl = session.progress.minedChannelUrl,
                    levelLine = session.progress.levelLineText,
                    levelPercentText = session.progress.levelPercentText,
                    levelBadgeUrl = session.progress.levelBadgeUrl,
                    levelFraction = session.progress.levelProgressFraction,
                    streakText = session.progress.dailyStreakText,
                    dailyRewardText = session.progress.dailyRewardText
                )
            )
        }
    }

    private fun updateTelemetrySnapshot() {
        _sessions.value = _sessions.value.map { session ->
            val sampled = NetworkMeterRegistry.forAccount(session.account.id).sample()
            session.copy(network = sampled)
        }
    }

    private inline fun updateSession(accountId: String, transform: (AccountSessionState) -> AccountSessionState) {
        _sessions.value = _sessions.value.map { if (it.account.id == accountId) transform(it) else it }
    }
}
