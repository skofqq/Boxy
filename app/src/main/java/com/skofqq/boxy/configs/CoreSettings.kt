package com.skofqq.boxy.configs

import android.content.Context
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.CheckResult
import com.skofqq.boxy.root.ConfigCheck
import com.skofqq.boxy.root.CoreTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings of Xray and sing-box that their run-time APIs do not have, changed in the config and kept by Boxy.
 *
 * Xray: the panel config is replaced at every load from the subscription, so every value set here is kept in the
 * app and applied to the active config now and to each config Boxy loads later ([applyXray]).
 * sing-box: plain values go to the overrides file (sing-box/.overrides, merged by the module with -C, it survives
 * subscription updates); values inside outbounds and rules ("⚡️ Fastest", blocking QUIC) can only be set in the
 * config itself: they are kept too and applied when Boxy loads a config (an update by the module itself resets them
 * until then).
 * Every change is checked with the core first; the previous file comes back when the core rejects it.
 */
object CoreSettings {
    private const val PREFS = "core_settings"

    // ---- Xray keys
    const val X_LOGLEVEL = "x.log.loglevel"
    const val X_DNSLOG = "x.log.dnsLog"
    const val X_PROBE_INTERVAL = "x.probe.interval"
    const val X_PROBE_TIMEOUT = "x.probe.timeout"
    const val X_PROBE_SAMPLING = "x.probe.sampling"
    const val X_PROBE_URL = "x.probe.url"
    const val X_STRATEGY = "x.balancer.strategy"
    const val X_MAXRTT = "x.balancer.maxRTT"
    const val X_DOMAIN_STRATEGY = "x.routing.domainStrategy"
    const val X_BLOCK_QUIC = "x.blockQuic"
    const val X_SNIFF = "x.sniff.enabled"
    const val X_SNIFF_ROUTE_ONLY = "x.sniff.routeOnly"
    const val X_SNIFF_DEST = "x.sniff.destOverride"
    const val X_DNS_QUERY = "x.dns.queryStrategy"
    const val X_DNS_NOCACHE = "x.dns.disableCache"

    // ---- sing-box keys (overrides)
    const val S_LOGLEVEL = "s.log.level"
    const val S_TIMESTAMP = "s.log.timestamp"
    const val S_DNS_STRATEGY = "s.dns.strategy"
    const val S_DNS_INDEPENDENT = "s.dns.independent_cache"
    const val S_AUTO_IFACE = "s.route.auto_detect_interface"
    const val S_CACHE_RDRC = "s.cache.store_rdrc"
    // ---- sing-box keys (config)
    const val S_URLTEST_URL = "s.urltest.url"
    const val S_URLTEST_INTERVAL = "s.urltest.interval"
    const val S_URLTEST_TOLERANCE = "s.urltest.tolerance"
    const val S_BLOCK_QUIC = "s.blockQuic"

    private const val QUIC_TAG = "boxy-block-quic"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun stored(context: Context): Map<String, String> = prefs(context).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    private fun store(context: Context, key: String, value: String?) {
        prefs(context).edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    private fun stripComments(text: String) = text.split("\n").joinToString("\n") { l -> if (l.trimStart().startsWith("//")) "" else l }

    private fun JSONObject.obj(name: String): JSONObject = optJSONObject(name) ?: JSONObject().also { put(name, it) }

    private fun typed(v: String): Any = when {
        v == "true" -> true
        v == "false" -> false
        v.toIntOrNull() != null -> v.toInt()
        else -> v
    }

    // ------------------------------------------------------------------ Xray

    /** The current Xray values: the ones set in Boxy, else what the active config has. */
    suspend fun xrayValues(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        val cfg = BoxModule.readFile(CoreTools.activeConfig("xray"))?.let { runCatching { JSONObject(stripComments(it)) }.getOrNull() } ?: JSONObject()
        val out = LinkedHashMap<String, String>()
        val log = cfg.optJSONObject("log")
        out[X_LOGLEVEL] = log?.optString("loglevel")?.ifBlank { null } ?: "warning"
        out[X_DNSLOG] = (log?.optBoolean("dnsLog") == true).toString()
        val burst = cfg.optJSONObject("burstObservatory")?.optJSONObject("pingConfig")
        val obs = cfg.optJSONObject("observatory")
        out[X_PROBE_INTERVAL] = burst?.optString("interval")?.ifBlank { null } ?: obs?.optString("probeInterval")?.ifBlank { null } ?: "5m"
        out[X_PROBE_TIMEOUT] = burst?.optString("timeout")?.ifBlank { null } ?: "5s"
        out[X_PROBE_SAMPLING] = burst?.opt("sampling")?.toString() ?: "2"
        out[X_PROBE_URL] = burst?.optString("destination")?.ifBlank { null } ?: obs?.optString("probeUrl")?.ifBlank { null } ?: "http://www.gstatic.com/generate_204"
        val bal = serverBalancers(cfg).firstOrNull()?.optJSONObject("strategy")
        out[X_STRATEGY] = bal?.optString("type")?.ifBlank { null } ?: "leastLoad"
        out[X_MAXRTT] = bal?.optJSONObject("settings")?.optString("maxRTT")?.ifBlank { null } ?: "3s"
        out[X_DOMAIN_STRATEGY] = cfg.optJSONObject("routing")?.optString("domainStrategy")?.ifBlank { null } ?: "AsIs"
        out[X_BLOCK_QUIC] = (cfg.optJSONObject("routing")?.optJSONArray("rules")?.let { r -> (0 until r.length()).any { r.optJSONObject(it)?.optString("ruleTag") == QUIC_TAG } } == true).toString()
        val sniff = tproxyInbounds(cfg).firstOrNull()?.optJSONObject("sniffing")
        out[X_SNIFF] = (sniff?.optBoolean("enabled") == true).toString()
        out[X_SNIFF_ROUTE_ONLY] = (sniff?.optBoolean("routeOnly") == true).toString()
        out[X_SNIFF_DEST] = sniff?.optJSONArray("destOverride")?.let { a -> (0 until a.length()).joinToString(",") { a.optString(it) } }?.ifBlank { null } ?: "http,tls"
        val dns = cfg.optJSONObject("dns")
        out[X_DNS_QUERY] = dns?.optString("queryStrategy")?.ifBlank { null } ?: "UseIP"
        out[X_DNS_NOCACHE] = (dns?.optBoolean("disableCache") == true).toString()
        out + stored(context).filterKeys { it.startsWith("x.") }
    }

    /** Balancers that pick among servers (not a direct-only group such as "RU сайты"). */
    private fun serverBalancers(cfg: JSONObject): List<JSONObject> {
        val freedom = cfg.optJSONArray("outbounds")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("protocol") == "freedom" }.map { it.optString("tag") } }.orEmpty()
        val bals = cfg.optJSONObject("routing")?.optJSONArray("balancers") ?: return emptyList()
        return (0 until bals.length()).mapNotNull { bals.optJSONObject(it) }.filter { b ->
            val sel = b.optJSONArray("selector")
            val prefixes = (0 until (sel?.length() ?: 0)).map { sel!!.optString(it) }
            !(prefixes.isNotEmpty() && prefixes.all { p -> freedom.any { it.startsWith(p) } })
        }
    }

    private fun tproxyInbounds(cfg: JSONObject): List<JSONObject> {
        val a = cfg.optJSONArray("inbounds") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("protocol") in setOf("dokodemo-door", "tunnel") }
    }

    /** Writes the values set in Boxy into an Xray config (in place). */
    fun applyXray(cfg: JSONObject, values: Map<String, String>) {
        values[X_LOGLEVEL]?.let { cfg.obj("log").put("loglevel", it) }
        values[X_DNSLOG]?.let { cfg.obj("log").put("dnsLog", it == "true") }
        val burst = cfg.optJSONObject("burstObservatory")
        val obs = cfg.optJSONObject("observatory")
        if (burst != null) {
            val p = burst.obj("pingConfig")
            values[X_PROBE_INTERVAL]?.let { p.put("interval", it) }
            values[X_PROBE_TIMEOUT]?.let { p.put("timeout", it) }
            values[X_PROBE_SAMPLING]?.toIntOrNull()?.let { p.put("sampling", it) }
            values[X_PROBE_URL]?.let { p.put("destination", it) }
        } else if (obs != null) {
            values[X_PROBE_INTERVAL]?.let { obs.put("probeInterval", it) }
            values[X_PROBE_URL]?.let { obs.put("probeUrl", it) }
        }
        val strategy = values[X_STRATEGY]
        val maxRtt = values[X_MAXRTT]
        if (strategy != null || maxRtt != null) serverBalancers(cfg).forEach { b ->
            val s = b.obj("strategy")
            if (strategy != null && s.optString("type") != strategy) {
                s.put("type", strategy)
                s.remove("settings")
            }
            if (maxRtt != null && s.optString("type") in setOf("leastLoad", "leastPing")) s.obj("settings").put("maxRTT", maxRtt)
        }
        values[X_DOMAIN_STRATEGY]?.let { cfg.obj("routing").put("domainStrategy", it) }
        values[X_BLOCK_QUIC]?.let { on ->
            val routing = cfg.obj("routing")
            val rules = routing.optJSONArray("rules") ?: JSONArray().also { routing.put("rules", it) }
            val kept = (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }.filter { it.optString("ruleTag") != QUIC_TAG }
            val list = ArrayList<JSONObject>()
            if (on == "true") {
                val obs2 = cfg.optJSONArray("outbounds") ?: JSONArray().also { cfg.put("outbounds", it) }
                val block = (0 until obs2.length()).mapNotNull { obs2.optJSONObject(it) }.firstOrNull { it.optString("protocol") == "blackhole" }?.optString("tag")
                    ?: "block".also { obs2.put(JSONObject().put("tag", "block").put("protocol", "blackhole")) }
                // QUIC (UDP 443) blocked: apps fall back to TCP, which the proxies carry better.
                list += JSONObject().put("ruleTag", QUIC_TAG).put("network", "udp").put("port", "443").put("outboundTag", block)
            }
            routing.put("rules", JSONArray(list + kept))
        }
        tproxyInbounds(cfg).forEach { ib ->
            val sn = ib.obj("sniffing")
            values[X_SNIFF]?.let { sn.put("enabled", it == "true") }
            values[X_SNIFF_ROUTE_ONLY]?.let { sn.put("routeOnly", it == "true") }
            values[X_SNIFF_DEST]?.let { sn.put("destOverride", JSONArray(it.split(',').filter { s -> s.isNotBlank() })) }
        }
        values[X_DNS_QUERY]?.let { cfg.obj("dns").put("queryStrategy", it) }
        values[X_DNS_NOCACHE]?.let { cfg.obj("dns").put("disableCache", it == "true") }
    }

    /** Applies the stored Xray values to a config Boxy is about to install. */
    fun applyXrayStored(context: Context, cfg: JSONObject) = applyXray(cfg, stored(context).filterKeys { it.startsWith("x.") })

    /** Sets one Xray value: kept in the app, written to the active config, checked; Xray restarts when it runs. */
    suspend fun setXray(context: Context, key: String, value: String): Result<Unit> = withContext(Dispatchers.IO) {
        val path = CoreTools.activeConfig("xray")
        val text = BoxModule.readFile(path) ?: return@withContext Result.failure(IllegalStateException(path))
        if (text.split("\n").any { it.trimStart().startsWith("//") }) return@withContext Result.failure(IllegalStateException("comments"))
        val cfg = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext Result.failure(IllegalStateException("JSON"))
        applyXray(cfg, mapOf(key to value))
        if (!BoxModule.writeFile(path, GeoRules.pretty(cfg))) return@withContext Result.failure(IllegalStateException("write"))
        val check = ConfigCheck.check("xray", path)
        if (check is CheckResult.Failed) {
            BoxModule.writeFile(path, text)
            return@withContext Result.failure(IllegalStateException(check.output))
        }
        store(context, key, value)
        if (BoxModule.state().running && BoxModule.unquote(BoxModule.state().core) == "xray") BoxModule.restart()
        Result.success(Unit)
    }

    // ------------------------------------------------------------------ sing-box

    private val OVERRIDE_PATHS = mapOf(
        S_LOGLEVEL to listOf("log", "level"),
        S_TIMESTAMP to listOf("log", "timestamp"),
        S_DNS_STRATEGY to listOf("dns", "strategy"),
        S_DNS_INDEPENDENT to listOf("dns", "independent_cache"),
        S_AUTO_IFACE to listOf("route", "auto_detect_interface"),
        S_CACHE_RDRC to listOf("experimental", "cache_file", "store_rdrc"),
    )

    private fun pathGet(o: JSONObject?, path: List<String>): Any? {
        var cur: JSONObject = o ?: return null
        for (p in path.dropLast(1)) cur = cur.optJSONObject(p) ?: return null
        return cur.opt(path.last())
    }

    private fun urltest(cfg: JSONObject): JSONObject? = cfg.optJSONArray("outbounds")?.let { a ->
        val all = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("type") == "urltest" }
        // The servers' urltest ("⚡️ Fastest"), not the one over the direct outbound ("🇷🇺 Без VPN").
        all.firstOrNull { it.optJSONArray("outbounds")?.let { o -> o.length() != 1 || o.optString(0) != "direct" } ?: true } ?: all.firstOrNull()
    }

    suspend fun singBoxValues(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        val ov = SingBoxOverrides.read()?.let { runCatching { JSONObject(stripComments(it)) }.getOrNull() }
        val cfg = BoxModule.readFile(CoreTools.activeConfig("sing-box"))?.let { runCatching { JSONObject(stripComments(it)) }.getOrNull() } ?: JSONObject()
        val out = LinkedHashMap<String, String>()
        val defaults = mapOf(S_LOGLEVEL to "info", S_TIMESTAMP to "false", S_DNS_STRATEGY to "prefer_ipv4", S_DNS_INDEPENDENT to "false", S_AUTO_IFACE to "false", S_CACHE_RDRC to "false")
        OVERRIDE_PATHS.forEach { (k, path) -> out[k] = (pathGet(ov, path) ?: pathGet(cfg, path))?.toString() ?: defaults.getValue(k) }
        val u = urltest(cfg)
        out[S_URLTEST_URL] = u?.optString("url")?.ifBlank { null } ?: "https://www.gstatic.com/generate_204"
        out[S_URLTEST_INTERVAL] = u?.optString("interval")?.ifBlank { null } ?: "3m"
        out[S_URLTEST_TOLERANCE] = u?.opt("tolerance")?.toString() ?: "50"
        out[S_BLOCK_QUIC] = (cfg.optJSONObject("route")?.optJSONArray("rules")?.let { r ->
            (0 until r.length()).any { r.optJSONObject(it)?.let { x -> x.optString("action") == "reject" && x.optString("network") == "udp" && x.optInt("port") == 443 } == true }
        } == true).toString()
        out + stored(context).filterKeys { it.startsWith("s.") }
    }

    /** Writes the config-side sing-box values (urltest, QUIC) into a sing-box config (in place). */
    fun applySingBox(cfg: JSONObject, values: Map<String, String>) {
        urltest(cfg)?.let { u ->
            values[S_URLTEST_URL]?.let { u.put("url", it) }
            values[S_URLTEST_INTERVAL]?.let { u.put("interval", it) }
            values[S_URLTEST_TOLERANCE]?.toIntOrNull()?.let { u.put("tolerance", it) }
        }
        values[S_BLOCK_QUIC]?.let { on ->
            val route = cfg.obj("route")
            val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }
            val list = (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }
                .filterNot { it.optString("action") == "reject" && it.optString("network") == "udp" && it.optInt("port") == 443 && it.length() == 3 }
                .toMutableList()
            if (on == "true") {
                // After sniff / hijack-dns, before the routing rules.
                val at = list.indexOfFirst { it.optString("action") !in setOf("sniff", "hijack-dns") }.let { if (it < 0) list.size else it }
                list.add(at, JSONObject().put("network", "udp").put("port", 443).put("action", "reject"))
            }
            route.put("rules", JSONArray(list))
        }
    }

    fun applySingBoxStored(context: Context, cfg: JSONObject) = applySingBox(cfg, stored(context).filterKeys { it in setOf(S_URLTEST_URL, S_URLTEST_INTERVAL, S_URLTEST_TOLERANCE, S_BLOCK_QUIC) })

    /** Sets one sing-box value (overrides or config), checked with the core; sing-box reloads when it runs. */
    suspend fun setSingBox(context: Context, key: String, value: String): Result<Unit> = withContext(Dispatchers.IO) {
        val path = OVERRIDE_PATHS[key]
        val target = if (path != null) {
            SingBoxOverrides.ensure()
            SingBoxOverrides.FILE
        } else CoreTools.activeConfig("sing-box")
        val text = BoxModule.readFile(target) ?: return@withContext Result.failure(IllegalStateException(target))
        val o = runCatching { JSONObject(stripComments(text)) }.getOrNull() ?: return@withContext Result.failure(IllegalStateException("JSON"))
        if (path != null) {
            var cur = o
            for (p in path.dropLast(1)) cur = cur.obj(p)
            cur.put(path.last(), typed(value))
        } else {
            if (text.split("\n").any { it.trimStart().startsWith("//") }) return@withContext Result.failure(IllegalStateException("comments"))
            applySingBox(o, mapOf(key to value))
        }
        val header = if (path != null) "// Your settings over the sing-box config (Boxy → panel → Settings); they survive subscription updates.\n" else ""
        if (!BoxModule.writeFile(target, header + GeoRules.pretty(o))) return@withContext Result.failure(IllegalStateException("write"))
        val check = ConfigCheck.check("sing-box", CoreTools.activeConfig("sing-box"))
        if (check is CheckResult.Failed) {
            BoxModule.writeFile(target, text)
            return@withContext Result.failure(IllegalStateException(check.output))
        }
        store(context, key, value)
        val st = BoxModule.state()
        if (st.running && BoxModule.unquote(st.core) == "sing-box") BoxModule.reloadConfig("sing-box")
        Result.success(Unit)
    }

    /** Forgets sing-box's saved group choices and cached rule sets (cache.db); sing-box restarts when it runs. */
    suspend fun clearSingBoxCache(): Boolean = withContext(Dispatchers.IO) {
        val st = BoxModule.state()
        val running = st.running && BoxModule.unquote(st.core) == "sing-box"
        if (running) BoxModule.stop()
        BoxModule.exec("rm -f '${BoxModule.BOX_DIR}/sing-box/cache.db'")
        if (running) BoxModule.start() else true
    }
}
