package com.skofqq.boxy.ui.proxies

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateMapOf
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CoreTools
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Icons of proxy groups for cores whose configs have no icon field (Xray balancers, sing-box selectors): taken from
 * the mihomo configs' proxy-groups (`icon:`) by group name, so the same groups look the same in every panel, and
 * set by hand (long press on a group). Names are matched without emoji, punctuation, spaces and case.
 * Kept in the app's files (group_icons.json: "user" and "auto" maps); the images are cached by [ProxyIcons].
 */
object GroupIcons {
    private val auto = mutableStateMapOf<String, String>()
    private val user = mutableStateMapOf<String, String>()
    /** From the panel's Xray template ("boxy": {"icons": {group: url}}), saved when a config is loaded from it. */
    private val panel = mutableStateMapOf<String, String>()
    private var loaded = false

    /** "⚡️ Fastest" and "Fastest", "RU сайты" and "ru-сайты" are one group. */
    fun key(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /** The icon URL for group [name]: the one set by hand, else the one from the mihomo config. */
    fun icon(name: String): String? = key(name).let { user[it] ?: panel[it] ?: auto[it] }

    fun isCustom(name: String) = key(name) in user

    private fun file(context: Context) = File(context.filesDir, "group_icons.json")

    /** Reads the saved maps, then the mihomo configs' group icons (once per run). */
    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        loaded = true
        runCatching {
            val o = JSONObject(file(context).readText())
            o.optJSONObject("user")?.let { u -> u.keys().forEach { k -> user[k] = u.getString(k) } }
            o.optJSONObject("auto")?.let { a -> a.keys().forEach { k -> auto[k] = a.getString(k) } }
            o.optJSONObject("panel")?.let { a -> a.keys().forEach { k -> panel[k] = a.getString(k) } }
        }
        val fresh = fromMihomo()
        if (fresh.isNotEmpty()) {
            auto.clear()
            auto.putAll(fresh)
            save(context)
        }
    }

    /** The icons of a panel template's "boxy" key ({"icons": {group: url}}): they replace the previous panel's. */
    suspend fun setPanel(context: Context, boxy: JSONObject?) = withContext(Dispatchers.IO) {
        val icons = boxy?.optJSONObject("icons") ?: return@withContext
        load(context)
        panel.clear()
        icons.keys().forEach { k -> icons.optString(k).takeIf { it.startsWith("http") }?.let { panel[key(k)] = it } }
        save(context)
    }

    suspend fun set(context: Context, name: String, url: String?) = withContext(Dispatchers.IO) {
        val k = key(name)
        if (url.isNullOrBlank()) user.remove(k) else user[k] = url.trim()
        save(context)
    }

    private fun save(context: Context) = runCatching {
        val o = JSONObject().put("user", JSONObject(user.toMap())).put("auto", JSONObject(auto.toMap())).put("panel", JSONObject(panel.toMap()))
        file(context).writeText(o.toString())
    }

    /** name → icon from the active mihomo config first, then the other *.yaml in clash/ (the first one wins). */
    private suspend fun fromMihomo(): Map<String, String> {
        val dir = "${BoxModule.BOX_DIR}/clash"
        val active = runCatching { CoreTools.activeConfig("clash") }.getOrNull()
        val files = listOfNotNull(active) +
            Shell.cmd("ls $dir/*.yaml $dir/*.yml 2>/dev/null").exec().out.filter { it.isNotBlank() && it != active }
        val out = LinkedHashMap<String, String>()
        for (f in files) {
            val text = BoxModule.readFile(f) ?: continue
            if (!text.contains("icon:")) continue
            parse(text).forEach { (n, u) -> out.putIfAbsent(key(n), u) }
        }
        return out
    }

    private val NAME = Regex("""^\s*(?:-\s+)?name:\s*(.+?)\s*$""")
    private val ICON = Regex("""^\s*(?:-\s+)?icon:\s*(.+?)\s*$""")
    private val FLOW = Regex("""\{[^{}]*\}""")

    /** (group name, icon URL) pairs of proxy-groups, block or flow style. */
    internal fun parse(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val start = Regex("(?m)^proxy-groups:").find(text) ?: return out
        val section = text.substring(start.range.last + 1).let { s -> Regex("(?m)^[A-Za-z][\\w-]*:").find(s)?.let { s.substring(0, it.range.first) } ?: s }
        var name: String? = null
        var icon: String? = null
        fun flush() {
            if (name != null && icon != null) out += name!! to icon!!
            name = null
            icon = null
        }
        // Items start with "- " at one indent; deeper ones ("proxies:" lists) belong to the item.
        var itemIndent = -1
        for (line in section.lines()) {
            val indent = line.length - line.trimStart().length
            if (line.trimStart().startsWith("- ") && (itemIndent < 0 || indent <= itemIndent)) {
                itemIndent = indent
                flush()
                FLOW.find(line)?.let { f ->
                    val n = Regex("""name:\s*("[^"]*"|'[^']*'|[^,}]+)""").find(f.value)?.groupValues?.get(1)
                    val i = Regex("""icon:\s*("[^"]*"|'[^']*'|[^,}]+)""").find(f.value)?.groupValues?.get(1)
                    if (n != null && i != null) out += unquote(n) to unquote(i)
                    continue
                }
            }
            // The item's own keys only: on its "- " line or one level in.
            if (itemIndent >= 0 && indent > itemIndent + 2 && !line.trimStart().startsWith("- ")) continue
            if (itemIndent >= 0 && indent > itemIndent && line.trimStart().startsWith("- ")) continue
            NAME.find(line)?.let { if (name == null) name = unquote(it.groupValues[1]) }
            ICON.find(line)?.let { icon = unquote(it.groupValues[1]) }
        }
        flush()
        return out.filter { it.second.startsWith("http") }
    }

    private fun unquote(s: String) = s.trim().removeSurrounding("\"").removeSurrounding("'").trim()
}

/** A group's icon, round, nothing while it loads or when there is none. */
@androidx.compose.runtime.Composable
internal fun GroupIconImage(url: String?, size: androidx.compose.ui.unit.Dp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val img by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, url) { value = url?.let { ProxyIcons.load(context, it) } }
    img?.let {
        androidx.compose.foundation.Image(
            it, null,
            androidx.compose.ui.Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(50)),
        )
    }
}

/** Long press on a group: its icon's URL; empty gives back the one from the mihomo config. */
@androidx.compose.runtime.Composable
internal fun GroupIconDialog(name: String, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    com.skofqq.boxy.ui.components.InputDialog(
        title = androidx.compose.ui.res.stringResource(com.skofqq.boxy.R.string.group_icon_title, name),
        fields = listOf(androidx.compose.ui.res.stringResource(com.skofqq.boxy.R.string.group_icon_hint) to (if (GroupIcons.isCustom(name)) GroupIcons.icon(name).orEmpty() else "")),
        message = androidx.compose.ui.res.stringResource(com.skofqq.boxy.R.string.group_icon_message),
        validate = { v -> if (v[0].isBlank() || v[0].trim().startsWith("http")) null else "http(s)://…" },
        onConfirm = { v ->
            scope.launch {
                GroupIcons.set(context, name, v[0])
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}
