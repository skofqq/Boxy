package com.skofqq.boxy.ui.proxies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
    if (core.checked && api == null) return NoCore(bottom)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var rules by remember { mutableStateOf<List<CoreRule>?>(null) }
    var providers by remember { mutableStateOf<List<RuleProvider>>(emptyList()) }
    var providersTab by rememberSaveable { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val updating = remember { mutableStateMapOf<String, Boolean>() }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(api, reload) {
        if (api == null) return@LaunchedEffect
        rules = CoreApi.rules(api).orEmpty()
        providers = CoreApi.ruleProviders(api)
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
            itemsIndexed(shown) { _, (i, r) -> RuleCard(i + 1, r, Modifier.padding(horizontal = 16.dp).fillMaxWidth()) }
        }
    }
}

@Composable
private fun RuleCard(number: Int, r: CoreRule, modifier: Modifier) {
    val small = MaterialTheme.typography.bodySmall
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(Boxy.colors.card).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(number.toString(), Modifier.width(36.dp), style = small, color = Boxy.colors.text2)
        Column(Modifier.weight(1f)) {
            Text(r.payload.ifBlank { r.type }, style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(r.type, style = small, color = Boxy.colors.text2, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Icon(BoxyIcons.ArrowCircle, null, Modifier.size(14.dp), tint = Boxy.colors.text2)
        Spacer(Modifier.width(4.dp))
        Text(r.proxy, Modifier.fillMaxWidth(0.4f), style = small, color = Boxy.colors.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
