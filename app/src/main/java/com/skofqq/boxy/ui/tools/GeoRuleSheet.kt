package com.skofqq.boxy.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skofqq.boxy.R
import com.skofqq.boxy.configs.GeoRuleSets
import com.skofqq.boxy.configs.GeoRules
import com.skofqq.boxy.data.GeoBase
import com.skofqq.boxy.root.CoreTools
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.GlassChip
import com.skofqq.boxy.ui.components.OptionRow
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import kotlinx.coroutines.launch

/**
 * Picks a GeoSite / GeoIP category from the local geo base (search or alphabetical scroll), then the group or
 * outbound of the open config, and hands back the edited config text with the rule inserted.
 */
@Composable
fun GeoRuleSheet(core: String, configText: () -> String, onInsert: (GeoRules.Edit) -> Unit, onError: (String) -> Unit, onDismiss: () -> Unit) {
    var all by remember { mutableStateOf<List<GeoBase.Category>?>(null) }
    var multi by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(GeoBase.Kind.SITE) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<GeoBase.Category?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val targets = remember { runCatching { GeoRules.targets(core, configText()) } }
    LaunchedEffect(core) {
        val (primary, extras) = CoreTools.geoSources()
        multi = extras.isNotEmpty()
        all = runCatching { GeoBase.load(core, primary, extras) }.getOrDefault(emptyList())
    }

    val chosen = category
    BoxySheet(
        stringResource(R.string.geo_rule_title),
        if (chosen == null) stringResource(R.string.geo_rule_sub) else stringResource(R.string.geo_rule_target_sub, chosen.name),
        onDismiss,
    ) {
        if (chosen == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip("GeoSite", kind == GeoBase.Kind.SITE) { kind = GeoBase.Kind.SITE }
                GlassChip("GeoIP", kind == GeoBase.Kind.IP) { kind = GeoBase.Kind.IP }
            }
            Spacer(Modifier.height(10.dp))
            BoxyTextField(
                query, { query = it.trim().lowercase() }, stringResource(R.string.geo_rule_search),
                Modifier.fillMaxWidth(),
                leading = { Icon(BoxyIcons.Search, null, tint = Boxy.colors.text2) },
            )
            Spacer(Modifier.height(10.dp))
            val l = all
            if (l == null) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                CategoryList(l.filter { it.kind == kind }, query, showSource = multi, kind = kind, primary = l.firstOrNull { it.primary }) { category = it }
            }
        } else {
            val list = targets.getOrNull()
            when {
                list == null -> Text(
                    stringResource(R.string.geo_rule_parse_failed, targets.exceptionOrNull()?.message.orEmpty()),
                    color = Boxy.colors.text2,
                )
                list.isEmpty() -> Text(stringResource(R.string.geo_rule_no_targets), color = Boxy.colors.text2)
                else -> {
                    var target by remember { mutableStateOf<GeoRules.Target?>(null) }
                    list.forEach { t ->
                        OptionRow(t.name, if (t.balancer) stringResource(R.string.geo_rule_balancer) else null, target == t) { target = t }
                    }
                    val t = target
                    if (t != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            GeoRules.preview(core, chosen, t),
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Boxy.colors.surface2).padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = Boxy.colors.text,
                        )
                        val hint = when {
                            core == "sing-box" -> stringResource(R.string.geo_rule_singbox_hint)
                            core == "clash" && !chosen.primary -> stringResource(R.string.geo_rule_mihomo_hint, chosen.source.title)
                            else -> null
                        }
                        if (hint != null) {
                            Text(hint, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                        }
                    }
                    if (busy) {
                        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    SheetButtons(
                        stringResource(R.string.geo_rule_insert),
                        {
                            val tt = target ?: return@SheetButtons
                            if (busy) return@SheetButtons
                            busy = true
                            scope.launch {
                                // What the rule needs on the device first: sing-box's compiled rule set,
                                // mihomo's merged GeoSite.dat with the extra source's prefixed names.
                                val ready = when {
                                    core == "sing-box" -> chosen.path != null &&
                                        GeoRuleSets.buildSingBox(chosen.kind, chosen.source, chosen.name, chosen.path) != null
                                    core == "clash" && !chosen.primary -> GeoRuleSets.mergeMihomo()
                                    else -> true
                                }
                                busy = false
                                if (!ready) {
                                    onError(context.getString(R.string.geo_rule_build_failed))
                                    return@launch
                                }
                                try {
                                    onInsert(GeoRules.insert(core, configText(), chosen, tt))
                                } catch (e: Exception) {
                                    onError(e.message.orEmpty())
                                }
                            }
                        },
                        stringResource(R.string.action_back),
                        { category = null },
                    )
                }
            }
        }
    }
}

/** Filtered categories with a letter rail: a tap on a letter scrolls to the first name starting with it. */
@Composable
private fun CategoryList(
    all: List<GeoBase.Category>,
    query: String,
    showSource: Boolean,
    kind: GeoBase.Kind,
    primary: GeoBase.Category?,
    onPick: (GeoBase.Category) -> Unit,
) {
    // The same name from two sources shows twice, each with its source.
    val sorted = remember(all) { all.sortedWith(compareBy({ it.name }, { !it.primary }, { it.source.title })) }
    val shown = remember(sorted, query) {
        if (query.isEmpty()) sorted
        else sorted.filter { query in it.name }.sortedWith(compareBy({ !it.name.startsWith(query) }, { it.name.length }, { it.name }, { !it.primary }))
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val letters = remember(shown, query) { if (query.isEmpty()) shown.mapNotNull { it.name.firstOrNull() }.distinct() else emptyList() }
    if (all.isEmpty()) {
        Text(stringResource(R.string.geo_rule_no_base), color = Boxy.colors.text2, modifier = Modifier.padding(vertical = 8.dp))
    }
    Row(Modifier.fillMaxWidth().height(380.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = state) {
            // A name typed by hand works too, for the primary source (a category the local base does not list).
            val p = primary
            if (p != null && query.isNotEmpty() && shown.none { it.name == query } && Regex("[a-z0-9@!_.-]+").matches(query)) {
                item {
                    CategoryRow(stringResource(R.string.geo_rule_use_typed, query), null) {
                        onPick(p.copy(name = query, kind = kind))
                    }
                }
            }
            items(shown, key = { "${it.source.id}/${it.name}" }) { c -> CategoryRow(c.name, if (showSource) c.source.title else null) { onPick(c) } }
        }
        if (letters.size > 1) {
            Column(Modifier.width(22.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                letters.forEach { ch ->
                    Text(
                        ch.uppercase(),
                        Modifier.clickable {
                            val i = shown.indexOfFirst { it.name.startsWith(ch) }
                            if (i >= 0) scope.launch { state.scrollToItem(i) }
                        },
                        fontSize = 10.sp,
                        color = Boxy.colors.accent,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(text: String, source: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = Boxy.colors.text)
        if (source != null) {
            Text(
                source,
                Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.surface2).padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Boxy.colors.text2,
            )
        }
    }
}
