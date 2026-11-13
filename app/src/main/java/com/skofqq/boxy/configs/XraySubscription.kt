package com.skofqq.boxy.configs

import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CoreTools
import com.skofqq.boxy.root.RootTask
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Subscriptions for Xray and V2Ray: box.tool subs downloads the list of share links (settings.ini
 * subscription_url_xray) into <core>/subscription.txt, Boxy turns it into outbounds tagged "sub-…" in a confdir file
 * of its own, checks the whole folder with the core and keeps the previous file when the check fails.
 * A balancer "sub" picks among all "sub-" outbounds by its selector, so it never needs editing after an update.
 */
object XraySubscription {
    private const val BOX = BoxModule.BOX_DIR
    const val BALANCER = "sub"
    const val URL_KEY = "subscription_url_xray"

    fun file(core: String) = "$BOX/$core/zz-boxy-subscription.json"
    fun downloaded(core: String) = "$BOX/$core/subscription.txt"
    /** Links of the last update that did not become servers: "name<TAB>scheme<TAB>reason" per line (not .json: the core skips it). */
    fun skippedFile(core: String) = "$BOX/$core/subscription.skipped"

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    data class Status(val servers: Int, val balancer: Boolean, val byDefault: Boolean, val skipped: List<ShareLinks.Skipped> = emptyList())

    suspend fun url(): String = BoxModule.unquote(BoxModule.readSetting(URL_KEY)).orEmpty()

    suspend fun setUrl(url: String): Boolean = BoxModule.writeSetting(URL_KEY, url.trim())

    /** settings.ini subscription_extra_xray: more lists, "<prefix> <url>" each (outbounds "<prefix>-…"). */
    const val EXTRA_KEY = "subscription_extra_xray"

    data class Extra(val prefix: String, val url: String)

    suspend fun extras(): List<Extra> {
        val raw = BoxModule.readSettingRaw(EXTRA_KEY).orEmpty()
        return Regex("\"([^\"]*)\"|'([^']*)'").findAll(raw).mapNotNull { m ->
            val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            val p = v.substringBefore(' ', "")
            val u = v.substringAfter(' ', "").trim()
            if (p.isBlank() || u.isBlank()) null else Extra(p, u)
        }.toList()
    }

    /** A prefix as a tag part: letters and digits only, so "wl" gives outbounds "wl-…". */
    fun cleanPrefix(p: String) = p.trim().lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(12)

    suspend fun setExtras(list: List<Extra>): Boolean = BoxModule.writeSettingRaw(
        EXTRA_KEY,
        list.joinToString(" ", "(", ")") { "\"${it.prefix} ${it.url.replace("\"", "").replace(" ", "%20")}\"" },
    )

    /** A subscription URL as the app shows it: the host only (the rest usually carries a token). */
    fun maskUrl(url: String): String {
        val m = Regex("^([a-zA-Z]+://[^/?#]+)").find(url.trim()) ?: return "***"
        return m.groupValues[1] + "/***"
    }

    suspend fun status(core: String): Status {
        suspend fun count(path: String): Int = BoxModule.readFile(path)?.let { runCatching { JSONObject(it).optJSONArray("outbounds")?.length() ?: 0 }.getOrDefault(0) } ?: 0
        val extraFiles = if (core == "xray") Shell.cmd("ls ${CoreTools.XRAY_SUBS}/*.json 2>/dev/null").exec().out.filter { it.isNotBlank() } else emptyList()
        val sub = count(file(core)) + extraFiles.fold(0) { acc, f -> acc + count(f) }
        val cfg = BoxModule.readFile(CoreTools.activeConfig(core)).orEmpty()
        val routing = runCatching { JSONObject(cfg).optJSONObject("routing") }.getOrNull()
        val bal = routing?.optJSONArray("balancers")?.let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optString("tag") == BALANCER } } == true
        val def = routing?.optJSONArray("rules")?.let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optString("balancerTag") == BALANCER } } == true
        val skipped = BoxModule.readFile(skippedFile(core)).orEmpty().lines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 3) null else ShareLinks.Skipped(p[0], p[1], runCatching { ShareLinks.SkipReason.valueOf(p[2]) }.getOrDefault(ShareLinks.SkipReason.BROKEN))
        }
        return Status(sub, bal, def, skipped)
    }

    /** Downloads with the module (its User-Agent and mirror), then applies; the module's event is not sent. */
    suspend fun downloadScript(core: String): String = "(export BOXY_NO_EVENT=1; " + BoxModule.toolCommand("subs", core, force = true) + ")"

    /**
     * Converts the downloaded links into the subscription file, checks the core's folder and restarts the core when
     * it runs. Returns (servers, skipped links); on failure the previous file stays and the core's complaint is the
     * message.
     */
    suspend fun apply(core: String): Result<Pair<Int, List<ShareLinks.Skipped>>> = withContext(Dispatchers.IO) {
        val body = BoxModule.readFile(downloaded(core))
        // Extra lists (subscription_extra_xray): xray/subs/<prefix>.txt into xray/subs/<prefix>.json, "<prefix>-…".
        val extras = if (core == "xray") Shell.cmd("ls ${CoreTools.XRAY_SUBS}/*.txt 2>/dev/null").exec().out.filter { it.isNotBlank() } else emptyList()
        if (body == null && extras.isEmpty()) return@withContext Result.failure(IllegalStateException("no subscription.txt"))
        val writes = LinkedHashMap<String, String>()
        val skipped = ArrayList<ShareLinks.Skipped>()
        var servers = 0
        if (body != null) {
            val conv = ShareLinks.convert(body, core)
            skipped += conv.skipped
            servers += conv.outbounds.size
            if (conv.outbounds.isNotEmpty()) {
                val o = JSONObject().put("outbounds", JSONArray(conv.outbounds))
                // leastPing needs probes of the subscription's servers; the user's own observatory, if any, is left alone.
                val userHasObservatory = Shell.cmd("grep -l '\"observatory\"' $BOX/$core/*.json ${q(CoreTools.activeConfig(core))} 2>/dev/null | grep -v zz-boxy").exec().out.isNotEmpty()
                if (core == "xray" && !userHasObservatory) {
                    o.put(
                        "observatory",
                        JSONObject().put("subjectSelector", JSONArray().put(ShareLinks.TAG_PREFIX))
                            .put("probeUrl", "http://www.gstatic.com/generate_204").put("probeInterval", "3m").put("enableConcurrency", true),
                    )
                }
                writes[file(core)] = GeoRules.pretty(o)
            }
        }
        for (txt in extras) {
            val prefix = txt.substringAfterLast('/').removeSuffix(".txt")
            val conv = ShareLinks.convert(BoxModule.readFile(txt).orEmpty(), core, "$prefix-")
            skipped += conv.skipped.map { it.copy(name = "$prefix: ${it.name}") }
            servers += conv.outbounds.size
            if (conv.outbounds.isNotEmpty()) writes["${CoreTools.XRAY_SUBS}/$prefix.json"] = GeoRules.pretty(JSONObject().put("outbounds", JSONArray(conv.outbounds)))
        }
        // Kept for the subscription section, so a server missing from the panel is never a mystery.
        BoxModule.writeFile(skippedFile(core), skipped.joinToString("") { "${it.name.replace('\t', ' ').replace('\n', ' ')}\t${it.scheme}\t${it.reason}\n" })
        if (writes.isEmpty()) return@withContext Result.failure(IllegalStateException("no servers (${skipped.size} links skipped)"))
        val previous = writes.keys.associateWith { BoxModule.readFile(it) }
        if (writes.any { (path, text) -> !BoxModule.writeFile(path, text) }) return@withContext Result.failure(IllegalStateException("write"))
        val test = if (core == "xray") "XRAY_LOCATION_ASSET=$BOX/xray timeout 60 $BOX/bin/xray -test ${CoreTools.xrayArgs()}"
        else "V2RAY_LOCATION_ASSET=$BOX/v2fly timeout 30 $BOX/bin/v2fly test -d $BOX/v2fly"
        val r = Shell.cmd("$test 2>&1").exec()
        if (!r.isSuccess) {
            previous.forEach { (path, old) -> if (old != null) BoxModule.writeFile(path, old) else Shell.cmd("rm -f ${q(path)}").exec() }
            val why = r.out.map { RootTask.stripAnsi(it) }.filter { "rror" in it || "ailed" in it }.takeLast(4).joinToString("\n")
            return@withContext Result.failure(IllegalStateException(why.ifBlank { "check failed" }))
        }
        val st = BoxModule.state()
        if (st.running && BoxModule.unquote(st.core) == core) BoxModule.restart()
        Result.success(servers to skipped)
    }

    /**
     * Adds the balancer "sub" (selector "sub-": every subscription server) to the user's config, and with
     * [byDefault] a last routing rule sending the rest of the traffic to it. Xray balances by ping, V2Ray at random.
     */
    suspend fun ensureBalancer(core: String, byDefault: Boolean): Boolean {
        val path = CoreTools.activeConfig(core)
        val text = BoxModule.readFile(path) ?: return false
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val balancers = routing.optJSONArray("balancers") ?: JSONArray().also { routing.put("balancers", it) }
        if ((0 until balancers.length()).none { balancers.optJSONObject(it)?.optString("tag") == BALANCER }) {
            balancers.put(
                JSONObject().put("tag", BALANCER).put("selector", JSONArray().put(ShareLinks.TAG_PREFIX))
                    .put("strategy", JSONObject().put("type", if (core == "xray") "leastPing" else "random")),
            )
        }
        val rules = routing.optJSONArray("rules") ?: JSONArray().also { routing.put("rules", it) }
        val idx = (0 until rules.length()).firstOrNull { rules.optJSONObject(it)?.optString("balancerTag") == BALANCER && rules.optJSONObject(it)?.optString("network") == "tcp,udp" }
        if (byDefault && idx == null) rules.put(JSONObject().put("type", "field").put("network", "tcp,udp").put("balancerTag", BALANCER))
        if (!byDefault && idx != null) rules.remove(idx)
        if (!BoxModule.writeFile(path, GeoRules.pretty(root))) return false
        val st = BoxModule.state()
        if (st.running && BoxModule.unquote(st.core) == core) BoxModule.restart()
        return true
    }
}
