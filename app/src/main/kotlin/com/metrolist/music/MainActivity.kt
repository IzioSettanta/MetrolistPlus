package com.metrolist.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.constants.*
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.playback.MusicService
import com.metrolist.music.playback.MusicService.MusicBinder
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.*
import com.metrolist.music.ui.component.shimmer.ShimmerTheme
import com.metrolist.music.ui.player.BottomSheetPlayer
import com.metrolist.music.ui.screens.Screens
import com.metrolist.music.ui.screens.navigationBuilder
import com.metrolist.music.ui.screens.settings.DarkMode
import com.metrolist.music.ui.screens.settings.NavigationTab
import com.metrolist.music.ui.theme.ColorSaver
import com.metrolist.music.ui.theme.DefaultThemeColor
import com.metrolist.music.ui.theme.MetrolistTheme
import com.metrolist.music.ui.theme.extractThemeColor
import com.metrolist.music.ui.utils.appBarScrollBehavior
import com.metrolist.music.ui.utils.resetHeightOffset
import com.metrolist.music.utils.*
import com.metrolist.music.viewmodels.HomeViewModel
import com.valentinilk.shimmer.LocalShimmerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import kotlin.time.Duration.Companion.days

@Suppress("DEPRECATION", "ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        private const val ACTION_SEARCH = "com.metrolist.music.action.SEARCH"
        private const val ACTION_LIBRARY = "com.metrolist.music.action.LIBRARY"
    }

    @Inject lateinit var database: MusicDatabase
    @Inject lateinit var downloadUtil: DownloadUtil
    @Inject lateinit var syncUtils: SyncUtils

    private lateinit var navController: NavHostController
    private var pendingIntent: Intent? = null
    private var latestVersionName by mutableStateOf(BuildConfig.VERSION_NAME)
    private var playerConnection by mutableStateOf<PlayerConnection?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service is MusicBinder) {
                playerConnection = PlayerConnection(this@MainActivity, service, database, lifecycleScope)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playerConnection?.dispose()
            playerConnection = null
        }
    }

    /**
     * Gestisce l'audio ducking abbassando il volume del player.
     * @param enabled Se true, abbassa il volume al 20%, se false lo riporta al 100%.
     */
    private fun setVolumeDucking(enabled: Boolean) {
        playerConnection?.service?.player?.let { player ->
            player.volume = if (enabled) 0.2f else 1.0f
        }
    }

    /**
     * Esempio di funzione da chiamare quando si avvia la ricerca vocale.
     */
    fun onVoiceSearchStarted() {
        setVolumeDucking(true)
    }

    /**
     * Esempio di funzione da chiamare quando la ricerca vocale termina o riceve un risultato.
     */
    fun onVoiceSearchFinished() {
        setVolumeDucking(false)
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1000)
            }
        }
        bindService(Intent(this, MusicService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        unbindService(serviceConnection)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (dataStore.get(StopMusicOnTaskClearKey, false) && playerConnection?.isPlaying?.value == true && isFinishing) {
            stopService(Intent(this, MusicService::class.java))
            playerConnection = null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::navController.isInitialized) {
            handleDeepLinkIntent(intent, navController)
        } else {
            pendingIntent = intent
        }
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = display ?: windowManager.defaultDisplay
            val currentMode = display.mode
            val highRefreshRateMode = display.supportedModes
                .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
                .maxByOrNull { it.refreshRate }
            highRefreshRateMode?.let { mode ->
                window.attributes = window.attributes.also { params -> params.preferredDisplayModeId = mode.modeId }
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val locale = dataStore[AppLanguageKey]?.takeUnless { it == SYSTEM_DEFAULT }?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
            setAppLocale(this, locale)
        }

        lifecycleScope.launch {
            dataStore.data.map { it[DisableScreenshotKey] ?: false }.distinctUntilChanged().collectLatest {
                if (it) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        setContent {
            MetrolistApp(
                latestVersionName = latestVersionName,
                onLatestVersionNameChange = { latestVersionName = it },
                playerConnection = playerConnection,
                database = database,
                downloadUtil = downloadUtil,
                syncUtils = syncUtils,
            )
        }
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MetrolistApp(
        latestVersionName: String,
        onLatestVersionNameChange: (String) -> Unit,
        playerConnection: PlayerConnection?,
        database: MusicDatabase,
        downloadUtil: DownloadUtil,
        syncUtils: SyncUtils,
    ) {
        val checkForUpdates by rememberPreference(CheckForUpdatesKey, defaultValue = true)

        LaunchedEffect(checkForUpdates) {
            if (checkForUpdates) {
                withContext(Dispatchers.IO) {
                    if (System.currentTimeMillis() - Updater.lastCheckTime > 1.days.inWholeMilliseconds) {
                        val updatesEnabled = dataStore.get(CheckForUpdatesKey, true)
                        val notifEnabled = dataStore.get(UpdateNotificationsEnabledKey, true)
                        if (!updatesEnabled) return@withContext
                        Updater.getLatestVersionName().onSuccess {
                            onLatestVersionNameChange(it)
                            if (it != BuildConfig.VERSION_NAME && notifEnabled) {
                                val downloadUrl = Updater.getLatestDownloadUrl()
                                val intent = Intent(Intent.ACTION_VIEW, downloadUrl.toUri())
                                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                                val pending = PendingIntent.getActivity(this@MainActivity, 1001, intent, flags)
                                val notif = NotificationCompat.Builder(this@MainActivity, "updates")
                                    .setSmallIcon(R.drawable.update).setContentTitle(getString(R.string.update_available_title))
                                    .setContentText(it).setContentIntent(pending).setAutoCancel(true).build()

                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                    NotificationManagerCompat.from(this@MainActivity).notify(1001, notif)
                                }
                            }
                        }
                    }
                }
            } else {
                onLatestVersionNameChange(BuildConfig.VERSION_NAME)
            }
        }

        val enableDynamicTheme by rememberPreference(DynamicThemeKey, defaultValue = true)
        val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
        val isSystemInDarkTheme = isSystemInDarkTheme()
        val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) { if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON }

        LaunchedEffect(useDarkTheme) { setSystemBarAppearance(useDarkTheme) }

        val pureBlackEnabled by rememberPreference(PureBlackKey, defaultValue = false)
        val pureBlack = remember(pureBlackEnabled, useDarkTheme) { pureBlackEnabled && useDarkTheme }

        var themeColor by rememberSaveable(stateSaver = ColorSaver) { mutableStateOf(DefaultThemeColor) }

        LaunchedEffect(playerConnection, enableDynamicTheme) {
            val pc = playerConnection
            if (!enableDynamicTheme || pc == null) { themeColor = DefaultThemeColor; return@LaunchedEffect }
            pc.service.currentMediaMetadata.collectLatest { song ->
                if (song?.thumbnailUrl != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            val result = imageLoader.execute(ImageRequest.Builder(this@MainActivity).data(song.thumbnailUrl).allowHardware(false).build())
                            themeColor = result.image?.toBitmap()?.extractThemeColor() ?: DefaultThemeColor
                        } catch (e: Exception) { themeColor = DefaultThemeColor }
                    }
                } else { themeColor = DefaultThemeColor }
            }
        }

        MetrolistTheme(darkTheme = useDarkTheme, pureBlack = pureBlack, themeColor = themeColor) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface)) {
                val density = LocalDensity.current
                val configuration = LocalConfiguration.current
                val windowsInsets = WindowInsets.systemBars
                val bottomInset = with(density) { windowsInsets.getBottom(density).toDp() }

                val navController = rememberNavController()
                val homeViewModel: HomeViewModel = hiltViewModel()
                val accountImageUrl by homeViewModel.accountImageUrl.collectAsState()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val (_, setPreviousTab) = rememberSaveable { mutableStateOf("home") }

                val navigationItems = remember { Screens.MainScreens }
                val (slimNav) = rememberPreference(SlimNavBarKey, defaultValue = false)
                val (useNewMiniPlayerDesign) = rememberPreference(UseNewMiniPlayerDesignKey, defaultValue = true)
                val defaultOpenTab = remember { dataStore[DefaultOpenTabKey].toEnum(defaultValue = NavigationTab.HOME) }
                val tabOpenedFromShortcut = remember { when (intent?.action) { ACTION_SEARCH -> NavigationTab.LIBRARY; ACTION_LIBRARY -> NavigationTab.SEARCH; else -> null } }

                val topLevelScreens = remember { listOf(Screens.Home.route, Screens.Library.route, "settings") }
                val (_, onQueryChange) = rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }

                val currentRoute by remember { derivedStateOf { navBackStackEntry?.destination?.route } }
                val inSearchScreen by remember { derivedStateOf { currentRoute?.startsWith("search/") == true } }
                val navigationItemRoutes = remember(navigationItems) { navigationItems.map { it.route }.toSet() }
                val shouldShowNavigationBar = remember(currentRoute, navigationItemRoutes) { currentRoute == null || navigationItemRoutes.contains(currentRoute) || inSearchScreen }

                val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
                val showRail = isLandscape && !inSearchScreen
                val navPadding = if (shouldShowNavigationBar && !showRail) { if (slimNav) SlimNavBarHeight else NavigationBarHeight } else 0.dp

                val navigationBarHeight by animateDpAsState(targetValue = if (shouldShowNavigationBar && !showRail) NavigationBarHeight else 0.dp, label = "navBarHeight")

                val playerBottomSheetState = rememberBottomSheetState(
                    dismissedBound = 0.dp,
                    collapsedBound = MiniPlayerHeight + bottomInset + (if (!showRail && shouldShowNavigationBar) navPadding else 0.dp) + (if (useNewMiniPlayerDesign) MiniPlayerBottomSpacing else 0.dp),
                    expandedBound = maxHeight,
                )

                val currentSong by playerConnection?.service?.currentMediaMetadata?.collectAsState(null) ?: remember { mutableStateOf(null) }
                LaunchedEffect(currentSong) {
                    if (currentSong != null && playerBottomSheetState.isDismissed) {
                        playerBottomSheetState.collapseSoft()
                    }
                }

                val playerAwareWindowInsets = remember(bottomInset, shouldShowNavigationBar, playerBottomSheetState.isDismissed, showRail) {
                    var bottom = bottomInset
                    if (shouldShowNavigationBar && !showRail) bottom += navPadding
                    if (!playerBottomSheetState.isDismissed) bottom += MiniPlayerHeight
                    windowsInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top).add(WindowInsets(top = AppBarHeight, bottom = bottom))
                }

                val topAppBarScrollBehavior = appBarScrollBehavior(canScroll = { !inSearchScreen && (playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed) })

                LaunchedEffect(navBackStackEntry) {
                    if (inSearchScreen) {
                        val q = withContext(Dispatchers.IO) {
                            val raw = navBackStackEntry?.arguments?.getString("query") ?: ""
                            if (raw.contains("%")) raw else URLDecoder.decode(raw, "UTF-8")
                        }
                        onQueryChange(TextFieldValue(q, TextRange(q.length)))
                    } else if (navigationItems.fastAny { it.route == currentRoute }) {
                        onQueryChange(TextFieldValue())
                    }
                    topAppBarScrollBehavior.state.resetHeightOffset()
                    currentRoute?.let { setPreviousTab(it) }
                }

                var shouldShowTopBar by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(navBackStackEntry) { shouldShowTopBar = currentRoute in topLevelScreens && currentRoute != "settings" }

                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(Unit) {
                    if (pendingIntent != null) { handleDeepLinkIntent(pendingIntent!!, navController); pendingIntent = null }
                    else handleDeepLinkIntent(intent, navController)
                }

                DisposableEffect(Unit) {
                    val listener = Consumer<Intent> { handleDeepLinkIntent(it, navController) }
                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }

                val currentTitleRes = remember(navBackStackEntry) {
                    when (currentRoute) {
                        Screens.Home.route -> R.string.home
                        Screens.Search.route -> R.string.search
                        Screens.Library.route -> R.string.filter_library
                        else -> null
                    }
                }

                var showAccountDialog by remember { mutableStateOf(false) }
                val baseBg = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer

                CompositionLocalProvider(
                    LocalDatabase provides database,
                    LocalContentColor provides if (pureBlack) Color.White else contentColorFor(MaterialTheme.colorScheme.surface),
                    LocalPlayerConnection provides playerConnection,
                    LocalPlayerAwareWindowInsets provides playerAwareWindowInsets,
                    LocalDownloadUtil provides downloadUtil,
                    LocalShimmerTheme provides ShimmerTheme,
                    LocalSyncUtils provides syncUtils,
                ) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            AnimatedVisibility(visible = shouldShowTopBar, enter = slideInHorizontally { -it / 4 } + fadeIn(), exit = slideOutHorizontally { -it / 4 } + fadeOut()) {
                                TopAppBar(
                                    title = { Text(text = currentTitleRes?.let { stringResource(it) } ?: "", style = MaterialTheme.typography.titleLarge) },
                                    actions = {
                                        IconButton(onClick = { navController.navigate("history") }) { Icon(painterResource(R.drawable.history), null) }
                                        IconButton(onClick = { navController.navigate("stats") }) { Icon(painterResource(R.drawable.stats), null) }
                                        IconButton(onClick = { showAccountDialog = true }) {
                                            BadgedBox(badge = { if (latestVersionName != BuildConfig.VERSION_NAME) Badge() }) {
                                                if (accountImageUrl != null) {
                                                    AsyncImage(model = accountImageUrl, contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape))
                                                } else { Icon(painterResource(R.drawable.account), null, Modifier.size(24.dp)) }
                                            }
                                        }
                                    },
                                    scrollBehavior = topAppBarScrollBehavior,
                                    colors = TopAppBarDefaults.topAppBarColors(containerColor = baseBg, scrolledContainerColor = baseBg)
                                )
                            }
                        },
                        bottomBar = {
                            if (currentRoute != "wrapped") {
                                Box {
                                    BottomSheetPlayer(state = playerBottomSheetState, navController = navController, pureBlack = pureBlack)

                                    if (!showRail) {
                                        AppNavigationBar(
                                            navigationItems = navigationItems,
                                            currentRoute = currentRoute,
                                            onItemClick = { screen, isSelected ->
                                                if (playerBottomSheetState.isExpanded) playerBottomSheetState.collapseSoft()
                                                if (isSelected) navController.currentBackStackEntry?.savedStateHandle?.set("scrollToTop", true)
                                                else navController.navigate(screen.route) { popUpTo(navController.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }
                                            },
                                            pureBlack = pureBlack,
                                            slimNav = slimNav,
                                            modifier = Modifier.align(Alignment.BottomCenter).height(bottomInset + navPadding).graphicsLayer {
                                                val totalPx = (bottomInset + navPadding).toPx()
                                                translationY = totalPx * playerBottomSheetState.progress
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize().nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            if (showRail && currentRoute != "wrapped") {
                                AppNavigationRail(navigationItems = navigationItems, currentRoute = currentRoute, onItemClick = { screen, isSelected ->
                                    if (playerBottomSheetState.isExpanded) playerBottomSheetState.collapseSoft()
                                    if (isSelected) navController.currentBackStackEntry?.savedStateHandle?.set("scrollToTop", true)
                                    else navController.navigate(screen.route) { popUpTo(navController.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }
                                }, pureBlack = pureBlack)
                            }
                            Box(Modifier.weight(1f)) {
                                NavHost(
                                    navController = navController,
                                    startDestination = when (tabOpenedFromShortcut ?: defaultOpenTab) { NavigationTab.LIBRARY -> Screens.Library.route; else -> Screens.Home.route },
                                    modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                                ) {
                                    navigationBuilder(navController, topAppBarScrollBehavior, latestVersionName, this@MainActivity, snackbarHostState)
                                }
                            }
                        }
                    }
                    if (showAccountDialog) { AccountSettingsDialog(navController, onDismiss = { showAccountDialog = false; homeViewModel.refresh() }, latestVersionName = latestVersionName) }
                }
            }
        }
    }

    private fun handleDeepLinkIntent(intent: Intent, navController: NavHostController) {
        val uri = intent.data ?: intent.extras?.getString(Intent.EXTRA_TEXT)?.toUri() ?: return
        intent.data = null
        val coroutineScope = lifecycle.coroutineScope
        when (val path = uri.pathSegments.firstOrNull()) {
            "playlist" -> uri.getQueryParameter("list")?.let { id -> if (id.startsWith("OLAK5uy_")) coroutineScope.launch(Dispatchers.IO) { YouTube.albumSongs(id).onSuccess { songs -> songs.firstOrNull()?.album?.id?.let { bId -> withContext(Dispatchers.Main) { navController.navigate("album/$bId") } } } } else navController.navigate("online_playlist/$id") }
            "browse" -> uri.lastPathSegment?.let { id -> navController.navigate("album/$id") }
            "channel", "c" -> uri.lastPathSegment?.let { id -> navController.navigate("artist/$id") }
            "search" -> uri.getQueryParameter("q")?.let { q -> navController.navigate("search/${URLEncoder.encode(q, "UTF-8")}") }
            else -> {
                val vId = if (path == "watch") uri.getQueryParameter("v") else if (uri.host == "youtu.be") uri.pathSegments.firstOrNull() else null
                val pId = uri.getQueryParameter("list")
                if (vId != null) coroutineScope.launch(Dispatchers.IO) { YouTube.queue(listOf(vId), pId).onSuccess { q -> withContext(Dispatchers.Main) { playerConnection?.playQueue(YouTubeQueue(WatchEndpoint(vId, pId), q.firstOrNull()?.toMediaMetadata())) } } }
                else if (pId != null) coroutineScope.launch(Dispatchers.IO) { YouTube.queue(null, pId).onSuccess { q -> withContext(Dispatchers.Main) { playerConnection?.playQueue(YouTubeQueue(WatchEndpoint(null, pId), q.firstOrNull()?.toMediaMetadata())) } } }
            }
        }
    }

    private fun setSystemBarAppearance(isDark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView.rootView).apply { isAppearanceLightStatusBars = !isDark; isAppearanceLightNavigationBars = !isDark }
    }
}

val LocalDatabase = staticCompositionLocalOf<MusicDatabase> { error("No database") }
val LocalPlayerConnection = staticCompositionLocalOf<PlayerConnection?> { error("No PlayerConnection") }
val LocalPlayerAwareWindowInsets = compositionLocalOf<WindowInsets> { error("No WindowInsets") }
val LocalDownloadUtil = staticCompositionLocalOf<DownloadUtil> { error("No DownloadUtil") }
val LocalSyncUtils = staticCompositionLocalOf<SyncUtils> { error("No SyncUtils") }
