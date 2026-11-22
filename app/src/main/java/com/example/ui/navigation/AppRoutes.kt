package com.example.ui.navigation

import com.example.core.models.Platform
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute {
    @Serializable
    data object Dashboard : AppRoute

    @Serializable
    data object Inventory : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object Help : AppRoute

    @Serializable
    data class AccountSettings(val accountId: String) : AppRoute

    @Serializable
    data class PlatformLogin(val platform: Platform, val existingAccountId: String? = null) : AppRoute

    @Serializable
    data object LogsViewer : AppRoute
}
