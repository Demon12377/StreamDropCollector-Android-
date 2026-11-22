package com.example

import android.app.Application
import com.example.core.logging.AppLogger
import com.example.data.SettingsRepository
import com.example.network.KickApiClient
import com.example.network.MiningEngine
import com.example.network.NotificationDispatcher
import com.example.network.TwitchGqlClient
import com.example.network.TwitchHelixClient
import com.example.network.UpdateChecker
import com.example.network.WebViewMiningBridge

class DropCollectorApp : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var webViewBridge: WebViewMiningBridge
        private set
    lateinit var twitchHelixClient: TwitchHelixClient
        private set
    lateinit var twitchGqlClient: TwitchGqlClient
        private set
    lateinit var kickApiClient: KickApiClient
        private set
    lateinit var notificationDispatcher: NotificationDispatcher
        private set
    lateinit var updateChecker: UpdateChecker
        private set
    lateinit var miningEngine: MiningEngine
        private set

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        settingsRepository = SettingsRepository(this)
        webViewBridge = WebViewMiningBridge()
        twitchHelixClient = TwitchHelixClient(settingsRepository)
        twitchGqlClient = TwitchGqlClient(settingsRepository)
        kickApiClient = KickApiClient(webViewBridge)
        notificationDispatcher = NotificationDispatcher(this)
        updateChecker = UpdateChecker()
        miningEngine = MiningEngine(
            settingsRepository = settingsRepository,
            webViewBridge = webViewBridge,
            twitchGqlClient = twitchGqlClient,
            twitchHelixClient = twitchHelixClient,
            kickApiClient = kickApiClient,
            notificationDispatcher = notificationDispatcher
        )
        miningEngine.start()
    }
}
