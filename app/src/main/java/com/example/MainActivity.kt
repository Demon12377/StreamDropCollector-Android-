package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.core.models.Platform
import com.example.ui.components.HelixDeviceCodeDialog
import com.example.ui.navigation.AppRoute
import com.example.ui.screens.AccountSettingsScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HelpScreen
import com.example.ui.screens.InventoryScreen
import com.example.ui.screens.LogsViewerScreen
import com.example.ui.screens.PlatformLoginScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.DropCollectorTheme
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalDropColors
import com.example.ui.viewmodel.MainViewModel

private data class TopLevelDestination(
    val route: AppRoute,
    val labelRes: Int,
    val icon: ImageVector,
    val testTag: String
)

private val TOP_LEVEL_DESTINATIONS = listOf(
    TopLevelDestination(AppRoute.Dashboard, R.string.nav_dashboard, Icons.Default.Dashboard, "nav_dashboard"),
    TopLevelDestination(AppRoute.Inventory, R.string.nav_inventory, Icons.Default.Inventory2, "nav_inventory"),
    TopLevelDestination(AppRoute.Settings, R.string.nav_settings, Icons.Default.Settings, "nav_settings"),
    TopLevelDestination(AppRoute.Help, R.string.nav_help, Icons.AutoMirrored.Filled.HelpOutline, "nav_help")
)

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val sessions by viewModel.sessions.collectAsStateWithLifecycle()
            val knownGames by viewModel.knownGames.collectAsStateWithLifecycle()
            val logs by viewModel.logs.collectAsStateWithLifecycle()
            val helixPrompt by viewModel.helixPrompt.collectAsStateWithLifecycle()
            val helixConnected by viewModel.helixConnected.collectAsStateWithLifecycle()
            val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()

            DropCollectorTheme(themeMode = settings.themeMode) {
                DropCollectorRoot(
                    viewModel = viewModel,
                    settings = settings,
                    sessions = sessions,
                    knownGames = knownGames,
                    logs = logs,
                    helixPrompt = helixPrompt,
                    helixConnected = helixConnected,
                    updateInfo = updateInfo
                )
            }
        }
    }
}

@Composable
private fun DropCollectorRoot(
    viewModel: MainViewModel,
    settings: com.example.core.models.AppSettings,
    sessions: List<com.example.core.models.AccountSessionState>,
    knownGames: List<com.example.data.KnownGameEntry>,
    logs: List<com.example.core.models.LogEntry>,
    helixPrompt: com.example.core.models.TwitchDeviceCodePrompt?,
    helixConnected: Boolean,
    updateInfo: com.example.core.models.UpdateInfo?
) {
    val colors = LocalDropColors.current
    val context = LocalContext.current
    val app = context.applicationContext as DropCollectorApp
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun openExternalUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbar.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination
    val isTopLevel = currentDest == null || TOP_LEVEL_DESTINATIONS.any { currentDest.hasRoute(it.route::class) }

    fun navigateTopLevel(route: AppRoute) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.mainBackground)) {
        // Hidden 1.dp background WebViews for silent header/HLS/Kick fetch automation
        AndroidView(
            modifier = Modifier.size(1.dp).alpha(0f),
            factory = { ctx ->
                app.webViewBridge.createOrGetWebView(ctx, Platform.TWITCH).apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                }
            }
        )
        AndroidView(
            modifier = Modifier.size(1.dp).alpha(0f),
            factory = { ctx ->
                app.webViewBridge.createOrGetWebView(ctx, Platform.KICK).apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                }
            }
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isExpanded = maxWidth >= 600.dp

            Scaffold(
                containerColor = colors.mainBackground,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    if (isTopLevel && !isExpanded) {
                        AppBrandHeaderBar(colors = colors)
                    }
                },
                bottomBar = {
                    if (isTopLevel && !isExpanded) {
                        NavigationBar(
                            containerColor = colors.headerBackground,
                            contentColor = colors.textPrimary
                        ) {
                            TOP_LEVEL_DESTINATIONS.forEach { dest ->
                                val selected = currentDest?.hasRoute(dest.route::class) == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest.route) },
                                    icon = { Icon(dest.icon, contentDescription = stringResource(dest.labelRes)) },
                                    label = { Text(stringResource(dest.labelRes), maxLines = 1) },
                                    modifier = Modifier.testTag(dest.testTag),
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = colors.accent,
                                        selectedTextColor = colors.accent,
                                        unselectedIconColor = colors.textMuted,
                                        unselectedTextColor = colors.textMuted,
                                        indicatorColor = colors.accent.copy(alpha = 0.14f)
                                    )
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isTopLevel && isExpanded) {
                        NavigationRail(
                            containerColor = colors.headerBackground,
                            contentColor = colors.textPrimary,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Spacer(modifier = Modifier.size(12.dp))
                            Image(
                                painter = painterResource(id = R.drawable.ic_brand_logo),
                                contentDescription = stringResource(R.string.cd_app_logo),
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted,
                                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                            )
                            TOP_LEVEL_DESTINATIONS.forEach { dest ->
                                val selected = currentDest?.hasRoute(dest.route::class) == true
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest.route) },
                                    icon = { Icon(dest.icon, contentDescription = stringResource(dest.labelRes)) },
                                    label = { Text(stringResource(dest.labelRes)) },
                                    modifier = Modifier.testTag(dest.testTag),
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = colors.accent,
                                        selectedTextColor = colors.accent,
                                        unselectedIconColor = colors.textMuted,
                                        unselectedTextColor = colors.textMuted,
                                        indicatorColor = colors.accent.copy(alpha = 0.14f)
                                    )
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = AppRoute.Dashboard,
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 1040.dp)
                        ) {
                            composable<AppRoute.Dashboard> {
                                DashboardScreen(
                                    sessions = sessions,
                                    helixConnected = helixConnected,
                                    isExpandedWidth = isExpanded,
                                    onAddAccount = { platform ->
                                        navController.navigate(AppRoute.PlatformLogin(platform = platform, existingAccountId = null))
                                    },
                                    onLoginAccount = { session ->
                                        navController.navigate(
                                            AppRoute.PlatformLogin(
                                                platform = session.account.platform,
                                                existingAccountId = session.account.id
                                            )
                                        )
                                    },
                                    onToggleAccountEnabled = { id, enabled ->
                                        viewModel.toggleAccountEnabled(id, enabled)
                                    },
                                    onOpenAccountSettings = { id ->
                                        navController.navigate(AppRoute.AccountSettings(accountId = id))
                                    },
                                    onRemoveAccount = { id -> viewModel.removeAccount(id) },
                                    onResetNetwork = { id -> viewModel.resetAccountNetwork(id) },
                                    onRefreshAll = { viewModel.refreshAll() },
                                    onAuthorizeHelix = { viewModel.startHelixAuthorization() }
                                )
                            }

                            composable<AppRoute.Inventory> {
                                val ignoredGames = remember(settings, knownGames) {
                                    viewModel.getIgnoredGames(settings, knownGames)
                                }
                                InventoryScreen(
                                    sessions = sessions,
                                    settings = settings,
                                    ignoredGames = ignoredGames,
                                    getSortedCampaigns = { list, mode, hide ->
                                        viewModel.getSortedCampaigns(list, mode, hide)
                                    },
                                    onSortModeChange = { viewModel.setInventorySortMode(it) },
                                    onHideCompletedChange = { viewModel.setHideCompletedCampaigns(it) },
                                    onRefreshAll = { viewModel.refreshAll() },
                                    onPinCampaign = { accId, campId -> viewModel.pinCampaign(accId, campId) },
                                    onMoveGameToTop = { platform, slug -> viewModel.moveGameToTop(platform, slug) },
                                    onIgnoreGame = { platform, slug -> viewModel.ignoreGame(platform, slug) },
                                    onUnignoreGame = { platform, slug -> viewModel.unignoreGame(platform, slug) },
                                    onOpenUrl = { openExternalUrl(it) }
                                )
                            }

                            composable<AppRoute.Settings> {
                                SettingsScreen(
                                    settings = settings,
                                    knownGames = knownGames,
                                    updateInfo = updateInfo,
                                    onUpdateSettings = { viewModel.updateSettings(it) },
                                    onCheckUpdates = { viewModel.checkForUpdates(manual = true) },
                                    onOpenUrl = { openExternalUrl(it) },
                                    onUpdatePriorityStreamers = { p, raw -> viewModel.updatePriorityStreamers(p, raw) },
                                    onImportKickFollows = { viewModel.importKickFollows() },
                                    onSendTestWebhook = { viewModel.sendTestWebhook(it) },
                                    onOpenLogsViewer = { navController.navigate(AppRoute.LogsViewer) },
                                    onRemoveAllAccounts = { viewModel.removeAllAccounts() },
                                    onRequestNotificationPermissionIfNeeded = { requestNotificationsIfNeeded() }
                                )
                            }

                            composable<AppRoute.Help> {
                                HelpScreen(onOpenUrl = { openExternalUrl(it) })
                            }

                            composable<AppRoute.AccountSettings> { entry ->
                                val route = entry.toRoute<AppRoute.AccountSettings>()
                                val account = sessions.firstOrNull { it.account.id == route.accountId }?.account
                                AccountSettingsScreen(
                                    account = account,
                                    globalSettings = settings,
                                    knownGames = knownGames,
                                    onImportKickFollows = { cb -> viewModel.importKickFollows(cb) },
                                    onSaveOverrides = { overrides ->
                                        viewModel.saveAccountOverrides(route.accountId, overrides)
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable<AppRoute.PlatformLogin> { entry ->
                                val route = entry.toRoute<AppRoute.PlatformLogin>()
                                PlatformLoginScreen(
                                    platform = route.platform,
                                    existingAccountId = route.existingAccountId,
                                    onLoginComplete = { platform, accId, token, displayName, cookies ->
                                        viewModel.completePlatformLogin(platform, accId, token, displayName, cookies)
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable<AppRoute.LogsViewer> {
                                LogsViewerScreen(
                                    logs = logs,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val activePrompt = helixPrompt
    if (activePrompt != null) {
        HelixDeviceCodeDialog(
            prompt = activePrompt,
            onOpenActivation = { openExternalUrl(it) },
            onDismiss = { viewModel.dismissHelixPrompt() }
        )
    }
}

@Composable
private fun AppBrandHeaderBar(colors: com.example.ui.theme.DropCollectorColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.headerBackground)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_brand_logo),
                contentDescription = stringResource(R.string.cd_app_logo),
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_full_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Text(
                    text = stringResource(R.string.app_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted
                )
            }
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                fontFamily = JetBrainsMonoFamily,
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent
            )
        }
        HorizontalDivider(color = colors.border, thickness = 1.dp)
    }
}
