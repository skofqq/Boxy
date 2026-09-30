package com.skofqq.boxy

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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

private enum class Tab { HOME, APPS, LOGS, TOOLS, SETTINGS }

/** Full-screen pages opened on top of the tabs. */
private enum class Overlay { APPS, LOGS, PANEL, SUBSTORE }

class MainActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        BoxyApp.foreground = true
    }

    override fun onStop() {
        BoxyApp.foreground = false
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        ImportLinks.fromIntent(intent)?.let { ImportBus.request = it }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = (application as BoxyApp).prefs
        if (prefs.notifications) BoxStatusService.sync(this, true)
        val firstLaunch = savedInstanceState == null
        if (firstLaunch) ImportLinks.fromIntent(intent)?.let { ImportBus.request = it }
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
                )
                CompositionLocalProvider(LocalDensity provides scaled, LocalGlass provides glass) {
                    if (!prefs.onboardingDone) {
                        OnboardingScreen(onFinish = { prefs.updateOnboardingDone(true) })
                    } else {
                        MainScreen(prefs, openPanel = firstLaunch && prefs.openPanelOnLaunch)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScreen(prefs: Prefs, openPanel: Boolean) {
    val context = LocalContext.current
    val tabs = buildList {
        add(Tab.HOME)
        when (prefs.navExtra) {
            NavExtra.APPS -> add(Tab.APPS)
            NavExtra.LOGS -> add(Tab.LOGS)
            NavExtra.NONE -> Unit
        }
        add(Tab.TOOLS)
        add(Tab.SETTINGS)
    }
    var current by rememberSaveable { mutableStateOf(Tab.HOME) }
    var subPage by rememberSaveable { mutableStateOf(if (openPanel) Overlay.PANEL else null) }
    var lastSub by rememberSaveable { mutableStateOf(Overlay.APPS) }
    subPage?.let { lastSub = it }
    val pager = rememberPagerState(initialPage = tabs.indexOf(current).coerceAtLeast(0)) { tabs.size }
    val scope = rememberCoroutineScope()
    var lastBack by remember { mutableLongStateOf(0L) }

    // Keep the same tab selected when the bar gains or loses a page.
    LaunchedEffect(tabs.size) {
        val index = tabs.indexOf(current).takeIf { it >= 0 } ?: 0
        if (pager.currentPage != index) pager.scrollToPage(index)
    }
    LaunchedEffect(pager.settledPage) { current = tabs.getOrElse(pager.settledPage) { Tab.HOME } }
    BackHandler(enabled = subPage == null && pager.currentPage != 0) { scope.launch { pager.animateScrollToPage(0) } }
    val backAgain = stringResource(R.string.app_back_again)
    BackHandler(enabled = subPage == null && pager.currentPage == 0) {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) {
            (context as? Activity)?.finish()
        } else {
            lastBack = now
            Toast.makeText(context, backAgain, Toast.LENGTH_SHORT).show()
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
    val backdrop = rememberGraphicsLayer()

    Box(Modifier.fillMaxSize().background(Boxy.colors.page).blur(sheetBlur)) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { tabs[it] }) { index ->
                when (tabs[index]) {
                    Tab.HOME -> HomeScreen(
                        padding,
                        prefs,
                        onOpenPanel = { subPage = Overlay.PANEL },
                        onOpenLogs = {
                            val i = tabs.indexOf(Tab.LOGS)
                            if (i >= 0) scope.launch { pager.animateScrollToPage(i) } else subPage = Overlay.LOGS
                        },
                        onOpenSubStore = { subPage = Overlay.SUBSTORE },
                        onOpenDns = {
                            com.skofqq.boxy.ui.tools.ToolsNav.request = com.skofqq.boxy.ui.tools.ROUTE_DNS
                            val i = tabs.indexOf(Tab.TOOLS)
                            if (i >= 0) scope.launch { pager.animateScrollToPage(i) }
                        },
                    )
                    Tab.APPS -> AppsScreen(padding)
                    Tab.LOGS -> LogsScreen(padding)
                    Tab.TOOLS -> ToolsScreen(padding, prefs.navExtra, active = subPage == null && pager.currentPage == index) {
                        subPage = if (it == NavExtra.LOGS) Overlay.LOGS else Overlay.APPS
                    }
                    Tab.SETTINGS -> SettingsScreen(padding, prefs, active = subPage == null && pager.currentPage == index)
                }
            }
        }
        val labels = mapOf(
            Tab.HOME to BarItem(stringResource(R.string.tab_home), BoxyIcons.Home),
            Tab.APPS to BarItem(stringResource(R.string.tab_apps), BoxyIcons.Apps),
            Tab.LOGS to BarItem(stringResource(R.string.tab_logs), BoxyIcons.Description),
            Tab.TOOLS to BarItem(stringResource(R.string.tab_tools), BoxyIcons.Build),
            Tab.SETTINGS to BarItem(stringResource(R.string.tab_settings), BoxyIcons.Settings),
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
            Box(Modifier.fillMaxSize().background(Boxy.colors.page)) {
                when (lastSub) {
                    Overlay.LOGS -> LogsScreen(subPadding, header)
                    Overlay.APPS -> AppsScreen(subPadding, header)
                    Overlay.PANEL -> PanelScreen(subPadding, header, PanelKind.CORE) { subPage = null }
                    Overlay.SUBSTORE -> PanelScreen(subPadding, header, PanelKind.SUBSTORE) { subPage = null }
                }
            }
        }

        ImportBus.request?.let { r -> ImportSheet(r) { ImportBus.request = null } }

        // Opaque system bars: a solid strip instead of content showing through.
        if (prefs.opaqueStatusBar) {
            Box(Modifier.fillMaxWidth().height(topInset).background(Boxy.colors.page).align(Alignment.TopCenter))
        }
        if (prefs.opaqueNavBar) {
            Box(Modifier.fillMaxWidth().height(bottomInset).background(Boxy.colors.page).align(Alignment.BottomCenter))
        }
    }
}
