package com.skofqq.boxy.net

import com.skofqq.boxy.root.BoxModule
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Xray's light panel: the metrics HTTP endpoint (/debug/vars: observatory alive / delay, traffic per outbound from
 * stats) and the API for a balancer override (`xray api bo`). Xray has no clash-style API; these blocks are added on
 * request in a file of their own (see [enablePanel]), so the user's config stays as it is.
 * Polled only while the panel or the home page is open.
 */
object XrayApi {
    private const val BOX = BoxModule.BOX_DIR
    private const val DIR = "$BOX/xray"

    /**
     * Xray merges the confdir files sorted by name and a later file replaces a whole top-level object, so this one
     * sorts last; for "policy" it carries the user's own policy plus the stats switches.
     */
    const val PANEL_FILE = "$DIR/zz-boxy-panel.json"
    const val METRICS_LISTEN = "127.0.0.1:11111"
    const val API_LISTEN = "127.0.0.1:10085"
    /** Access log written when the user turns it on; box.service empties it at every start. */
    const val ACCESS_LOG = "$BOX/run/xray-access.log"

    data class Outbound(val tag: String, val protocol: String)

    /** What the confdir already has, merged over all its files. */
    data class Setup(
        val metrics: String?,
        val api: String?,
        val apiRouting: Boolean,
        val observatory: Boolean,
        val stats: Boolean,
        val outboundStats: Boolean,
        val outbounds: List<Outbound>,
        val balancers: List<String>,
        /** Each balancer's selector: the tag prefixes of the outbounds it picks from. */
        val selectors: Map<String, List<String>> = emptyMap(),
        val policy: JSONObject?,
        val log: JSONObject?,
    ) {
        /** The access log file, null when Xray writes none. */
        val accessLog: String? get() = log?.optString("access")?.takeIf { it.isNotBlank() && it != "none" }
        val panelReady get() = metrics != null
    }

    data class OutboundState(val tag: String, val alive: Boolean?, val delayMs: Long?, val error: String?, val up: Long, val down: Long)

    data class Metrics(val outbounds: List<OutboundState>, val inUp: Long, val inDown: Long)

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** The confdir's JSON files (Xray reads them all, hidden ones too), read and merged as Xray does. */
    suspend fun setup(withoutPanel: Boolean = false): Setup = withContext(Dispatchers.IO) {
        val panel = PANEL_FILE.substringAfterLast('/')
        val names = Shell.cmd("ls -a $DIR 2>/dev/null").exec().out.filter { it.endsWith(".json") && !(withoutPanel && it == panel) }.sorted()
        val merged = JSONObject()
        val outbounds = ArrayList<Outbound>()
        val balancers = ArrayList<String>()
        val selectors = LinkedHashMap<String, List<String>>()
        for (n in names) {
            val text = BoxModule.readFile("$DIR/$n") ?: continue
            val o = runCatching { JSONObject(text) }.getOrNull() ?: continue
            o.optJSONArray("outbounds")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { ob ->
                    ob.optString("tag").takeIf { it.isNotBlank() }?.let { outbounds.add(Outbound(it, ob.optString("protocol"))) }
                }
            }
            o.optJSONObject("routing")?.optJSONArray("balancers")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { b ->
                    val tag = b.optString("tag").takeIf { it.isNotBlank() } ?: return@let
                    balancers.add(tag)
                    val sel = b.optJSONArray("selector")
                    selectors[tag] = if (sel == null) emptyList() else (0 until sel.length()).map { sel.optString(it) }.filter { it.isNotBlank() }
                }
            }
            o.keys().forEach { k -> if (k != "outbounds" && k != "inbounds") merged.put(k, o.get(k)) }
        }
        val api = merged.optJSONObject("api")
        val sys = merged.optJSONObject("policy")?.optJSONObject("system")
        Setup(
            metrics = merged.optJSONObject("metrics")?.optString("listen")?.takeIf { it.isNotBlank() },
            api = api?.optString("listen")?.takeIf { it.isNotBlank() },
            apiRouting = api?.optJSONArray("services")?.let { s -> (0 until s.length()).any { s.optString(it) == "RoutingService" } } == true,
            observatory = merged.has("observatory") || merged.has("burstObservatory"),
            stats = merged.has("stats"),
            outboundStats = sys?.optBoolean("statsOutboundUplink") == true && sys.optBoolean("statsOutboundDownlink"),
            outbounds = outbounds.distinctBy { it.tag },
            balancers = balancers.distinct(),
            selectors = selectors,
            policy = merged.optJSONObject("policy"),
            log = merged.optJSONObject("log"),
        )
    }

    /** The outbounds [balancer] chooses from: tags starting with one of its selector prefixes. */
    fun balancerTargets(s: Setup, balancer: String): List<String> {
        val prefixes = s.selectors[balancer].orEmpty()
        return s.outbounds.map { it.tag }.filter { t -> prefixes.any { t.startsWith(it) } }
    }

    /** Outbounds that carry traffic to a server (not direct / block / dns), the ones worth probing. */
    fun proxyTags(s: Setup): List<String> = s.outbounds.filter { it.protocol !in setOf("freedom", "blackhole", "dns", "loopback") }.map { it.tag }

    /**
     * Writes the panel file: metrics, stats, the stats switches of policy (with the user's policy kept), an
     * observatory over the proxy outbounds (probe every 3 minutes, only what leastPing balancers need anyway) and,
     * with [withApi], the API with RoutingService for balancer overrides. Only what the config lacks is added.
     */
    fun panelJson(s: Setup, withApi: Boolean, accessLog: Boolean): String {
        val o = JSONObject()
        if (accessLog && s.accessLog == null) {
            // A later file replaces the whole "log" object: the user's own settings are carried over.
            val log = s.log?.let { JSONObject(it.toString()) } ?: JSONObject().put("loglevel", "warning")
            o.put("log", log.put("access", ACCESS_LOG))
        }
        o.put("metrics", JSONObject().put("tag", "boxy-metrics").put("listen", s.metrics ?: METRICS_LISTEN))
        if (!s.stats) o.put("stats", JSONObject())
        if (!s.outboundStats) {
            val policy = s.policy?.let { JSONObject(it.toString()) } ?: JSONObject()
            val sys = policy.optJSONObject("system") ?: JSONObject().also { policy.put("system", it) }
            sys.put("statsOutboundUplink", true).put("statsOutboundDownlink", true)
            sys.put("statsInboundUplink", true).put("statsInboundDownlink", true)
            o.put("policy", policy)
        }
        if (!s.observatory) {
            val tags = proxyTags(s)
            if (tags.isNotEmpty()) {
                o.put(
                    "observatory",
                    JSONObject().put("subjectSelector", JSONArray(tags))
                        .put("probeUrl", "https://www.gstatic.com/generate_204").put("probeInterval", "3m").put("enableConcurrency", true),
                )
            }
        }
        if (withApi && (s.api == null || !s.apiRouting)) {
            o.put(
                "api",
                JSONObject().put("tag", "boxy-api").put("listen", s.api ?: API_LISTEN)
                    .put("services", JSONArray(listOf("RoutingService", "StatsService", "HandlerService"))),
            )
        }
        return com.skofqq.boxy.configs.GeoRules.pretty(o)
    }

    /**
     * Adds the panel file, checks the confdir with the core and restarts it (Xray has no reload). The file is
     * removed again when the check fails; the core's complaint comes back as the failure.
     */
    suspend fun enablePanel(withApi: Boolean, accessLog: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        val s = setup(withoutPanel = true)
        val had = BoxModule.readFile(PANEL_FILE)
        if (!BoxModule.writeFile(PANEL_FILE, panelJson(s, withApi, accessLog))) return@withContext Result.failure(IllegalStateException("write"))
        val r = Shell.cmd("XRAY_LOCATION_ASSET=$DIR timeout 30 $BOX/bin/xray -test -confdir $DIR 2>&1").exec()
        if (!r.isSuccess) {
            if (had != null) BoxModule.writeFile(PANEL_FILE, had) else Shell.cmd("rm -f ${q(PANEL_FILE)}").exec()
            return@withContext Result.failure(
                IllegalStateException(r.out.map { com.skofqq.boxy.root.RootTask.stripAnsi(it) }.filter { "rror" in it || "ailed" in it }.takeLast(6).joinToString("\n").ifBlank { "check failed" }),
            )
        }
        if (BoxModule.state().running) BoxModule.restart()
        Result.success(Unit)
    }

    /** Rewrites the panel file with the access log on or off, keeping its other choices. */
    suspend fun setAccessLog(on: Boolean): Result<Unit> {
        val panel = BoxModule.readFile(PANEL_FILE)?.let { runCatching { JSONObject(it) }.getOrNull() }
        return enablePanel(withApi = panel?.has("api") == true || setup(withoutPanel = true).api != null, accessLog = on)
    }

    data class AccessEntry(val time: String, val from: String, val target: String, val route: String, val rejected: Boolean)

    private val ACCESS_LINE = Regex("""^(\S+ \S+) from (\S+) (accepted|rejected) (\S+)(?: \[([^\]]*)])?""")

    /** The last [lines] entries of the access log, newest first (read on demand, nothing is kept). */
    suspend fun accessLog(path: String, lines: Int = 400): List<AccessEntry> = withContext(Dispatchers.IO) {
        Shell.cmd("tail -n $lines ${q(path)} 2>/dev/null").exec().out.mapNotNull { l ->
            val m = ACCESS_LINE.find(l.trim()) ?: return@mapNotNull null
            AccessEntry(
                time = m.groupValues[1].substringAfter(' ').substringBefore('.'),
                from = m.groupValues[2],
                target = m.groupValues[4].removePrefix("tcp:").removePrefix("udp:"),
                route = m.groupValues[5],
                rejected = m.groupValues[3] == "rejected",
            )
        }.asReversed()
    }

    suspend fun disablePanel(): Boolean = withContext(Dispatchers.IO) {
        val ok = Shell.cmd("rm -f ${q(PANEL_FILE)}").exec().isSuccess
        if (ok && BoxModule.state().running) BoxModule.restart()
        ok
    }

    /** /debug/vars of the running core; null when it does not answer. */
    suspend fun metrics(listen: String): Metrics? = withContext(Dispatchers.IO) {
        runCatching {
            val host = listen.let { if (it.startsWith(":")) "127.0.0.1$it" else it.replace("0.0.0.0", "127.0.0.1") }
            val c = URL("http://$host/debug/vars").openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 3000
            val body = try {
                c.inputStream.bufferedReader().readText()
            } finally {
                c.disconnect()
            }
            parse(JSONObject(body))
        }.getOrNull()
    }

    fun parse(o: JSONObject): Metrics {
        val obs = o.optJSONObject("observatory")
        val stats = o.optJSONObject("stats")
        val outStats = stats?.optJSONObject("outbound")
        val tags = LinkedHashSet<String>()
        outStats?.keys()?.forEach { tags.add(it) }
        obs?.keys()?.forEach { tags.add(it) }
        val list = tags.map { t ->
            val ob = obs?.optJSONObject(t)
            val st = outStats?.optJSONObject(t)
            val delay = ob?.optLong("delay", -1)?.takeIf { it in 0 until 99_999_999 }
            OutboundState(
                tag = t,
                alive = ob?.let { it.optBoolean("alive", false) },
                delayMs = delay,
                error = ob?.optString("last_error_reason")?.takeIf { it.isNotBlank() },
                up = st?.optLong("uplink") ?: 0,
                down = st?.optLong("downlink") ?: 0,
            )
        }
        var inUp = 0L
        var inDown = 0L
        stats?.optJSONObject("inbound")?.let { ib ->
            ib.keys().forEach { k -> ib.optJSONObject(k)?.let { inUp += it.optLong("uplink"); inDown += it.optLong("downlink") } }
        }
        return Metrics(list, inUp, inDown)
    }

    /** Points a balancer at [target], or back to its own choice with null (`xray api bo`). */
    suspend fun overrideBalancer(api: String, balancer: String, target: String?): Boolean = withContext(Dispatchers.IO) {
        val server = api.let { if (it.startsWith(":")) "127.0.0.1$it" else it }
        val args = if (target == null) "-b ${q(balancer)} -r" else "-b ${q(balancer)} ${q(target)}"
        Shell.cmd("timeout 10 $BOX/bin/xray api bo --server=$server $args").exec().isSuccess
    }
}
