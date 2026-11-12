package com.skofqq.boxy.configs

import com.skofqq.boxy.BoxyApp
import com.skofqq.boxy.data.GeoBase
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CoreTools
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the cores that cannot use a second .dat get from the geo sources:
 *  - sing-box: each category a config uses is compiled into geo-rules/<tag>.srs (tag geosite-<prefix>-<category>),
 *    referenced as a local rule set;
 *  - mihomo: GeoSite.dat / GeoIP.dat are the merge of the primary pair and the extra pairs, whose categories get
 *    the source's prefix (rf-ru-blocked).
 * Both are rebuilt after every geo update (the module tells Boxy with GEO_UPDATED).
 */
object GeoRuleSets {
    private const val BOX = BoxModule.BOX_DIR
    const val DIR = "geo-rules"

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun singBoxTag(kind: GeoBase.Kind, source: GeoSource, category: String) =
        "${if (kind == GeoBase.Kind.SITE) "geosite" else "geoip"}-${source.prefix}-$category"

    /** Name of an extra source's category in mihomo's merged files. */
    fun mihomoName(source: GeoSource, category: String) = "${source.prefix}-$category"

    private fun scratch(name: String): File = File(BoxyApp.instance.cacheDir, "geo").apply { mkdirs() }.let { File(it, name) }

    /** Copies an app file into place as root (world-readable, as the module's own files). */
    private fun install(from: File, to: String): Boolean =
        Shell.cmd("mkdir -p ${q(to.substringBeforeLast('/'))} && cat ${q(from.absolutePath)} > ${q("$to.tmp")} && mv -f ${q("$to.tmp")} ${q(to)} && chmod 644 ${q(to)}")
            .exec().isSuccess.also { from.delete() }

    // ---- sing-box ----

    /** Compiles [category] from [path] into geo-rules/<tag>.srs; returns the tag, or null when it failed. */
    suspend fun buildSingBox(kind: GeoBase.Kind, source: GeoSource, category: String, path: String): String? = withContext(Dispatchers.IO) {
        val tag = singBoxTag(kind, source, category)
        val rule = JSONObject()
        if (kind == GeoBase.Kind.SITE) {
            val d = GeoBase.extractSite(path, category) ?: return@withContext null
            if (d.full.isNotEmpty()) rule.put("domain", JSONArray(d.full))
            if (d.suffix.isNotEmpty()) rule.put("domain_suffix", JSONArray(d.suffix))
            if (d.keyword.isNotEmpty()) rule.put("domain_keyword", JSONArray(d.keyword))
            if (d.regex.isNotEmpty()) rule.put("domain_regex", JSONArray(d.regex))
            if (d.isEmpty) rule.put("domain", JSONArray().put("empty.boxy.invalid"))
        } else {
            val ips = GeoBase.extractIp(path, category) ?: return@withContext null
            rule.put("ip_cidr", JSONArray(ips.ifEmpty { listOf("0.0.0.0/32") }))
        }
        val src = scratch("$tag.json")
        src.writeText(JSONObject().put("version", 2).put("rules", JSONArray().put(rule)).toString())
        val dir = "$BOX/sing-box/$DIR"
        val ok = Shell.cmd(
            "mkdir -p ${q(dir)} && cat ${q(src.absolutePath)} > ${q("$dir/.$tag.json")} && " +
                "$BOX/bin/sing-box rule-set compile --output ${q("$dir/$tag.srs")} ${q("$dir/.$tag.json")} >/dev/null 2>&1; " +
                "rc=\$?; rm -f ${q("$dir/.$tag.json")}; chmod 644 ${q("$dir/$tag.srs")} 2>/dev/null; [ \$rc -eq 0 ]",
        ).exec().isSuccess
        src.delete()
        if (ok) tag else null
    }

    /** Rebuilds every rule set in sing-box/geo-rules from the current .dat files; the number rebuilt. */
    suspend fun rebuildSingBox(): Int = withContext(Dispatchers.IO) {
        val files = Shell.cmd("ls $BOX/sing-box/$DIR 2>/dev/null").exec().out.filter { it.endsWith(".srs") }
        var n = 0
        for (f in files) {
            val tag = f.removeSuffix(".srs")
            val kind = when {
                tag.startsWith("geosite-") -> GeoBase.Kind.SITE
                tag.startsWith("geoip-") -> GeoBase.Kind.IP
                else -> continue
            }
            val rest = tag.substringAfter('-')
            val source = GeoSources.ALL.firstOrNull { rest.startsWith(it.prefix + "-") } ?: continue
            val category = rest.removePrefix(source.prefix + "-")
            val file = "$BOX/sing-box/${if (kind == GeoBase.Kind.SITE) "geosite" else "geoip"}_${source.id}.dat"
            // The primary source's pair may not be there yet: keep the rule set built earlier.
            if (!Shell.cmd("[ -s ${q(file)} ]").exec().isSuccess) continue
            if (buildSingBox(kind, source, category, file) != null) n++
        }
        n
    }

    // ---- mihomo ----

    /**
     * Writes clash/GeoSite.dat and GeoIP.dat from the primary pair plus the extra pairs (prefixed names).
     * False when there is nothing to merge (no extra sources, or the primary pair was not downloaded yet).
     */
    suspend fun mergeMihomo(): Boolean = withContext(Dispatchers.IO) {
        val (primary, extras) = CoreTools.geoSources()
        val others = extras.filter { it.id != primary.id }
        if (others.isEmpty()) return@withContext false
        val dir = "$BOX/clash"
        val present = Shell.cmd("ls $dir 2>/dev/null").exec().out.toSet()
        if ("geosite_${primary.id}.dat" !in present) return@withContext false
        var ok = true
        for (kind in listOf("geosite" to "GeoSite.dat", "geoip" to "GeoIP.dat")) {
            val inputs = (listOf(primary to null) + others.map { it to it.prefix })
                .filter { (s, _) -> "${kind.first}_${s.id}.dat" in present }
                .map { (s, p) -> "$dir/${kind.first}_${s.id}.dat" to p }
            if (inputs.isEmpty()) continue
            val out = scratch(kind.second)
            val n = out.outputStream().buffered(1 shl 16).use { GeoBase.merge(inputs, it) }
            ok = if (n > 0) install(out, "$dir/${kind.second}") && ok else { out.delete(); false }
        }
        ok
    }

    /** After a geo update of [core]: rebuilds what was made from the .dat files. True when something changed. */
    suspend fun rebuild(core: String): Boolean = when (core) {
        "clash" -> runCatching { mergeMihomo() }.getOrDefault(false)
        "sing-box" -> runCatching { rebuildSingBox() }.getOrDefault(0) > 0
        else -> false
    }
}
