package com.skofqq.boxy.configs

import com.skofqq.boxy.data.GeoBase
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Insert rule from the geo base": the rule for a GeoSite / GeoIP category in the syntax of the config's core.
 *  - mihomo: `- GEOSITE,twitch,Group` at the top of rules; an extra source's category carries its prefix
 *    (GEOSITE,rf-ru-blocked,Group), as in the merged GeoSite.dat;
 *  - sing-box: a route rule with a local rule set geo-rules/<tag>.srs compiled from the .dat (see GeoRuleSets);
 *  - xray / v2fly: "geosite:twitch", or "ext:geosite_<src>.dat:twitch" for an extra source.
 * Rules go first so they win over the catch-all ones further down.
 */
object GeoRules {
    data class Target(val name: String, val balancer: Boolean = false)

    /** Result of an insertion: the new text and the 0-based line of the inserted rule. */
    data class Edit(val text: String, val line: Int)

    class EditError(message: String) : Exception(message)

    fun syntaxCore(core: String): Boolean = core in setOf("clash", "sing-box", "xray", "v2fly")

    private fun GeoBase.Category.site() = kind == GeoBase.Kind.SITE

    /** The category as the core's rules name it. */
    fun ruleValue(core: String, c: GeoBase.Category): String = when (core) {
        "clash" -> if (c.primary) c.name else GeoRuleSets.mihomoName(c.source, c.name)
        "sing-box" -> GeoRuleSets.singBoxTag(c.kind, c.source, c.name)
        else -> {
            val kind = if (c.site()) "geosite" else "geoip"
            if (c.primary) "$kind:${c.name}" else "ext:${kind}_${c.source.id}.dat:${c.name}"
        }
    }

    /** Rule as the user sees it before inserting. */
    fun preview(core: String, c: GeoBase.Category, target: Target): String {
        val v = ruleValue(core, c)
        return when (core) {
            "clash" -> "- ${if (c.site()) "GEOSITE" else "GEOIP"},$v,${target.name}"
            "sing-box" -> "{ \"rule_set\": \"$v\", \"outbound\": \"${target.name}\" }"
            else -> "{ \"${if (c.site()) "domain" else "ip"}\": [\"$v\"], " +
                "\"${if (target.balancer) "balancerTag" else "outboundTag"}\": \"${target.name}\" }"
        }
    }

    // ---- targets: the groups / outbounds a rule can send traffic to ----

    fun targets(core: String, text: String): List<Target> = when (core) {
        "clash" -> clashTargets(text)
        "sing-box" -> jsonTags(text, "outbounds").map { Target(it) }
        else -> xrayTargets(text)
    }

    private fun clashTargets(text: String): List<Target> {
        val names = ArrayList<String>()
        val section = topLevelSection(text, "proxy-groups")
        if (section != null) {
            Regex("(?m)^\\s*-?\\s*\\{?\\s*name:\\s*['\"]?([^'\",}\\n]+?)['\"]?\\s*(,|}|$)").findAll(section).forEach { names.add(it.groupValues[1].trim()) }
        }
        return (names + listOf("DIRECT", "REJECT")).distinct().map { Target(it) }
    }

    /** Lines of a top-level YAML key (without the key line), or null. */
    private fun topLevelSection(text: String, key: String): String? {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith("$key:") }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val l = lines[i]
            l.isNotEmpty() && !l[0].isWhitespace() && !l.startsWith("#") && !l.startsWith("-")
        } ?: lines.size
        return lines.subList(start + 1, end).joinToString("\n")
    }

    private fun parseJson(text: String): JSONObject = try {
        JSONObject(text)
    } catch (e: Exception) {
        throw EditError(e.message ?: "JSON")
    }

    private fun jsonTags(text: String, key: String): List<String> {
        val arr = parseJson(text).optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("tag")?.takeIf { t -> t.isNotBlank() } }
    }

    private fun xrayTargets(text: String): List<Target> {
        val root = parseJson(text)
        val out = ArrayList<Target>()
        root.optJSONArray("outbounds")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.optString("tag")?.takeIf { it.isNotBlank() }?.let { out.add(Target(it)) }
        }
        root.optJSONObject("routing")?.optJSONArray("balancers")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.optString("tag")?.takeIf { it.isNotBlank() }?.let { out.add(Target(it, balancer = true)) }
        }
        return out
    }

    // ---- insertion ----

    fun insert(core: String, text: String, c: GeoBase.Category, target: Target): Edit = when (core) {
        "clash" -> insertClash(text, c, target)
        "sing-box" -> insertSingBox(text, c, target)
        else -> insertXray(text, c, target)
    }

    private fun insertClash(text: String, c: GeoBase.Category, target: Target): Edit {
        val rule = "${if (c.site()) "GEOSITE" else "GEOIP"},${ruleValue("clash", c)},${target.name}"
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { Regex("^rules:\\s*(#.*)?$").matches(it) }
        if (at < 0) {
            if (lines.lastOrNull()?.isNotEmpty() == true) lines.add("")
            lines.add("rules:")
            lines.add("  - $rule")
            return Edit(lines.joinToString("\n"), lines.size - 1)
        }
        // Same indent as the existing items.
        val indent = (at + 1 until lines.size).map { lines[it] }.firstOrNull { it.trimStart().startsWith("- ") }
            ?.let { it.substring(0, it.length - it.trimStart().length) } ?: "  "
        lines.add(at + 1, "$indent- $rule")
        return Edit(lines.joinToString("\n"), at + 1)
    }

    private fun insertSingBox(text: String, c: GeoBase.Category, target: Target): Edit {
        val root = parseJson(text)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val tag = ruleValue("sing-box", c)
        val sets = route.optJSONArray("rule_set") ?: JSONArray().also { route.put("rule_set", it) }
        val has = (0 until sets.length()).any { sets.optJSONObject(it)?.optString("tag") == tag }
        if (!has) {
            sets.put(JSONObject().put("tag", tag).put("type", "local").put("format", "binary").put("path", "${GeoRuleSets.DIR}/$tag.srs"))
        }
        route.put("rules", prepend(route.optJSONArray("rules"), JSONObject().put("rule_set", tag).put("outbound", target.name)))
        val out = pretty(root)
        return Edit(out, lineOf(out, "\"rule_set\": \"$tag\""))
    }

    private fun insertXray(text: String, c: GeoBase.Category, target: Target): Edit {
        val root = parseJson(text)
        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val value = ruleValue("xray", c)
        val rule = JSONObject().put("type", "field")
            .put(if (c.site()) "domain" else "ip", JSONArray().put(value))
            .put(if (target.balancer) "balancerTag" else "outboundTag", target.name)
        routing.put("rules", prepend(routing.optJSONArray("rules"), rule))
        val out = pretty(root)
        return Edit(out, lineOf(out, "\"$value\""))
    }

    private fun prepend(arr: JSONArray?, item: JSONObject): JSONArray {
        val out = JSONArray().put(item)
        if (arr != null) for (i in 0 until arr.length()) out.put(arr.get(i))
        return out
    }

    /** org.json escapes "/" as "\/": valid JSON, but the cores' own format writes it plain. */
    fun pretty(o: JSONObject): String = o.toString(2).replace("\\/", "/") + "\n"

    private fun lineOf(text: String, needle: String): Int {
        val i = text.indexOf(needle)
        return if (i < 0) 0 else text.substring(0, i).count { it == '\n' }
    }
}
