package com.skofqq.boxy.configs

import android.content.Context
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CheckResult
import com.skofqq.boxy.root.ConfigCheck
import com.skofqq.boxy.root.RootFiles
import com.skofqq.boxy.service.BoxControl
import com.skofqq.boxy.util.Countries
import org.json.JSONArray
import org.json.JSONObject

/** Ad / tracker lists the app can switch on in a config (geosite rule-sets from MetaCubeX meta-rules-dat). */
enum class BlockList(val tag: String, val geosite: String) {
    ADS("boxy-ads", "category-ads-all"),
    TRACKERS("boxy-trackers", "tracker"),
    ;

    val mrsUrl get() = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo/geosite/$geosite.mrs"
    val srsUrl get() = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/geosite/$geosite.srs"
}

/**
 * A node group Boxy keeps in a config: nodes of several subscriptions merged into one group and filtered by
 * country and regular expressions. mihomo filters by itself on every subscription update (`include-all-providers`
 * or `use` with `filter` / `exclude-filter`); sing-box has no filters, so the matching nodes are written out
 * when the group is saved, and nodes of other profiles can be copied in.
 */
data class NodeGroup(
    val name: String,
    /** select / url-test (mihomo names; sing-box gets selector / urltest). */
    val type: String = "url-test",
    /** mihomo: proxy-providers to take nodes from, empty = all. sing-box: other profiles whose nodes are copied in. */
    val sources: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val include: String = "",
    val exclude: String = "",
    /** Selector groups the new group is added to, so it can be picked there. */
    val addTo: List<String> = emptyList(),
    /** sing-box: tags of the nodes copied in from [sources] by the last save (removed again on the next one). */
    val imported: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("type", type).put("sources", JSONArray(sources)).put("countries", JSONArray(countries))
        .put("include", include).put("exclude", exclude).put("addTo", JSONArray(addTo)).put("imported", JSONArray(imported))

    /** The filter as one mihomo (regexp2) expression: country AND include. */
    fun mihomoFilter(): String {
        val parts = listOfNotNull(Countries.regexFor(countries).ifEmpty { null }, include.ifBlank { null })
        return when (parts.size) {
            0 -> ""
            1 -> parts[0]
            else -> "^" + parts.joinToString("") { "(?=.*?(?:$it))" } + ".*$"
        }
    }

    /** Whether a node name passes the filter (the same rules, evaluated here for sing-box and the preview). */
    fun matches(node: String): Boolean {
        if (countries.isNotEmpty() && Countries.of(node) !in countries) return false
        if (include.isNotBlank() && runCatching { Regex(stripFlags(include)).containsMatchIn(node) }.getOrDefault(false).not()) return false
        if (exclude.isNotBlank() && runCatching { Regex(stripFlags(exclude)).containsMatchIn(node) }.getOrDefault(false)) return false
        return true
    }

    companion object {
        fun fromJson(o: JSONObject) = NodeGroup(
            o.optString("name"), o.optString("type", "url-test"), o.optJSONArray("sources").strings(), o.optJSONArray("countries").strings(),
            o.optString("include"), o.optString("exclude"), o.optJSONArray("addTo").strings(), o.optJSONArray("imported").strings(),
        )

        /** Java regex has no "(?I)": the Go / regexp2 spellings people copy are made case-insensitive here. */
        private fun stripFlags(r: String) = r.replace("(?I)", "(?i)")
    }
}

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

/** Kinds of nodes in a sing-box config (everything else is a group or a built-in). */
private val SINGBOX_NODE_TYPES = setOf(
    "socks", "http", "shadowsocks", "vmess", "trojan", "wireguard", "hysteria", "vless", "shadowtls", "tuic", "hysteria2", "anytls", "ssh", "tor",
)

/**
 * Text edits of mihomo (YAML) and sing-box (JSON) configs that keep the rest of the file as it is: YAML is edited
 * line by line (comments and order stay), JSON is re-written with two-space indentation.
 */
object ConfigEdits {
    private const val MARK = "# boxy"

    fun isSupported(core: String) = core == "clash" || core == "sing-box"

    // ---- Ad / tracker blocking ----

    fun blocked(core: String, text: String): Set<BlockList> = BlockList.entries.filter { l ->
        if (core == "sing-box") {
            singboxJson(text)?.optJSONObject("route")?.optJSONArray("rule_set")?.let { arr -> (0 until arr.length()).any { arr.optJSONObject(it)?.optString("tag") == l.tag } } == true
        } else {
            Regex("^\\s*${l.tag}:", RegexOption.MULTILINE).containsMatchIn(text)
        }
    }.toSet()

    /** The config with the list switched on or off; null when the file could not be parsed. */
    fun setBlocked(core: String, text: String, list: BlockList, on: Boolean): String? =
        if (core == "sing-box") singboxBlock(text, list, on) else mihomoBlock(text, list, on)

    private fun mihomoBlock(text: String, list: BlockList, on: Boolean): String {
        var lines = text.lines().toMutableList()
        // Off (and before adding again): drop the provider line and the rule.
        lines.removeAll { l -> Regex("^\\s*${list.tag}:\\s*\\{").containsMatchIn(l) || Regex("^\\s*-\\s*RULE-SET\\s*,\\s*${list.tag}\\s*,").containsMatchIn(l) }
        if (on) {
            val provider = "${list.tag}: {type: http, behavior: domain, format: mrs, url: \"${list.mrsUrl}\", path: ./rule-sets/${list.tag}.mrs, interval: 86400} $MARK"
            lines = insertIntoMap(lines, "rule-providers", provider)
            lines = insertIntoList(lines, "rules", "RULE-SET,${list.tag},REJECT $MARK", first = true)
        }
        return lines.joinToString("\n")
    }

    private fun singboxBlock(text: String, list: BlockList, on: Boolean): String? {
        val root = singboxJson(text) ?: return null
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val sets = route.optJSONArray("rule_set") ?: JSONArray().also { route.put("rule_set", it) }
        val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }
        removeWhere(sets) { it.optString("tag") == list.tag }
        removeWhere(rules) { r -> r.optString("rule_set") == list.tag || r.optJSONArray("rule_set")?.strings() == listOf(list.tag) }
        if (on) {
            sets.put(JSONObject().put("type", "remote").put("tag", list.tag).put("format", "binary").put("url", list.srsUrl).put("update_interval", "1d"))
            // After sniffing and DNS hijacking: the domain is known only once the connection was sniffed.
            var at = 0
            while (at < rules.length() && rules.optJSONObject(at)?.optString("action") in setOf("sniff", "hijack-dns", "resolve")) at++
            insertAt(rules, at, JSONObject().put("rule_set", list.tag).put("action", "reject"))
        }
        if (sets.length() == 0) route.remove("rule_set")
        if (rules.length() == 0) route.remove("rules")
        return singboxText(root)
    }

    // ---- Node groups ----

    /** Groups Boxy saved for this config (kept beside it in .boxy/, since sing-box allows no comments). */
    suspend fun groups(path: String): List<NodeGroup> = runCatching {
        val arr = JSONObject(RootFiles.read(sidecar(path)) ?: return emptyList()).optJSONArray("groups") ?: return emptyList()
        (0 until arr.length()).map { NodeGroup.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    suspend fun saveGroups(path: String, groups: List<NodeGroup>): Boolean {
        val dir = sidecar(path).substringBeforeLast('/')
        RootFiles.createDir(dir)
        val root = runCatching { JSONObject(RootFiles.read(sidecar(path)) ?: "{}") }.getOrDefault(JSONObject())
        return RootFiles.write(sidecar(path), root.put("groups", JSONArray(groups.map { it.toJson() })).toString(2))
    }

    /** Lists switched on for this config in Boxy, re-applied when a subscription replaces the file. */
    suspend fun blockedState(path: String): Set<BlockList> = runCatching {
        val arr = JSONObject(RootFiles.read(sidecar(path)) ?: return emptySet()).optJSONArray("blocked") ?: return emptySet()
        arr.strings().mapNotNull { n -> BlockList.entries.firstOrNull { it.name == n } }.toSet()
    }.getOrDefault(emptySet())

    suspend fun saveBlockedState(path: String, blocked: Set<BlockList>): Boolean {
        val root = runCatching { JSONObject(RootFiles.read(sidecar(path)) ?: "{}") }.getOrDefault(JSONObject())
        RootFiles.createDir(sidecar(path).substringBeforeLast('/'))
        return RootFiles.write(sidecar(path), root.put("blocked", JSONArray(blocked.map { it.name })).toString(2))
    }

    /**
     * Boxy's blocking and node groups of [path] written again into [text], a new version of that file (a subscription
     * update replaces the whole config). Returns [text] unchanged when nothing was set or it cannot be parsed.
     */
    suspend fun reapply(core: String, path: String, text: String): String {
        if (!isSupported(core)) return text
        var out = text
        blockedState(path).forEach { l -> setBlocked(core, out, l, true)?.let { out = it } }
        val groups = groups(path)
        if (groups.isEmpty()) return out
        val dir = path.substringBeforeLast('/')
        val saved = ArrayList<NodeGroup>()
        for (g in groups) {
            if (core == "sing-box") {
                val sources = g.sources.mapNotNull { f -> RootFiles.read("$dir/$f")?.let { f to it } }.toMap()
                val r = singboxGroup(out, g, g, sources) ?: return out
                out = r.first
                saved += r.second ?: g
            } else {
                out = mihomoGroup(out, g, g)
                saved += g
            }
        }
        if (core == "sing-box") saveGroups(path, saved)
        return out
    }

    private fun sidecar(path: String) = "${path.substringBeforeLast('/')}/.boxy/${path.substringAfterLast('/')}.groups.json"

    /** proxy-providers of a mihomo config. */
    fun mihomoProviders(text: String): List<String> = mapKeys(text.lines(), "proxy-providers")

    /** Groups a selector can be added to: mihomo select groups / sing-box selectors. */
    fun selectorGroups(core: String, text: String): List<String> = if (core == "sing-box") {
        singboxOutbounds(singboxJson(text)).filter { it.optString("type") == "selector" }.map { it.optString("tag") }
    } else {
        Regex("^\\s*-\\s*(?:\\{\\s*)?name:\\s*(.+?)\\s*$", RegexOption.MULTILINE).let { re ->
            val lines = text.lines()
            val (start, end) = block(lines, "proxy-groups") ?: return emptyList()
            val out = ArrayList<String>()
            var name: String? = null
            for (i in start + 1 until end) {
                val l = lines[i]
                re.find(l)?.let { m -> name = unquote(m.groupValues[1].substringBefore(',').substringBefore(" #")) }
                if (Regex("^\\s*type:\\s*select\\b").containsMatchIn(l) || Regex("^\\s*-\\s*\\{.*type:\\s*select\\b").containsMatchIn(l)) name?.let { out += it }
            }
            out.distinct()
        }
    }

    /** Every outbound / proxy / group name of the config: a new group must not take one of them. */
    fun allNames(core: String, text: String): Set<String> = if (core == "sing-box") {
        singboxOutbounds(singboxJson(text)).map { it.optString("tag") }.toSet()
    } else {
        Regex("^\\s*-\\s*\\{?\\s*name:\\s*(\"[^\"]*\"|'[^']*'|[^,}#\\n]+)", RegexOption.MULTILINE).findAll(text)
            .map { unquote(it.groupValues[1]) }.toSet()
    }

    /** Nodes of a sing-box config, for the preview. */
    fun singboxNodes(text: String): List<String> =
        singboxOutbounds(singboxJson(text)).filter { it.optString("type") in SINGBOX_NODE_TYPES }.map { it.optString("tag") }

    /**
     * Writes [group] into a mihomo config (replacing an older version of it, [previous] when it was renamed);
     * null to remove it.
     */
    fun mihomoGroup(text: String, group: NodeGroup?, previous: NodeGroup?): String {
        var lines = text.lines().toMutableList()
        listOfNotNull(previous, group).map { it.name }.distinct().forEach { name ->
            lines.removeAll { it.trimStart().startsWith("- {name: ${quote(name)},") && it.trimEnd().endsWith(MARK) }
            previous?.addTo?.forEach { lines = removeMember(lines, it, name) }
        }
        if (group != null) {
            val fields = mutableListOf("name: ${quote(group.name)}", "type: ${group.type}")
            if (group.sources.isEmpty()) fields += "include-all-providers: true" else fields += "use: [${group.sources.joinToString(", ") { quote(it) }}]"
            group.mihomoFilter().takeIf { it.isNotEmpty() }?.let { fields += "filter: ${quote(it)}" }
            group.exclude.takeIf { it.isNotBlank() }?.let { fields += "exclude-filter: ${quote(it)}" }
            if (group.type == "url-test") fields += listOf("url: \"https://www.gstatic.com/generate_204\"", "interval: 300", "tolerance: 50", "lazy: true")
            lines = insertIntoList(lines, "proxy-groups", "{${fields.joinToString(", ")}} $MARK", first = false)
            group.addTo.forEach { lines = addMember(lines, it, group.name) }
        }
        return lines.joinToString("\n")
    }

    /**
     * Writes [group] into a sing-box config: copies the nodes of [sourceTexts] (other profiles) in, picks the
     * nodes that pass the filter and adds the group. Returns the text and the group with the copied tags.
     */
    fun singboxGroup(text: String, group: NodeGroup?, previous: NodeGroup?, sourceTexts: Map<String, String>): Pair<String, NodeGroup?>? {
        val root = singboxJson(text) ?: return null
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        val old = listOfNotNull(previous, group).flatMap { it.imported }.toSet()
        val names = listOfNotNull(previous, group).map { it.name }.toSet()
        removeWhere(outbounds) { it.optString("tag") in old || it.optString("tag") in names }
        // The group's name in selectors it was added to.
        singboxOutbounds(root).forEach { o -> o.optJSONArray("outbounds")?.let { arr -> removeString(arr, names) } }
        if (group == null) return singboxText(root) to null

        val taken = singboxOutbounds(root).map { it.optString("tag") }.toMutableSet()
        val imported = ArrayList<String>()
        sourceTexts.forEach { (_, src) ->
            singboxOutbounds(singboxJson(src)).filter { it.optString("type") in SINGBOX_NODE_TYPES }.forEach { o ->
                var tag = o.optString("tag")
                var n = 2
                while (tag in taken) tag = "${o.optString("tag")} #${n++}"
                taken += tag
                imported += tag
                // A node that needed another outbound of its profile (detour) would point to nothing here.
                o.remove("detour")
                outbounds.put(o.put("tag", tag))
            }
        }
        val members = singboxOutbounds(root).filter { it.optString("type") in SINGBOX_NODE_TYPES }.map { it.optString("tag") }.filter { group.matches(it) }
        val g = JSONObject().put("type", if (group.type == "select") "selector" else "urltest").put("tag", group.name)
            .put("outbounds", JSONArray(members.ifEmpty { listOf("direct").filter { it in taken } }))
        if (group.type != "select") g.put("url", "https://www.gstatic.com/generate_204").put("interval", "5m").put("tolerance", 50)
        // Next to the other groups: before the first node.
        val firstNode = (0 until outbounds.length()).firstOrNull { outbounds.optJSONObject(it)?.optString("type") in SINGBOX_NODE_TYPES } ?: outbounds.length()
        insertAt(outbounds, firstNode, g)
        singboxOutbounds(root).filter { it.optString("type") == "selector" && it.optString("tag") in group.addTo }.forEach { s ->
            val arr = s.optJSONArray("outbounds") ?: JSONArray().also { s.put("outbounds", it) }
            insertAt(arr, 0, group.name)
        }
        return singboxText(root) to group.copy(imported = imported)
    }

    // ---- Saving ----

    sealed interface SaveResult {
        data object Saved : SaveResult
        data object Applied : SaveResult
        data class CheckFailed(val output: String) : SaveResult
        data object WriteFailed : SaveResult
    }

    /**
     * Checks the new text with the core, keeps the old file as the "previous version" for the diff, writes it and
     * restarts a running service when this is its active config.
     */
    suspend fun save(context: Context, core: String, path: String, text: String, force: Boolean = false): SaveResult {
        if (!force) {
            val r = ConfigCheck.checkText(core, path, text)
            if (r is CheckResult.Failed) return SaveResult.CheckFailed(r.output)
        }
        keepPrevious(path)
        if (!RootFiles.write(path, text)) return SaveResult.WriteFailed
        val active = BoxModule.readSetting(com.skofqq.boxy.ui.tools.coreConfig(core).second)
        val running = runCatching { BoxModule.state() }.getOrNull()
        if (active == path.substringAfterLast('/') && running?.running == true && running.core?.let { com.skofqq.boxy.ui.tools.coreConfig(it).first } == core) {
            BoxControl.restart(context.applicationContext)
            return SaveResult.Applied
        }
        return SaveResult.Saved
    }

    /** Where the version before Boxy's last change of [path] is kept. */
    fun previousPath(path: String) = "${path.substringBeforeLast('/')}/.boxy/${path.substringAfterLast('/')}.prev"

    suspend fun keepPrevious(path: String) {
        val old = RootFiles.read(path) ?: return
        RootFiles.createDir(previousPath(path).substringBeforeLast('/'))
        RootFiles.write(previousPath(path), old)
    }

    // ---- YAML helpers (top-level blocks) ----

    /** Header line and end (exclusive) of a top-level key's block. */
    private fun block(lines: List<String>, key: String): Pair<Int, Int>? {
        val start = lines.indexOfFirst { it.startsWith("$key:") }
        if (start < 0) return null
        var end = start + 1
        while (end < lines.size) {
            val l = lines[end]
            if (l.isNotEmpty() && !l[0].isWhitespace() && l[0] != '#' && l[0] != '-') break
            end++
        }
        // Trailing blank lines and comments belong to whatever follows.
        while (end > start + 1 && (lines[end - 1].isBlank() || lines[end - 1].startsWith("#"))) end--
        return start to end
    }

    /** Indentation of the block's entries ("  " by default). */
    private fun childIndent(lines: List<String>, start: Int, end: Int): String =
        (start + 1 until end).map { lines[it] }.firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
            ?.let { it.substring(0, it.length - it.trimStart().length) } ?: "  "

    private fun ensureBlock(lines: MutableList<String>, key: String): MutableList<String> {
        val i = lines.indexOfFirst { it.startsWith("$key:") }
        if (i >= 0) {
            // "rule-providers: {}" / "rules: []": an empty inline value becomes a block.
            if (lines[i].substringAfter(':').substringBefore('#').trim() in setOf("{}", "[]", "~", "null")) lines[i] = "$key:"
            return lines
        }
        if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
        lines += "$key:"
        return lines
    }

    private fun insertIntoMap(input: MutableList<String>, key: String, entry: String): MutableList<String> {
        val lines = ensureBlock(input, key)
        val (start, end) = block(lines, key)!!
        val indent = childIndent(lines, start, end).ifEmpty { "  " }
        lines.add(start + 1, indent + entry)
        return lines
    }

    private fun insertIntoList(input: MutableList<String>, key: String, item: String, first: Boolean): MutableList<String> {
        val lines = ensureBlock(input, key)
        val (start, end) = block(lines, key)!!
        val indent = (start + 1 until end).map { lines[it] }.firstOrNull { it.trimStart().startsWith("- ") }
            ?.let { it.substring(0, it.length - it.trimStart().length) } ?: "  "
        lines.add(if (first) start + 1 else end, "$indent- $item")
        return lines
    }

    private fun mapKeys(lines: List<String>, key: String): List<String> {
        val (start, end) = block(lines, key) ?: return emptyList()
        val indent = childIndent(lines, start, end)
        return (start + 1 until end).map { lines[it] }
            .filter { it.startsWith(indent) && it.length > indent.length && !it[indent.length].isWhitespace() && !it.trimStart().startsWith("#") }
            .mapNotNull { l -> l.trimStart().substringBefore(':').takeIf { ':' in l }?.let(::unquote) }
    }

    /** Line range of the group [name] (block style "- name: X") inside proxy-groups, and its keys' indent. */
    private fun groupEntry(lines: List<String>, name: String): Triple<Int, Int, String>? {
        val (start, end) = block(lines, "proxy-groups") ?: return null
        for (i in start + 1 until end) {
            val l = lines[i]
            val m = Regex("^(\\s*)-(\\s+)name:\\s*(.+?)\\s*(#.*)?$").find(l) ?: continue
            if (unquote(m.groupValues[3]) != name) continue
            val dashIndent = m.groupValues[1]
            val keyIndent = dashIndent + " " + m.groupValues[2]
            var j = i + 1
            while (j < end) {
                val t = lines[j]
                if (t.isNotBlank() && !t.trimStart().startsWith("#")) {
                    val ind = t.length - t.trimStart().length
                    if (ind <= dashIndent.length) break
                }
                j++
            }
            return Triple(i, j, keyIndent)
        }
        return null
    }

    private fun addMember(input: MutableList<String>, groupName: String, member: String): MutableList<String> {
        val lines = input
        val (from, to, keyIndent) = groupEntry(lines, groupName) ?: return lines
        val p = (from + 1 until to).firstOrNull { Regex("^${Regex.escape(keyIndent)}proxies:").containsMatchIn(lines[it]) }
        if (p == null) {
            lines.add(from + 1, "${keyIndent}proxies: [${quote(member)}]")
            return lines
        }
        val rest = lines[p].substringAfter("proxies:").trim()
        if (rest.startsWith("[")) {
            lines[p] = "${keyIndent}proxies: [${quote(member)}" + (if (rest.removePrefix("[").trimStart().startsWith("]")) "" else ", ") + rest.removePrefix("[").trimStart()
            return lines
        }
        val itemIndent = (p + 1 until to).map { lines[it] }.firstOrNull { it.trimStart().startsWith("- ") }
            ?.let { it.substring(0, it.length - it.trimStart().length) } ?: "$keyIndent  "
        lines.add(p + 1, "$itemIndent- ${quote(member)}")
        return lines
    }

    private fun removeMember(input: MutableList<String>, groupName: String, member: String): MutableList<String> {
        val lines = input
        val (from, to, _) = groupEntry(lines, groupName) ?: return lines
        val q = Regex.escape(member)
        for (i in to - 1 downTo from + 1) {
            val l = lines[i]
            if (Regex("^\\s*-\\s*[\"']?$q[\"']?\\s*$").matches(l)) {
                lines.removeAt(i)
            } else if (l.trimStart().startsWith("proxies:") && '[' in l) {
                lines[i] = l.replace(Regex("[\"']?$q[\"']?\\s*,\\s*"), "").replace(Regex(",\\s*[\"']?$q[\"']?(?=\\s*])"), "")
                    .replace(Regex("\\[\\s*[\"']?$q[\"']?\\s*]"), "[]")
            }
        }
        return lines
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && (t[0] == '"' && t.last() == '"' || t[0] == '\'' && t.last() == '\'')) {
            t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        } else {
            t
        }
    }

    // ---- JSON helpers ----

    fun singboxJson(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()

    private fun singboxOutbounds(root: JSONObject?): List<JSONObject> {
        val arr = root?.optJSONArray("outbounds") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** Two-space JSON without Android's escaped slashes ("https:\/\/"). */
    fun singboxText(root: JSONObject): String =
        root.toString(2).replace(Regex("(?<!\\\\)((?:\\\\\\\\)*)\\\\/"), "$1/") + "\n"

    private fun removeWhere(arr: JSONArray, predicate: (JSONObject) -> Boolean) {
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            if (predicate(o)) arr.remove(i)
        }
    }

    private fun removeString(arr: JSONArray, values: Set<String>) {
        for (i in arr.length() - 1 downTo 0) if (arr.opt(i) is String && arr.optString(i) in values) arr.remove(i)
    }

    private fun insertAt(arr: JSONArray, index: Int, value: Any) {
        val items = (0 until arr.length()).map { arr.get(it) }.toMutableList()
        items.add(index.coerceIn(0, items.size), value)
        while (arr.length() > 0) arr.remove(arr.length() - 1)
        items.forEach { arr.put(it) }
    }
}
