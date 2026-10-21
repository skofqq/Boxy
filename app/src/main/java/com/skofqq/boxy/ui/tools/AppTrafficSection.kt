package com.skofqq.boxy.ui.tools

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.skofqq.boxy.R
import com.skofqq.boxy.data.AppTraffic
import com.skofqq.boxy.data.AppUsage
import com.skofqq.boxy.ui.components.GlassChip
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import com.skofqq.boxy.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val SHOWN = 20

/** Traffic per app for today / 7 / 30 days: through a proxy and direct, biggest first. */
@Composable
fun AppTrafficSection(reload: Int) {
    val context = LocalContext.current
    var days by rememberSaveable { mutableStateOf(1) }
    var list by remember { mutableStateOf<List<AppUsage>?>(null) }
    var system by remember { mutableStateOf(true) }
    var all by remember { mutableStateOf(false) }
    LaunchedEffect(days, reload) {
        system = AppTraffic.ensureUsageAccess(context)
        while (true) {
            AppTraffic.sample(context)
            list = AppTraffic.usage(context, days, system)
            delay(10_000)
        }
    }

    SectionCard(stringResource(R.string.traffic_apps_title), stringResource(R.string.traffic_apps_sub)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(1 to R.string.traffic_range_today, 7 to R.string.traffic_range_week, 30 to R.string.traffic_range_month).forEach { (d, label) ->
                GlassChip(stringResource(label), days == d) { days = d }
            }
        }
        val hint = when {
            !system -> R.string.traffic_apps_hint_system
            AppTraffic.coreKnowsApps == false -> R.string.traffic_apps_hint_process
            else -> null
        }
        if (hint != null) {
            Text(
                stringResource(hint),
                Modifier.padding(horizontal = 18.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Tints.amber.bg).padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Boxy.colors.text,
            )
        }
        val l = list
        when {
            l == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            l.isEmpty() -> Text(stringResource(R.string.traffic_empty), Modifier.padding(horizontal = 18.dp, vertical = 10.dp), color = Boxy.colors.text2)
            else -> {
                val max = l.first().total.coerceAtLeast(1)
                (if (all) l else l.take(SHOWN)).forEach { AppRow(it, max) }
                if (!all && l.size > SHOWN) {
                    Text(
                        stringResource(R.string.traffic_apps_more, l.size),
                        Modifier.fillMaxWidth().clickable { all = true }.padding(horizontal = 18.dp, vertical = 12.dp),
                        color = Boxy.colors.accent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun AppRow(u: AppUsage, max: Long) {
    val context = LocalContext.current
    val label = remember(u.pkg) { appLabel(context, u.pkg) }
    val icon by produceState<ImageBitmap?>(null, u.pkg) { value = withContext(Dispatchers.IO) { appIcon(context, u.pkg) } }
    val proxyColor = Boxy.colors.accent
    val directColor = Tints.gray.fg
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Boxy.colors.surface2), contentAlignment = Alignment.Center) {
            val img = icon
            if (img != null) {
                Image(img, null, Modifier.size(36.dp))
            } else {
                Icon(if (u.pkg == AppTraffic.CORE) BoxyIcons.Core else BoxyIcons.Android, null, Modifier.size(20.dp), tint = Boxy.colors.text2)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Format.bytes(context, u.total), style = MaterialTheme.typography.labelLarge, color = Boxy.colors.text)
            }
            Spacer(Modifier.height(4.dp))
            // Bar scaled to the biggest app: proxied part in the accent colour, direct part grey.
            Row(Modifier.fillMaxWidth(u.total.toFloat() / max).height(5.dp).clip(RoundedCornerShape(50))) {
                if (u.proxy > 0) Box(Modifier.weight(u.proxy.toFloat()).fillMaxHeight().background(proxyColor))
                if (u.direct > 0) Box(Modifier.weight(u.direct.toFloat()).fillMaxHeight().background(directColor.copy(alpha = 0.55f)))
            }
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(
                    u.proxy.takeIf { it > 0 }?.let { stringResource(R.string.traffic_apps_proxy, Format.bytes(context, it)) },
                    u.direct.takeIf { it > 0 }?.let { stringResource(R.string.traffic_apps_direct, Format.bytes(context, it)) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Boxy.colors.text2,
                maxLines = 1,
            )
        }
    }
}

private fun appLabel(context: Context, pkg: String): String = when {
    pkg == AppTraffic.CORE -> context.getString(R.string.traffic_apps_core)
    pkg == "@uid-4" -> context.getString(R.string.traffic_apps_removed)
    pkg == "@uid-5" -> context.getString(R.string.traffic_apps_tethering)
    pkg.startsWith("@uid") -> "UID " + pkg.removePrefix("@uid")
    else -> runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

private fun appIcon(context: Context, pkg: String): ImageBitmap? {
    if (pkg.startsWith("@")) return null
    return runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
}
