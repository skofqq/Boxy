package com.skofqq.boxy.ui.proxies

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skofqq.boxy.R
import com.skofqq.boxy.net.XrayApi
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CheckResult
import com.skofqq.boxy.root.ConfigCheck
import com.skofqq.boxy.root.CoreTools
import com.skofqq.boxy.ui.components.InputDialog
import com.skofqq.boxy.ui.components.NoticeCard
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SettingsRow
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * Xray in the panel's tabs. Xray has no clash-style API, so each tab shows what Xray itself offers:
 * Connections = its access log (no live list, no closing), Logs = its own log file, Rules = routing rules of the
 * merged config, Settings = the few run-time options it has (log level, the observatory's probing).
 */

/** Connections: Xray keeps no list of open connections; its access log says what went where. */
@Composable
internal fun XrayConnectionsTab(bottom: Dp) {
    XrayAccessLog(bottom, onBack = null)
}

private val XRAY_LEVELS = listOf("Error", "Warning", "Info", "Debug")

/** Logs: the tail of Xray's own log, read every 2 s while the tab is open, newest on top. */
@Composable
internal fun XrayLogsTab(bottom: Dp, reloadKey: Int) {
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var level by rememberSaveable { mutableStateOf("Warning") }
    var query by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(reloadKey, paused) {
        if (paused) return@LaunchedEffect
        val path = runCatching { XrayApi.setup().log?.optString("error") }.getOrNull()?.takeIf { it.isNotBlank() && it != "none" }
            ?: "${BoxModule.BOX_DIR}/run/xray.log"
        while (true) {
            lines = withContext(Dispatchers.IO) {
                com.topjohnwu.superuser.Shell.cmd("tail -n 400 '${path.replace("'", "")}' 2>/dev/null").exec().out
            }.reversed()
            delay(2000)
        }
    }
    val shown = XRAY_LEVELS.take(XRAY_LEVELS.indexOf(level) + 1)
    PinnedLazyPage(PaddingValues(bottom = bottom + 16.dp), header = {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                XRAY_LEVELS.forEach { l -> Chip(l.lowercase(), l == level) { level = l } }
                Chip(stringResource(if (paused) R.string.xray_logs_resume else R.string.xray_logs_pause), paused) { paused = !paused }
            }
            com.skofqq.boxy.ui.components.BoxyTextField(query, { query = it }, stringResource(R.string.xray_access_search), Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val list = lines
        if (list == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@PinnedLazyPage
        }
        // Lines without a level (start-up banner) go with Info.
        val filtered = list.filter { line ->
            val lv = XRAY_LEVELS.firstOrNull { "[$it]" in line } ?: "Info"
            lv in shown && (query.isBlank() || query.lowercase() in line.lowercase())
        }
        if (filtered.isEmpty()) item { EmptyLine(stringResource(R.string.xray_logs_empty)) }
        filtered.take(400).forEachIndexed { i, line ->
            item(key = "l$i") {
                val color = when {
                    "[Error]" in line -> Tints.red.fg
                    "[Warning]" in line -> Tints.amber.fg
                    else -> Boxy.colors.text
                }
                Text(
                    line,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(Boxy.colors.card).padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Boxy.colors.accent.copy(alpha = 0.18f) else Boxy.colors.card)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Boxy.colors.accent else Boxy.colors.text,
    )
}

/** One routing rule as read from the merged config. */
private data class XrayRule(val index: Int, val match: String, val target: String, val balancer: Boolean)

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

/** Rules: the routing rules of the config Xray runs, in order, with where each one sends traffic. */
@Composable
internal fun XrayRulesTab(bottom: Dp, reloadKey: Int) {
    var rules by remember { mutableStateOf<List<XrayRule>?>(null) }
    var query by remember { mutableStateOf("") }
    val anyText = stringResource(R.string.xray_rule_any)
    LaunchedEffect(reloadKey) {
        rules = withContext(Dispatchers.IO) {
            val out = ArrayList<XrayRule>()
            for (f in CoreTools.xrayFiles()) {
                val o = BoxModule.readFile(f)?.let { t -> runCatching { JSONObject(t) }.getOrNull() } ?: continue
                val a = o.optJSONObject("routing")?.optJSONArray("rules") ?: continue
                for (i in 0 until a.length()) {
                    val r = a.optJSONObject(i) ?: continue
                    val parts = buildList {
                        r.optJSONArray("domain").strings().let { if (it.isNotEmpty()) add(it.take(3).joinToString(", ") + if (it.size > 3) " +${it.size - 3}" else "") }
                        r.optJSONArray("ip").strings().let { if (it.isNotEmpty()) add(it.take(3).joinToString(", ") + if (it.size > 3) " +${it.size - 3}" else "") }
                        r.optString("port").takeIf { it.isNotBlank() }?.let { add("port $it") }
                        r.optString("network").takeIf { it.isNotBlank() }?.let { add(it) }
                        r.optJSONArray("protocol").strings().let { if (it.isNotEmpty()) add(it.joinToString(", ")) }
                        r.optJSONArray("inboundTag").strings().let { if (it.isNotEmpty()) add("in: " + it.joinToString(", ")) }
                    }
                    val bal = r.optString("balancerTag")
                    out += XrayRule(out.size + 1, parts.joinToString("  ·  ").ifBlank { anyText }, bal.ifBlank { r.optString("outboundTag") }, bal.isNotBlank())
                }
            }
            out
        }
    }
    PinnedLazyPage(PaddingValues(bottom = bottom + 16.dp), header = {
        com.skofqq.boxy.ui.components.BoxyTextField(query, { query = it }, stringResource(R.string.xray_access_search), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val list = rules
        if (list == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@PinnedLazyPage
        }
        val filtered = list.filter { query.isBlank() || query.lowercase() in "${it.match} ${it.target}".lowercase() }
        if (filtered.isEmpty()) item { EmptyLine(stringResource(R.string.xray_logs_empty)) }
        filtered.forEach { r ->
            item(key = "r${r.index}") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp)).background(Boxy.colors.card).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${r.index}", style = MaterialTheme.typography.labelMedium, color = Boxy.colors.text2, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.match, style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            XrayApi.displayName(r.target),
                            Modifier.clip(RoundedCornerShape(50)).background((if (r.balancer) Boxy.colors.accent else Tints.gray.fg).copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (r.balancer) Boxy.colors.accent else Boxy.colors.text,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Settings: only what Xray has at run time — its log level and how the observatory probes servers. Each value is
 * changed in the file that sets it (when that file is plain JSON), checked with the core, and Xray restarts.
 */
@Composable
internal fun XraySettingsTab(bottom: Dp, reloadKey: Int, running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    // key path → (file, value); null file = not set anywhere
    var values by remember { mutableStateOf<Map<String, Pair<String?, String>>?>(null) }
    var edit by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reloadKey, reload) {
        values = withContext(Dispatchers.IO) {
            val res = LinkedHashMap<String, Pair<String?, String>>()
            res["log.loglevel"] = null to "warning"
            for (f in CoreTools.xrayFiles()) {
                val o = BoxModule.readFile(f)?.let { t -> runCatching { JSONObject(t) }.getOrNull() } ?: continue
                o.optJSONObject("log")?.optString("loglevel")?.takeIf { it.isNotBlank() }?.let { res["log.loglevel"] = f to it }
                o.optJSONObject("burstObservatory")?.optJSONObject("pingConfig")?.let { p ->
                    res["burstObservatory.pingConfig.interval"] = f to p.optString("interval")
                    res["burstObservatory.pingConfig.destination"] = f to p.optString("destination")
                }
                o.optJSONObject("observatory")?.let { p ->
                    res["observatory.probeInterval"] = f to p.optString("probeInterval")
                    res["observatory.probeUrl"] = f to p.optString("probeUrl")
                }
            }
            res
        }
    }

    fun save(key: String, value: String) {
        val file = values?.get(key)?.first
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                // Not set anywhere yet (the log level): it goes into the active config.
                val target = file ?: CoreTools.activeConfig("xray")
                val o = BoxModule.readFile(target)?.let { t -> runCatching { JSONObject(t) }.getOrNull() } ?: return@withContext false
                val path = key.split('.')
                var cur = o
                for (p in path.dropLast(1)) cur = cur.optJSONObject(p) ?: JSONObject().also { cur.put(p, it) }
                cur.put(path.last(), value)
                val old = BoxModule.readFile(target)
                if (!BoxModule.writeFile(target, o.toString(2) + "\n")) return@withContext false
                if (ConfigCheck.check("xray", CoreTools.activeConfig("xray")) is CheckResult.Failed) {
                    old?.let { BoxModule.writeFile(target, it) }
                    return@withContext false
                }
                if (running) BoxModule.restart()
                true
            }
            Toast.makeText(context, if (ok) R.string.xray_settings_saved else R.string.xray_settings_failed, Toast.LENGTH_SHORT).show()
            reload++
        }
    }

    edit?.let { key ->
        val cur = values?.get(key)?.second.orEmpty()
        InputDialog(
            title = stringResource(labelOf(key)),
            fields = listOf(stringResource(labelOf(key)) to cur),
            message = if (key == "log.loglevel") "debug · info · warning · error · none" else null,
            validate = { v -> if (key == "log.loglevel" && v[0].trim() !in setOf("debug", "info", "warning", "error", "none")) "debug / info / warning / error / none" else null },
            onConfirm = { v -> edit = null; save(key, v[0].trim()) },
            onDismiss = { edit = null },
        )
    }

    PinnedLazyPage(PaddingValues(bottom = bottom + 16.dp), header = {}, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val v = values
        if (v == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@PinnedLazyPage
        }
        item {
            SectionCard(stringResource(R.string.xray_settings_title), stringResource(R.string.xray_settings_note)) {
                val keys = v.keys.toList()
                keys.forEachIndexed { i, k ->
                    val (file, value) = v.getValue(k)
                    SettingsRow(
                        if (k.startsWith("log")) BoxyIcons.Description else BoxyIcons.Speed,
                        stringResource(labelOf(k)),
                        value.ifBlank { "—" } + (file?.let { "  ·  " + it.substringAfterLast('/') } ?: ""),
                        showDivider = i < keys.size - 1,
                    ) { edit = k }
                }
            }
        }
        item { NoticeCard(stringResource(R.string.xray_settings_more_title), stringResource(R.string.xray_settings_more), Modifier.padding(horizontal = 2.dp)) }
    }
}

private fun labelOf(key: String): Int = when (key) {
    "log.loglevel" -> R.string.xray_settings_loglevel
    "burstObservatory.pingConfig.interval", "observatory.probeInterval" -> R.string.xray_settings_probe_interval
    else -> R.string.xray_settings_probe_url
}
