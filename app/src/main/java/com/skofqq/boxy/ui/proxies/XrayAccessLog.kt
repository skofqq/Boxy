package com.skofqq.boxy.ui.proxies

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.net.XrayApi
import com.skofqq.boxy.ui.components.HeaderAction
import com.skofqq.boxy.ui.components.NoticeCard
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.launch

/** Xray's access log: which domain went through which inbound and outbound. Read when opened or refreshed. */
@Composable
internal fun XrayAccessLog(bottom: Dp, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var path by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<XrayApi.AccessEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        path = XrayApi.setup().accessLog
        checked = true
        entries = path?.let { XrayApi.accessLog(it) }
    }

    PinnedLazyPage(PaddingValues(bottom = bottom + 16.dp), header = {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderAction(BoxyIcons.ArrowBack, stringResource(R.string.action_back), onClick = onBack)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.xray_access_log), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text)
                if (path != null) HeaderAction(BoxyIcons.Refresh, stringResource(R.string.action_refresh_page)) { reload++ }
            }
            if (path != null) {
                // No autofocus: the log is read first, searched only on demand.
                com.skofqq.boxy.ui.components.BoxyTextField(query, { query = it }, stringResource(R.string.xray_access_search), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !checked -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            path == null -> item {
                SectionCard(null) {
                    NoticeCard(stringResource(R.string.xray_access_off_title), stringResource(R.string.xray_access_off_body))
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        SheetButtons(stringResource(if (busy) R.string.check_running else R.string.xray_access_enable), {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    val r = XrayApi.setAccessLog(true)
                                    busy = false
                                    if (r.isFailure) Toast.makeText(context, context.getString(R.string.xray_panel_failed, r.exceptionOrNull()?.message.orEmpty()), Toast.LENGTH_LONG).show()
                                    reload++
                                }
                            }
                        })
                    }
                    Spacer(Modifier.width(1.dp).padding(bottom = 12.dp))
                }
            }
            else -> {
                val list = entries.orEmpty().filter { query.isBlank() || query.lowercase() in "${it.target} ${it.route} ${it.from}".lowercase() }
                if (list.isEmpty()) item {
                    Text(stringResource(R.string.xray_access_empty), Modifier.fillMaxWidth().padding(24.dp), color = Boxy.colors.text2)
                }
                list.forEach { e ->
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.target, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = if (e.rejected) Tints.red.fg else Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(e.time, style = MaterialTheme.typography.labelSmall, color = Boxy.colors.text2)
                            }
                            Text(e.route.ifBlank { e.from }, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
