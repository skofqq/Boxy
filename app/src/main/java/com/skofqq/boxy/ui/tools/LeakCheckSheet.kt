package com.skofqq.boxy.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.net.GeoIp
import com.skofqq.boxy.net.LeakCheck
import com.skofqq.boxy.net.LeakResult
import com.skofqq.boxy.net.LeakScope
import com.skofqq.boxy.net.LeakVerdict
import com.skofqq.boxy.net.flagEmoji
import com.skofqq.boxy.ui.components.Badge
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.SheetGroup
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints

/** What websites see of this phone. Runs when the sheet opens and on "Check again", never in the background. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LeakCheckSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var run by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<LeakResult?>(null) }
    LaunchedEffect(run) {
        result = null
        result = LeakCheck.run(context)
    }
    BoxySheet(stringResource(R.string.leak_title), stringResource(R.string.leak_sub), onDismiss) {
        val r = result
        if (r == null) {
            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LoadingIndicator(Modifier.size(72.dp), color = Boxy.colors.accent)
                Text(stringResource(R.string.dnscheck_running), color = Boxy.colors.text2)
            }
            return@BoxySheet
        }
        Summary(r)
        r.hints.forEach { hint ->
            Text(
                stringResource(hint),
                Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Boxy.colors.text2,
            )
        }
        Spacer(Modifier.height(12.dp))
        val noAnswer = stringResource(R.string.dnscheck_no_answer)
        SheetGroup {
            LeakRow("IPv4", listOfNotNull(r.ipv4), noAnswer, r.ipv4Verdict)
            RowDivider()
            LeakRow("IPv6", listOfNotNull(r.ipv6), stringResource(R.string.leak_no_ipv6), r.ipv6Verdict)
            RowDivider()
            LeakRow(stringResource(R.string.leak_dns), r.dns, noAnswer, r.dnsVerdict)
            RowDivider()
            LeakRow("WebRTC", r.webRtc, stringResource(R.string.leak_no_webrtc), r.webRtcVerdict)
            RowDivider()
            LeakRow(stringResource(R.string.leak_real), r.real, noAnswer, LeakVerdict.UNKNOWN)
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(18.dp)).background(Boxy.colors.card).clickable { run++ },
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.dnscheck_again), color = Boxy.colors.text, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Boxy.colors.outline)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Summary(r: LeakResult) {
    val verdict = r.overall
    val judged = r.scope == LeakScope.PROXIED
    val tint = when {
        judged && verdict == LeakVerdict.OK -> Tints.green
        judged && verdict == LeakVerdict.LEAK -> Tints.red
        else -> Tints.amber
    }
    val message = when {
        r.scope == LeakScope.SERVICE_OFF -> R.string.leak_service_off
        r.scope == LeakScope.BOXY_EXCLUDED -> R.string.leak_boxy_excluded
        verdict == LeakVerdict.OK -> R.string.leak_ok
        verdict == LeakVerdict.LEAK -> R.string.leak_found
        verdict == LeakVerdict.WARN -> R.string.leak_dns_home
        else -> R.string.leak_unknown
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(tint.bg).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(tint.fg.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(if (judged && verdict == LeakVerdict.OK) BoxyIcons.CheckCircle else BoxyIcons.Block, null, Modifier.size(24.dp), tint = tint.fg)
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Boxy.colors.text)
    }
}

@Composable
private fun LeakRow(label: String, addresses: List<GeoIp>, empty: String, verdict: LeakVerdict) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
            when (verdict) {
                LeakVerdict.OK -> Badge(stringResource(R.string.leak_state_ok), Tints.green)
                LeakVerdict.WARN -> Badge(stringResource(R.string.leak_state_warn), Tints.amber)
                LeakVerdict.LEAK -> Badge(stringResource(R.string.leak_state_leak), Tints.red)
                LeakVerdict.UNKNOWN -> Unit
            }
        }
        if (addresses.isEmpty()) {
            Text(empty, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text2)
        }
        addresses.forEach { g ->
            Text(g.ip, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text)
            val place = listOfNotNull(g.isp, g.country).joinToString(", ")
            if (place.isNotEmpty()) {
                Text(
                    "${flagEmoji(g.countryCode)} $place".trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Boxy.colors.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
