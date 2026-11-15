package com.skofqq.boxy.ui.proxies

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.GlassSurface
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons

/*
 * The panel as a tab of the app: its sections switch on a floating toolbar at the bottom (M3 Expressive), under the
 * thumb, instead of a row of pills on top. The toolbar also holds the search of the shown section (a magnifier that
 * opens into the field); the section's actions (⚡, pause, close all…) go to the panel's top row, so the toolbar keeps
 * its full width in every section. A section's FloatingSearch hands its query, hint and
 * actions to the toolbar instead of drawing a bar of its own.
 */

/** What a section puts on the toolbar. */
class PanelSearch(
    val query: String,
    val onQuery: (String) -> Unit,
    val hint: String,
    val actions: @Composable RowScope.() -> Unit,
)

@Stable
class PanelNav {
    val searches = mutableStateMapOf<Int, PanelSearch>()
    var visible by mutableStateOf(true)

    /** The section shown; its actions sit in the panel's top row (PanelScreen), not on the toolbar. */
    var current by mutableIntStateOf(0)

    /** Hides the toolbar while the list moves down, shows it as soon as it moves up. */
    val scroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < -4f) visible = false else if (available.y > 4f) visible = true
            return Offset.Zero
        }
    }
}

/** Set inside the panel's sections when the toolbar is there. */
internal val LocalPanelNav = compositionLocalOf<PanelNav?> { null }

/** The index of the section a composable belongs to. */
internal val LocalPanelPage = compositionLocalOf { -1 }

/** Room the toolbar takes over the bottom of a section's list. */
internal val PANEL_TOOLBAR_ROOM = 160.dp


/** A section's search goes to the toolbar: true when it did (the section then draws no bar of its own). */
@Composable
internal fun registerPanelSearch(query: String, onQuery: (String) -> Unit, hint: String, actions: @Composable RowScope.() -> Unit): Boolean {
    val nav = LocalPanelNav.current ?: return false
    val page = LocalPanelPage.current
    if (page < 0) return false
    SideEffect { nav.searches[page] = PanelSearch(query, onQuery, hint, actions) }
    DisposableEffect(nav, page) { onDispose { nav.searches.remove(page) } }
    return true
}

@Composable
internal fun PanelToolbar(
    nav: PanelNav,
    sections: List<Pair<String, ImageVector>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    bottom: Dp,
    modifier: Modifier = Modifier,
) {
    val search = nav.searches[selected]
    val page = Boxy.colors.page
    val density = LocalDensity.current
    val root = LocalView.current.rootView
    var below by remember { mutableIntStateOf(0) }
    val keyboard = with(density) { (WindowInsets.ime.getBottom(density) - below).coerceAtLeast(0).toDp() }
    AnimatedVisibility(
        // A search in progress keeps the bar out.
        nav.visible || !search?.query.isNullOrEmpty(),
        modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Box(
            Modifier
                // Taps on the bar's strip (between and around the pills) never reach the list under it.
                .pointerInput(Unit) { detectTapGestures { } }
                .onGloballyPositioned { c -> below = (root.height - (c.positionInWindow().y + c.size.height)).toInt().coerceAtLeast(0) }
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to page.copy(alpha = 0f), 0.35f to page.copy(alpha = 0.85f), 1f to page))
                .padding(top = 16.dp, bottom = maxOf(bottom, keyboard)),
        ) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // The section's search, stretched, and its actions (⚡, pause, close all…) beside it, above the bar.
                if (search != null) Row(
                    Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth().height(52.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val pill = RoundedCornerShape(50)
                    GlassSurface(
                        pill, Boxy.colors.card, 26.dp,
                        Modifier.weight(1f).fillMaxHeight().shadow(10.dp, pill, clip = false).border(1.dp, Boxy.colors.outline, pill),
                        element = true,
                    ) {
                        BoxyTextField(
                            search.query, search.onQuery, search.hint,
                            Modifier.fillMaxSize(),
                            leading = { Icon(BoxyIcons.Search, null, Modifier.size(20.dp), tint = Boxy.colors.text2) },
                            trailing = if (search.query.isEmpty()) null else ({
                                Icon(BoxyIcons.Close, stringResource(R.string.action_cancel), Modifier.size(22.dp).clickable { search.onQuery("") }, tint = Boxy.colors.text2)
                            }),
                            container = Color.Transparent,
                        )
                    }
                    search.actions(this)
                }
                // The sections as the app's own bottom bar: five equal items, icon and name, the chosen one on a pill.
                val motions = listOf(
                    com.skofqq.boxy.ui.components.TapMotion.SPIN, com.skofqq.boxy.ui.components.TapMotion.SHAKE,
                    com.skofqq.boxy.ui.components.TapMotion.FLIP, com.skofqq.boxy.ui.components.TapMotion.DROP,
                    com.skofqq.boxy.ui.components.TapMotion.GEAR,
                )
                com.skofqq.boxy.ui.components.BottomBar(
                    sections.mapIndexed { i, (name, icon) -> com.skofqq.boxy.ui.components.BarItem(name, icon, motions.getOrElse(i) { com.skofqq.boxy.ui.components.TapMotion.POP }) },
                    selected.toFloat(),
                    onSelect,
                    Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}

