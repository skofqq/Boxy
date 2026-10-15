package com.skofqq.boxy.ui.tools

import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.RootFiles
import com.skofqq.boxy.service.BoxControl
import com.skofqq.boxy.ui.components.HeaderAction
import com.skofqq.boxy.ui.components.PageSearchField
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import com.skofqq.boxy.ui.components.withTap1

/** One server of public-resolvers.md with the properties encoded in its sdns:// stamp. */
data class DnsServer(
    val name: String,
    val description: String,
    val protocol: String,
    val dnssec: Boolean,
    val noLog: Boolean,
    val noFilter: Boolean,
    val ipv6: Boolean,
)

private const val DIR = "${BoxModule.BOX_DIR}/dnscrypt"
private const val TOML = "$DIR/dnscrypt-proxy.toml"
private const val LIST = "$DIR/public-resolvers.md"
private val LIST_URLS = listOf(
    "https://download.dnscrypt.info/resolvers-list/v3/public-resolvers.md",
    "https://raw.githubusercontent.com/DNSCrypt/dnscrypt-resolvers/master/v3/public-resolvers.md",
)

object DnsCryptList {
    /** Parses the markdown list: "## name", a description, then one or more sdns:// stamps. */
    fun parse(md: String): List<DnsServer> = md.split(Regex("\n## ")).drop(1).mapNotNull { block ->
        val lines = block.lines()
        val name = lines.first().trim()
        val stamp = lines.firstOrNull { it.startsWith("sdns://") } ?: return@mapNotNull null
        val desc = lines.drop(1).takeWhile { !it.startsWith("sdns://") }.joinToString(" ") { it.trim() }.trim()
        decode(name, desc, stamp.trim())
    }

    private fun decode(name: String, desc: String, stamp: String): DnsServer? = runCatching {
        val b = Base64.decode(stamp.removePrefix("sdns://"), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val protocol = when (b[0].toInt() and 0xff) {
            0x01 -> "DNSCrypt"
            0x02 -> "DoH"
            0x03 -> "DoT"
            0x04 -> "DoQ"
            0x05 -> "ODoH"
            else -> return@runCatching null
        }
        val props = b[1].toInt() and 0xff
        val addrLen = b[9].toInt() and 0xff
        val addr = String(b, 10, addrLen)
        DnsServer(name, desc, protocol, props and 1 != 0, props and 2 != 0, props and 4 != 0, addr.startsWith("[") || name.contains("ipv6"))
    }.getOrNull()

    /** rtt of each server from the latest dnscrypt-proxy start ("[name] OK (DNSCrypt) - rtt: 66ms"). */
    fun latencies(log: String): Map<String, Int> =
        Regex("\\[([^\\]]+)] OK \\([^)]*\\) - rtt: (\\d+)ms").findAll(log).associate { it.groupValues[1] to it.groupValues[2].toInt() }

    /** server_names of the config, empty when it is commented out (automatic choice). */
    fun selected(toml: String): Set<String> {
        val line = Regex("^server_names\\s*=\\s*\\[(.*)]", RegexOption.MULTILINE).find(toml) ?: return emptySet()
        return Regex("['\"]([^'\"]+)['\"]").findAll(line.groupValues[1]).map { it.groupValues[1] }.toSet()
    }

    /** New config text with these server names (empty = automatic choice), turning on DoH when a DoH server is chosen. */
    fun withSelection(toml: String, names: List<String>, needDoh: Boolean): String {
        var s = toml.replace(Regex("^server_names\\s*=.*\\n", RegexOption.MULTILINE), "")
        if (names.isNotEmpty()) {
            val line = "server_names = [" + names.joinToString(", ") { "'$it'" } + "]\n"
            val at = Regex("^listen_addresses", RegexOption.MULTILINE).find(s)?.range?.first ?: 0
            s = s.substring(0, at) + line + "\n" + s.substring(at)
        }
        if (needDoh) s = s.replace(Regex("^doh_servers = false", RegexOption.MULTILINE), "doh_servers = true")
        return s
    }

    suspend fun download(): String? = withContext(Dispatchers.IO) {
        LIST_URLS.firstNotNullOfOrNull { url ->
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                try {
                    if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
                } finally {
                    c.disconnect()
                }
            }.getOrNull()?.takeIf { it.contains("sdns://") }
        }
    }
}

private enum class Sort { LATENCY, NAME }

/** Pick DNSCrypt servers: search, filters by protocol and properties, sorted by latency or name. */
@Composable
fun DnsCryptServersScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<DnsServer>?>(null) }
    var rtt by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var saved by remember { mutableStateOf<Set<String>>(emptySet()) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var protocol by rememberSaveable { mutableStateOf<String?>(null) }
    var noLog by rememberSaveable { mutableStateOf(false) }
    var noFilter by rememberSaveable { mutableStateOf(false) }
    var dnssec by rememberSaveable { mutableStateOf(false) }
    var hideIpv6 by rememberSaveable { mutableStateOf(true) }
    var onlyPicked by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(Sort.LATENCY) }

    LaunchedEffect(reload) {
        loading = true
        var md = RootFiles.read(LIST)
        if (md.isNullOrBlank() || reload > 0) {
            // Fresh list from dnscrypt.info; the proxy keeps its own signed copy up to date as well.
            DnsCryptList.download()?.let { fresh ->
                md = fresh
                RootFiles.write(LIST, fresh)
            }
        }
        servers = md?.let { DnsCryptList.parse(it) } ?: emptyList()
        rtt = DnsCryptList.latencies(RootFiles.tail("${BoxModule.RUN_DIR}/dnscrypt.log", 400_000))
        saved = DnsCryptList.selected(RootFiles.read(TOML).orEmpty())
        picked = saved
        loading = false
    }

    val list = servers
    val shown = remember(list, query, protocol, noLog, noFilter, dnssec, hideIpv6, onlyPicked, sort, rtt, picked) {
        list.orEmpty().asSequence()
            .filter { protocol == null || it.protocol == protocol }
            .filter { !noLog || it.noLog }
            .filter { !noFilter || it.noFilter }
            .filter { !dnssec || it.dnssec }
            .filter { !hideIpv6 || !it.ipv6 }
            .filter { !onlyPicked || it.name in picked }
            .filter { query.isBlank() || it.name.contains(query.trim(), true) || it.description.contains(query.trim(), true) }
            .sortedWith(
                if (sort == Sort.NAME) compareBy { it.name } else compareBy<DnsServer> { rtt[it.name] ?: Int.MAX_VALUE }.thenBy { it.name },
            )
            .toList()
    }

    fun apply(names: List<String>) {
        scope.launch {
            val toml = RootFiles.read(TOML)
            if (toml == null) {
                Toast.makeText(context, R.string.op_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val needDoh = list.orEmpty().any { it.name in names && it.protocol == "DoH" }
            if (RootFiles.write(TOML, DnsCryptList.withSelection(toml, names, needDoh))) {
                saved = names.toSet()
                Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
                if (BoxModule.dnscrypt().running) BoxControl.restart(context.applicationContext)
            } else {
                Toast.makeText(context, R.string.op_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        PinnedLazyPage(contentPadding, header = {
            SubPageHeader(
                stringResource(R.string.dnscrypt_servers),
                list?.let { stringResource(R.string.dnscrypt_servers_summary, shown.size, it.size) },
                onBack,
            ) {
                HeaderAction(BoxyIcons.Search, stringResource(R.string.action_search), tint = if (searching) Boxy.colors.accent else Boxy.colors.text) {
                    searching = !searching
                    if (!searching) query = ""
                }
                HeaderAction(BoxyIcons.Refresh, stringResource(R.string.dnscrypt_servers_refresh)) { reload++ }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceChip(
                    stringResource(if (sort == Sort.LATENCY) R.string.dnscrypt_sort_latency else R.string.dnscrypt_sort_name),
                    listOf(stringResource(R.string.dnscrypt_sort_latency) to { sort = Sort.LATENCY }, stringResource(R.string.dnscrypt_sort_name) to { sort = Sort.NAME }),
                )
                ChoiceChip(
                    protocol ?: stringResource(R.string.dnscrypt_all_protocols),
                    listOf<Pair<String, () -> Unit>>(stringResource(R.string.dnscrypt_all_protocols) to { protocol = null }) +
                        listOf("DNSCrypt", "DoH", "ODoH").map { p -> p to { protocol = p } },
                    active = protocol != null,
                )
                ToggleChip(stringResource(R.string.dnscrypt_f_nolog), noLog) { noLog = !noLog }
                ToggleChip(stringResource(R.string.dnscrypt_f_nofilter), noFilter) { noFilter = !noFilter }
                ToggleChip("DNSSEC", dnssec) { dnssec = !dnssec }
                ToggleChip(stringResource(R.string.dnscrypt_f_no_ipv6), hideIpv6) { hideIpv6 = !hideIpv6 }
                ToggleChip(stringResource(R.string.dnscrypt_f_picked, picked.size), onlyPicked) { onlyPicked = !onlyPicked }
            }
            if (searching) {
                PageSearchField(query, { query = it }, stringResource(R.string.dnscrypt_search_hint), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list == null || loading) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                return@PinnedLazyPage
            }
            if (list.isEmpty()) {
                item { Text(stringResource(R.string.dnscrypt_servers_empty), Modifier.padding(24.dp), color = Boxy.colors.text2) }
                return@PinnedLazyPage
            }
            item {
                Text(
                    stringResource(if (saved.isEmpty()) R.string.dnscrypt_mode_auto else R.string.dnscrypt_mode_manual, saved.size),
                    Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Boxy.colors.text2,
                )
            }
            items(shown, key = { it.name }) { s ->
                ServerRow(s, rtt[s.name], s.name in picked) {
                    picked = if (s.name in picked) picked - s.name else picked + s.name
                }
            }
            item { Spacer(Modifier.padding(bottom = 80.dp)) }
        }

        AnimatedVisibility(
            visible = list != null && picked != saved || saved.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(contentPadding).padding(bottom = 12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (saved.isNotEmpty() || picked.isNotEmpty()) {
                    PillButton(stringResource(R.string.dnscrypt_auto), Boxy.colors.card, Boxy.colors.text) {
                        picked = emptySet()
                        apply(emptyList())
                    }
                }
                if (picked != saved && picked.isNotEmpty()) {
                    PillButton(stringResource(R.string.dnscrypt_apply, picked.size), Boxy.colors.accent, Color.White) { apply(picked.sorted()) }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(s: DnsServer, ms: Int?, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp)).background(Boxy.colors.card)
            .clickable(onClick = onToggle).padding(start = 6.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, withTap1<Boolean> { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = Boxy.colors.accent))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ms != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$ms ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            ms < 80 -> Tints.green.fg
                            ms < 200 -> Tints.amber.fg
                            else -> Tints.red.fg
                        },
                    )
                }
            }
            Text(s.description, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(s.protocol, Tints.blue.fg)
                if (s.noLog) Tag(stringResource(R.string.dnscrypt_f_nolog), Tints.green.fg)
                if (!s.noFilter) Tag(stringResource(R.string.dnscrypt_tag_filter), Tints.amber.fg)
                if (s.dnssec) Tag("DNSSEC", Tints.teal.fg)
                if (s.ipv6) Tag("IPv6", Tints.purple.fg)
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
private fun ToggleChip(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(if (active) Boxy.colors.accent.copy(alpha = 0.15f) else Boxy.colors.card)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (active) Boxy.colors.accent else Boxy.colors.text,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
    )
}

@Composable
private fun ChoiceChip(text: String, items: List<Pair<String, () -> Unit>>, active: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(if (active) Boxy.colors.accent.copy(alpha = 0.15f) else Boxy.colors.card)
                .clickable { open = true }.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = if (active) Boxy.colors.accent else Boxy.colors.text, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            Icon(BoxyIcons.ExpandMore, null, Modifier.padding(start = 2.dp).size(20.dp), tint = if (active) Boxy.colors.accent else Boxy.colors.text2)
        }
        DropdownMenu(open, { open = false }, containerColor = Boxy.colors.card) {
            items.forEach { (label, action) -> DropdownMenuItem({ Text(label) }, { open = false; action() }) }
        }
    }
}

@Composable
private fun PillButton(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        color = fg,
        fontWeight = FontWeight.SemiBold,
    )
}
