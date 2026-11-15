package com.skofqq.boxy.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.configs.ConfigBuilder
import com.skofqq.boxy.configs.ConfigBuilder.Group
import com.skofqq.boxy.configs.RoutingProfiles
import com.skofqq.boxy.net.XrayApi
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.BoxyToast
import com.skofqq.boxy.ui.components.GlassChip
import com.skofqq.boxy.ui.components.GlassMenu
import com.skofqq.boxy.ui.components.GlassMenuItem
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SheetButton
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.components.TonalButton
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * "Create your own config" (Xray). The entry page lists the configs made here (rename, delete) with a button that
 * starts the wizard: 1 Servers (a subscription URL, Boxy's own subscription or pasted links; servers picked one by
 * one), 2 Groups (each picks one of its servers; the first takes what no route matched), 3 Routing (a preset or a
 * routing profile), 4 Finish (name, summary, made active or not). One step per page, Back / Next at the bottom.
 */

private const val STEPS = 4

private fun fileName(rel: String) = rel.substringAfterLast('/')

@Composable
fun ConfigBuilderScreen(contentPadding: PaddingValues, onBack: () -> Unit, onRouting: () -> Unit) {
    var wizard by rememberSaveable { mutableStateOf(false) }
    var madeKey by remember { mutableIntStateOf(0) }
    if (wizard) BuilderWizard(contentPadding, onExit = { wizard = false; madeKey++ }, onRouting = onRouting)
    else BuilderEntry(contentPadding, madeKey, onBack) { wizard = true }
}

// ------------------------------------------------------------------ entry: your configs

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BuilderEntry(contentPadding: PaddingValues, madeKey: Int, onBack: () -> Unit, onCreate: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var made by remember(madeKey) { mutableStateOf(ConfigBuilder.made(context)) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    PinnedLazyPage(contentPadding, header = {
        SubPageHeader(stringResource(R.string.cb_title), stringResource(R.string.cb_sub), onBack)
    }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            // What this is, M3E style: a cookie with the tool and the big button to start.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp))
                    .background(Boxy.colors.accent.copy(alpha = 0.12f)).padding(20.dp),
            ) {
                Box(Modifier.size(56.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(Boxy.colors.accent), contentAlignment = Alignment.Center) {
                    Icon(BoxyIcons.Build, null, Modifier.size(28.dp), tint = Color.White)
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.cb_intro), style = MaterialTheme.typography.bodyLarge, color = Boxy.colors.text)
                Spacer(Modifier.height(16.dp))
                SheetButton(stringResource(R.string.cb_new), Boxy.colors.accent, Color.White, Modifier.fillMaxWidth(), onCreate)
            }
        }
        item {
            SectionCard(stringResource(R.string.cb_made), stringResource(R.string.cb_made_sub)) {
                if (made.isEmpty()) Text(
                    stringResource(R.string.cb_made_none), Modifier.padding(horizontal = 18.dp).padding(bottom = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2,
                )
                made.forEach { rel ->
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Boxy.colors.surface2), contentAlignment = Alignment.Center) {
                            Icon(BoxyIcons.File, null, Modifier.size(22.dp), tint = Boxy.colors.text2)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(fileName(rel), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        RowMenu(
                            listOf(
                                MenuEntry(stringResource(R.string.rt_rename), BoxyIcons.Edit) { renaming = rel },
                                MenuEntry(stringResource(R.string.rt_delete), BoxyIcons.Delete, destructive = true) { deleting = rel },
                            ),
                        )
                    }
                }
            }
        }
    }
    renaming?.let { rel ->
        com.skofqq.boxy.ui.components.InputDialog(
            stringResource(R.string.rt_rename), listOf(stringResource(R.string.rt_name) to fileName(rel).removeSuffix(".json")),
            validate = { v -> if (v[0].isBlank()) "…" else null },
            onConfirm = { v ->
                renaming = null
                scope.launch {
                    val to = ConfigBuilder.rename(context.applicationContext, rel, v[0])
                    made = ConfigBuilder.made(context)
                    BoxyToast.makeText(context, if (to != null) context.getString(R.string.cs_applied) else context.getString(R.string.op_failed), BoxyToast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { rel ->
        com.skofqq.boxy.ui.components.ConfirmDialog(
            stringResource(R.string.rt_delete), stringResource(R.string.cb_delete_confirm, fileName(rel)), stringResource(R.string.rt_delete),
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

// ------------------------------------------------------------------ the wizard

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuilderWizard(contentPadding: PaddingValues, onExit: () -> Unit, onRouting: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { ConfigBuilder.load(context) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    // 0 = subscription URL, 1 = Boxy's subscription, 2 = pasted links.
    var source by remember { mutableIntStateOf(if (saved?.links.isNullOrBlank()) 0 else 2) }
    var url by remember { mutableStateOf(saved?.url.orEmpty()) }
    var links by remember { mutableStateOf(saved?.links.orEmpty()) }
    var boxyUrl by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf(saved?.name ?: "my-config") }
    var groups by remember { mutableStateOf(saved?.groups?.ifEmpty { null } ?: listOf(Group("⚡️ Fastest"))) }
    var exclude by remember { mutableStateOf(saved?.exclude.orEmpty().toSet()) }
    var routing by remember { mutableStateOf(saved?.routing ?: ConfigBuilder.ROUTE_PROXY) }
    var activate by remember { mutableStateOf(saved?.activate ?: true) }
    var servers by remember { mutableStateOf<List<JSONObject>?>(null) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<ConfigBuilder.Outcome?>(null) }
    val profiles = remember { RoutingProfiles.load(context).profiles }
    LaunchedEffect(Unit) {
        boxyUrl = runCatching { com.skofqq.boxy.configs.XraySubscription.url() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun plan() = ConfigBuilder.Plan(
        name.trim(), if (source == 2) "" else if (source == 1) boxyUrl.orEmpty() else url.trim(), if (source == 2) links else "",
        groups, exclude.toList(), routing, activate,
    )
    fun loadServers() {
        if (loading) return
        loading = true
        serverError = null
        scope.launch {
            val s = ConfigBuilder.servers(context.applicationContext, plan())
            loading = false
            servers = s.outbounds
            serverError = when (s.error) {
                "download" -> context.getString(R.string.core_setup_err_download)
                "empty" -> context.getString(R.string.cb_no_servers)
                else -> null
            }
            // Servers no longer in the list are not "left out" any more.
            val tags = s.outbounds.map { it.optString("tag") }.toSet()
            exclude = exclude.filter { it in tags }.toSet()
        }
    }
    val chosen = servers.orEmpty().map { it.optString("tag") }.filter { it !in exclude }
    val isProfile = routing != ConfigBuilder.ROUTE_PROXY && routing != ConfigBuilder.ROUTE_RU
    val valid = when (step) {
        0 -> chosen.isNotEmpty()
        1 -> groups.isNotEmpty()
        2 -> !isProfile || profiles.any { it.id == routing }
        else -> name.isNotBlank()
    }
    val why = when (step) {
        0 -> stringResource(R.string.cb_need_servers)
        2 -> stringResource(R.string.cb_need_profile)
        3 -> stringResource(R.string.cb_need_name)
        else -> ""
    }
    val entered = url.isNotBlank() || links.isNotBlank() || servers != null
    fun back() {
        when {
            done != null -> onExit()
            step > 0 -> step--
            entered -> leaving = true
            else -> onExit()
        }
    }
    fun create() {
        if (creating || !valid) return
        creating = true
        scope.launch {
            val r = ConfigBuilder.create(context.applicationContext, plan())
            creating = false
            if (r.ok) done = r
            else BoxyToast.makeText(
                context,
                when (r.error) {
                    "download" -> context.getString(R.string.core_setup_err_download)
                    "empty" -> context.getString(R.string.cb_no_servers)
                    else -> context.getString(R.string.cs_failed, r.error?.lines()?.takeLast(2)?.joinToString(" "))
                },
                BoxyToast.LENGTH_LONG, BoxyToast.Kind.ERROR,
            ).show()
        }
    }
    BackHandler { back() }

    val result = done
    if (result != null) {
        BuilderDone(contentPadding, result, plan(), onPanel = { onExit(); com.skofqq.boxy.PanelBus.request = true }, onDone = onExit)
        return
    }
    val titles = listOf(R.string.cb_servers, R.string.cb_groups, R.string.rt_title, R.string.cb_step_finish)
    Box(Modifier.fillMaxSize()) {
        PinnedLazyPage(
            PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 104.dp),
            header = {
                SubPageHeader(stringResource(titles[step]), stringResource(R.string.cb_step, step + 1, STEPS), { back() })
                StepProgress(step)
            },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                0 -> {
                    item {
                        SectionCard(null) {
                            FlowRow(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassChip(stringResource(R.string.cb_from_url), source == 0) { source = 0 }
                                GlassChip(stringResource(R.string.cb_from_boxy), source == 1) { source = 1 }
                                GlassChip(stringResource(R.string.cb_from_links), source == 2) { source = 2 }
                            }
                            Spacer(Modifier.height(12.dp))
                            when (source) {
                                0 -> BoxyTextField(url, { url = it }, "https://…", Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                                1 -> Text(
                                    if (boxyUrl != null) stringResource(R.string.cb_from_boxy_sub) else stringResource(R.string.cb_from_boxy_none),
                                    Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2,
                                )
                                else -> BoxyTextField(links, { links = it }, "vless://… vmess://… trojan://… ss://… hy2://…", Modifier.fillMaxWidth().padding(horizontal = 18.dp).heightIn(min = 140.dp), singleLine = false)
                            }
                            Spacer(Modifier.height(12.dp))
                            val canLoad = !loading && when (source) { 0 -> url.isNotBlank(); 1 -> boxyUrl != null; else -> links.isNotBlank() }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                TonalButton(stringResource(if (loading) R.string.check_running else R.string.cb_load), BoxyIcons.Download, enabled = canLoad) { loadServers() }
                                if (loading) {
                                    Spacer(Modifier.width(12.dp))
                                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                }
                            }
                            serverError?.let {
                                Text(it, Modifier.padding(horizontal = 18.dp).padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = Tints.red.fg)
                            }
                            if (servers == null && serverError == null) Text(
                                stringResource(R.string.cb_servers_empty), Modifier.padding(horizontal = 18.dp).padding(top = 10.dp),
                                style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2,
                            )
                        }
                    }
                    val list = servers.orEmpty()
                    if (list.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.cb_selected, chosen.size, list.size), Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text2,
                                )
                                GlassChip(stringResource(R.string.cb_all_servers), exclude.isEmpty()) {
                                    exclude = if (exclude.isEmpty()) list.map { it.optString("tag") }.toSet() else emptySet()
                                }
                            }
                        }
                        list.forEach { o ->
                            val tag = o.optString("tag")
                            item(key = "s:$tag") {
                                ServerRow(XrayApi.displayName(tag), o.optString("protocol"), tag !in exclude) {
                                    exclude = if (tag in exclude) exclude - tag else exclude + tag
                                }
                            }
                        }
                    }
                }
                1 -> {
                    item {
                        Text(stringResource(R.string.cb_groups_sub), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
                    }
                    groups.forEachIndexed { i, g ->
                        item(key = "g:$i:${g.name}") {
                            GroupRow(
                                g, i == 0, chosen,
                                onEdit = { editing = i },
                                onUp = if (i > 0) ({ groups = groups.toMutableList().apply { add(i - 1, removeAt(i)) } }) else null,
                                onDown = if (i < groups.lastIndex) ({ groups = groups.toMutableList().apply { add(i + 1, removeAt(i)) } }) else null,
                                onDelete = if (groups.size > 1) ({ groups = groups.filterIndexed { j, _ -> j != i } }) else null,
                            )
                        }
                    }
                    item {
                        TonalButton(stringResource(R.string.cb_add_group), BoxyIcons.Add, Modifier.padding(horizontal = 16.dp)) {
                            groups = groups + Group(context.getString(R.string.cb_new_group, groups.size + 1))
                            editing = groups.size - 1
                        }
                    }
                }
                2 -> {
                    item {
                        ChoiceCard(BoxyIcons.Language, stringResource(R.string.cb_rt_proxy), stringResource(R.string.cb_rt_proxy_sub, groups.first().name), routing == ConfigBuilder.ROUTE_PROXY) {
                            routing = ConfigBuilder.ROUTE_PROXY
                        }
                    }
                    item {
                        ChoiceCard(BoxyIcons.Router, stringResource(R.string.cb_rt_ru), stringResource(R.string.cb_rt_ru_sub, groups.first().name), routing == ConfigBuilder.ROUTE_RU) {
                            routing = ConfigBuilder.ROUTE_RU
                        }
                    }
                    item {
                        ChoiceCard(BoxyIcons.FilterList, stringResource(R.string.cb_rt_profile), stringResource(R.string.cb_rt_profile_sub), isProfile) {
                            if (!isProfile) routing = profiles.firstOrNull()?.id ?: "none"
                        }
                    }
                    if (isProfile) {
                        item {
                            SectionCard(null) {
                                if (profiles.isEmpty()) Text(
                                    stringResource(R.string.cb_rt_no_profiles), Modifier.padding(horizontal = 18.dp).padding(bottom = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2,
                                )
                                FlowRow(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    profiles.forEach { pr -> GlassChip(pr.name, routing == pr.id) { routing = pr.id } }
                                }
                                Spacer(Modifier.height(12.dp))
                                TonalButton(stringResource(R.string.rt_edit_rules), BoxyIcons.Edit, Modifier.padding(horizontal = 18.dp), onClick = onRouting)
                            }
                        }
                    }
                }
                else -> {
                    item {
                        SectionCard(stringResource(R.string.rt_name)) {
                            BoxyTextField(name, { name = it }, "my-config", Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                            Text(
                                ConfigBuilder.fileName(plan()).substringAfterLast('/'), Modifier.padding(horizontal = 20.dp).padding(top = 6.dp),
                                style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2,
                            )
                        }
                    }
                    item {
                        SectionCard(null) {
                            SummaryRow(BoxyIcons.Dns, stringResource(R.string.cb_servers), androidx.compose.ui.res.pluralStringResource(R.plurals.pl_servers, chosen.size, chosen.size)) { step = 0 }
                            SummaryRow(BoxyIcons.Bolt, stringResource(R.string.cb_groups), groups.joinToString(", ") { it.name }) { step = 1 }
                            SummaryRow(
                                BoxyIcons.FilterList, stringResource(R.string.rt_title),
                                when (routing) {
                                    ConfigBuilder.ROUTE_PROXY -> stringResource(R.string.cb_rt_proxy)
                                    ConfigBuilder.ROUTE_RU -> stringResource(R.string.cb_rt_ru)
                                    else -> profiles.firstOrNull { it.id == routing }?.name.orEmpty()
                                },
                            ) { step = 2 }
                        }
                    }
                    item {
                        SectionCard(null) {
                            com.skofqq.boxy.ui.components.SwitchRow(
                                BoxyIcons.CheckCircle, stringResource(R.string.cb_activate), stringResource(R.string.cb_activate_sub), activate, showDivider = false,
                            ) { activate = it }
                        }
                    }
                }
            }
        }
        // Back / Next pinned at the bottom; under a disabled Next, why.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Boxy.colors.page.copy(alpha = 0f), 0.3f to Boxy.colors.page))
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = contentPadding.calculateBottomPadding() + 12.dp),
        ) {
            if (!valid && why.isNotEmpty()) Text(
                why, Modifier.fillMaxWidth().padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = Boxy.colors.text2, textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton(
                    stringResource(R.string.action_back), Boxy.colors.card, Boxy.colors.text,
                    Modifier.weight(1f).border(1.dp, Boxy.colors.outline, RoundedCornerShape(18.dp)),
                ) { back() }
                val last = step == STEPS - 1
                SheetButton(
                    stringResource(if (creating) R.string.check_running else if (last) R.string.cb_create_btn else R.string.cb_next),
                    if (valid) Boxy.colors.accent else Boxy.colors.surface2, if (valid) Color.White else Boxy.colors.text2, Modifier.weight(1.4f),
                ) {
                    if (!valid) return@SheetButton
                    if (last) create() else step++
                }
            }
        }
    }
    if (leaving) com.skofqq.boxy.ui.components.ConfirmDialog(
        stringResource(R.string.cb_leave_title), stringResource(R.string.cb_leave_body), stringResource(R.string.cb_leave),
        onConfirm = { leaving = false; ConfigBuilder.save(context, plan()); onExit() },
        onDismiss = { leaving = false },
    )
    editing?.let { i ->
        val g = groups.getOrNull(i) ?: return@let
        GroupSheet(
            g, chosen, canDelete = groups.size > 1,
            onSave = { ng -> groups = groups.mapIndexed { j, x -> if (j == i) ng else x }; editing = null },
            onDelete = { groups = groups.filterIndexed { j, _ -> j != i }; editing = null },
            onDismiss = { editing = null },
        )
    }
}

/** Where the wizard is: one bar per step, the done ones filled. */
@Composable
private fun StepProgress(step: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(STEPS) { i ->
            val fill by animateFloatAsState(if (i <= step) 1f else 0f, label = "step")
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50)).background(Boxy.colors.surface2)) {
                Box(Modifier.fillMaxWidth(fill).height(6.dp).clip(RoundedCornerShape(50)).background(Boxy.colors.accent))
            }
        }
    }
}

/** A server of step 1: picked or left out with a tap. */
@Composable
private fun ServerRow(name: String, protocol: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp))
            .background(if (on) Boxy.colors.accent.copy(alpha = 0.10f) else Boxy.colors.card)
            .clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(protocol, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
        }
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                .then(if (on) Modifier.background(Boxy.colors.accent) else Modifier.border(2.dp, Boxy.colors.text2.copy(alpha = 0.6f), RoundedCornerShape(8.dp))),
            contentAlignment = Alignment.Center,
        ) { if (on) Icon(BoxyIcons.Check, null, Modifier.size(18.dp), tint = Color.White) }
    }
}

/** A group of step 2: icon, name (the first one is the default), its servers and method; menu to edit / move / delete. */
@Composable
private fun GroupRow(g: Group, first: Boolean, chosen: List<String>, onEdit: () -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?, onDelete: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)).background(Boxy.colors.card)
            .clickable(onClick = onEdit).padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
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
                if (first) {
                    Spacer(Modifier.width(8.dp))
                    Badge(stringResource(R.string.cb_default))
                }
            }
            val members = g.members.filter { it in chosen }
            Text(
                (if (members.isEmpty()) stringResource(R.string.cb_all_servers) else members.joinToString(", ") { XrayApi.displayName(it) }) + " · " + strategyName(g.strategy),
                style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        RowMenu(
            listOfNotNull(
                MenuEntry(stringResource(R.string.action_edit), BoxyIcons.Edit, onClick = onEdit),
                onUp?.let { MenuEntry(stringResource(R.string.cb_move_up), BoxyIcons.ArrowUp, onClick = it) },
                onDown?.let { MenuEntry(stringResource(R.string.cb_move_down), BoxyIcons.ArrowDown, onClick = it) },
                onDelete?.let { MenuEntry(stringResource(R.string.rt_delete), BoxyIcons.Delete, destructive = true, onClick = it) },
            ),
        )
    }
}

private class MenuEntry(val label: String, val icon: ImageVector, val destructive: Boolean = false, val onClick: () -> Unit)

/** ⋮ with a menu. */
@Composable
private fun RowMenu(items: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Icon(
            BoxyIcons.MoreVert, stringResource(R.string.action_more),
            Modifier.size(44.dp).clip(RoundedCornerShape(50)).clickable { open = true }.padding(11.dp), tint = Boxy.colors.text2,
        )
        GlassMenu(open, { open = false }) {
            items.forEach { e -> GlassMenuItem(e.label, { open = false; e.onClick() }, e.icon, destructive = e.destructive) }
        }
    }
}

/** A large selectable card (M3E): icon, title, what it does; the chosen one outlined. */
@Composable
private fun ChoiceCard(icon: ImageVector, title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp))
            .background(if (selected) Boxy.colors.accent.copy(alpha = 0.12f) else Boxy.colors.card)
            .then(if (selected) Modifier.border(2.dp, Boxy.colors.accent, RoundedCornerShape(28.dp)) else Modifier)
            .clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(if (selected) Boxy.colors.accent else Boxy.colors.surface2),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(24.dp), tint = if (selected) Color.White else Boxy.colors.text2) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
        }
    }
}

/** A line of the summary; a tap goes back to its step. */
@Composable
private fun SummaryRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = Boxy.colors.text2)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = Boxy.colors.text2)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = Boxy.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(BoxyIcons.Edit, null, Modifier.size(18.dp), tint = Boxy.colors.text2)
    }
}

/** After "Create": what was made, and where to go. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BuilderDone(contentPadding: PaddingValues, r: ConfigBuilder.Outcome, p: ConfigBuilder.Plan, onPanel: () -> Unit, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(96.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(Tints.green.fg), contentAlignment = Alignment.Center) {
            Icon(BoxyIcons.Check, null, Modifier.size(48.dp), tint = Color.White)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.cb_done_title), style = MaterialTheme.typography.headlineMedium, color = Boxy.colors.text)
        Spacer(Modifier.height(8.dp))
        Text(
            r.path?.substringAfterLast('/').orEmpty() + ": " + androidx.compose.ui.res.pluralStringResource(R.plurals.pl_servers, r.servers, r.servers) +
                if (p.activate) "\n" + stringResource(R.string.cb_done_active) else "",
            style = MaterialTheme.typography.bodyLarge, color = Boxy.colors.text2, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (p.activate) SheetButton(stringResource(R.string.cb_open_panel), Boxy.colors.accent, Color.White, Modifier.fillMaxWidth(), onPanel)
        Spacer(Modifier.height(10.dp))
        SheetButton(
            stringResource(R.string.cb_finish), if (p.activate) Boxy.colors.card else Boxy.colors.accent, if (p.activate) Boxy.colors.text else Color.White,
            Modifier.fillMaxWidth(), onDone,
        )
    }
}

@Composable
private fun strategyName(s: String) = when (s) {
    "leastLoad" -> stringResource(R.string.cs_strategy_load)
    "random" -> stringResource(R.string.cs_strategy_random)
    "roundRobin" -> stringResource(R.string.cs_strategy_rr)
    else -> stringResource(R.string.cs_strategy_ping)
}

/** A small pill: "default". */
@Composable
private fun Badge(text: String) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.accent.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium, color = Boxy.colors.accent, maxLines = 1,
    )
}

/** Adding or changing a group: name, icon, method, servers (search, all). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupSheet(g: Group, tags: List<String>, canDelete: Boolean, onSave: (Group) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(g.name) }
    var members by remember { mutableStateOf(g.members.filter { it in tags }.toSet()) }
    var strategy by remember { mutableStateOf(g.strategy) }
    var icon by remember { mutableStateOf(com.skofqq.boxy.ui.proxies.GroupIcons.icon(g.name).orEmpty()) }
    var query by remember { mutableStateOf("") }
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
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.cs_strategy), style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text2)
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("leastPing", "leastLoad", "random", "roundRobin").forEach { s -> GlassChip(strategyName(s), strategy == s) { strategy = s } }
        }
        Text(stringResource(R.string.cb_members), style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text2)
        if (tags.size > 8) BoxyTextField(query, { query = it }, stringResource(R.string.xray_search_outbounds), Modifier.fillMaxWidth().padding(top = 8.dp))
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip(stringResource(R.string.cb_all_servers), members.isEmpty()) { members = emptySet() }
            tags.filter { query.isBlank() || XrayApi.displayName(it).contains(query, true) }.forEach { t ->
                GlassChip(XrayApi.displayName(t), t in members) { members = if (t in members) members - t else members + t }
            }
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
