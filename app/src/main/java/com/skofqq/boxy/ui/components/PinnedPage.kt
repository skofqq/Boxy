package com.skofqq.boxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.ui.theme.Boxy

/**
 * Page with a header that stays in place while the list below it scrolls
 * (title, back button and action buttons are always reachable). The list runs under the header,
 * which is liquid glass when that is on (otherwise the page colour, as before).
 */
@Composable
fun PinnedLazyPage(
    contentPadding: PaddingValues,
    header: @Composable ColumnScope.() -> Unit,
    state: LazyListState = rememberLazyListState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    val backdrop = rememberBackdrop()
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().backdropSource(backdrop),
            state = state,
            contentPadding = PaddingValues(top = headerHeight + 4.dp, bottom = contentPadding.calculateBottomPadding()),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            GlassSurface(
                RectangleShape,
                Boxy.colors.page,
                0.dp,
                Modifier.fillMaxWidth().onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                element = true,
                lens = false,
                edge = false,
            ) {
                Column(Modifier.fillMaxWidth().padding(top = contentPadding.calculateTopPadding()).padding(bottom = 4.dp), content = header)
            }
        }
    }
}
