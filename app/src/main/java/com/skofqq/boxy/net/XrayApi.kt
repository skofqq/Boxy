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
    const val PROBE_URL = "http://www.gstatic.com/generate_204"
    /** Local socks ports of [testDelays], one per outbound tested. */
    private const val TEST_PORT = 20800
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
        /** Each balancer's strategy type (leastPing, random, roundRobin, leastLoad). */
        val strategies: Map<String, String> = emptyMap(),
        val policy: JSONObject?,
        val log: JSONObject?,
    ) {
        /** The access log file, null when Xray writes none. */
        val accessLog: String? get() = log?.optString("access")?.takeIf { it.isNotBlank() && it != "none" }
        val panelReady get() = metrics != null
    }

    data class OutboundState(
        val tag: String,
        val alive: Boolean?,
        val delayMs: Long?,
        val error: String?,
        val up: Long,
        val down: Long,
        /** The observatory's last probe, ms since the epoch. */
        val probedAt: Long? = null,
    )

    data class Metrics(val outbounds: List<OutboundState>, val inUp: Long, val inDown: Long)

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** The confdir's JSON files (Xray reads them all, hidden ones too), read and merged as Xray does. */
    suspend fun setup(withoutPanel: Boolean = false): Setup = withContext(Dispatchers.IO) {
        // The files the core runs with (the whole folder, or a profile with Boxy's files), as Xray merges them.
        val names = com.skofqq.boxy.root.CoreTools.xrayFiles().filter { !(withoutPanel && it == PANEL_FILE) }
        val merged = JSONObject()
        val outbounds = ArrayList<Outbound>()
        val balancers = ArrayList<String>()
        val selectors = LinkedHashMap<String, List<String>>()
        val strategies = LinkedHashMap<String, String>()
        for (n in names) {
            val text = BoxModule.readFile(n) ?: continue
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
                    strategies[tag] = b.optJSONObject("strategy")?.optString("type")?.takeIf { it.isNotBlank() } ?: "random"
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
            strategies = strategies,
            policy = merged.optJSONObject("policy"),
            log = merged.optJSONObject("log"),
        )
    }

    /**
     * A tag as people read it: without Boxy's subscription prefix ("sub-") or a similar short technical prefix
     * in front of a flag, "_" as spaces. Tags themselves stay as they are.
     */
    fun displayName(tag: String): String {
        var t = tag
        if (t.startsWith(com.skofqq.boxy.configs.ShareLinks.TAG_PREFIX)) t = t.removePrefix(com.skofqq.boxy.configs.ShareLinks.TAG_PREFIX)
        else PREFIX_BEFORE_FLAG.find(t)?.let { t = t.substring(it.value.length) }
        return t.replace('_', ' ').trim().ifBlank { tag }
    }

    /** "out-🇩🇪…", "proxy-🇫🇮…": a lowercase word and a dash right before a flag (regional indicator pair). */
    private val PREFIX_BEFORE_FLAG = Regex("^[a-z0-9]{1,8}[-_](?=[\\x{1F1E6}-\\x{1F1FF}])")

    /** Per balancer: the pinned outbound ("" when none) and the one it picks now (null when Xray does not say). */
    data class BalancerInfo(val override: String, val picks: String?)

    /**
     * Asks Xray (`xray api bi`) what each balancer is on: one call for all of them, read once when the panel opens
     * and after a change, so a pin made earlier shows up. The answer lists the balancers in the order asked.
     */
    suspend fun balancerInfo(api: String, balancers: List<String>): Map<String, BalancerInfo> = withContext(Dispatchers.IO) {
        if (balancers.isEmpty()) return@withContext emptyMap()
        val server = api.let { if (it.startsWith(":")) "127.0.0.1$it" else it }
        val r = Shell.cmd("timeout 10 $BOX/bin/xray api bi --server=$server ${balancers.joinToString(" ") { q(it) }} 2>/dev/null").exec()
        if (!r.isSuccess) return@withContext emptyMap()
        val blocks = ArrayList<BalancerInfo>()
        var section = ""
        var override = ""
        var picks: String? = null
        var started = false
        fun close() { if (started) blocks.add(BalancerInfo(override, picks)) }
        for (raw in r.out) {
            val line = raw.trim()
            when {
                line.startsWith("- Selecting Override") -> { close(); started = true; override = ""; picks = null; section = "o" }
                line.startsWith("- Selects") -> section = "s"
                line.startsWith("-") -> section = ""
                else -> {
                    val v = line.substringAfter(' ', "").trim()
                    if (line.firstOrNull()?.isDigit() == true && v.isNotEmpty()) {
                        if (section == "o" && override.isEmpty()) override = v
                        if (section == "s" && picks == null) picks = v
                    }
                }
            }
        }
        close()
        if (blocks.size != balancers.size) return@withContext emptyMap()
        balancers.zip(blocks).toMap()
    }

    /** The outbounds [balancer] chooses from: tags starting with one of its selector prefixes. */
    fun balancerTargets(s: Setup, balancer: String): List<String> {
        val prefixes = s.selectors[balancer].orEmpty()
        val own = s.outbounds.map { it.tag }.filter { t -> prefixes.any { t.startsWith(it) } }
        // A balancer over "direct" only (a group like "RU sites: no VPN") goes direct by itself; a pin can point it
        // at any server, since Xray takes any outbound tag as the override.
        // A balancer over servers can likewise be pinned to "No VPN" (a freedom outbound); its own choice never is.
        val proxies = proxyTags(s)
        val direct = s.outbounds.filter { it.protocol == "freedom" }.map { it.tag }
        return if (own.isNotEmpty() && own.none { it in proxies }) own + proxies.filter { it !in own }
        else own + direct.filter { it !in own }
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
                        .put("probeUrl", "http://www.gstatic.com/generate_204").put("probeInterval", "3m").put("enableConcurrency", true),
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
        val r = Shell.cmd("XRAY_LOCATION_ASSET=$DIR timeout 30 $BOX/bin/xray -test ${com.skofqq.boxy.root.CoreTools.xrayArgs()} 2>&1").exec()
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
                probedAt = ob?.optLong("last_try_time", 0)?.takeIf { it > 0 }?.times(1000),
            )
        }
        var inUp = 0L
        var inDown = 0L
        stats?.optJSONObject("inbound")?.let { ib ->
            ib.keys().forEach { k -> ib.optJSONObject(k)?.let { inUp += it.optLong("uplink"); inDown += it.optLong("downlink") } }
        }
        return Metrics(list, inUp, inDown)
    }

    /** Result of [testDelays]: ms of a request over an open connection (0 = failed) and when it was measured. */
    data class Tested(val delays: Map<String, Int>, val at: Long)

    /**
     * Tests [tags] now, on tap only: a short-lived Xray (same user as the core, so the proxy rules let it out) with a
     * local socks port per outbound and the confdir's outbounds as they are. Two requests to the probe URL go over
     * each one; the second one, over the open connection, is the delay, like mihomo's unified-delay (the first one
     * also carries the TCP / TLS / REALITY handshake). Xray's own observatory times a whole new connection, so its
     * numbers are higher; the balancer still picks by those.
     */
    suspend fun testDelays(tags: List<String>, url: String = PROBE_URL): Tested = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (tags.isEmpty()) return@withContext Tested(emptyMap(), now)
        val outbounds = JSONArray()
        com.skofqq.boxy.root.CoreTools.xrayFiles().forEach { n ->
            val o = BoxModule.readFile(n)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@forEach
            o.optJSONArray("outbounds")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { outbounds.put(it) } }
        }
        val inbounds = JSONArray()
        val rules = JSONArray()
        tags.forEachIndexed { i, t ->
            inbounds.put(JSONObject().put("tag", "boxy-t$i").put("listen", "127.0.0.1").put("port", TEST_PORT + i).put("protocol", "socks"))
            rules.put(JSONObject().put("inboundTag", JSONArray().put("boxy-t$i")).put("outboundTag", t))
        }
        val cfg = JSONObject().put("log", JSONObject().put("loglevel", "none")).put("inbounds", inbounds).put("outbounds", outbounds)
            .put("routing", JSONObject().put("rules", rules))
        val file = "$BOX/run/boxy-delay.json"
        if (!BoxModule.writeFile(file, cfg.toString())) return@withContext Tested(emptyMap(), now)
        val group = BoxModule.unquote(BoxModule.readSetting("box_user_group")).orEmpty().ifBlank { "root:net_admin" }
        val last = "%04X".format(TEST_PORT + tags.size - 1)
        val curls = tags.indices.joinToString(" ") { i ->
            "( r=\$($BOX/bin/curl -s -o /dev/null -m 6 -x socks5h://127.0.0.1:${TEST_PORT + i} -w '%{http_code}:%{time_total} ' ${q(url)} ${q(url)}); echo \"$i \$r\" ) & w=\"\$w \$!\";"
        }
        val script = "( export SSL_CERT_DIR=/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts; " +
            "pkill -f boxy-delay.json 2>/dev/null; n=0; while [ \$n -lt 20 ] && grep -qi '0100007F:%04X 00000000:0000 0A' /proc/net/tcp; do sleep 0.1; n=\$((n+1)); done; ".format(TEST_PORT) +
            "BB=busybox; for b in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do [ -x \$b ] && BB=\$b && break; done; " +
            "\$BB setuidgid ${q(group)} $BOX/bin/xray run -c $file >/dev/null 2>&1 & p=\$!; " +
            "n=0; while [ \$n -lt 30 ] && ! grep -qi '0100007F:$last 00000000:0000 0A' /proc/net/tcp; do sleep 0.1; n=\$((n+1)); done; " +
            "w=; $curls wait \$w; kill \$p 2>/dev/null; wait \$p 2>/dev/null; rm -f $file )"
        val out = Shell.cmd(script).exec().out
        val delays = HashMap<String, Int>()
        for (line in out) {
            val parts = line.trim().split(Regex("\\s+"))
            val i = parts.firstOrNull()?.toIntOrNull() ?: continue
            val tag = tags.getOrNull(i) ?: continue
            val ok = parts.drop(1).mapNotNull { r ->
                val code = r.substringBefore(':').toIntOrNull() ?: 0
                val sec = r.substringAfter(':').toDoubleOrNull() ?: 0.0
                if (code in 200..399 && sec > 0) (sec * 1000).toInt().coerceAtLeast(1) else null
            }
            delays[tag] = ok.getOrNull(1) ?: ok.getOrNull(0) ?: 0
        }
        tags.forEach { delays.putIfAbsent(it, 0) }
        Tested(delays, System.currentTimeMillis())
    }

    /** Time of the observatory's last probe of [tag] (ms), null when it has not probed it yet. */
    fun lastProbe(m: Metrics?, tag: String): Long? = m?.outbounds?.firstOrNull { it.tag == tag }?.probedAt

    /**
     * Pins made in Boxy, "config<TAB>balancer<TAB>outbound" per line. Xray keeps an override only while it runs, so Boxy puts
     * them back after a start ([restorePins]). A .txt file: Xray's confdir reads only its config formats.
     */
    const val PINS_FILE = "$DIR/boxy-pins.txt"

    /** All remembered pins: config name -> (balancer -> outbound). Each config keeps its own. */
    private suspend fun allPins(): Map<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, LinkedHashMap<String, String>>()
        BoxModule.readFile(PINS_FILE).orEmpty().lines().forEach { l ->
            val p = l.split('\t')
            if (p.size == 3 && p.all { it.isNotBlank() }) out.getOrPut(p[0]) { LinkedHashMap() }[p[1]] = p[2]
        }
        out
    }

    private suspend fun activeName(): String = BoxModule.unquote(BoxModule.readSetting("name_xray_config")).orEmpty().ifBlank { "config.json" }

    /** Pins of the active config. */
    suspend fun pins(): Map<String, String> = allPins()[activeName()].orEmpty()

    /** Remembers (or with null forgets) the pin of [balancer] for the active config. */
    suspend fun savePin(balancer: String, target: String?) {
        val all = allPins().mapValues { it.value.toMutableMap() }.toMutableMap()
        val mine = all.getOrPut(activeName()) { mutableMapOf() }
        if (target == null) mine.remove(balancer) else mine[balancer] = target
        val text = all.entries.joinToString("") { (cfg, m) -> m.entries.joinToString("") { "$cfg\t${it.key}\t${it.value}\n" } }
        if (text.isEmpty()) Shell.cmd("rm -f ${q(PINS_FILE)}").exec() else BoxModule.writeFile(PINS_FILE, text)
    }

    /**
     * Puts the remembered pins back into a running Xray (after a start the overrides are gone): only those whose
     * balancer and outbound still exist and that Xray does not have already. Returns how many were applied.
     */
    suspend fun restorePins(s: Setup? = null): Int {
        val pins = pins()
        if (pins.isEmpty()) return 0
        val setup = s ?: setup()
        val api = setup.api?.takeIf { setup.apiRouting } ?: return 0
        val tags = setup.outbounds.map { it.tag }.toSet()
        val wanted = pins.filter { (b, t) -> b in setup.balancers && t in tags }
        if (wanted.isEmpty()) return 0
        val now = balancerInfo(api, setup.balancers)
        var n = 0
        wanted.forEach { (b, t) -> if (now[b]?.override != t && overrideBalancer(api, b, t)) n++ }
        return n
    }

    /** Points a balancer at [target], or back to its own choice with null (`xray api bo`). */
    suspend fun overrideBalancer(api: String, balancer: String, target: String?): Boolean = withContext(Dispatchers.IO) {
        val server = api.let { if (it.startsWith(":")) "127.0.0.1$it" else it }
        val args = if (target == null) "-b ${q(balancer)} -r" else "-b ${q(balancer)} ${q(target)}"
        Shell.cmd("timeout 10 $BOX/bin/xray api bo --server=$server $args").exec().isSuccess
    }
}
