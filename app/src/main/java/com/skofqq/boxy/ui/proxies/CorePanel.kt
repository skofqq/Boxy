package com.skofqq.boxy.ui.proxies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.toShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.net.ClashApi
import com.skofqq.boxy.net.Net
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.ui.components.BarItem
import com.skofqq.boxy.ui.components.BottomBar
import com.skofqq.boxy.ui.components.TapMotion
import com.skofqq.boxy.ui.components.LocalBackdrop
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.backdropSourceOf
import com.skofqq.boxy.ui.components.rememberBackdrop
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons

/** Height of the bottom bar plus its margin: the floating search of each tab sits right above it. */
private val BAR_ROOM = 12.dp + 68.dp

/**
 * Boxy's own panel, laid out like Zashboard: Proxies, Connections, Logs, Rules and Settings behind a floating
 * bottom bar.
 */
@Composable
fun CorePanel(reloadKey: Int) {
    // Xray has no clash-style API: its own panel works from the metrics endpoint.
    val state by produceState<com.skofqq.boxy.root.ServiceState?>(null, reloadKey) { value = runCatching { BoxModule.state() }.getOrNull() }
    val s = state ?: return
    if (BoxModule.unquote(s.core) == "xray") return XrayPanel(16.dp, reloadKey, s.running)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // The tabs are recorded on their own for the bar: the panel's backdrop holds the bar and would draw it into itself.
    val barBackdrop = rememberBackdrop()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSourceOf(barBackdrop)) {
            when (tab) {
                0 -> ProxiesScreen(PaddingValues(bottom = BAR_ROOM), header = {}, embedded = true, reloadKey = reloadKey)
                1 -> ConnectionsTab(BAR_ROOM, reloadKey)
                2 -> CoreLogsTab(BAR_ROOM, reloadKey)
                3 -> RulesTab(BAR_ROOM, reloadKey)
                else -> SettingsTab(BAR_ROOM, reloadKey)
            }
        }
        CompositionLocalProvider(LocalBackdrop provides barBackdrop) {
            BottomBar(
                listOf(
                    BarItem(stringResource(R.string.proxies_tab_groups), BoxyIcons.Language, TapMotion.SPIN),
                    BarItem(stringResource(R.string.panel_tab_connections), BoxyIcons.Link, TapMotion.SHAKE),
                    BarItem(stringResource(R.string.panel_tab_logs), BoxyIcons.Description, TapMotion.FLIP),
                    BarItem(stringResource(R.string.panel_tab_rules), BoxyIcons.FilterList, TapMotion.DROP),
                    BarItem(stringResource(R.string.tab_settings), BoxyIcons.Settings, TapMotion.GEAR),
                ),
                tab.toFloat(),
                { tab = it },
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )
        }
    }
}

/**
 * The running core's API for a tab; [checked] turns true once it has been looked up. [running] tells a stopped
 * service from a core without the API (xray, v2fly, hysteria); [mihomo] marks the core with the full API
 * (rule switches, GEO databases, run-time settings), which sing-box's clash_api only partly has.
 */
internal class CoreApiState(val checked: Boolean, val api: ClashApi?, val running: Boolean = false, val core: String = "clash") {
    val mihomo: Boolean get() = core == "clash"
}

@Composable
internal fun rememberCoreApi(reloadKey: Int): CoreApiState {
    val state by produceState(CoreApiState(false, null), reloadKey) {
        val s = runCatching { BoxModule.state() }.getOrNull()
        val running = s?.running == true
        value = CoreApiState(true, if (running) Net.clashApi(s?.core) else null, running, s?.core ?: "clash")
    }
    return state
}

/**
 * The frame of a panel tab: [bar] pinned on top, the list, and the search with the tab's [actions] floating
 * over the bottom of the list, right above the bottom bar.
 */
@Composable
internal fun PanelTabPage(
    bottom: Dp,
    bar: @Composable () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    hint: String,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    content: LazyListScope.() -> Unit,
) {
    // The list on its own for the floating search, as on the Proxies tab.
    val listBackdrop = rememberBackdrop()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSourceOf(listBackdrop)) {
            PinnedLazyPage(PaddingValues(bottom = bottom + 96.dp), header = { bar() }, verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
        CompositionLocalProvider(LocalBackdrop provides listBackdrop) {
            FloatingSearch(query, onQuery, hint, bottom = bottom, modifier = Modifier.align(Alignment.BottomCenter), actions = actions)
        }
    }
}

/** What a tab shows while the core is off or its API is out of reach. */
/** [running]: the service runs but its core has no Clash API (xray, v2fly, hysteria, or no external-controller). */
@Composable
internal fun NoCore(bottom: Dp, running: Boolean = false) {
    Column(
        Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(if (running) R.string.proxies_no_api_title else R.string.proxies_stopped_title), style = MaterialTheme.typography.titleLarge, color = Boxy.colors.text, textAlign = TextAlign.Center)
        Text(
            stringResource(if (running) R.string.proxies_no_api else R.string.panel_core_needed),
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Boxy.colors.text2,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Material 3 Expressive empty state for a panel tab: a slowly turning cookie that breathes, with the tab's symbol
 * on a smaller cookie, a title and a line of help. It springs in when it appears.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveEmpty(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    val loop = androidx.compose.animation.core.rememberInfiniteTransition(label = "empty")
    val turn by loop.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(24_000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "turn",
    )
    val breath by loop.animateFloat(
        0.94f, 1.04f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(2_400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val appear = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 300f)) }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(150.dp).graphicsLayer { scaleX = appear.value; scaleY = appear.value },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(140.dp).graphicsLayer { rotationZ = turn; scaleX = breath; scaleY = breath }
                    .clip(androidx.compose.material3.MaterialShapes.Cookie12Sided.toShape())
                    .background(Boxy.colors.accent.copy(alpha = 0.16f)),
            )
            Box(
                Modifier.size(72.dp).graphicsLayer { rotationZ = -turn / 3 }
                    .clip(androidx.compose.material3.MaterialShapes.Cookie4Sided.toShape())
                    .background(Boxy.colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(icon, null, Modifier.size(34.dp).graphicsLayer { rotationZ = turn / 3 }, tint = androidx.compose.ui.graphics.Color.White)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Boxy.colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2, textAlign = TextAlign.Center)
    }
}

/** A quiet line in the middle of an empty list. */
@Composable
internal fun EmptyLine(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(32.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = Boxy.colors.text2,
        textAlign = TextAlign.Center,
    )
}
