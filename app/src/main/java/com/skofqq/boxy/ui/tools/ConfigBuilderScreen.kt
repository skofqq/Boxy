package com.skofqq.boxy.ui.tools

import com.skofqq.boxy.configs.RoutingProfiles
import com.skofqq.boxy.ui.components.TonalButton
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.skofqq.boxy.configs.ConfigBuilder
import com.skofqq.boxy.configs.ConfigBuilder.Group
import com.skofqq.boxy.net.XrayApi
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.BoxyToast
import com.skofqq.boxy.ui.components.GlassChip
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SheetButton
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * "Create your own config" (Xray): a name, servers from a subscription URL or pasted share links, groups (name,
 * servers, how it picks), then a complete config made active. Routing is the routing profiles' job: they target
 * these groups by name.
 */
private fun fileName(rel: String) = rel.substringAfterLast('/')

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfigBuilderScreen(contentPadding: PaddingValues, onBack: () -> Unit, onRouting: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { ConfigBuilder.load(context) }
    var name by remember { mutableStateOf(saved?.name ?: "my-config") }
    var fromUrl by remember { mutableStateOf(saved?.links.isNullOrBlank()) }
    var url by remember { mutableStateOf(saved?.url.orEmpty()) }
    var links by remember { mutableStateOf(saved?.links.orEmpty()) }
    var groups by remember { mutableStateOf(saved?.groups?.ifEmpty { null } ?: listOf(Group("⚡️ Fastest"))) }
    var servers by remember { mutableStateOf<List<JSONObject>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    // Bumped after a create, so "Your configs" lists the new one.
    var madeKey by remember { mutableStateOf(0) }
    fun plan() = ConfigBuilder.Plan(name.trim(), if (fromUrl) url.trim() else "", if (fromUrl) "" else links, groups)
    fun loadServers() {
        if (loading) return
        loading = true
        scope.launch {
            val s = ConfigBuilder.servers(context.applicationContext, plan())
            loading = false
            servers = s.outbounds
            val msg = when (s.error) {
                "download" -> context.getString(R.string.core_setup_err_download)
                "empty" -> context.getString(R.string.cb_no_servers)
                else -> context.getString(R.string.cb_servers_loaded, s.outbounds.size) + if (s.skipped > 0) " · " + context.getString(R.string.core_setup_skipped, s.skipped) else ""
            }
            BoxyToast.makeText(context, msg, BoxyToast.LENGTH_LONG, if (s.error == null) BoxyToast.Kind.SUCCESS else BoxyToast.Kind.ERROR).show()
        }
    }
    val tags = servers.orEmpty().map { it.optString("tag") }
    val routing = remember { RoutingProfiles.load(context).let { s -> s.profiles.firstOrNull { it.id == s.active }?.name } }
    fun create() {
        if (creating || name.isBlank()) return
        creating = true
        scope.launch {
            val r = ConfigBuilder.create(context.applicationContext, plan())
            creating = false
            madeKey++
            val msg = when {
                r.ok -> context.getString(R.string.cb_created, r.path?.substringAfterLast('/').orEmpty(), r.servers)
                r.error == "download" -> context.getString(R.string.core_setup_err_download)
                r.error == "empty" -> context.getString(R.string.cb_no_servers)
                else -> context.getString(R.string.cs_failed, r.error?.lines()?.takeLast(2)?.joinToString(" "))
            }
            BoxyToast.makeText(context, msg, BoxyToast.LENGTH_LONG, if (r.ok) BoxyToast.Kind.SUCCESS else BoxyToast.Kind.ERROR).show()
        }
    }
    Box(Modifier.fillMaxSize()) {
    // Room at the bottom for the pinned "Create" button.
    PinnedLazyPage(PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 88.dp), header = {
        SubPageHeader(stringResource(R.string.cb_title), stringResource(R.string.cb_sub), onBack)
    }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1 · Servers
        item {
            val list = servers
            StepCard(1, stringResource(R.string.cb_servers), stringResource(R.string.cb_servers_sub), done = !list.isNullOrEmpty()) {
                Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(stringResource(R.string.cb_from_url), fromUrl) { fromUrl = true }
                    GlassChip(stringResource(R.string.cb_from_links), !fromUrl) { fromUrl = false }
                }
                Spacer(Modifier.height(12.dp))
                if (fromUrl) BoxyTextField(url, { url = it }, "https://…", Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                else BoxyTextField(links, { links = it }, "vless://… vmess://… trojan://… ss://… hy2://…", Modifier.fillMaxWidth().padding(horizontal = 18.dp).heightIn(min = 140.dp), singleLine = false)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    TonalButton(stringResource(if (loading) R.string.check_running else R.string.cb_load), BoxyIcons.Download, enabled = !loading) { loadServers() }
                    Spacer(Modifier.weight(1f))
                    if (!list.isNullOrEmpty()) Badge(stringResource(R.string.cb_servers_loaded, list.size), accent = true)
                }
                if (list.isNullOrEmpty()) {
                    Text(stringResource(R.string.cb_servers_empty), Modifier.padding(horizontal = 18.dp).padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                } else {
                    var all by remember { mutableStateOf(false) }
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (if (all) list else list.take(12)).forEach { o -> Badge(XrayApi.displayName(o.optString("tag"))) }
                        if (!all && list.size > 12) Text(
                            "+${list.size - 12}",
                            Modifier.clip(RoundedCornerShape(50)).clickable { all = true }.background(Boxy.colors.accent.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium, color = Boxy.colors.accent,
                        )
                    }
                }
            }
        }
        // 2 · Groups
        item {
            StepCard(2, stringResource(R.string.cb_groups), stringResource(R.string.cb_groups_sub), done = true) {
                groups.forEachIndexed { i, g ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = i }.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Tints.purple.bg), contentAlignment = Alignment.Center) {
                            val url = com.skofqq.boxy.ui.proxies.GroupIcons.icon(g.name)
                            if (url != null) com.skofqq.boxy.ui.proxies.GroupIconImage(url, 32.dp)
                            else Icon(BoxyIcons.Bolt, null, Modifier.size(22.dp), tint = Tints.purple.fg)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(g.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (i == 0) {
                                    Spacer(Modifier.width(8.dp))
                                    Badge(stringResource(R.string.cb_default), accent = true)
                                }
                            }
                            Text(
                                (if (g.members.isEmpty()) stringResource(R.string.cb_all_servers) else g.members.joinToString(", ") { XrayApi.displayName(it) }) + " · " + strategyName(g.strategy),
                                style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(BoxyIcons.Edit, stringResource(R.string.rt_rename), Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable { editing = i }.padding(10.dp), tint = Boxy.colors.text2)
                    }
                }
                TonalButton(stringResource(R.string.cb_add_group), BoxyIcons.Add, Modifier.padding(horizontal = 18.dp).padding(top = 8.dp)) {
                    groups = groups + Group(context.getString(R.string.cb_new_group, groups.size + 1))
                    editing = groups.size - 1
                }
            }
        }
        // 3 · Routing: the active routing profile, edited in Routing profiles.
        item {
            StepCard(3, stringResource(R.string.rt_title), stringResource(R.string.cb_routing_sub), done = routing != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    TonalButton(stringResource(R.string.rt_edit_rules), BoxyIcons.FilterList, onClick = onRouting)
                    Spacer(Modifier.weight(1f))
                    Badge(stringResource(R.string.rt_now) + ": " + (routing ?: stringResource(R.string.rt_template)), Modifier.widthIn(max = 160.dp))
                }
            }
        }
        // 4 · Name of the file; the button below creates it.
        item {
            StepCard(4, stringResource(R.string.rt_name), null, done = name.isNotBlank()) {
                BoxyTextField(name, { name = it }, "my-config", Modifier.fillMaxWidth().padding(horizontal = 18.dp))
            }
        }
        item {
            var made by remember(madeKey) { mutableStateOf(ConfigBuilder.made(context)) }
            var renaming by remember { mutableStateOf<String?>(null) }
            var deleting by remember { mutableStateOf<String?>(null) }
            if (made.isNotEmpty()) SectionCard(stringResource(R.string.cb_made), stringResource(R.string.cb_made_sub)) {
                made.forEach { rel ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(BoxyIcons.File, null, Modifier.size(22.dp), tint = Boxy.colors.text2)
                        Spacer(Modifier.width(12.dp))
                        Text(rel.substringAfterLast('/'), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(BoxyIcons.Edit, stringResource(R.string.rt_rename), Modifier.size(36.dp).clip(RoundedCornerShape(50)).clickable { renaming = rel }.padding(8.dp), tint = Boxy.colors.text2)
                        Icon(BoxyIcons.Delete, stringResource(R.string.rt_delete), Modifier.size(36.dp).clip(RoundedCornerShape(50)).clickable { deleting = rel }.padding(8.dp), tint = Tints.red.fg)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            renaming?.let { rel ->
                com.skofqq.boxy.ui.components.InputDialog(
                    stringResource(R.string.rt_rename), listOf(stringResource(R.string.rt_name) to rel.substringAfterLast('/').removeSuffix(".json")),
                    validate = { v -> if (v[0].isBlank()) "…" else null },
                    onConfirm = { v ->
                        renaming = null
                        scope.launch {
                            val to = ConfigBuilder.rename(context.applicationContext, rel, v[0])
                            made = ConfigBuilder.made(context)
                            if (to != null && fileName(rel) == fileName(ConfigBuilder.fileName(plan()))) name = v[0]
                            BoxyToast.makeText(context, if (to != null) context.getString(R.string.cs_applied) else context.getString(R.string.op_failed), BoxyToast.LENGTH_SHORT).show()
                        }
                    },
                    onDismiss = { renaming = null },
                )
            }
            deleting?.let { rel ->
                com.skofqq.boxy.ui.components.ConfirmDialog(
                    stringResource(R.string.rt_delete), stringResource(R.string.cb_delete_confirm, rel.substringAfterLast('/')), stringResource(R.string.rt_delete),
                    onConfirm = {
                        deleting = null
                        scope.launch {
                            ConfigBuilder.delete(context.applicationContext, rel)
                            made = ConfigBuilder.made(context)
                        }
                    },
                    onDismiss = { deleting = null },
                )
            }
        }
        item { Text(stringResource(R.string.cb_note), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2) }
    }
    // Pinned above the navigation bar: the last step, always in reach.
    Box(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 12.dp)) {
        SheetButton(stringResource(if (creating) R.string.check_running else R.string.cb_create), if (name.isBlank()) Boxy.colors.surface2 else Boxy.colors.accent, if (name.isBlank()) Boxy.colors.text2 else Color.White, Modifier.fillMaxWidth()) { create() }
    }
    }
    editing?.let { i ->
        val g = groups.getOrNull(i) ?: return@let
        GroupSheet(g, tags, canDelete = groups.size > 1, onSave = { ng -> groups = groups.mapIndexed { j, x -> if (j == i) ng else x }; editing = null },
            onDelete = { groups = groups.filterIndexed { j, _ -> j != i }; editing = null }, onDismiss = { editing = null })
    }
}

@Composable
private fun strategyName(s: String) = when (s) {
    "leastLoad" -> stringResource(R.string.cs_strategy_load)
    "random" -> stringResource(R.string.cs_strategy_random)
    "roundRobin" -> stringResource(R.string.cs_strategy_rr)
    else -> stringResource(R.string.cs_strategy_ping)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupSheet(g: Group, tags: List<String>, canDelete: Boolean, onSave: (Group) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(g.name) }
    var members by remember { mutableStateOf(g.members.toSet()) }
    var strategy by remember { mutableStateOf(g.strategy) }
    var icon by remember { mutableStateOf(com.skofqq.boxy.ui.proxies.GroupIcons.icon(g.name).orEmpty()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BoxySheet(stringResource(R.string.cb_group), null, onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Tints.purple.bg), contentAlignment = Alignment.Center) {
                if (icon.startsWith("http")) com.skofqq.boxy.ui.proxies.GroupIconImage(icon, 36.dp)
                else Icon(BoxyIcons.Bolt, null, Modifier.size(22.dp), tint = Tints.purple.fg)
            }
            Spacer(Modifier.width(12.dp))
            BoxyTextField(name, { name = it }, stringResource(R.string.rt_name), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        BoxyTextField(icon, { icon = it.trim() }, stringResource(R.string.cb_icon_hint), Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.cs_strategy), style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text2)
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("leastPing", "leastLoad", "random", "roundRobin").forEach { s -> GlassChip(strategyName(s), strategy == s) { strategy = s } }
        }
        Text(stringResource(R.string.cb_members), style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text2)
        if (tags.isEmpty()) Text(stringResource(R.string.cb_members_load), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip(stringResource(R.string.cb_all_servers), members.isEmpty()) { members = emptySet() }
            (tags + g.members.filter { it !in tags }).forEach { t -> GlassChip(XrayApi.displayName(t), t in members) { members = if (t in members) members - t else members + t } }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canDelete) SheetButton(stringResource(R.string.rt_delete), Tints.red.bg, Tints.red.fg, Modifier.weight(1f), onDelete)
            SheetButton(stringResource(R.string.action_save), Boxy.colors.accent, Color.White, Modifier.weight(1f)) {
                if (name.isNotBlank()) {
                    // The icon is kept by name, as for every other group (Boxy's icons, shown in the panel too).
                    scope.launch { com.skofqq.boxy.ui.proxies.GroupIcons.set(context.applicationContext, name.trim(), icon.takeIf { it.startsWith("http") }) }
                    onSave(Group(name.trim(), members.toList(), strategy))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** A numbered step of the builder (M3 Expressive): the number turns into a check when the step has what it needs. */
@Composable
private fun StepCard(n: Int, title: String, subtitle: String?, done: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp)).background(Boxy.colors.card).padding(vertical = 18.dp)) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(50)).background(if (done) Boxy.colors.accent else Boxy.colors.surface2),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(BoxyIcons.Check, null, Modifier.size(18.dp), tint = Color.White)
                else Text("$n", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Boxy.colors.text2)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Boxy.colors.text)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
            }
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** A small pill: a server, the count, "default". */
@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(50)).background(if (accent) Boxy.colors.accent.copy(alpha = 0.14f) else Boxy.colors.surface2).padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (accent) Boxy.colors.accent else Boxy.colors.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
