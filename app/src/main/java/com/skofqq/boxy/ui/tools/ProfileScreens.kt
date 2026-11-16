package com.skofqq.boxy.ui.tools

import com.skofqq.boxy.ui.components.BoxyToast as Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skofqq.boxy.R
import com.skofqq.boxy.configs.BlockList
import com.skofqq.boxy.configs.ConfigEdits
import com.skofqq.boxy.configs.NodeGroup
import com.skofqq.boxy.net.Net
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.RootFiles
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.ConfigErrorDialog
import com.skofqq.boxy.ui.components.ConfirmDialog
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SettingsRow
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.components.SheetGroup
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import com.skofqq.boxy.util.Countries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------------------------
// Diff
// ---------------------------------------------------------------------------------------------------------------

/** One line of a diff: kept, added (only in the new file) or removed (only in the old one); null = skipped lines. */
data class DiffLine(val kind: Char, val text: String, val oldNo: Int, val newNo: Int)

object LineDiff {
    /** Cells of the LCS table at most; bigger pairs of files are refused rather than run out of memory. */
    private const val MAX_CELLS = 8_000_000L

    /** Line diff (longest common subsequence) with [context] unchanged lines around each change; null if too big. */
    fun diff(a: List<String>, b: List<String>, context: Int = 3): List<DiffLine?>? {
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val x = a.subList(pre, a.size - suf)
        val y = b.subList(pre, b.size - suf)
        if ((x.size + 1L) * (y.size + 1L) > MAX_CELLS) return null
        val w = y.size + 1
        val t = IntArray((x.size + 1) * w)
        for (i in x.size - 1 downTo 0) for (j in y.size - 1 downTo 0) {
            t[i * w + j] = if (x[i] == y[j]) t[(i + 1) * w + j + 1] + 1 else maxOf(t[(i + 1) * w + j], t[i * w + j + 1])
        }
        val all = ArrayList<DiffLine>(a.size + b.size)
        for (k in 0 until pre) all += DiffLine(' ', a[k], k + 1, k + 1)
        var i = 0
        var j = 0
        while (i < x.size || j < y.size) {
            when {
                i < x.size && j < y.size && x[i] == y[j] -> { all += DiffLine(' ', x[i], pre + i + 1, pre + j + 1); i++; j++ }
                // Removed lines before the added ones that replace them, as in a unified diff.
                i < x.size && (j == y.size || t[(i + 1) * w + j] >= t[i * w + j + 1]) -> { all += DiffLine('-', x[i], pre + i + 1, 0); i++ }
                else -> { all += DiffLine('+', y[j], 0, pre + j + 1); j++ }
            }
        }
        for (k in 0 until suf) all += DiffLine(' ', a[a.size - suf + k], a.size - suf + k + 1, b.size - suf + k + 1)
        // Keep the changes and their context; a run of skipped lines becomes one null.
        val keep = BooleanArray(all.size)
        all.forEachIndexed { n, l -> if (l.kind != ' ') for (m in maxOf(0, n - context)..minOf(all.lastIndex, n + context)) keep[m] = true }
        val out = ArrayList<DiffLine?>()
        all.forEachIndexed { n, l ->
            if (keep[n]) out += l else if (out.isNotEmpty() && out.last() != null) out += null
        }
        if (out.lastOrNull() == null && out.isNotEmpty()) out.removeAt(out.lastIndex)
        return out
    }
}

/** Two configs side by side as a unified diff: removed lines in red, added in green, with line numbers. */
@Composable
fun DiffScreen(contentPadding: PaddingValues, left: String, right: String, onBack: () -> Unit) {
    var lines by remember { mutableStateOf<List<DiffLine?>?>(null) }
    var state by remember { mutableStateOf(0) } // 0 loading, 1 ready, 2 too big, 3 read failed
    LaunchedEffect(left, right) {
        val a = RootFiles.read(left)
        val b = RootFiles.read(right)
        if (a == null || b == null) {
            state = 3
            return@LaunchedEffect
        }
        val d = withContext(Dispatchers.Default) { LineDiff.diff(a.replace("\r", "").lines(), b.replace("\r", "").lines()) }
        lines = d
        state = if (d == null) 2 else 1
    }
    val added = lines?.count { it?.kind == '+' } ?: 0
    val removed = lines?.count { it?.kind == '-' } ?: 0
    PinnedLazyPage(contentPadding, header = {
        SubPageHeader(
            stringResource(R.string.diff_title),
            "${displayName(left)} → ${displayName(right)}",
            onBack,
        )
    }, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        when (state) {
            0 -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            2, 3 -> item {
                Text(
                    stringResource(if (state == 2) R.string.diff_too_big else R.string.diff_read_failed),
                    Modifier.padding(24.dp),
                    color = Boxy.colors.text2,
                )
            }
            else -> {
                val list = lines.orEmpty()
                item {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.skofqq.boxy.ui.components.Badge("+$added", Tints.green)
                        com.skofqq.boxy.ui.components.Badge("−$removed", Tints.red)
                    }
                }
                if (list.isEmpty()) {
                    item { Text(stringResource(R.string.diff_same), Modifier.padding(24.dp), color = Boxy.colors.text2) }
                }
                list.forEachIndexed { n, l ->
                    item(key = n) {
                        if (l == null) {
                            Text("⋯", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = Boxy.colors.text2, fontFamily = FontFamily.Monospace)
                        } else {
                            DiffRow(l)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffRow(l: DiffLine) {
    val (bg, fg) = when (l.kind) {
        '+' -> Tints.green.bg to Tints.green.fg
        '-' -> Tints.red.bg to Tints.red.fg
        else -> Color.Transparent to Boxy.colors.text
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).background(bg).padding(horizontal = 6.dp, vertical = 1.dp)) {
        val no = if (l.kind == '+') l.newNo else l.oldNo
        Text(
            no.toString(),
            Modifier.widthIn(min = 34.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Boxy.colors.text2,
        )
        Text(l.kind.toString(), Modifier.width(14.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, color = fg)
        Text(l.text.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, color = fg)
    }
}

private fun displayName(path: String): String {
    val name = path.substringAfterLast('/')
    return if (path.contains("/.boxy/") && name.endsWith(".prev")) name.removeSuffix(".prev") + " (prev)" else name
}

// ---------------------------------------------------------------------------------------------------------------
// Profile tools: ad blocking and node groups of the active config
// ---------------------------------------------------------------------------------------------------------------

/** Ad / tracker blocking and Boxy's node groups for the active config of the current core. */
@Composable
fun ProfileToolsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onDiff: (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var core by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var groups by remember { mutableStateOf<List<NodeGroup>>(emptyList()) }
    var hasPrevious by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NodeGroup?>(null) }
    var creating by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<Pair<String, String>?>(null) } // output, text to save anyway
    var failedAfter by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    var deleting by remember { mutableStateOf<NodeGroup?>(null) }

    LaunchedEffect(reload) {
        val c = BoxModule.readSetting("bin_name") ?: "clash"
        val (dir, key) = coreConfig(c)
        core = dir
        val name = BoxModule.readSetting(key)
        val p = name?.let { "${BoxModule.BOX_DIR}/$dir/$it" }
        path = p
        text = p?.let { RootFiles.read(it) }
        groups = p?.let { ConfigEdits.groups(it) }.orEmpty()
        hasPrevious = p != null && RootFiles.exists(ConfigEdits.previousPath(p))
    }

    fun report(r: ConfigEdits.SaveResult, newText: String, after: suspend () -> Unit = {}) {
        when (r) {
            is ConfigEdits.SaveResult.CheckFailed -> { failed = r.output to newText; failedAfter = after }
            ConfigEdits.SaveResult.WriteFailed -> Toast.makeText(context, R.string.op_failed, Toast.LENGTH_SHORT).show()
            ConfigEdits.SaveResult.Applied -> Toast.makeText(context, R.string.profile_saved_restarted, Toast.LENGTH_SHORT).show()
            ConfigEdits.SaveResult.Saved -> Toast.makeText(context, R.string.profile_saved, Toast.LENGTH_SHORT).show()
        }
        if (r !is ConfigEdits.SaveResult.CheckFailed && r != ConfigEdits.SaveResult.WriteFailed) scope.launch { after(); reload++ }
    }

    fun write(newText: String, force: Boolean = false, after: suspend () -> Unit = {}) {
        val c = core ?: return
        val p = path ?: return
        busy = true
        scope.launch {
            val r = ConfigEdits.save(context, c, p, newText, force)
            busy = false
            report(r, newText, after)
        }
    }

    fun toggleBlock(list: BlockList, on: Boolean) {
        val c = core ?: return
        val t = text ?: return
        val next = ConfigEdits.setBlocked(c, t, list, on) ?: run {
            Toast.makeText(context, R.string.profile_parse_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val p = path ?: return
        val now = ConfigEdits.blocked(c, t).let { if (on) it + list else it - list }
        write(next) { ConfigEdits.saveBlockedState(p, now) }
    }

    fun saveGroup(group: NodeGroup?, previous: NodeGroup?) {
        val c = core ?: return
        val p = path ?: return
        val t = text ?: return
        busy = true
        scope.launch {
            val next: String
            var saved = group
            if (c == "sing-box") {
                val sources = group?.sources.orEmpty().mapNotNull { f -> RootFiles.read("${p.substringBeforeLast('/')}/$f")?.let { f to it } }.toMap()
                val r = ConfigEdits.singboxGroup(t, group, previous, sources)
                if (r == null) {
                    busy = false
                    Toast.makeText(context, R.string.profile_parse_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                next = r.first
                saved = r.second
            } else {
                next = ConfigEdits.mihomoGroup(t, group, previous)
            }
            val list = groups.filter { it.name != previous?.name && it.name != group?.name } + listOfNotNull(saved)
            busy = false
            write(next) { ConfigEdits.saveGroups(p, list) }
        }
    }

    val supported = core?.let { ConfigEdits.isSupported(it) } == true
    PinnedLazyPage(contentPadding, header = {
        SubPageHeader(
            stringResource(R.string.profile_tools_title),
            path?.substringAfterLast('/')?.let { stringResource(R.string.profile_tools_for, it) } ?: stringResource(R.string.profile_tools_row_sub),
            onBack,
        )
    }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when {
            core == null -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            !supported -> item { Text(stringResource(R.string.profile_tools_unsupported), Modifier.padding(24.dp), color = Boxy.colors.text2) }
            text == null -> item { Text(stringResource(R.string.profile_tools_no_config), Modifier.padding(24.dp), color = Boxy.colors.text2) }
            else -> {
                val c = core!!
                val blocked = ConfigEdits.blocked(c, text!!)
                item {
                    SectionCard(stringResource(R.string.block_title), stringResource(R.string.block_sub)) {
                        SwitchRow(BoxyIcons.Block, stringResource(R.string.block_ads), stringResource(R.string.block_ads_sub), BlockList.ADS in blocked, enabled = !busy) {
                            toggleBlock(BlockList.ADS, it)
                        }
                        SwitchRow(
                            BoxyIcons.Shield,
                            stringResource(R.string.block_trackers),
                            stringResource(R.string.block_trackers_sub),
                            BlockList.TRACKERS in blocked,
                            enabled = !busy,
                            showDivider = false,
                        ) { toggleBlock(BlockList.TRACKERS, it) }
                    }
                }
                item {
                    SectionCard(stringResource(R.string.groups_title), stringResource(if (c == "sing-box") R.string.groups_sub_singbox else R.string.groups_sub_mihomo)) {
                        groups.forEach { g ->
                            SettingsRow(BoxyIcons.Merge, g.name, groupSummary(g)) { editing = g }
                        }
                        SettingsRow(BoxyIcons.Add, stringResource(R.string.groups_add), null, showDivider = false) { creating = true }
                    }
                }
                if (hasPrevious) {
                    item {
                        SectionCard(null) {
                            SettingsRow(BoxyIcons.Compare, stringResource(R.string.profile_compare_prev), stringResource(R.string.profile_compare_prev_sub), showDivider = false) {
                                path?.let { onDiff(ConfigEdits.previousPath(it), it) }
                            }
                        }
                    }
                }
                if (busy) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
        }
    }

    if (creating || editing != null) {
        val c = core ?: "clash"
        GroupEditorSheet(
            core = c,
            path = path ?: "",
            text = text ?: "",
            initial = editing,
            taken = groups.map { it.name }.toSet() - setOfNotNull(editing?.name),
            onSave = { g -> val prev = editing; creating = false; editing = null; saveGroup(g, prev) },
            onDelete = editing?.let { g -> { editing = null; deleting = g } },
            onDismiss = { creating = false; editing = null },
        )
    }
    deleting?.let { g ->
        ConfirmDialog(
            stringResource(R.string.groups_delete_title, g.name),
            stringResource(R.string.groups_delete_body),
            stringResource(R.string.action_delete),
            danger = true,
            onConfirm = { deleting = null; saveGroup(null, g) },
            onDismiss = { deleting = null },
        )
    }
    failed?.let { (output, newText) ->
        ConfigErrorDialog(
            output,
            stringResource(R.string.profile_save_anyway),
            onProceed = { failed = null; write(newText, force = true, after = failedAfter ?: {}) },
            onDismiss = { failed = null },
        )
    }
}

@Composable
private fun groupSummary(g: NodeGroup): String {
    val parts = ArrayList<String>()
    parts += if (g.type == "select") stringResource(R.string.groups_type_select) else stringResource(R.string.groups_type_auto)
    if (g.countries.isNotEmpty()) parts += g.countries.joinToString(" ") { Countries.flag(it) }
    if (g.include.isNotBlank()) parts += "+/${g.include}/"
    if (g.exclude.isNotBlank()) parts += "−/${g.exclude}/"
    return parts.joinToString(" · ")
}

/** Nodes the group can choose from: the running core's (accurate) or what the config and its files hold. */
private suspend fun availableNodes(core: String, path: String, text: String, sources: List<String>): List<Pair<String, String>> {
    val dir = path.substringBeforeLast('/')
    if (core == "sing-box") {
        val own = ConfigEdits.singboxNodes(text).map { it to path.substringAfterLast('/') }
        val other = sources.flatMap { f -> RootFiles.read("$dir/$f")?.let { ConfigEdits.singboxNodes(it) }.orEmpty().map { it to f } }
        return own + other
    }
    val state = runCatching { BoxModule.state() }.getOrNull()
    if (state?.running == true) {
        val providers = Net.clashApi(state.core)?.let { Net.clashProviderList(it) }
        if (!providers.isNullOrEmpty()) return providers.flatMap { p -> p.nodes.map { it.name to p.name } }
    }
    // Stopped: the providers' downloaded files.
    return ConfigEdits.mihomoProviders(text).flatMap { name ->
        val file = Regex("(?ms)^\\s+${Regex.escape(name)}:.*?^\\s+path:\\s*[\"']?([^\"'\\n]+)").find(text)?.groupValues?.get(1)?.trim() ?: return@flatMap emptyList()
        val full = if (file.startsWith("/")) file else "$dir/${file.removePrefix("./")}"
        nodeNamesOf(RootFiles.read(full).orEmpty()).map { it to name }
    }
}

/** Node names in a provider file: YAML "name:" entries, or share links (the name after #), plain or base64. */
private fun nodeNamesOf(content: String): List<String> {
    val yaml = Regex("(?m)^\\s*-\\s*\\{?\\s*name:\\s*(\"[^\"]*\"|'[^']*'|[^,}\\n]+)").findAll(content).map { it.groupValues[1].trim().trim('"', '\'') }.toList()
    if (yaml.isNotEmpty()) return yaml
    val plain = if ("://" in content) content else runCatching { String(android.util.Base64.decode(content.trim(), android.util.Base64.DEFAULT)) }.getOrDefault("")
    return plain.lines().filter { "://" in it && '#' in it }.map { runCatching { java.net.URLDecoder.decode(it.substringAfterLast('#'), "UTF-8") }.getOrDefault(it.substringAfterLast('#')) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupEditorSheet(
    core: String,
    path: String,
    text: String,
    initial: NodeGroup?,
    taken: Set<String>,
    onSave: (NodeGroup) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: "url-test") }
    var sources by remember { mutableStateOf(initial?.sources.orEmpty().toSet()) }
    var countries by remember { mutableStateOf(initial?.countries.orEmpty().toSet()) }
    var include by remember { mutableStateOf(initial?.include ?: "") }
    var exclude by remember { mutableStateOf(initial?.exclude ?: "") }
    var addTo by remember { mutableStateOf(initial?.addTo.orEmpty().toSet()) }
    var nodes by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val appLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val singbox = core == "sing-box"
    val dir = path.substringBeforeLast('/')
    var otherProfiles by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        if (singbox) {
            otherProfiles = RootFiles.list(dir).filter { !it.isDir && !it.name.startsWith(".") && it.extension == "json" && it.path != path }.map { it.name }
        }
    }
    LaunchedEffect(sources) { nodes = availableNodes(core, path, text, sources.toList()) }
    val sourceChoices = if (singbox) otherProfiles else ConfigEdits.mihomoProviders(text)
    val selectors = remember(text) { ConfigEdits.selectorGroups(core, text).filter { it != initial?.name } }
    val existing = remember(text) { ConfigEdits.allNames(core, text) - setOfNotNull(initial?.name) - initial?.imported.orEmpty().toSet() }

    val draft = NodeGroup(name.trim(), type, sources.toList(), countries.toList(), include.trim(), exclude.trim(), addTo.toList(), initial?.imported.orEmpty())
    // mihomo's "use" narrows the nodes to the chosen providers; sing-box shows its own nodes plus the copied ones.
    val pool = nodes.orEmpty().filter { singbox || sources.isEmpty() || it.second in sources }
    val countryCounts = pool.mapNotNull { Countries.of(it.first) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
    val matching = pool.filter { draft.matches(it.first) }

    BoxySheet(stringResource(if (initial == null) R.string.groups_add else R.string.groups_edit), stringResource(R.string.groups_editor_sub), onDismiss) {
        BoxyTextField(name, { name = it; error = null }, stringResource(R.string.groups_name), Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Label(stringResource(R.string.groups_type))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.groups_type_auto), type == "url-test") { type = "url-test" }
            Chip(stringResource(R.string.groups_type_select), type == "select") { type = "select" }
        }
        if (sourceChoices.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Label(stringResource(if (singbox) R.string.groups_sources_singbox else R.string.groups_sources_mihomo))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!singbox) Chip(stringResource(R.string.groups_sources_all), sources.isEmpty()) { sources = emptySet() }
                sourceChoices.forEach { s -> Chip(s, s in sources) { sources = if (s in sources) sources - s else sources + s } }
            }
        }
        Spacer(Modifier.height(12.dp))
        Label(stringResource(R.string.groups_countries))
        if (nodes == null) {
            CircularProgressIndicator(Modifier.padding(8.dp).width(20.dp).height(20.dp), strokeWidth = 2.dp)
        } else if (countryCounts.isEmpty()) {
            Text(stringResource(R.string.groups_no_countries), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                countryCounts.forEach { (code, n) ->
                    Chip("${Countries.flag(code)} ${Countries.displayName(code, appLocale)} · $n", code in countries) {
                        countries = if (code in countries) countries - code else countries + code
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BoxyTextField(include, { include = it; error = null }, stringResource(R.string.groups_include), Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        BoxyTextField(exclude, { exclude = it; error = null }, stringResource(R.string.groups_exclude), Modifier.fillMaxWidth())
        if (selectors.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Label(stringResource(R.string.groups_add_to))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                selectors.forEach { s -> Chip(s, s in addTo) { addTo = if (s in addTo) addTo - s else addTo + s } }
            }
        }
        Spacer(Modifier.height(12.dp))
        SheetGroup {
            Text(
                stringResource(R.string.groups_preview, matching.size, pool.size),
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleSmall,
                color = if (matching.isEmpty()) Tints.amber.fg else Boxy.colors.text,
            )
            matching.take(12).forEach { (n, _) ->
                Text(
                    Countries.withFlag(n),
                    Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Boxy.colors.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (matching.size > 12) Text("…", Modifier.padding(horizontal = 16.dp), color = Boxy.colors.text2)
            if (!singbox) Text(stringResource(R.string.groups_preview_note_mihomo), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
        }
        error?.let { Text(it, Modifier.padding(top = 8.dp), color = Tints.red.fg, style = MaterialTheme.typography.bodySmall) }
        val nameEmpty = stringResource(R.string.groups_name_empty)
        val nameTaken = stringResource(R.string.groups_name_taken)
        val badRegex = stringResource(R.string.groups_bad_regex)
        SheetButtons(
            stringResource(R.string.action_save),
            {
                error = when {
                    draft.name.isEmpty() -> nameEmpty
                    draft.name in taken || draft.name in existing -> nameTaken
                    listOf(draft.include, draft.exclude).any { it.isNotBlank() && runCatching { Regex(it.replace("(?I)", "(?i)")) }.isFailure } -> badRegex
                    else -> null
                }
                if (error == null) onSave(draft)
            },
            if (onDelete != null) stringResource(R.string.action_delete) else null,
            { onDelete?.invoke() },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.labelLarge, color = Boxy.colors.text2, fontWeight = FontWeight.Medium)
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Boxy.colors.accent.copy(alpha = 0.18f) else Boxy.colors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Boxy.colors.accent else Boxy.colors.text,
        maxLines = 1,
    )
}
