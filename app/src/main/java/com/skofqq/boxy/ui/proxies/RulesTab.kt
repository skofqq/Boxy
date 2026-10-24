package com.skofqq.boxy.ui.proxies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.net.CoreApi
import com.skofqq.boxy.net.Net
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import com.skofqq.boxy.net.CoreRule
import com.skofqq.boxy.net.RuleProvider
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Zashboard's Rules: the core's rules in order with the proxy each one sends to, and the rule sets to update. */
@Composable
internal fun RulesTab(bottom: Dp, reloadKey: Int) {
    val core = rememberCoreApi(reloadKey)
    val api = core.api
    if (core.checked && api == null) return NoCore(bottom, core.running)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var rules by remember { mutableStateOf<List<CoreRule>?>(null) }
    var providers by remember { mutableStateOf<List<RuleProvider>>(emptyList()) }
    var providersTab by rememberSaveable { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val updating = remember { mutableStateMapOf<String, Boolean>() }
    var reload by remember { mutableIntStateOf(0) }

    // Groups the rules send to, for the group → node chip and picking a node, as on the Proxies tab.
    var snapshot by remember { mutableStateOf<com.skofqq.boxy.net.ProxySnapshot?>(null) }
    val delays = remember { mutableStateMapOf<String, Int>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    var openGroup by remember { mutableStateOf<String?>(null) }
    val info = remember { mutableStateMapOf<Int, Boolean>() }

    LaunchedEffect(api, reload) {
        if (api == null) return@LaunchedEffect
        rules = CoreApi.rules(api).orEmpty()
        providers = CoreApi.ruleProviders(api)
        snapshot = Net.clashProxies(api)
    }

    fun toggle(r: CoreRule) {
        val a = api ?: return
        // Shown at once; the core's answer comes with the reload.
        rules = rules?.map { if (it.index == r.index) it.copy(disabled = !r.disabled) else it }
        scope.launch {
            if (!CoreApi.setRuleDisabled(a, r.index, !r.disabled)) {
                android.widget.Toast.makeText(context, R.string.op_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
            reload++
        }
    }

    fun testGroup(g: com.skofqq.boxy.net.ProxyGroup) {
        val a = api ?: return
        if (testing[g.name] == true) return
        testing[g.name] = true
        scope.launch {
            val result = Net.clashGroupDelay(a, g.name).orEmpty()
            g.all.forEach { n -> delays[n] = result[n] ?: 0 }
            testing[g.name] = false
            snapshot = Net.clashProxies(a)
        }
    }

    fun testNode(name: String) {
        val a = api ?: return
        if (testing[name] == true) return
        testing[name] = true
        scope.launch {
            delays[name] = Net.clashProxyDelay(a, name) ?: 0
            testing[name] = false
        }
    }

    val snap = snapshot
    val group = snap?.group(openGroup ?: "")
    if (snap != null && group != null) {
        GroupDialog(
            content = { m ->
                OpenGroup(
                    group, snap, delays, testing,
                    onClose = { openGroup = null },
                    onTestGroup = { testGroup(group) },
                    onTestNode = ::testNode,
                    onSelect = { name ->
                        val a = api
                        if (a != null) scope.launch { if (Net.clashSelect(a, group.name, name)) snapshot = Net.clashProxies(a) }
                    },
                    modifier = m,
                )
            },
            onClose = { openGroup = null },
        )
    }

    suspend fun update(p: RuleProvider) {
        val a = api ?: return
        updating[p.name] = true
        if (!CoreApi.updateRuleProvider(a, p.name)) {
            android.widget.Toast.makeText(context, context.getString(R.string.provider_update_failed, p.name), android.widget.Toast.LENGTH_SHORT).show()
        }
        updating[p.name] = false
    }

    val q = query.trim()
    PanelTabPage(
        bottom,
        bar = {
            PageBar(
                listOf(stringResource(R.string.panel_tab_rules) to rules?.size, stringResource(R.string.proxies_tab_providers) to providers.size),
                if (providersTab) 1 else 0,
                { providersTab = it == 1 },
            )
        },
        query = query,
        onQuery = { query = it },
        hint = stringResource(if (providersTab) R.string.proxies_search_providers else R.string.rules_search),
        actions = {
            if (providersTab) {
                val busy = updating.values.any { it }
                GlassAction(BoxyIcons.Refresh, stringResource(R.string.rules_update_all), busy = busy, enabled = !busy && providers.isNotEmpty()) {
                    scope.launch {
                        coroutineScope { providers.map { p -> async { update(p) } }.awaitAll() }
                        reload++
                    }
                }
            }
        },
    ) {
        if (providersTab) {
            val shown = providers.filter { q.isEmpty() || it.name.contains(q, true) }
            if (shown.isEmpty()) item { EmptyLine(stringResource(R.string.proxies_nothing_found)) }
            items(shown, key = { it.name }) { p ->
                RuleProviderCard(p, updating[p.name] == true, Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    scope.launch { update(p); reload++ }
                }
            }
        } else {
            val all = rules ?: return@PanelTabPage
            // Numbers stay those of the full list, so a rule found by search keeps its place in the config.
            val shown = all.withIndex().filter { (_, r) -> q.isEmpty() || r.payload.contains(q, true) || r.type.contains(q, true) || r.proxy.contains(q, true) }
            if (shown.isEmpty()) item { EmptyLine(stringResource(R.string.proxies_nothing_found)) }
            val byName = providers.associateBy { it.name }
            itemsIndexed(shown) { _, (i, r) ->
                val set = byName[r.payload].takeIf { r.type == "RuleSet" }
                RuleCard(
                    i + 1, r, set, snap, core.mihomo,
                    delays,
                    updating = set != null && updating[set.name] == true,
                    showInfo = info[r.index] == true,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    onToggle = { toggle(r) },
                    onInfo = { info[r.index] = info[r.index] != true },
                    onUpdate = { if (set != null) scope.launch { update(set); reload++ } },
                    onGroup = { openGroup = it },
                )
            }
        }
    }
}

/**
 * A rule as Zashboard shows it: number, type and payload, the size of its rule set with ⟳ for one that is
 * downloaded, ⓘ for how often it matched; a switch that turns it off until the core restarts, and where it sends
 * traffic: the group and the node it uses now with its delay (a tap opens the group to pick another node).
 */
@Composable
private fun RuleCard(
    number: Int,
    r: CoreRule,
    set: RuleProvider?,
    snap: com.skofqq.boxy.net.ProxySnapshot?,
    /** mihomo: rules can be switched off and count their matches; sing-box has neither. */
    full: Boolean,
    delays: Map<String, Int>,
    updating: Boolean,
    showInfo: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onInfo: () -> Unit,
    onUpdate: () -> Unit,
    onGroup: (String) -> Unit,
) {
    val small = MaterialTheme.typography.bodySmall
    val locale = LocalConfiguration.current.locales[0]
    val dim = if (r.disabled) 0.45f else 1f
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Boxy.colors.card).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(number.toString(), style = small, color = Boxy.colors.text2)
            Spacer(Modifier.width(10.dp))
            Text("${r.type}:", style = small, color = Boxy.colors.text2.copy(alpha = dim), maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Text(
                r.payload.ifBlank { r.type },
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyLarge,
                color = Boxy.colors.text.copy(alpha = dim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (set != null) {
                Spacer(Modifier.width(4.dp))
                Text("(${set.count})", style = small, color = Boxy.colors.text2)
            }
            Spacer(Modifier.weight(0.001f).width(4.dp))
            if (set != null && set.vehicle == "HTTP") {
                if (updating) {
                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                } else {
                    QuietIcon(BoxyIcons.Refresh, stringResource(R.string.provider_update), onUpdate)
                }
            }
            if (full) QuietIcon(BoxyIcons.Info, stringResource(R.string.rules_info), onInfo)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A compact switch: without the 48 dp touch frame the card stays as low as Zashboard's.
            if (full) CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
                androidx.compose.material3.Switch(
                    !r.disabled,
                    { onToggle() },
                    Modifier.graphicsLayer { scaleX = 0.8f; scaleY = 0.8f },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Boxy.colors.accent),
                )
            }
            if (full) Spacer(Modifier.width(6.dp))
            val group = snap?.group(r.proxy)
            Row(
                Modifier.weight(1f, fill = false).clip(RoundedCornerShape(50)).background(Boxy.colors.surface2)
                    .then(if (group != null) Modifier.clickable { onGroup(group.name) } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.proxy, style = MaterialTheme.typography.labelLarge, color = Boxy.colors.text.copy(alpha = dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (group != null) {
                    val leaf = snap.leaf(group.name)
                    if (leaf != null && leaf != group.name) {
                        Icon(BoxyIcons.ChevronRight, null, Modifier.padding(horizontal = 2.dp).size(16.dp), tint = Boxy.colors.text2)
                        Text(leaf, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, color = Boxy.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val delay = delays[leaf] ?: snap.nodes[leaf]?.delay
                        if (delay != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (delay > 0) delay.toString() else "×",
                                style = MaterialTheme.typography.labelLarge,
                                color = delayColor(delay).let { if (it == androidx.compose.ui.graphics.Color.Unspecified) Boxy.colors.text2 else it },
                            )
                        }
                    }
                }
            }
        }
        if (showInfo) {
            val last = r.hitAt?.let { " · " + stringResource(R.string.rules_last_hit, timeAgo(it, locale)) }.orEmpty()
            Text(
                stringResource(R.string.rules_hits, r.hits, r.misses) + last,
                style = small,
                color = Boxy.colors.text2,
            )
        }
    }
}

/** A small grey icon button for the rule's title line. */
@Composable
private fun QuietIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(17.dp), tint = Boxy.colors.text2)
    }
}

@Composable
private fun RuleProviderCard(p: RuleProvider, updating: Boolean, modifier: Modifier, onUpdate: () -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    val locale = LocalConfiguration.current.locales[0]
    Row(modifier.clip(RoundedCornerShape(18.dp)).background(Boxy.colors.card).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(p.behavior, p.vehicle, stringResource(R.string.rules_count, p.count)).filter { it.isNotBlank() }.joinToString(" · "),
                style = small,
                color = Boxy.colors.text2,
            )
            p.updatedAt?.let { Text(stringResource(R.string.provider_updated, timeAgo(it, locale)), style = small, color = Boxy.colors.text2) }
        }
        ProgressButton(BoxyIcons.Refresh, stringResource(R.string.provider_update), updating, onUpdate)
    }
}
