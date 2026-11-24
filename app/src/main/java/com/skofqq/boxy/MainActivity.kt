package com.skofqq.boxy

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.data.NavExtra
import com.skofqq.boxy.data.Prefs
import com.skofqq.boxy.service.BoxStatusService
import com.skofqq.boxy.ui.apps.AppsScreen
import com.skofqq.boxy.ui.components.BackPill
import com.skofqq.boxy.ui.components.BarItem
import com.skofqq.boxy.ui.components.BottomBar
import com.skofqq.boxy.ui.components.GlassStyle
import com.skofqq.boxy.ui.components.LocalBackdrop
import com.skofqq.boxy.ui.components.LocalGlass
import com.skofqq.boxy.ui.components.SheetBlurState
import com.skofqq.boxy.ui.components.backdropSource
import com.skofqq.boxy.ui.home.HomeScreen
import com.skofqq.boxy.ui.logs.LogsScreen
import com.skofqq.boxy.ui.onboarding.OnboardingScreen
import com.skofqq.boxy.ui.panel.PanelKind
import com.skofqq.boxy.ui.panel.PanelScreen
import com.skofqq.boxy.ui.settings.SettingsScreen
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.BoxyTheme
import com.skofqq.boxy.ui.tools.ImportBus
import com.skofqq.boxy.ui.tools.ImportLinks
import com.skofqq.boxy.ui.tools.ImportSheet
import com.skofqq.boxy.ui.tools.ToolsScreen
import com.skofqq.boxy.util.withAppLocale
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

private enum class Tab { HOME, APPS, TOOLS, SETTINGS }

/** Full-screen pages opened on top of the tabs. */
private enum class Overlay { APPS, LOGS, PANEL, SUBSTORE }

class MainActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        BoxyApp.foreground = true
    }

    override fun onStop() {
        BoxyApp.foreground = false
        // The theme may have been changed in Settings since onCreate.
        if (android.os.Build.VERSION.SDK_INT >= 33) splashTheme((application as BoxyApp).prefs.themeMode)
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        ImportLinks.fromIntent(intent)?.let { ImportBus.request = it }
        tileLongPress(intent)
    }

    /**
     * Pages asked for by an intent: the panel (long press on the Quick Settings tile, setting on)
     * Settings (an app or module update notification) or the Update page (a core update notification).
     */
    private fun tileLongPress(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(TileLongPressActivity.EXTRA_OPEN_PANEL, false) == true) {
            intent.removeExtra(TileLongPressActivity.EXTRA_OPEN_PANEL)
            PanelBus.request = true
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) {
            intent.removeExtra(EXTRA_OPEN_SETTINGS)
            PanelBus.settings = true
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_UPDATE, false) == true) {
            intent.removeExtra(EXTRA_OPEN_UPDATE)
            com.skofqq.boxy.ui.tools.ToolsNav.request = com.skofqq.boxy.ui.tools.ROUTE_UPDATE
            com.skofqq.boxy.ui.tools.ToolsNav.showTools = true
        }
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "com.skofqq.boxy.extra.OPEN_SETTINGS"
        const val EXTRA_OPEN_UPDATE = "com.skofqq.boxy.extra.OPEN_UPDATE"
        /** How long the launch screen may hold the first frame so the logo animation can play out. */
        private const val SPLASH_MS = 400L
    }

    /**
     * Launch screen (Android 12+): the first frame waits for the animated logo to finish (at most [SPLASH_MS]),
     * then the logo grows and fades while the launch screen dissolves into the app.
     */
    @androidx.annotation.RequiresApi(31)
    private fun splashAnimation() {
        val start = android.os.SystemClock.uptimeMillis()
        val content = findViewById<android.view.View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val left = SPLASH_MS - (android.os.SystemClock.uptimeMillis() - start)
                if (left > 0) {
                    content.postDelayed({ content.invalidate() }, left)
                    return false
                }
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        splashScreen.setOnExitAnimationListener { splash ->
            val icon = splash.iconView
            val ease = android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
            icon?.animate()?.scaleX(1.35f)?.scaleY(1.35f)?.alpha(0f)?.setDuration(320)?.setInterpolator(ease)?.start()
            splash.animate().alpha(0f).setStartDelay(80).setDuration(300)
                .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction { splash.remove() }
                .start()
        }
    }

    /** The next launch screen follows Boxy's theme when it is forced light or dark (the system remembers it). */
    @androidx.annotation.RequiresApi(33)
    private fun splashTheme(mode: com.skofqq.boxy.data.ThemeMode) {
        splashScreen.setSplashScreenTheme(
            when (mode) {
                com.skofqq.boxy.data.ThemeMode.LIGHT -> R.style.Theme_Boxy_SplashLight
                com.skofqq.boxy.data.ThemeMode.DARK -> R.style.Theme_Boxy_SplashDark
                else -> android.content.res.Resources.ID_NULL
            },
        )
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 31 && savedInstanceState == null) splashAnimation()
        val prefs = (application as BoxyApp).prefs
        if (android.os.Build.VERSION.SDK_INT >= 33) splashTheme(prefs.themeMode)
        if (prefs.notifications) BoxStatusService.sync(this, true)
        val firstLaunch = savedInstanceState == null
        if (firstLaunch) ImportLinks.fromIntent(intent)?.let { ImportBus.request = it }
        if (firstLaunch) tileLongPress(intent)
        if (firstLaunch) lifecycleScope.launch { runCatching { com.skofqq.boxy.net.SubClient.syncModule(applicationContext) } }
        setContent {
            BoxyTheme(prefs.themeMode, prefs.trueBlack) {
                val dark = Boxy.colors.isDark
                DisposableEffect(dark) {
                    val style = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                    enableEdgeToEdge(style, style)
                    onDispose {}
                }
                val density = LocalDensity.current
                val scaled = Density(density.density * prefs.uiScale / 100f, density.fontScale)
                val glass = GlassStyle(
                    enabled = prefs.blurEffects && Build.VERSION.SDK_INT >= 31,
                    translucent = prefs.glassTranslucent,
                    blur = prefs.blurStrength,
                    lens = prefs.lensStrength,
                    sheetBlur = prefs.sheetBlur,
                    elements = prefs.glassElements,
                )
                CompositionLocalProvider(LocalDensity provides scaled, LocalGlass provides glass) {
                    // Boxy's own pop-up messages over every screen (BoxyToast).
                    com.skofqq.boxy.ui.components.BoxyToastHost {
                        if (!prefs.onboardingDone) {
                            OnboardingScreen(onFinish = { prefs.updateOnboardingDone(true) })
                        } else {
                            MainScreen(prefs, openPanel = firstLaunch && prefs.openPanelOnLaunch, checkUpdate = firstLaunch)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScreen(prefs: Prefs, openPanel: Boolean, checkUpdate: Boolean) {
    val context = LocalContext.current
    var updateOffer by remember { mutableStateOf<List<com.skofqq.boxy.net.AppRelease>?>(null) }
    LaunchedEffect(Unit) {
        if (checkUpdate) updateOffer = com.skofqq.boxy.net.Updates.launchOffer(context, prefs)
    }
    // What changed since the last version shown (only after an update; one request at most).
    var whatsNew by remember { mutableStateOf<List<com.skofqq.boxy.ui.settings.ChangeEntry>>(emptyList()) }
    LaunchedEffect(Unit) { whatsNew = com.skofqq.boxy.ui.settings.WhatsNew.pending(context, prefs) }
    var panelRequest by remember { mutableStateOf(false) }
    LaunchedEffect(PanelBus.request) {
        if (PanelBus.request) {
            panelRequest = true
            PanelBus.request = false
        }
    }
    // Four places: Home, Apps (which apps go through the proxy), Configuration (the Tools page stack) and the
    // app's Settings. The core's Panel, Logs and Sub-Store open over the tabs (from Home and Configuration).
    val tabs = listOf(Tab.HOME, Tab.APPS, Tab.TOOLS, Tab.SETTINGS)
    var current by rememberSaveable { mutableStateOf(Tab.HOME) }
    var subPage by rememberSaveable { mutableStateOf<Overlay?>(if (openPanel) Overlay.PANEL else null) }
    var lastSub by rememberSaveable { mutableStateOf(Overlay.APPS) }
    subPage?.let { lastSub = it }
    val pager = rememberPagerState(initialPage = tabs.indexOf(current).coerceAtLeast(0)) { tabs.size }
    val scope = rememberCoroutineScope()
    var lastBack by remember { mutableLongStateOf(0L) }
    LaunchedEffect(panelRequest) {
        if (panelRequest) {
            subPage = Overlay.PANEL
            panelRequest = false
        }
    }

    // Keep the same tab selected when the bar gains or loses a page.
    LaunchedEffect(tabs.size) {
        val index = tabs.indexOf(current).takeIf { it >= 0 } ?: 0
        if (pager.currentPage != index) pager.scrollToPage(index)
    }
    LaunchedEffect(pager.settledPage) { current = tabs.getOrElse(pager.settledPage) { Tab.HOME } }
    LaunchedEffect(com.skofqq.boxy.ui.tools.ToolsNav.showTools) {
        if (com.skofqq.boxy.ui.tools.ToolsNav.showTools) {
            com.skofqq.boxy.ui.tools.ToolsNav.showTools = false
            // A page opened over the tabs (the panel) closes, so the Tools page is seen.
            subPage = null
            val i = tabs.indexOf(Tab.TOOLS)
            // Not in this effect: resetting showTools restarts it, which cancelled the scroll on a cold start
            // (opened from the core update notification).
            if (i >= 0) scope.launch { pager.animateScrollToPage(i) }
        }
    }
    LaunchedEffect(com.skofqq.boxy.ui.components.AppsNav.open) {
        if (com.skofqq.boxy.ui.components.AppsNav.open) {
            com.skofqq.boxy.ui.components.AppsNav.open = false
            subPage = null
            pager.animateScrollToPage(tabs.indexOf(Tab.APPS))
        }
    }
    LaunchedEffect(PanelBus.settings) {
        if (PanelBus.settings) {
            PanelBus.settings = false
            subPage = null
            pager.scrollToPage(tabs.indexOf(Tab.SETTINGS).coerceAtLeast(0))
        }
    }
    BackHandler(enabled = subPage == null && pager.currentPage != 0) { scope.launch { pager.animateScrollToPage(0) } }
    val backAgain = stringResource(R.string.app_back_again)
    BackHandler(enabled = subPage == null && pager.currentPage == 0) {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) {
            (context as? Activity)?.finish()
        } else {
            lastBack = now
            com.skofqq.boxy.ui.components.BoxyToast.makeText(context, backAgain, com.skofqq.boxy.ui.components.BoxyToast.LENGTH_SHORT).show()
        }
    }

    val insets = WindowInsets.statusBars.asPaddingValues()
    val topInset = insets.calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val padding = PaddingValues(top = topInset, bottom = bottomInset + 100.dp)
    val glass = LocalGlass.current
    val sheetBlur by animateDpAsState(
        if (glass.enabled && glass.sheetBlur && SheetBlurState.open > 0) glass.blurRadius / 2 else 0.dp,
        tween(250),
        label = "sheetBlur",
    )
    val backdrop = com.skofqq.boxy.ui.components.rememberBackdrop()

    Box(Modifier.fillMaxSize().background(Boxy.colors.page).blur(sheetBlur)) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
          CompositionLocalProvider(com.skofqq.boxy.ui.components.LocalScreenBackdrop provides backdrop) {
            // Where you read logs or edit text a slanted scroll would flip the tab, so swiping is off there
            // (the bar still switches tabs): the Logs tab, the config editor, and pages opened on top.
            val onTab = tabs.getOrNull(pager.currentPage)
            val swipeLocked = subPage != null ||
                (onTab == Tab.TOOLS && com.skofqq.boxy.ui.tools.ToolsNav.editorOpen)
            HorizontalPager(
                pager,
                Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { tabs[it] },
                userScrollEnabled = !swipeLocked,
            ) { index ->
                // Only the shown tab takes focus: a field on a tab beside (the panel's search) getting it back after
                // a dialog with the keyboard closes would make the pager scroll to that tab.
                Box(Modifier.fillMaxSize().focusProperties { canFocus = index == pager.currentPage }) {
                when (tabs[index]) {
                    Tab.HOME -> HomeScreen(
                        padding,
                        prefs,
                        onOpenPanel = { subPage = Overlay.PANEL },
                        onOpenLogs = { subPage = Overlay.LOGS },
                        onOpenSubStore = { subPage = Overlay.SUBSTORE },
                        onOpenDns = {
                            com.skofqq.boxy.ui.tools.ToolsNav.request = com.skofqq.boxy.ui.tools.ROUTE_DNS
                            val i = tabs.indexOf(Tab.TOOLS)
                            if (i >= 0) scope.launch { pager.animateScrollToPage(i) }
                        },
                        active = subPage == null && pager.currentPage == index,
                    )
                    Tab.APPS -> AppsScreen(padding)
                    Tab.TOOLS -> ToolsScreen(padding, NavExtra.NONE, active = subPage == null && pager.currentPage == index) {
                        if (it == NavExtra.LOGS) subPage = Overlay.LOGS else scope.launch { pager.animateScrollToPage(tabs.indexOf(Tab.APPS)) }
                    }
                    Tab.SETTINGS -> SettingsScreen(padding, prefs, active = subPage == null && pager.currentPage == index)
                }
                }
            }
          }
        }
        val labels = mapOf(
            Tab.HOME to BarItem(stringResource(R.string.tab_home), BoxyIcons.Home, com.skofqq.boxy.ui.components.TapMotion.JUMP),
            Tab.APPS to BarItem(stringResource(R.string.tab_apps), BoxyIcons.Apps, com.skofqq.boxy.ui.components.TapMotion.SPIN),
            Tab.TOOLS to BarItem(stringResource(R.string.tab_config), BoxyIcons.Build, com.skofqq.boxy.ui.components.TapMotion.WIGGLE),
            Tab.SETTINGS to BarItem(stringResource(R.string.tab_settings), BoxyIcons.Settings, com.skofqq.boxy.ui.components.TapMotion.GEAR),
        )
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            BottomBar(
                items = tabs.map { labels.getValue(it) },
                selected = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, tabs.lastIndex.toFloat()),
                onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 16.dp),
            )
        }

        // Pages opened on top of the tabs: Apps / Logs from Tools, Panel and SubStore from Home.
        AnimatedVisibility(
            visible = subPage != null,
            enter = slideInHorizontally(tween(280)) { it },
            exit = slideOutHorizontally(tween(240)) { it },
        ) {
            BackHandler { subPage = null }
            val subPadding = PaddingValues(top = topInset, bottom = bottomInset + 24.dp)
            val header: @Composable () -> Unit = { BackPill { subPage = null } }
            val overlayBackdrop = com.skofqq.boxy.ui.components.rememberBackdrop()
            Box(Modifier.fillMaxSize().background(Boxy.colors.page).backdropSource(overlayBackdrop)) {
              CompositionLocalProvider(com.skofqq.boxy.ui.components.LocalScreenBackdrop provides overlayBackdrop) {
                when (lastSub) {
                    Overlay.LOGS -> LogsScreen(subPadding, header)
                    Overlay.APPS -> AppsScreen(subPadding, header)
                    // The core's panel over the tabs: its sections on its own floating toolbar at the bottom.
                    Overlay.PANEL -> PanelScreen(subPadding, header, PanelKind.CORE, asTab = true, active = subPage == Overlay.PANEL) { subPage = null }
                    Overlay.SUBSTORE -> PanelScreen(subPadding, header, PanelKind.SUBSTORE) { subPage = null }
                }
              }
            }
        }

        ImportBus.request?.let { r -> ImportSheet(r) { ImportBus.request = null } }

        updateOffer?.let { releases ->
            val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
            com.skofqq.boxy.ui.settings.AppUpdateSheet(version, releases) {
                prefs.declinedUpdate = releases.firstOrNull { !it.prerelease }?.version.orEmpty()
                updateOffer = null
            }
        }
        if (updateOffer == null && whatsNew.isNotEmpty()) com.skofqq.boxy.ui.settings.WhatsNewSheet(whatsNew) { whatsNew = emptyList() }

        // Opaque system bars: a solid strip instead of content showing through.
        if (prefs.opaqueStatusBar) {
            Box(Modifier.fillMaxWidth().height(topInset).background(Boxy.colors.page).align(Alignment.TopCenter))
        }
        if (prefs.opaqueNavBar) {
            Box(Modifier.fillMaxWidth().height(bottomInset).background(Boxy.colors.page).align(Alignment.BottomCenter))
        }
    }
}

/** Requests from outside the UI: the panel (Quick Settings tile long press), Settings (update notification). */
object PanelBus {
    var request by mutableStateOf(false)
    var settings by mutableStateOf(false)
}
