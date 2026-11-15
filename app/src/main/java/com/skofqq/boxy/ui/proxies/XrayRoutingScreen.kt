package com.skofqq.boxy.ui.proxies

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skofqq.boxy.R
import com.skofqq.boxy.configs.GeoRules
import com.skofqq.boxy.configs.RoutingProfiles
import com.skofqq.boxy.configs.RoutingProfiles.Profile
import com.skofqq.boxy.configs.RoutingProfiles.Route
import com.skofqq.boxy.data.GeoBase
import com.skofqq.boxy.net.XrayApi
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CoreTools
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.ConfirmDialog
import com.skofqq.boxy.ui.components.GlassChip
import com.skofqq.boxy.ui.components.HeaderAction
import com.skofqq.boxy.ui.components.InputDialog
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SheetButton
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Opens the routing profiles over the panel (from the Rules and Settings tabs). */
object RoutingNav {
    var open by mutableStateOf(false)
}

/** How a target reads: "⚡️ Fastest", "🇷🇺 Без VPN", "Блокировать", or the group's own name. */
@Composable
private fun targetName(t: String): String = when (t) {
    RoutingProfiles.PROXY -> stringResource(R.string.rt_target_proxy)
    RoutingProfiles.DIRECT -> stringResource(R.string.xray_no_vpn)
    RoutingProfiles.BLOCK -> stringResource(R.string.rt_target_block)
    else -> XrayApi.displayName(t)
}

@Composable
private fun targetTint(t: String) = when (t) {
    RoutingProfiles.DIRECT -> Tints.green
    RoutingProfiles.BLOCK -> Tints.red
    RoutingProfiles.PROXY -> Tints.blue
    else -> Tints.purple
}

/**
 * Routing profiles for Xray, Happ-like: a list of profiles (the panel's template is "no profile"), a profile's
 * routes and options, a route's entries. Each change is saved at once; "Apply" rebuilds the active config.
 */
@Composable
fun XrayRoutingScreen(padding: PaddingValues, onBack: () -> Unit) {
    CompositionLocalProvider(LocalTopInset provides padding.calculateTopPadding()) { RoutingScreen(padding.calculateBottomPadding(), onBack) }
}

/** Status-bar inset of the screen, for the pinned headers of every level. */
private val LocalTopInset = staticCompositionLocalOf { 0.dp }

@Composable
private fun RoutingScreen(bottom: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var store by remember { mutableStateOf(RoutingProfiles.load(context)) }
    var base by remember { mutableStateOf<JSONObject?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var route by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        base = RoutingProfiles.baseConfig(context)
        // The first time: "Основной" made of the template's own routing, to edit (not active until applied).
        if (store.profiles.isEmpty()) base?.let { b ->
            store = store.copy(profiles = listOf(RoutingProfiles.fromTemplate(b, context.getString(R.string.rt_main))))
            RoutingProfiles.save(context, store)
        }
    }
    fun update(s: RoutingProfiles.Store) {
        store = s
        RoutingProfiles.save(context, s)
        dirty = true
    }
    fun updateProfile(p: Profile) = update(store.copy(profiles = store.profiles.map { if (it.id == p.id) p else it }))
    val commentsMsg = stringResource(R.string.cs_comments)
    fun apply(activeId: String?) {
        if (applying) return
        applying = true
        update(store.copy(active = activeId))
        scope.launch {
            val r = RoutingProfiles.rebuildXray(context.applicationContext)
            android.util.Log.i("Boxy", "routing rebuild: ok=${r.ok} skipped=${r.skipped} error=${r.error?.takeLast(700)}")
            applying = false
            if (r.ok) dirty = false
            val msg = when {
                r.ok && r.skipped.isNotEmpty() -> context.getString(R.string.rt_applied_skipped, r.skipped.joinToString(", "))
                r.ok -> context.getString(R.string.cs_applied)
                else -> context.getString(R.string.cs_failed, if (r.error == "comments") commentsMsg else r.error?.lines()?.takeLast(2)?.joinToString(" "))
            }
            com.skofqq.boxy.ui.components.BoxyToast.makeText(context, msg, com.skofqq.boxy.ui.components.BoxyToast.LENGTH_LONG).show()
        }
    }

    val p = store.profiles.firstOrNull { it.id == editing }
    val r = p?.routes?.firstOrNull { it.id == route }
    BackHandler { when { route != null -> route = null; editing != null -> editing = null; else -> onBack() } }
    when {
        p != null && r != null -> RouteEditor(bottom, p, r, base, onBack = { route = null }, onChange = { nr ->
            updateProfile(p.copy(routes = p.routes.map { if (it.id == nr.id) nr else it }))
        }, onDelete = {
            updateProfile(p.copy(routes = p.routes.filter { it.id != r.id }))
            route = null
        })
        p != null -> ProfileEditor(
            bottom, p, base, active = store.active == p.id, applying = applying, dirty = dirty,
            onBack = { editing = null }, onChange = { updateProfile(it) }, onOpenRoute = { route = it },
            onApply = { apply(p.id) },
        )
        else -> ProfileList(
            bottom, store, applying,
            onBack = onBack,
            onOpen = { editing = it },
            onActivate = { apply(it) },
            onChange = { update(it) },
            base = base,
        )
    }
}

@Composable
private fun Header(title: String, sub: String?, onBack: () -> Unit, end: @Composable () -> Unit = {}) {
    // The same header as every other page in Config.
    com.skofqq.boxy.ui.components.SubPageHeader(title, sub, onBack) { end() }
}

// ---------------------------------------------------------------- profiles

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileList(
    bottom: Dp, store: RoutingProfiles.Store, applying: Boolean, base: JSONObject?,
    onBack: () -> Unit, onOpen: (String) -> Unit, onActivate: (String?) -> Unit, onChange: (RoutingProfiles.Store) -> Unit,
) {
    val context = LocalContext.current
    var rename by remember { mutableStateOf<Profile?>(null) }
    var delete by remember { mutableStateOf<Profile?>(null) }
    var create by remember { mutableStateOf(false) }
    val active = store.profiles.firstOrNull { it.id == store.active }
    PinnedLazyPage(PaddingValues(top = LocalTopInset.current, bottom = bottom + 16.dp), header = {
        Header(stringResource(R.string.rt_title), stringResource(R.string.rt_sub), onBack)
    }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            // What runs now, M3E style: a cookie with the route symbol and the active profile's name.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp)).background(Boxy.colors.accent.copy(alpha = 0.14f)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(Boxy.colors.accent), contentAlignment = Alignment.Center) {
                    Icon(BoxyIcons.FilterList, null, Modifier.size(28.dp), tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.rt_now), style = MaterialTheme.typography.labelLarge, color = Boxy.colors.text2)
                    Text(active?.name ?: stringResource(R.string.rt_template), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text)
                }
                if (applying) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        item {
            ProfileCard(stringResource(R.string.rt_template), stringResource(R.string.rt_template_sub), null, store.active == null, onClick = { if (store.active != null) onActivate(null) })
        }
        store.profiles.forEach { pr ->
            item(key = pr.id) {
                val n = pr.routes.sumOf { it.entries.size }
                val badges = listOf(
                    stringResource(if (pr.mode == RoutingProfiles.Mode.OVER) R.string.rt_mode_over else R.string.rt_mode_replace),
                    stringResource(R.string.rt_count, pr.routes.size, n),
                )
                ProfileCard(pr.name, null, badges, store.active == pr.id, onClick = { onOpen(pr.id) }) {
                    MoreMenu(
                        listOfNotNull(
                            ("apply" to stringResource(R.string.rt_apply)).takeIf { store.active != pr.id },
                            "rename" to stringResource(R.string.rt_rename),
                            "copy" to stringResource(R.string.rt_duplicate),
                            "export" to stringResource(R.string.rt_export),
                            "delete" to stringResource(R.string.rt_delete),
                        ),
                    ) { a ->
                        when (a) {
                            "apply" -> onActivate(pr.id)
                            "rename" -> rename = pr
                            "copy" -> onChange(store.copy(profiles = store.profiles + pr.copy(id = RoutingProfiles.newId(), name = pr.name + " 2")))
                            "export" -> share(context, pr)
                            "delete" -> delete = pr
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton(stringResource(R.string.rt_new), Boxy.colors.accent, Color.White, Modifier.weight(1f)) { create = true }
                SheetButton(stringResource(R.string.rt_import), Boxy.colors.card, Boxy.colors.text, Modifier.weight(1f)) {
                    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    val pr = RoutingProfiles.import(clip)
                    if (pr == null) com.skofqq.boxy.ui.components.BoxyToast.makeText(context, R.string.rt_import_none, com.skofqq.boxy.ui.components.BoxyToast.LENGTH_LONG).show()
                    else {
                        onChange(store.copy(profiles = store.profiles + pr))
                        com.skofqq.boxy.ui.components.BoxyToast.makeText(context, context.getString(R.string.rt_imported, pr.name), com.skofqq.boxy.ui.components.BoxyToast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        item { Text(stringResource(R.string.rt_list_hint), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2) }
    }
    if (create) {
        val fromTemplate = stringResource(R.string.rt_from_template)
        val empty = stringResource(R.string.rt_empty)
        BoxySheet(stringResource(R.string.rt_new), null, { create = false }) {
            com.skofqq.boxy.ui.components.OptionRow(empty, stringResource(R.string.rt_empty_sub), false) {
                create = false
                onChange(store.copy(profiles = store.profiles + Profile(RoutingProfiles.newId(), empty, RoutingProfiles.Mode.OVER, routes = listOf(
                    Route(RoutingProfiles.newId(), RoutingProfiles.PROXY, emptyList()),
                    Route(RoutingProfiles.newId(), RoutingProfiles.DIRECT, emptyList()),
                    Route(RoutingProfiles.newId(), RoutingProfiles.BLOCK, emptyList()),
                ))))
            }
            if (base != null) com.skofqq.boxy.ui.components.OptionRow(fromTemplate, stringResource(R.string.rt_from_template_sub), false) {
                create = false
                onChange(store.copy(profiles = store.profiles + RoutingProfiles.fromTemplate(base, fromTemplate)))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    rename?.let { pr ->
        InputDialog(stringResource(R.string.rt_rename), listOf(stringResource(R.string.rt_name) to pr.name), validate = { v -> if (v[0].isBlank()) "…" else null },
            onConfirm = { v -> rename = null; onChange(store.copy(profiles = store.profiles.map { if (it.id == pr.id) it.copy(name = v[0]) else it })) }, onDismiss = { rename = null })
    }
    delete?.let { pr ->
        ConfirmDialog(stringResource(R.string.rt_delete), stringResource(R.string.rt_delete_confirm, pr.name), stringResource(R.string.rt_delete),
            onConfirm = {
                delete = null
                val wasActive = store.active == pr.id
                onChange(store.copy(profiles = store.profiles.filter { it.id != pr.id }, active = if (wasActive) store.active else store.active))
                if (wasActive) onActivate(null)
            }, onDismiss = { delete = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(name: String, sub: String?, badges: List<String>?, active: Boolean, onClick: () -> Unit, end: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)).background(Boxy.colors.card)
            .then(if (active) Modifier.border(2.dp, Boxy.colors.accent, RoundedCornerShape(24.dp)) else Modifier)
            .clickable(onClick = onClick).padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A radio: a filled check when active, an outlined ring otherwise.
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(50))
                .then(if (active) Modifier.background(Boxy.colors.accent) else Modifier.border(2.dp, Boxy.colors.text2.copy(alpha = 0.6f), RoundedCornerShape(50))),
            contentAlignment = Alignment.Center,
        ) { if (active) Icon(BoxyIcons.Check, null, Modifier.size(16.dp), tint = Color.White) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
            if (!badges.isNullOrEmpty()) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                badges.forEach { b ->
                    Text(b, Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.surface2).padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Boxy.colors.text2, maxLines = 1)
                }
            }
        }
        end()
    }
}

/** The ⋮ button of a card with its menu of [options] (key to label). */
@Composable
private fun MoreMenu(options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Icon(BoxyIcons.MoreVert, stringResource(R.string.action_more), Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable { open = true }.padding(9.dp), tint = Boxy.colors.text2)
        com.skofqq.boxy.ui.components.GlassMenu(open, { open = false }) {
            options.forEach { (key, text) -> com.skofqq.boxy.ui.components.GlassMenuItem(text, { open = false; onPick(key) }) }
        }
    }
}

private fun share(context: Context, p: Profile) {
    val text = RoutingProfiles.export(p)
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Boxy routing", text))
    runCatching {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), p.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ---------------------------------------------------------------- one profile

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditor(
    bottom: Dp, p: Profile, base: JSONObject?, active: Boolean, applying: Boolean, dirty: Boolean,
    onBack: () -> Unit, onChange: (Profile) -> Unit, onOpenRoute: (String) -> Unit, onApply: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var geoInfo by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var geoBusy by remember { mutableStateOf(false) }
    var geoKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(geoKey) {
        val (primary, extras) = CoreTools.geoSources()
        geoInfo = listOf("geosite.dat", "geoip.dat").map { f ->
            val out = BoxModule.exec("stat -c '%s %Y' '${BoxModule.BOX_DIR}/xray/$f' 2>/dev/null").second.firstOrNull().orEmpty()
            val size = out.substringBefore(' ').toLongOrNull()
            val time = out.substringAfter(' ').toLongOrNull()
            f to listOfNotNull(
                primary.title + if (extras.isNotEmpty()) " + " + extras.joinToString(", ") { it.title } else "",
                time?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it * 1000)) },
                size?.let { com.skofqq.boxy.util.Format.bytes(context, it) },
            ).joinToString(" · ")
        }
    }
    Box {
        PinnedLazyPage(PaddingValues(top = LocalTopInset.current, bottom = bottom + 96.dp), header = {
            Header(p.name, stringResource(if (active) R.string.rt_active else R.string.rt_not_active), onBack) {
                HeaderAction(BoxyIcons.Edit, stringResource(R.string.rt_rename)) { rename = true }
            }
        }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(stringResource(R.string.rt_mode), stringResource(if (p.mode == RoutingProfiles.Mode.OVER) R.string.rt_mode_over_hint else R.string.rt_mode_replace_hint)) {
                    // A FlowRow: long names (ru, de) wrap instead of the second chip being cut off.
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassChip(stringResource(R.string.rt_mode_over), p.mode == RoutingProfiles.Mode.OVER) { onChange(p.copy(mode = RoutingProfiles.Mode.OVER)) }
                        GlassChip(stringResource(R.string.rt_mode_replace), p.mode == RoutingProfiles.Mode.REPLACE) { onChange(p.copy(mode = RoutingProfiles.Mode.REPLACE)) }
                    }
                    ToggleRow(BoxyIcons.Language, stringResource(R.string.rt_global), stringResource(if (p.globalProxy) R.string.rt_global_on else R.string.rt_global_off), p.globalProxy, false, divider = false) {
                        onChange(p.copy(globalProxy = it))
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.rt_routes), stringResource(R.string.rt_routes_hint)) {
                    p.routes.forEachIndexed { i, rt ->
                        RouteRow(rt, first = i == 0, last = i == p.routes.size - 1, divider = true,
                            onOpen = { onOpenRoute(rt.id) },
                            onMove = { d ->
                                val l = p.routes.toMutableList()
                                val j = i + d
                                if (j in l.indices) { l[i] = l[j].also { l[j] = l[i] }; onChange(p.copy(routes = l)) }
                            })
                    }
                    TapRow(BoxyIcons.Add, stringResource(R.string.rt_add_route), null, false, divider = false) {
                        val nr = Route(RoutingProfiles.newId(), RoutingProfiles.PROXY, emptyList())
                        onChange(p.copy(routes = p.routes + nr))
                        onOpenRoute(nr.id)
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.rt_domains)) {
                    ChoiceRow(BoxyIcons.FilterList, stringResource(R.string.cs_domain_strategy), stringResource(R.string.cs_domain_strategy_hint), p.domainStrategy ?: "", listOf(
                        "" to stringResource(R.string.rt_from_tpl), "AsIs" to stringResource(R.string.cs_ds_asis),
                        "IPIfNonMatch" to stringResource(R.string.cs_ds_nonmatch), "IPOnDemand" to stringResource(R.string.cs_ds_ondemand),
                    ), false) { onChange(p.copy(domainStrategy = it.ifBlank { null })) }
                    ToggleRow(BoxyIcons.Dns, stringResource(R.string.rt_fakedns), stringResource(R.string.rt_fakedns_hint), p.fakeDns, false, divider = false) { onChange(p.copy(fakeDns = it)) }
                }
            }
            item {
                SectionCard("DNS", stringResource(R.string.rt_dns_hint)) {
                    DnsRows(stringResource(R.string.rt_dns_remote), p.remoteDns) { onChange(p.copy(remoteDns = it)) }
                    DnsRows(stringResource(R.string.rt_dns_domestic), p.domesticDns, divider = false) { onChange(p.copy(domesticDns = it)) }
                }
            }
            item {
                SectionCard(stringResource(R.string.rt_geo)) {
                    geoInfo.forEach { (f, info) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(BoxyIcons.Storage, null, Modifier.size(22.dp), tint = Boxy.colors.text2)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f, style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text)
                                Text(info, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                            }
                        }
                    }
                    // Updated with the other components in Tools → Updates (one place for every update).
                    TapRow(BoxyIcons.SystemUpdate, stringResource(R.string.panel_settings_updates), stringResource(R.string.rt_geo_hint), false, divider = false) {
                        com.skofqq.boxy.ui.tools.ToolsNav.request = com.skofqq.boxy.ui.tools.ROUTE_UPDATE
                        com.skofqq.boxy.ui.tools.ToolsNav.showTools = true
                    }
                }
            }
            item { WhereTo(base, p) }
        }
        // Apply: the profile becomes the active one and the config is rebuilt (checked by Xray first).
        Box(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = bottom + 12.dp)) {
            SheetButton(
                stringResource(if (applying) R.string.check_running else if (active && !dirty) R.string.rt_applied_now else R.string.rt_apply),
                if (active && !dirty) Boxy.colors.card else Boxy.colors.accent,
                if (active && !dirty) Boxy.colors.text2 else Color.White,
                Modifier.fillMaxWidth(),
            ) { if (!applying) onApply() }
        }
    }
    if (rename) InputDialog(stringResource(R.string.rt_rename), listOf(stringResource(R.string.rt_name) to p.name), validate = { v -> if (v[0].isBlank()) "…" else null },
        onConfirm = { v -> rename = false; onChange(p.copy(name = v[0])) }, onDismiss = { rename = false })
}

@Composable
private fun RouteRow(rt: Route, first: Boolean, last: Boolean, divider: Boolean, onOpen: () -> Unit, onMove: (Int) -> Unit) {
    val tint = targetTint(rt.target)
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.bg), contentAlignment = Alignment.Center) {
                val icon = GroupIcons.icon(rt.target)
                if (icon != null) GroupIconImage(icon, 28.dp)
                else Icon(when (rt.target) { RoutingProfiles.BLOCK -> BoxyIcons.Block; RoutingProfiles.DIRECT -> BoxyIcons.Web; else -> BoxyIcons.Bolt }, null, Modifier.size(22.dp), tint = tint.fg)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(targetName(rt.target), style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (rt.entries.isEmpty()) stringResource(R.string.rt_route_empty) else rt.entries.take(3).joinToString(", ") + if (rt.entries.size > 3) "  +${rt.entries.size - 3}" else "",
                    style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column {
                Icon(BoxyIcons.ExpandLess, stringResource(R.string.rt_up), Modifier.size(28.dp).clip(RoundedCornerShape(50)).clickable(enabled = !first) { onMove(-1) }.padding(3.dp),
                    tint = if (first) Boxy.colors.text2.copy(alpha = 0.3f) else Boxy.colors.text2)
                Icon(BoxyIcons.ExpandMore, stringResource(R.string.rt_down), Modifier.size(28.dp).clip(RoundedCornerShape(50)).clickable(enabled = !last) { onMove(1) }.padding(3.dp),
                    tint = if (last) Boxy.colors.text2.copy(alpha = 0.3f) else Boxy.colors.text2)
            }
        }
        if (divider) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 76.dp, end = 18.dp), color = Boxy.colors.outline)
    }
}

@Composable
private fun DnsRows(title: String, dns: RoutingProfiles.Dns?, divider: Boolean = true, onChange: (RoutingProfiles.Dns?) -> Unit) {
    val type = dns?.type ?: ""
    ChoiceRow(BoxyIcons.Dns, title, null, type, listOf(
        "" to stringResource(R.string.rt_from_tpl), "doh" to "DoH", "doq" to "DoQ", "tcp" to "TCP", "udp" to "UDP",
    ), false, divider = dns == null && divider) { t ->
        onChange(if (t.isBlank()) null else RoutingProfiles.Dns(t, RoutingProfiles.DNS_PRESETS[t]?.first()?.first.orEmpty()))
    }
    if (dns != null) {
        PresetRow(BoxyIcons.Link, stringResource(R.string.rt_dns_address), null, dns.address, RoutingProfiles.DNS_PRESETS[dns.type].orEmpty(), false,
            validate = { v -> if (v.isBlank() || ' ' in v) "…" else null }, divider = divider) { onChange(dns.copy(address = it)) }
    }
}

/** "Where does a site go": the first rule of the profile over the base that matches a domain or an IP. */
@Composable
private fun WhereTo(base: JSONObject?, p: Profile) {
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<RoutingProfiles.Match?>(null) }
    var none by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SectionCard(stringResource(R.string.rt_where), stringResource(R.string.rt_where_hint)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            BoxyTextField(input, { input = it; result = null; none = false }, "youtube.com", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SheetButton(stringResource(R.string.rt_check), Boxy.colors.accent, Color.White, Modifier.width(110.dp)) {
                val b = base ?: return@SheetButton
                if (busy || input.isBlank()) return@SheetButton
                busy = true
                scope.launch {
                    val cfg = JSONObject(b.toString())
                    RoutingProfiles.applyXray(cfg, p)
                    result = RoutingProfiles.match(cfg, input)
                    none = result == null
                    busy = false
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val r = result
        when {
            busy -> Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
            r != null -> Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("→", style = MaterialTheme.typography.titleLarge, color = Boxy.colors.text2)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(XrayApi.displayName(r.target).let { if (it == "direct") stringResource(R.string.xray_no_vpn) else it }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Boxy.colors.accent)
                    Text(stringResource(R.string.rt_by, r.rule + 1, r.by ?: stringResource(R.string.xray_rule_any)), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                }
            }
            none -> Text(stringResource(R.string.rt_no_match), Modifier.padding(horizontal = 18.dp).padding(bottom = 14.dp), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
            else -> Spacer(Modifier.height(4.dp))
        }
    }
}

// ---------------------------------------------------------------- one route

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteEditor(bottom: Dp, p: Profile, r: Route, base: JSONObject?, onBack: () -> Unit, onChange: (Route) -> Unit, onDelete: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var textMode by remember { mutableStateOf(false) }
    var text by remember(r.id, textMode) { mutableStateOf(r.entries.joinToString("\n")) }
    var picker by remember { mutableStateOf<GeoBase.Kind?>(null) }
    val targets = remember(base) { base?.let { RoutingProfiles.targets(it) }.orEmpty() }
    val invalid = input.isNotBlank() && RoutingProfiles.kind(input) == null
    fun add(v: String) {
        val e = v.trim()
        if (e.isEmpty() || RoutingProfiles.kind(e) == null || e in r.entries) return
        onChange(r.copy(entries = r.entries + e))
    }
    PinnedLazyPage(PaddingValues(top = LocalTopInset.current, bottom = bottom + 16.dp), header = {
        Header(targetName(r.target), p.name, onBack) {
            HeaderAction(BoxyIcons.Delete, stringResource(R.string.rt_delete), tint = Tints.red.fg, onClick = onDelete)
        }
    }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(null) {
                ChoiceRow(BoxyIcons.ArrowCircle, stringResource(R.string.rt_target), stringResource(R.string.rt_target_hint), r.target,
                    targets.map { t -> t.tag to when (t.tag) {
                        RoutingProfiles.PROXY -> stringResource(R.string.rt_target_proxy)
                        RoutingProfiles.DIRECT -> stringResource(R.string.xray_no_vpn)
                        RoutingProfiles.BLOCK -> stringResource(R.string.rt_target_block)
                        else -> XrayApi.displayName(t.tag)
                    } }, false, divider = false) { onChange(r.copy(target = it)) }
            }
        }
        item {
            SectionCard(stringResource(R.string.rt_entries, r.entries.size)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(stringResource(R.string.rt_chips), !textMode) { textMode = false }
                    GlassChip(stringResource(R.string.rt_text), textMode) { textMode = true }
                }
                Spacer(Modifier.height(10.dp))
                if (!textMode) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        BoxyTextField(input, { input = it }, stringResource(R.string.rt_entry_hint), Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        HeaderAction(BoxyIcons.Add, stringResource(R.string.rt_add)) { if (!invalid) { add(input); input = "" } }
                    }
                    Text(
                        if (invalid) stringResource(R.string.rt_entry_invalid) else stringResource(R.string.rt_entry_kinds),
                        Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (invalid) Tints.red.fg else Boxy.colors.text2,
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassChip("+ GeoSite", false) { picker = GeoBase.Kind.SITE }
                        GlassChip("+ GeoIP", false) { picker = GeoBase.Kind.IP }
                    }
                    Spacer(Modifier.height(12.dp))
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.entries.forEach { e ->
                            val kind = RoutingProfiles.kind(e)
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(if (kind == RoutingProfiles.Kind.IP) Tints.amber.bg else Boxy.colors.surface2).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(e, style = MaterialTheme.typography.labelLarge, color = Boxy.colors.text, maxLines = 1)
                                Icon(BoxyIcons.Close, stringResource(R.string.rt_delete), Modifier.size(26.dp).clip(RoundedCornerShape(50)).clickable { onChange(r.copy(entries = r.entries - e)) }.padding(5.dp), tint = Boxy.colors.text2)
                            }
                        }
                    }
                } else {
                    BoxyTextField(text, { text = it }, stringResource(R.string.rt_text_hint), Modifier.fillMaxWidth().padding(horizontal = 18.dp).heightIn(min = 180.dp), singleLine = false)
                    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val bad = lines.filter { RoutingProfiles.kind(it) == null }
                    Text(
                        if (bad.isEmpty()) stringResource(R.string.rt_text_ok, lines.size) else stringResource(R.string.rt_text_bad, bad.take(3).joinToString(", ")),
                        Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (bad.isEmpty()) Boxy.colors.text2 else Tints.red.fg,
                        fontFamily = FontFamily.Default, fontSize = 12.sp,
                    )
                    Box(Modifier.padding(horizontal = 18.dp).padding(bottom = 14.dp)) {
                        SheetButton(stringResource(R.string.action_save), Boxy.colors.accent, Color.White, Modifier.fillMaxWidth()) {
                            if (bad.isEmpty()) { onChange(r.copy(entries = lines.distinct())); textMode = false }
                        }
                    }
                }
            }
        }
    }
    picker?.let { kind -> GeoPickSheet(kind, onPick = { add(it) }, onDismiss = { picker = null }) }
}

/** GeoSite / GeoIP tags of the device's Xray files (all enabled sources), searchable; a tap adds the tag. */
@Composable
private fun GeoPickSheet(kind: GeoBase.Kind, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var all by remember { mutableStateOf<List<GeoBase.Category>?>(null) }
    var multi by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val (primary, extras) = CoreTools.geoSources()
        multi = extras.isNotEmpty()
        all = runCatching { GeoBase.load("xray", primary, extras) }.getOrDefault(emptyList())
    }
    BoxySheet(if (kind == GeoBase.Kind.SITE) "GeoSite" else "GeoIP", stringResource(R.string.rt_geo_pick_sub), onDismiss) {
        BoxyTextField(query, { query = it.trim().lowercase() }, stringResource(R.string.geo_rule_search), Modifier.fillMaxWidth(),
            leading = { Icon(BoxyIcons.Search, null, tint = Boxy.colors.text2) })
        Spacer(Modifier.height(10.dp))
        val l = all
        if (l == null) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else com.skofqq.boxy.ui.tools.CategoryList(l.filter { it.kind == kind }, query, showSource = multi, kind = kind, primary = l.firstOrNull { it.primary }) { c ->
            val v = GeoRules.ruleValue("xray", c)
            onPick(v)
            com.skofqq.boxy.ui.components.BoxyToast.makeText(context, context.getString(R.string.rt_added, v), com.skofqq.boxy.ui.components.BoxyToast.LENGTH_SHORT).show()
        }
        Spacer(Modifier.height(8.dp))
    }
}
